package com.mingh.wifianchor.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.content.ClipData
import android.content.ClipboardManager
import android.provider.MediaStore

object Env {

    fun hasPerm(ctx: Context, perm: String): Boolean =
        ctx.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED

    /** 系统定位总开关：Android 9+ 不开则 Wi-Fi 扫描恒空 */
    fun locationServicesOn(ctx: Context): Boolean = try {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (Build.VERSION.SDK_INT >= 28) lm.isLocationEnabled
        else {
            val mode = Settings.Secure.getString(
                ctx.contentResolver, "location_mode"
            )
            mode != null && mode != "0"
        }
    } catch (t: Throwable) {
        false
    }

    fun providerOn(ctx: Context, provider: String): Boolean = try {
        (ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager)
            .isProviderEnabled(provider)
    } catch (t: Throwable) {
        false
    }

    /** 「设置-位置-Wi-Fi 扫描」开关，影响我们能否蹭到系统被动扫描。读不到返回 n/a */
    fun wifiScanAlwaysEnabled(ctx: Context): String = try {
        when (Settings.Secure.getInt(ctx.contentResolver, "wifi_scan_always_enabled", -1)) {
            1 -> "开"
            0 -> "关"
            else -> "n/a"
        }
    } catch (t: Throwable) {
        "n/a"
    }

    /** getCurrentStandbyBucket 在部分 SDK 里属隐藏 API（本机 android.jar 就没有），反射取；取不到就 n/a */
    fun standbyBucket(ctx: Context): String = try {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        val m = runCatching { PowerManager::class.java.getMethod("getCurrentStandbyBucket") }.getOrNull()
        when (m?.invoke(pm) as? Int) {
            10 -> "active"
            20 -> "working_set"
            30 -> "frequency"
            40 -> "rare"
            50 -> "restricted"
            null -> "n/a（getCurrentStandbyBucket 非公开 API，厂商 ROM 屏蔽）"
            else -> m.invoke(pm).toString()
        }
    } catch (t: Throwable) {
        "n/a"
    }

    fun ignoringBatteryOpt(ctx: Context): String = try {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (Build.VERSION.SDK_INT >= 23)
            if (pm.isIgnoringBatteryOptimizations(ctx.packageName)) "已豁免" else "受限"
        else "n/a"
    } catch (t: Throwable) {
        "n/a"
    }

    fun copy(ctx: Context, label: String, text: String) {
        try {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText(label, text))
        } catch (t: Throwable) {
            // 某些 ROM 剪贴板受限：让调用方用文件导出兜底
        }
    }

    fun goLocationSettings(ctx: Context) = runCatching {
        ctx.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun goWifiSettings(ctx: Context) = runCatching {
        ctx.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun goAppPermissions(ctx: Context) = runCatching {
        val i = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
        i.data = Uri.parse("package:" + ctx.packageName)
        ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure {
        runCatching {
            val i = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            i.data = Uri.parse("package:" + ctx.packageName)
            ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    fun goBatterySettings(ctx: Context) = runCatching {
        val i = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** 关掉 Wi-Fi 扫描限流（开发者选项），用于对照实验；不能替用户开 */
    fun goScanThrottleSettings(ctx: Context) = runCatching {
        val i = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
        ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** 只做事实性统计，不做判断：判断留给离线分析，避免在 App 里预先下结论 */
object Stats {
    fun median(xs: List<Double>): Double? = if (xs.isEmpty()) null else sorted(xs)[xs.size / 2]
    fun mean(xs: List<Double>): Double? = if (xs.isEmpty()) null else xs.sum() / xs.size
    fun std(xs: List<Double>): Double? {
        if (xs.size < 2) return null
        val m = xs.sum() / xs.size
        return Math.sqrt(xs.map { (it - m) * (it - m) }.sum() / (xs.size - 1))
    }
    fun quantile(xs: List<Double>, q: Double): Double? =
        if (xs.isEmpty()) null else sorted(xs)[(q * (xs.size - 1) + 0.5).toInt().coerceIn(0, xs.size - 1)]
    fun min(xs: List<Double>) = xs.minOrNull()
    fun max(xs: List<Double>) = xs.maxOrNull()

    private fun sorted(xs: List<Double>) = xs.sorted()

    fun one(label: String, xs: List<Double>, unit: String = ""): String {
        if (xs.isEmpty()) return "$label: 无样本"
        val m = mean(xs)!!
        val sd = std(xs)
        val med = median(xs)!!
        val sb = StringBuilder()
        sb.append("$label: n=${xs.size} 均值=${f(m)} 中位=${f(med)}")
        if (sd != null) sb.append(" 标准差=${f(sd)}")
        sb.append(" 范围=[${f(min(xs)!!)}, ${f(max(xs)!!)}]")
        if (unit.isNotEmpty()) sb.append(" $unit")
        return sb.toString()
    }

    fun f(x: Double?, digits: Int = 1): String =
        if (x == null) "-" else String.format("%.${digits}f", x)
}
