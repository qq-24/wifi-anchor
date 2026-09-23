package com.mingh.wifianchor.probe

import android.content.Context
import android.os.SystemClock
import com.mingh.wifianchor.AnchorService
import com.mingh.wifianchor.util.Stats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 一键体检：约 90 秒跑完探针要回答的 Q1~Q6，产出一段可直接粘给我的文本。
 * 手机连不上电脑，所以「屏上能看懂 + 能复制」就是这个 App 的接口。
 * 这里刻意只列判据数字，不写结论 —— 结论等原始数据回来离线算。
 */
object Checkup {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _text = MutableStateFlow("")
    val text: StateFlow<String> = _text
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running
    private val _step = MutableStateFlow("")
    val step: StateFlow<String> = _step

    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    fun run(ctx: Context) {
        if (_running.value) return
        scope.launch { runSuspend(ctx) }
    }

    suspend fun runSuspend(ctx: Context) {
        _running.value = true
        val sb = StringBuilder()
        fun p(s: String) {
            sb.append(s).append('\n')
            _text.value = sb.toString()
        }
        try {
            ScanHub.refreshFlags()
            _step.value = "1/6 取第一帧"
            p("=== WiFi 锚点探针 一键体检 ===")
            p("时间 ${fmt.format(Date())}   ${probeName()}")
            val f0 = ScanHub.ensureFresh(15000)
            if (f0 == null) {
                p("!! 拿不到任何扫描结果。先看下面「能力」段的 Wi-Fi/定位/权限三项，八成是其中之一没开。")
            }

            _step.value = "2/6 能力诊断"
            p("")
            p(ScanHub.statusText().trimEnd())
            p("")
            p(Caps.asText(Caps.collect(ctx)))

            _step.value = "3/6 被动观察 24s（不主动扫描，看缓存会不会自己刷新）"
            p("")
            p("--- Q5 不靠自己扫描能不能拿到新数据（24s，每 2s 读一次缓存）---")
            var polls = 0
            var refreshes = 0
            var lastTs = -1L
            val ages = ArrayList<Double>()
            val apCounts = ArrayList<Double>()
            repeat(12) {
                val rs = ScanHub.latestResults()
                if (rs.isNotEmpty()) {
                    val mx = maxTsOf(rs)
                    if (lastTs >= 0 && mx > lastTs + 100_000) refreshes++
                    if (mx > 0) lastTs = mx
                    val f = ScanHub.snapshot("passive", null)
                    if (f != null && f.ageSane) ages += f.ageMs.toDouble()
                    apCounts += rs.size.toDouble()
                    polls++
                }
                delay(2000)
            }
            p("读缓存成功 $polls 次；其中未主动扫描却看到时间戳前进 $refreshes 次")
            p(Stats.one("缓存年龄", ages, "ms"))
            p(Stats.one("可见 AP 数", apCounts, "台"))
            p("扫描广播累计 ${ScanHub.state.value.bcCount} 次（resultsUpdated=true ${ScanHub.state.value.bcWithNew} 次）")
            ScanHub.event("checkup_passive", mapOf("polls" to polls, "refreshes" to refreshes,
                "bc" to ScanHub.state.value.bcCount))

            _step.value = "4/6 主动扫描配额（6 次连发）"
            p("")
            p("--- Q4 本机真实扫描配额：官方口径前台 4 次/2 分钟，OEM 常改 ---")
            ScanHub.quotaTestSuspend(6, 500, AnchorService.running)
            for (r in ScanHub.quotaRows.value) p(r)
            if (AnchorService.running) {
                p("（本轮是在前台服务开着的状态下测的）")
            }

            _step.value = "5/6 FTM 响应器与 RTT 测距"
            p("")
            p("--- Q2/Q3 AP 侧支持不支持 802.11mc，支持的话测距抖多大 ---")
            val resp = respondersOf()
            p("扫描到 ${ScanHub.state.value.apCount} 台 AP，其中宣告 FTM 响应器 ${resp.size} 台")
            for (line in ScanHub.lastFrame()?.aps?.take(14)?.map { it.toShort() } ?: emptyList()) p("   $line")
            if (resp.isNotEmpty()) {
                RttHub.runSuspend(ctx, 3, 6)
                for (s in RttHub.summary.value) p(s)
            } else {
                p("0 台响应器 → 这台机器 + 这个网络给不了真米数；R3 只能走指纹分级（见 SPEC §4.3）")
            }

            _step.value = "6/6 连接 RSSI 心跳 10s"
            p("")
            p("--- Q6 已连接 AP 的 RSSI 能不能当高频心跳 ---")
            val hb = ArrayList<Double>()
            var bad = 0
            repeat(10) {
                val c = ScanHub.connOf()
                if (c == null || c.bssid.isEmpty() || c.rssi == 0) bad++ else hb += c.rssi.toDouble()
                delay(1000)
            }
            val conn = ScanHub.connOf()
            p("连接：${conn?.ssid ?: "无"} / ${conn?.bssid?.takeLast(8) ?: "-"} / ch${conn?.freq ?: 0}")
            p("有效样本 ${hb.size}，读不到 $bad 次")
            p(Stats.one("连接RSSI", hb, "dBm"))

            p("")
            p("=== 判据汇总（把这四个数带回去就够定位问题）===")
            p("A. RTT 能力：${rttFeatureLine(ctx)}")
            p("B. FTM 响应器台数：${resp.size}")
            p("C. 未主动扫描时缓存刷新：$refreshes/${polls} 次")
            p("D. 6 次连发 startScan 成功：${ScanHub.quotaRows.value.count { it.contains("TRUE") }} 次")
            p("原始帧已写入 probe/ 目录，导出页可存成文件或分享。")

            val c = ctx
            val out = File(ScanHub.dir(c), "checkup-${ScanHub.dayFmt.format(Date())}.txt")
            runCatching { out.writeText(sb.toString()) }
            ScanHub.event("checkup_done", mapOf("resp" to resp.size, "refreshes" to refreshes,
                "polls" to polls, "quota_ok" to ScanHub.quotaRows.value.count { it.contains("TRUE") }))
        } catch (t: Throwable) {
            p("!! 体检异常中断：${t.javaClass.simpleName} ${t.message}")
        } finally {
            _running.value = false
            _step.value = ""
        }
    }

    private fun probeName(): String =
        "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"

    private fun rttFeatureLine(ctx: Context): String = try {
        val ok = ctx.packageManager.hasSystemFeature("android.hardware.wifi.rtt") == true
        if (ok) "手机侧 FEATURE_WIFI_RTT 有" else "手机侧不支持 Wi-Fi RTT（FEATURE_WIFI_RTT 为假）"
    } catch (t: Throwable) {
        "查询异常"
    }

    private fun respondersOf() = RttHub.responders()

    private fun maxTsOf(rs: List<android.net.wifi.ScanResult>): Long =
        rs.mapNotNull { runCatching { it.timestamp }.getOrNull() }.maxOrNull() ?: -1L

    fun textNow(): String = _text.value
}
