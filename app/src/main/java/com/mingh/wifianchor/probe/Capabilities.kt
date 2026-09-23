package com.mingh.wifianchor.probe

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import com.mingh.wifianchor.util.Env
import java.lang.reflect.Modifier

/**
 * 能力诊断：手机侧能不能做 RTT、Wi-Fi 子系统声明了什么特性、权限与开关齐不齐。
 * 这里刻意不用任何 adb 命令——手机连不上电脑，所以一切必须在 App 内自证。
 */
object Caps {

    data class Row(val g: String, val k: String, val v: String, val note: String = "")

    private fun feat(pm: PackageManager, name: String): String = try {
        if (pm.hasSystemFeature(name)) "有" else "无"
    } catch (t: Throwable) {
        "异常"
    }

    fun collect(ctx: Context): List<Row> {
        val pm = ctx.packageManager
        val out = ArrayList<Row>()
        val wifi = ctx.getSystemService(Context.WIFI_SERVICE) as? WifiManager

        out += Row("设备", "机型", "${Build.MANUFACTURER} ${Build.MODEL}", "")
        out += Row("设备", "产品/设备", "${Build.PRODUCT} / ${Build.DEVICE}", "")
        out += Row("设备", "系统", "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
            Build.VERSION.INCREMENTAL)
        out += Row("设备", "硬件", Build.HARDWARE, Build.BRAND)

        out += Row("硬件能力", "android.hardware.wifi", feat(pm, PackageManager.FEATURE_WIFI), "")
        out += Row("硬件能力", "android.hardware.wifi.rtt", feat(pm, "android.hardware.wifi.rtt"),
            "决定能否测真实距离：为「无」则 R3 只能走指纹估算")
        out += Row("硬件能力", "location.network", feat(pm, PackageManager.FEATURE_LOCATION_NETWORK), "")
        out += Row("硬件能力", "location.gnss", feat(pm, PackageManager.FEATURE_LOCATION_GPS), "")

        val rttSvc = rttServiceInfo(ctx)
        out += rttSvc

        out += Row("权限", "ACCESS_FINE_LOCATION", if (Env.hasPerm(ctx, Manifest.permission.ACCESS_FINE_LOCATION)) "已给" else "未给",
            "Android 10+ 读扫描结果的硬条件")
        out += Row("权限", "ACCESS_COARSE_LOCATION", if (Env.hasPerm(ctx, Manifest.permission.ACCESS_COARSE_LOCATION)) "已给" else "未给", "")
        out += Row("权限", "NEARBY_WIFI_DEVICES", if (Build.VERSION.SDK_INT >= 33)
            (if (Env.hasPerm(ctx, "android.permission.NEARBY_WIFI_DEVICES")) "已给" else "未给") else "不需要(SDK<33)", "")
        out += Row("权限", "CHANGE_WIFI_STATE", if (Env.hasPerm(ctx, Manifest.permission.CHANGE_WIFI_STATE)) "已给" else "未给",
            "startScan 需要")
        out += Row("权限", "POST_NOTIFICATIONS", if (Build.VERSION.SDK_INT >= 33)
            (if (Env.hasPerm(ctx, Manifest.permission.POST_NOTIFICATIONS)) "已给" else "未给") else "不需要", "前台服务通知可见性")

        out += Row("系统开关", "Wi-Fi", if (wifi?.isWifiEnabled == true) "开" else "关", "")
        out += Row("系统开关", "定位服务总开关", if (Env.locationServicesOn(ctx)) "开" else "关",
            "Android 9+ 关了就扫不到东西")
        out += Row("系统开关", "GPS provider", if (Env.providerOn(ctx, "gps")) "开" else "关", "")
        out += Row("系统开关", "network provider", if (Env.providerOn(ctx, "network")) "开" else "关",
            "开着才可能蹭到系统被动扫描")
        out += Row("系统开关", "passive provider", if (Env.providerOn(ctx, "passive")) "开" else "关", "")
        out += Row("系统开关", "设置里的 Wi-Fi 扫描", Env.wifiScanAlwaysEnabled(ctx),
            "「设置-位置-扫描-Wi-Fi 扫描」，决定系统会不会替 App 扫")

        out += Row("Wi-Fi 子系统", "isScanAlwaysAvailable",
            try { if (wifi?.isScanAlwaysAvailable() == true) "是" else "否" } catch (t: Throwable) { "异常" }, "")
        out += Row("Wi-Fi 子系统", "connectionInfo", connText(wifi), "连着哪台 AP；心跳通道用它")

        out += Row("省电状态", "standby bucket", Env.standbyBucket(ctx), "影响后台被冻结的程度")
        out += Row("省电状态", "电池优化", Env.ignoringBatteryOpt(ctx), "")
        out += Row("省电状态", "电量", battery(ctx), "")

        out += Row("原始", "supportedFeatures", supportedFeatures(wifi),
            "位图与 AOSP 常量名对照，反射取到多少列多少")
        return out
    }

    private fun connText(wifi: WifiManager?): String {
        val ci = try { wifi?.connectionInfo } catch (t: Throwable) { null } ?: return "未连接/读不到"
        val s = ci.ssid ?: "?"
        val b = ci.bssid ?: "?"
        val f = runCatching { ci.frequency }.getOrDefault(0)
        return "$s / ${b.takeLast(8)} / ${ci.rssi}dBm / ch$f"
    }

    private fun battery(ctx: Context): String = try {
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        if (bm == null) "n/a" else "${bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)}%"
    } catch (t: Throwable) {
        "n/a"
    }

    /**
     * RTT 服务入口。实测事实：本机 compileSdk 34 的 android.jar 里只有
     * android.net.wifi.rtt.*（android/net/rtt/ 一个类都没有），新版 RttManager 编译期不可用，
     * 而旧入口在 Android 12+ 运行时仍然提供，所以统一走 WifiRttManager。
     */
    private fun rttServiceInfo(ctx: Context): List<Row> {
        val out = ArrayList<Row>()
        if (Build.VERSION.SDK_INT < 28) {
            out += Row("RTT", "服务", "API < 28 无 RTT", "该系统版本不支持 Wi-Fi RTT")
            return out
        }
        val m = try {
            @Suppress("DEPRECATION")
            ctx.getSystemService(Context.WIFI_RTT_RANGING_SERVICE) as? android.net.wifi.rtt.WifiRttManager
        } catch (t: Throwable) {
            null
        }
        out += Row("RTT", "WifiRttManager", if (m != null) "存在" else "取不到",
            "取不到即该机 ROM 把旧入口删了，R3 只能走指纹")
        if (m != null) {
            out += Row("RTT", "isAvailable",
                try { if (m.isAvailable) "可用" else "当前不可用" } catch (t: Throwable) { "异常 ${t.javaClass.simpleName}" },
                "连着某些网络/被别的 App 占用/定位未开时会不可用")
        }
        return out
    }

    /** getSupportedFeatures 与 WIFI_FEATURE_* 常量在部分版本属隐藏 API：反射尽力，取不到就说明取不到 */
    private fun supportedFeatures(wifi: WifiManager?): String {
        if (wifi == null) return "无 WifiManager"
        return try {
            val mi = WifiManager::class.java.getMethod("getSupportedFeatures")
            val bits = mi.invoke(wifi) as Int
            val names = WifiManager::class.java.fields
                .filter { it.name.startsWith("WIFI_FEATURE_") && Modifier.isStatic(it.modifiers) }
                .mapNotNull { runCatching { it.name.removePrefix("WIFI_FEATURE_") to it.getInt(null) }.getOrNull() }
                .distinctBy { it.second }
            val hit = names.filter { bits and it.second != 0 }.map { it.first }
            "0x%08x".format(bits) + if (hit.isEmpty()) "（反射未取到常量名）" else "  " + hit.joinToString(",")
        } catch (t: Throwable) {
            "未暴露（${t.javaClass.simpleName}；该 API 非公开，厂商 ROM 普遍屏蔽，不影响结论）"
        }
    }

    fun asText(rows: List<Row>): String = buildString {
        var g = ""
        for (r in rows) {
            if (r.g != g) {
                g = r.g
                appendLine("[$g]")
            }
            appendLine("  ${r.k.padEnd(22)} = ${r.v}" + (if (r.note.isEmpty()) "" else "   // ${r.note}"))
        }
    }
}
