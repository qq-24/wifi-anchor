package com.mingh.wifianchor.model

import android.net.wifi.ScanResult
import android.net.wifi.WifiInfo
import android.os.Build
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject

/**
 * 原始记录一律「一行一帧」JSONL，不在设备端做汇总或判断——
 * 判断留给离线分析，免得 App 里预设有错的结论污染数据。
 */

/** 是否 802.11mc 响应器：本机 android.jar 实测有此方法（is80211mcResponder，API 28+） */
fun apMc(sr: ScanResult): Boolean = try {
    if (Build.VERSION.SDK_INT >= 28) sr.is80211mcResponder else false
} catch (t: Throwable) {
    false
}

/**
 * 802.11az NTB 标志：本机 android.jar（compileSdk 34）里没有这个方法，
 * 所以只能反射试探——有就报，没有就恒 false，不假装。
 */
private val azMethod: java.lang.reflect.Method? by lazy {
    runCatching { ScanResult::class.java.getMethod("is80211azNtbResponder") }.getOrNull()
}

fun apAz(sr: ScanResult): Boolean {
    val m = azMethod ?: return false
    return runCatching { m.invoke(sr) as? Boolean == true }.getOrDefault(false)
}

data class ApSnap(
    val bssid: String,
    val ssid: String,
    val rssi: Int,
    val freq: Int,
    val caps: String,
    val mc: Boolean,
    val az: Boolean,
    val centerFreq1: Int
) {
    fun toShort(): String =
        "${bssid.takeLast(8)} ${ssid.take(14)} ${rssi}dBm ch${freq} ${if (mc) "FTM" else ""}${if (az) "AZ" else ""}"
}

data class ConnSnap(
    val ssid: String,
    val bssid: String,
    val rssi: Int,
    val freq: Int,
    val linkSpeed: Int
)

data class Frame(
    val tWall: Long,
    val tBootUs: Long,
    /** 帧内最新一条 ScanResult.timestamp 距今多少毫秒；ScanResult.timestamp 用 BOOTTIME 微秒基线【推断】 */
    val ageMs: Long,
    /** 时间基线自检：若 age 为负或离谱说明本机 timestamp 语义不同，该列作废 */
    val ageSane: Boolean,
    val source: String,
    val resultsUpdated: Boolean?,
    val aps: List<ApSnap>,
    val conn: ConnSnap?
) {
    fun toJson(sess: String, seq: Int): JSONObject {
        val o = JSONObject()
        o.put("ev", "frame")
        o.put("t", tWall)
        o.put("tboot_us", tBootUs)
        o.put("age_ms", ageMs)
        o.put("age_sane", ageSane)
        o.put("sess", sess)
        o.put("seq", seq)
        o.put("from", source)
        o.put("res_upd", resultsUpdated ?: JSONObject.NULL)
        val a = JSONArray()
        for (s in aps) {
            a.put(JSONArray()
                .put(s.bssid).put(s.ssid).put(s.rssi).put(s.freq).put(s.caps)
                .put(if (s.mc) 1 else 0).put(if (s.az) 1 else 0).put(s.centerFreq1))
        }
        o.put("ap", a)
        conn?.let {
            o.put("conn", JSONObject()
                .put("ssid", it.ssid).put("bssid", it.bssid)
                .put("rssi", it.rssi).put("freq", it.freq).put("speed", it.linkSpeed))
        }
        return o
    }
}

object Frames {

    fun fromScan(
        results: List<ScanResult>,
        source: String,
        resultsUpdated: Boolean?,
        conn: ConnSnap?
    ): Frame {
        val bootUs = SystemClock.elapsedRealtime() * 1000
        val aps = results.map {
            ApSnap(
                bssid = it.BSSID ?: "?",
                ssid = it.SSID ?: "?",
                rssi = it.level,
                freq = it.frequency,
                caps = it.capabilities ?: "",
                mc = apMc(it),
                az = apAz(it),
                centerFreq1 = runCatching { it.centerFreq1 }.getOrDefault(0)
            )
        }.sortedByDescending { it.rssi }
        // 用最新一帧的 timestamp 反推缓存年龄；不同 AP 的时间戳不同，取最大值
        val newestUs = results.mapNotNull { runCatching { if (it.timestamp > 0) it.timestamp else null }.getOrNull() }
            .maxOrNull() ?: 0L
        val ageMs = if (newestUs > 0) (bootUs - newestUs) / 1000 else -1
        return Frame(
            tWall = System.currentTimeMillis(),
            tBootUs = bootUs,
            ageMs = ageMs,
            ageSane = newestUs > 0 && ageMs >= -2000 && ageMs < 3_600_000,
            source = source,
            resultsUpdated = resultsUpdated,
            aps = aps,
            conn = conn
        )
    }

    fun connOf(info: WifiInfo?): ConnSnap? {
        if (info == null) return null
        return try {
            ConnSnap(
                ssid = info.ssid?.trim('"') ?: "",
                bssid = info.bssid ?: "",
                rssi = info.rssi,
                freq = runCatching { info.frequency }.getOrDefault(0),
                linkSpeed = runCatching { info.linkSpeed }.getOrDefault(0)
            )
        } catch (t: Throwable) {
            null
        }
    }

    fun metaLine(ctx: android.content.Context, note: String): String {
        val o = JSONObject()
        o.put("ev", "meta")
        o.put("t", System.currentTimeMillis())
        o.put("brand", Build.BRAND)
        o.put("manufacturer", Build.MANUFACTURER)
        o.put("model", Build.MODEL)
        o.put("device", Build.DEVICE)
        o.put("product", Build.PRODUCT)
        o.put("release", Build.VERSION.RELEASE)
        o.put("sdk", Build.VERSION.SDK_INT)
        o.put("incremental", Build.VERSION.INCREMENTAL)
        o.put("note", note)
        return o.toString()
    }

    fun eventLine(ev: String, kv: Map<String, Any?>): String {
        val o = JSONObject()
        o.put("ev", ev)
        o.put("t", System.currentTimeMillis())
        for ((k, v) in kv) o.put(k, v ?: JSONObject.NULL)
        return o.toString()
    }
}
