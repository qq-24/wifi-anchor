package com.mingh.wifianchor.probe

import android.content.Context
import android.net.wifi.ScanResult
import android.os.Build
import android.os.SystemClock
import com.mingh.wifianchor.util.Stats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/**
 * 探针页 3：FTM 响应器发现 + RTT 测距实测。
 * 这一页决定 R3（离记录点多远）能不能给真米数：
 *   0 台响应器 —— 网络侧不配合，只能退回指纹分级；
 *   有响应器且多轮极差小 —— 可以做真测量。
 *
 * 入口用 android.net.wifi.rtt.WifiRttManager：本机 compileSdk 34 的 android.jar 里
 * 只有这一套（android/net/rtt/ 无任何类），且旧入口在 Android 12+ 运行时仍在。
 */
object RttHub {

    data class RttRow(
        val bssid: String, val ssid: String, val round: Int, val ok: Boolean,
        val distMm: Int, val stdMm: Int, val nOk: Int, val nTry: Int,
        val rssi: Int, val ms: Long, val detail: String
    )

    private class Out(
        val ok: Boolean, val distMm: Int, val stdMm: Int,
        val nOk: Int, val nTry: Int, val rssi: Int, val detail: String
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val exec = Executors.newSingleThreadExecutor()
    private var job: Job? = null

    private val _rows = MutableStateFlow<List<RttRow>>(emptyList())
    val rows: StateFlow<List<RttRow>> = _rows

    private val _summary = MutableStateFlow<List<String>>(emptyList())
    val summary: StateFlow<List<String>> = _summary

    private val _busy = MutableStateFlow<String?>(null)
    val busy: StateFlow<String?> = _busy

    /** 支持 FTM 的 AP；is80211mcResponder 是扫描结果里的现成标志位，不用猜也不用问网管 */
    fun responders(): List<ScanResult> = try {
        ScanHub.latestResults().filter {
            try {
                if (Build.VERSION.SDK_INT >= 28) it.is80211mcResponder else false
            } catch (t: Throwable) {
                false
            }
        }
    } catch (t: Throwable) {
        emptyList()
    }

    fun allApLines(): List<String> = ScanHub.lastFrame()?.aps?.map { it.toShort() } ?: emptyList()

    fun serviceInfo(ctx: Context): String {
        if (Build.VERSION.SDK_INT < 28) return "API < 28，系统不提供 Wi-Fi RTT"
        val m = mgr(ctx) ?: return "取不到 WifiRttManager（ROM 未提供旧入口）"
        val av = runCatching { m.isAvailable }.getOrDefault(false)
        return "WifiRttManager 存在，isAvailable=${if (av) "可用" else "当前不可用"}"
    }

    @Suppress("DEPRECATION")
    private fun mgr(ctx: Context): android.net.wifi.rtt.WifiRttManager? = try {
        ctx.getSystemService(Context.WIFI_RTT_RANGING_SERVICE) as? android.net.wifi.rtt.WifiRttManager
    } catch (t: Throwable) {
        null
    }

    fun stop() {
        job?.cancel()
        _busy.value = null
    }

    fun run(ctx: Context, maxTargets: Int, rounds: Int) {
        job?.cancel()
        job = scope.launch { runSuspend(ctx, maxTargets, rounds) }
    }

    /** 挂起版：一键体检需要等它跑完再往下走 */
    suspend fun runSuspend(ctx: Context, maxTargets: Int, rounds: Int) {
        _rows.value = emptyList()
        _summary.value = emptyList()
        val rs = responders()
        if (rs.isEmpty()) {
            _summary.value = listOf(
                "本次扫描结果里 0 台 AP 宣告 802.11mc(FTM) 支持。",
                "两种可能：宿舍 AP 未启用 FTM；或这一帧是旧缓存 —— 先去「扫描」页确认能拿到新数据再下结论。"
            )
            ScanHub.log("RTT: 未发现 FTM 响应器")
            ScanHub.event("rtt_none", mapOf("aps" to (ScanHub.lastFrame()?.aps?.size ?: 0)))
            return
        }
        val targets = rs.sortedByDescending { it.level }.take(maxTargets)
        ScanHub.log("RTT: 发现 ${rs.size} 台 FTM 响应器，测距 ${targets.size} 台 × $rounds 轮")
        _busy.value = "测距中"
        for ((idx, ap) in targets.withIndex()) {
            for (r in 1..rounds) {
                val t0 = SystemClock.elapsedRealtime()
                val out = withTimeoutOrNull(9000L) { rangeOnce(ctx, ap) }
                val ms = SystemClock.elapsedRealtime() - t0
                _rows.value = _rows.value + RttRow(
                    bssid = ap.BSSID ?: "?", ssid = ap.SSID ?: "?", round = r,
                    ok = out?.ok == true, distMm = out?.distMm ?: -1, stdMm = out?.stdMm ?: -1,
                    nOk = out?.nOk ?: 0, nTry = out?.nTry ?: 0,
                    rssi = out?.rssi ?: ap.level, ms = ms,
                    detail = out?.detail ?: "超时/无回调"
                )
                _busy.value = "测距中 ${idx + 1}/${targets.size} 第 $r 轮"
                if (r < rounds) delay(150)
            }
            _summary.value = _summary.value + summarize(idx + 1, ap)
        }
        _busy.value = null
        ScanHub.log("RTT 测距结束")
        ScanHub.event("rtt_done", mapOf(
            "targets" to targets.size, "rounds" to rounds,
            "per_ap" to _rows.value.groupBy { it.bssid }.map { (b, v) ->
                val okv = v.filter { it.ok }.map { it.distMm / 1000.0 }
                b.takeLast(8) to mapOf(
                    "ok" to okv.size, "n" to v.size,
                    "median_m" to Stats.median(okv), "std_m" to Stats.std(okv),
                    "min_m" to okv.minOrNull(), "max_m" to okv.maxOrNull()
                )
            }
        ))
    }

    private fun summarize(no: Int, ap: ScanResult): String {
        val b = ap.BSSID ?: "?"
        val all = _rows.value.filter { it.bssid == b }
        val ok = all.filter { it.ok }
        if (ok.isEmpty()) {
            return "#$no ${(ap.SSID ?: "?").take(16)} ${b.takeLast(8)}  成功 0/${all.size}，" +
                "失败原因样本：${all.firstOrNull()?.detail ?: "-"}"
        }
        val m = ok.map { it.distMm / 1000.0 }
        val sd = ok.map { it.stdMm / 1000.0 }.filter { it >= 0 }
        return "#%d %-16s %s  成功 %d/%d  中位=%sm 极差=[%s,%s]m 组内标准差=%sm 设备自报std=%sm 均值耗时=%dms".format(
            no, (ap.SSID ?: "?").take(16), b.takeLast(8), ok.size, all.size,
            Stats.f(Stats.median(m), 2), Stats.f(m.minOrNull(), 2), Stats.f(m.maxOrNull(), 2),
            Stats.f(Stats.std(m), 2), Stats.f(Stats.median(sd), 2),
            all.map { it.ms }.average().toLong()
        )
    }

    private suspend fun rangeOnce(ctx: Context, ap: ScanResult): Out =
        suspendCancellableCoroutine { cont ->
            val m = mgr(ctx)
            if (m == null) {
                cont.resume(Out(false, -1, -1, 0, 0, ap.level, "取不到 WifiRttManager"))
                return@suspendCancellableCoroutine
            }
            try {
                @Suppress("DEPRECATION")
                val req = android.net.wifi.rtt.RangingRequest.Builder().addAccessPoint(ap).build()
                @Suppress("DEPRECATION")
                val cb = object : android.net.wifi.rtt.RangingResultCallback() {
                    override fun onRangingFailure(code: Int) {
                        if (cont.isActive) cont.resume(Out(false, -1, -1, 0, 0, ap.level, "onRangingFailure=$code"))
                    }

                    @Suppress("DEPRECATION")
                    override fun onRangingResults(results: MutableList<android.net.wifi.rtt.RangingResult>) {
                        val r = results.firstOrNull()
                        if (r == null) {
                            if (cont.isActive) cont.resume(Out(false, -1, -1, 0, 0, ap.level, "空结果"))
                        } else {
                            val ok = r.status == android.net.wifi.rtt.RangingResult.STATUS_SUCCESS
                            if (cont.isActive) cont.resume(
                                Out(
                                    ok, r.distanceMm, runCatching { r.distanceStdDevMm }.getOrDefault(-1),
                                    runCatching { r.numSuccessfulMeasurements }.getOrDefault(0),
                                    runCatching { r.numAttemptedMeasurements }.getOrDefault(0),
                                    r.rssi, "status=${r.status}"
                                )
                            )
                        }
                    }
                }
                m.startRanging(req, exec, cb)
            } catch (t: Throwable) {
                if (cont.isActive) cont.resume(
                    Out(false, -1, -1, 0, 0, ap.level, "异常 ${t.javaClass.simpleName}: ${t.message}")
                )
            }
        }
}
