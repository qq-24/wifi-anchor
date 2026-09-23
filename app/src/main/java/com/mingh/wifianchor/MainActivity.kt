package com.mingh.wifianchor

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mingh.wifianchor.probe.Caps
import com.mingh.wifianchor.probe.Checkup
import com.mingh.wifianchor.probe.RttHub
import com.mingh.wifianchor.probe.ScanHub
import com.mingh.wifianchor.util.Env
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {

    private val wantedPerms: Array<String> by lazy {
        val l = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.CHANGE_WIFI_STATE
        )
        if (Build.VERSION.SDK_INT >= 33) {
            l.add("android.permission.NEARBY_WIFI_DEVICES")
            l.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        l.toTypedArray()
    }

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            ScanHub.refreshFlags()
            ScanHub.log("权限回调：${it.filterValues { g -> g }.keys.map { k -> k.substringAfterLast('.') }}")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ScanHub.init(this)
        ScanHub.refreshFlags()
        permLauncher.launch(wantedPerms)
        setContent {
            MaterialTheme {
                MainApp(wantedPerms) { permLauncher.launch(it) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        ScanHub.refreshFlags()
    }
}

private val TABS = listOf("总览", "能力", "扫描", "RTT", "采样", "导出")

@Composable
fun MainApp(perms: Array<String>, requestPerms: (Array<String>) -> Unit) {
    val ctx = LocalContext.current
    var tab by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    Scaffold(topBar = {
        Column {
            Text(
                "WiFi 锚点探针  v0.1",
                fontWeight = FontWeight.Bold, fontSize = 17.sp,
                modifier = Modifier.padding(start = 12.dp, top = 10.dp)
            )
            TabRow(selectedTabIndex = tab) {
                TABS.forEachIndexed { i, t ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t, fontSize = 13.sp) })
                }
            }
        }
    }) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(10.dp)
        ) {
            when (tab) {
                0 -> OverviewPage(ctx, perms, requestPerms, scope)
                1 -> CapsPage(ctx)
                2 -> ScanPage(ctx, scope)
                3 -> RttPage(ctx, scope)
                4 -> SessionPage(ctx)
                5 -> ExportPage(ctx)
            }
            Spacer(Modifier.height(30.dp))
        }
    }
}

// ---------------- 通用小件 ----------------

@Composable
fun H(t: String) {
    Text(t, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.padding(top = 12.dp, bottom = 2.dp))
}

@Composable
fun Mono(text: String, maxLines: Int = 200, size: Int = 11) {
    Column(Modifier.padding(top = 4.dp)) {
        Text(
            text,
            fontFamily = FontFamily.Monospace,
            fontSize = size.sp,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
fun Block(title: String, text: String, copyLabel: String = "复制本段") {
    val ctx = LocalContext.current
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(title, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text(copyLabel, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { Env.copy(ctx, title, text) })
            }
            Divider(Modifier.padding(vertical = 4.dp))
            Column(Modifier.height(300.dp).verticalScroll(rememberScrollState())) {
                Mono(text)
            }
        }
    }
}

@Composable
fun Btns(vararg items: Pair<String, () -> Unit>) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((t, f) in items) {
            Button(onClick = f, modifier = Modifier.weight(1f)) {
                Text(t, fontSize = 12.sp, maxLines = 2)
            }
        }
    }
}

@Composable
fun KV(k: String, v: String) {
    Text("$k：$v", fontSize = 12.sp, fontFamily = FontFamily.Monospace)
}

// ---------------- 页 0 总览 ----------------

@Composable
fun OverviewPage(ctx: Context, perms: Array<String>, requestPerms: (Array<String>) -> Unit, scope: kotlinx.coroutines.CoroutineScope) {
    val st by ScanHub.state.collectAsState()
    val running by Checkup.running.collectAsState()
    val step by Checkup.step.collectAsState()
    val text by Checkup.text.collectAsState()
    val logs by ScanHub.log.collectAsState()

    // 一进来先把第一帧拿到手，免得各页都是 0 台看着像坏了
    LaunchedEffect(Unit) {
        ScanHub.refreshFlags()
        ScanHub.ensureFresh(12000)
    }

    if (!st.hasPerm || !st.locOn || !st.wifiOn) {
        Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Column(Modifier.padding(10.dp)) {
                Text("开跑前三件事", fontWeight = FontWeight.Bold)
                KV("定位权限", if (st.hasPerm) "已给" else "未给 —— 不给读不到扫描结果")
                KV("系统定位开关", if (st.locOn) "已开" else "未开 —— Android 9+ 关了 Wi-Fi 扫描恒空")
                KV("Wi-Fi", if (st.wifiOn) "已开" else "未开")
                Btns(
                    "授予权限" to { requestPerms(perms) },
                    "定位设置" to { Env.goLocationSettings(ctx) },
                    "Wi-Fi 设置" to { Env.goWifiSettings(ctx) }
                )
            }
        }
    }

    H("一键体检（约 90 秒）")
    Text("一次跑完：能力诊断、缓存会不会自己刷新、扫描配额、FTM 响应器、RTT 测距、连接 RSSI 心跳。跑完把结果复制给我。",
        fontSize = 12.sp)
    Btns(if (running) "体检进行中…" to {} else "开始一键体检" to { Checkup.run(ctx) })
    if (running && step.isNotEmpty()) {
        LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 4.dp))
        Text(step, fontSize = 12.sp)
    }
    if (text.isNotEmpty()) {
        Block("体检结果（点右上复制）", text)
        Btns("复制全部" to { Env.copy(ctx, "checkup", text) })
    }

    H("当前状态")
    Mono(ScanHub.statusText())
    Btns("刷新一帧" to { ScanHub.snapshot("manual", null) },
        "重读权限/开关" to { ScanHub.refreshFlags() })

    H("实时日志（最近 25 行）")
    Mono(logs.takeLast(25).joinToString("\n"))
}

// ---------------- 页 1 能力 ----------------

@Composable
fun CapsPage(ctx: Context) {
    var rows by remember { mutableStateOf(Caps.collect(ctx)) }
    val txt = Caps.asText(rows)
    Btns("重新采集" to { rows = Caps.collect(ctx) },
        "复制" to { Env.copy(ctx, "caps", txt) })
    H("跳转")
    Btns("定位设置" to { Env.goLocationSettings(ctx) },
        "Wi-Fi 设置" to { Env.goWifiSettings(ctx) },
        "本应用权限" to { Env.goAppPermissions(ctx) })
    Btns("电池优化" to { Env.goBatterySettings(ctx) },
        "开发者选项" to { Env.goScanThrottleSettings(ctx) })
    H("能力清单")
    Text("关注两行：android.hardware.wifi.rtt 决定能不能测真米数；Wi-Fi 扫描（设置里那个）决定能不能蹭系统扫描。",
        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Mono(txt)
}

// ---------------- 页 2 扫描配额与新鲜度 ----------------

@Composable
fun ScanPage(ctx: Context, scope: kotlinx.coroutines.CoroutineScope) {
    val st by ScanHub.state.collectAsState()
    val quota by ScanHub.quotaRows.collectAsState()
    val heart by ScanHub.heartRows.collectAsState()
    val frames by ScanHub.frames.collectAsState()
    val fgs by AnchorUi.running.collectAsState()

    H("通道说明")
    Text("A 主动扫描（受配额）／B 蹭系统被动扫描（不花配额）／C 已连接 AP 的 RSSI（秒级）。这一页量的是 B 和 A。",
        fontSize = 12.sp)

    H("前台服务（对照实验用）")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("AnchorService：${if (fgs) "运行中" else "未运行"}", fontSize = 12.sp)
        Spacer(Modifier.width(10.dp))
        Switch(checked = fgs, onCheckedChange = { AnchorUi.toggle(ctx, it) })
    }

    H("主动扫描配额实测（Q4）")
    Text("官方口径：前台 4 次/2 分钟，后台合计 30 分钟 1 次；超限 startScan 返回 false 且给你旧结果。各厂商常改，必须现测。",
        fontSize = 12.sp)
    Btns("6 次连发" to { scope.launch { ScanHub.quotaTestSuspend(6, 500, fgs) } },
        "12 次连发" to { scope.launch { ScanHub.quotaTestSuspend(12, 500, fgs) } })
    if (quota.isNotEmpty()) Block("startScan 逐次结果", quota.joinToString("\n"))

    H("被动观察（Q5）：期间完全不主动扫描")
    Text("只按间隔读缓存，看时间戳会不会自己前进。前进说明系统/别的 App 在替我们扫，后台常驻就有救。",
        fontSize = 12.sp)
    Btns("跑 3 分钟" to { ScanHub.startPassiveObserve(180_000, 2000) },
        "跑 10 分钟" to { ScanHub.startPassiveObserve(600_000, 3000) },
        "停止" to { ScanHub.stopPassiveObserve() })
    KV("读缓存次数", "${st.pollCount}")
    KV("未主动扫描却刷新", "${st.refreshWithoutOwnScan}")
    KV("扫描广播次数", "${st.bcCount}（resultsUpdated=true ${st.bcWithNew}）")
    KV("最近一帧缓存年龄", "${st.lastAgeMs} ms   ${st.apCount} 台 AP，FTM ${st.ftmCount} 台")

    H("扫描结果回调监听（Android 13+，比轮询准）")
    val cb by ScanHub.cbCount.collectAsState()
    Text("registerScanResultsCallback 只在系统真的更新扫描结果时回调，能干净区分「新数据」和「读到旧缓存」。",
        fontSize = 12.sp)
    Btns("监听 60 秒" to { ScanHub.startCallbackWatch(60) },
        "监听 5 分钟" to { ScanHub.startCallbackWatch(300) })
    KV("本轮回调次数", "$cb")

    H("已连接 AP 的 RSSI 心跳（Q6）")
    Text("这条通道不触发扫描，理论上能秒级读，是省电设计的关键假设。需要手机正连着宿舍 Wi-Fi。",
        fontSize = 12.sp)
    Btns("采 60 秒" to { ScanHub.startHeartbeat(60, 1000) },
        "采 10 秒" to { ScanHub.startHeartbeat(10, 1000) })
    Mono("最近 ${heart.size} 个：${heart.takeLast(60).joinToString(" ")}")

    H("最近帧")
    Mono(frames.take(8).mapIndexed { i, f ->
        "#${frames.size - i} ${ScanHub.tsFmt.format(java.util.Date(f.tWall))} ${f.source} ${f.aps.size}台 age=${f.ageMs}ms sane=${f.ageSane}"
    }.joinToString("\n"))
    frames.firstOrNull()?.let { f ->
        H("这一帧的 AP（按强度排序，前 16 台）")
        Mono(f.aps.take(16).map { it.toShort() }.joinToString("\n"))
        Btns("复制这一帧全部 AP" to {
            Env.copy(ctx, "aps", f.aps.joinToString("\n") { s -> s.toShort() })
        })
    }
}

// ---------------- 页 3 RTT ----------------

@Composable
fun RttPage(ctx: Context, scope: kotlinx.coroutines.CoroutineScope) {
    val summary by RttHub.summary.collectAsState()
    val busy by RttHub.busy.collectAsState()
    val rows by RttHub.rows.collectAsState()
    val st by ScanHub.state.collectAsState()
    var targets by remember { mutableStateOf(3) }
    var rounds by remember { mutableStateOf(8) }

    H("这一步在决定什么")
    Text("AP 支持 802.11mc 才能测真实距离。is80211mcResponder 是 App 里直接可读的标志位，不用猜、不用问网管。",
        fontSize = 12.sp)
    KV("扫描到 AP", "${st.apCount} 台")
    KV("宣告 FTM 响应器", "${st.ftmCount} 台")
    KV("手机 RTT 能力", if (ctx.packageManager.hasSystemFeature("android.hardware.wifi.rtt")) "有" else "无")
    KV("RTT 入口", RttHub.serviceInfo(ctx))
    Btns("先刷新一帧" to { scope.launch { ScanHub.ensureFresh() } },
        "复制 AP 清单" to { Env.copy(ctx, "aps", RttHub.allApLines().joinToString("\n")) })

    Mono(RttHub.allApLines().take(20).joinToString("\n"))

    H("测距")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("台数 $targets", fontSize = 12.sp)
        OutlinedButton(onClick = { targets = (targets % 6) + 1 }, modifier = Modifier.width(90.dp)) { Text("改") }
        Spacer(Modifier.width(10.dp))
        Text("轮数 $rounds", fontSize = 12.sp)
        OutlinedButton(onClick = { rounds = if (rounds >= 20) 4 else rounds + 4 }, modifier = Modifier.width(90.dp)) { Text("改") }
    }
    Btns(if (busy != null) "$busy…" to {} else "开始测距" to { RttHub.run(ctx, targets, rounds) },
        "停止" to { RttHub.stop() })
    if (summary.isNotEmpty()) Block("每台 AP 的测距统计", summary.joinToString("\n"))
    if (rows.isNotEmpty()) {
        Mono("逐轮（最近 24 条）\n" + rows.takeLast(24).joinToString("\n") { r ->
            "${r.bssid.takeLast(8)} 第${r.round}轮 ${if (r.ok) "%.2fm".format(r.distMm / 1000.0) else "失败"} ${r.rssi}dBm ${r.ms}ms ${r.detail}"
        })
    }
    H("没有响应器时怎么看")
    Text("若 FTM 台数为 0：要么宿舍 AP 关了 FTM，要么这一帧是旧缓存。先在「扫描」页确认能拿到新数据，再下结论。",
        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

// ---------------- 页 4 点位采样 ----------------

private val PRESETS = listOf("宿舍-桌前", "宿舍-床边", "同层厕所", "走廊", "楼梯口", "别的房间")

@Composable
fun SessionPage(ctx: Context) {
    val st by ScanHub.state.collectAsState()
    var name by remember { mutableStateOf(PRESETS[0]) }
    var frames by remember { mutableStateOf(40) }
    var interval by remember { mutableStateOf(3) }

    H("录一个点位（跨时间可复现性是这条路的生死判据）")
    Text("在某个位置站住不动，按「开始」让它连拍。同一个点位要在早/午/晚各录一次，隔几天再录一次，我才算得出漂移和阈值。",
        fontSize = 12.sp)
    OutlinedTextField(
        value = name, onValueChange = { name = it }, label = { Text("点位名") },
        singleLine = true, modifier = Modifier.fillMaxWidth()
    )
    Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        PRESETS.forEach { p ->
            OutlinedButton(onClick = { name = p }) { Text(p, fontSize = 11.sp) }
        }
    }
    Btns("帧数改（现 $frames）" to { frames = if (frames >= 80) 20 else frames + 20 },
        "间隔改（现 ${interval}s）" to { interval = if (interval >= 8) 2 else interval + 1 })
    if (st.recording != null) {
        LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 4.dp))
        Text("正在录制 ${st.recording}：已 ${st.sessionFrames} 帧", fontSize = 12.sp)
        Btns("停止录制" to { ScanHub.stopSession() })
    } else {
        Btns("开始录制" to { ScanHub.startSession(name, frames, interval * 1000L) })
    }
    H("点位建议清单（跑完这一趟我就有足够数据算阈值）")
    Mono(
        "1 宿舍-桌前   （早/午/晚各一次）\n" +
            "2 宿舍-床边   （同上）\n" +
            "3 同层厕所    （同上，注意门开关状态请写进备注）\n" +
            "4 走廊-宿舍门口\n" +
            "5 楼梯口\n" +
            "6 别的楼层一间房（验证跨层会不会混）\n" +
            "同一位置跨天再录一轮，是判断「明天还好不好使」的唯一办法。"
    )
    H("已生成的文件")
    Mono(ScanHub.files().joinToString("\n") { "${it.name}  ${it.length() / 1024}KB" }.ifBlank { "（无）" })
}

// ---------------- 页 5 导出 ----------------

@Composable
fun ExportPage(ctx: Context) {
    val files = ScanHub.files()
    var tick by remember { mutableStateOf(0) }
    val merged = remember(files.map { it.name to it.length() }.toString() + tick) {
        buildString {
            for (f in files.sortedBy { it.name }) {
                if (f.extension != "jsonl" && f.extension != "txt") continue
                appendLine("### file=${f.name}")
                runCatching { append(f.readText()) }.onFailure { appendLine("读取失败 ${it.message}") }
            }
        }
    }
    val saver = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        if (uri != null) {
            runCatching {
                ctx.contentResolver.openOutputStream(uri)?.use { os ->
                    os.write(merged.toByteArray())
                }
                ScanHub.log("已导出 ${merged.length} 字符到 ${uri.lastPathSegment}")
            }.onFailure { ScanHub.log("导出失败: ${it.message}") }
        }
    }

    H("为什么要有这一页")
    Text("手机连不上电脑，所以数据只能靠这里出去：存成文件（可用微信/QQ 发我）、或直接复制文本粘贴给我。",
        fontSize = 12.sp)
    Btns("刷新列表" to { tick++ },
        "存成文件" to { saver.launch("wifi-anchor-${ScanHub.dayFmt.format(java.util.Date())}.jsonl") })
    Btns("复制全部（${merged.length} 字符）" to { Env.copy(ctx, "export", merged) },
        "只复制体检结果" to { Env.copy(ctx, "checkup", Checkup.textNow()) })
    Btns("分享文本" to {
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, merged.take(120_000))
            putExtra(Intent.EXTRA_SUBJECT, "wifi-anchor 数据")
        }
        runCatching { ctx.startActivity(Intent.createChooser(i, "分享数据")) }
            .onFailure { ScanHub.log("分享不可用: ${it.message}") }
    })
    H("文件目录")
    Text(ScanHub.dir(ctx).absolutePath, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
    Mono(files.joinToString("\n") { "${it.name}   ${it.length() / 1024} KB" }.ifBlank { "（还没有数据，先去总览页跑一键体检）" })
    H("数据量")
    KV("合并后字符数", "${merged.length}")
    Btns("删除全部探针数据" to {
        files.forEach { runCatching { it.delete() } }
        tick++
        ScanHub.log("已清空 probe 目录")
    })
    H("交接清单")
    Mono(
        "跑完请把这些给我：\n" +
            "1 总览页「一键体检」的复制文本\n" +
            "2 扫描页 12 次连发的截图或复制文本\n" +
            "3 采样页录的点位文件（整包导出即可）\n" +
            "4 RTT 页结果（哪怕显示 0 台响应器也要告诉我）\n" +
            "5 一句话：手机型号 + 系统版本 + 是否连着宿舍 Wi-Fi"
    )
}
