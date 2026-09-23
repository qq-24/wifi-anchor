package com.mingh.wifianchor.probe

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import com.mingh.wifianchor.model.Frames
import com.mingh.wifianchor.model.ConnSnap
import com.mingh.wifianchor.model.Frame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
 * 探针的心脏：三种取数路径都汇到这里，因为它们回答的是同一个问题——
 * 「这台机器上多久能拿到一次新指纹」。
 *  A 主动 startScan（受配额）
 *  B 蹭系统/别的 App 的被动扫描广播（不花自己配额）
 *  C 已连接 AP 的 RSSI（秒级，不触发扫描）
 */
object ScanHub {

    data class HubState(
        val wifiOn: Boolean = false,
        val hasPerm: Boolean = false,
        val locOn: Boolean = false,
        val bcCount: Int = 0,
        val bcWithNew: Int = 0,
        val pollCount: Int = 0,
        val refreshWithoutOwnScan: Int = 0,
        val lastAgeMs: Long = -1,
        val lastFrameAt: Long = 0,
        val apCount: Int = 0,
        val ftmCount: Int = 0,
        val recording: String? = null,
        val sessionFrames: Int = 0,
        val sessionDistinct: Int = 0,
        val busy: String? = null
    )

    private var ctx: Context? = null
    private var wifi: WifiManager? = null

    /** 会话内申请主动扫描的最小间隔：贴着实测的 4 次/2 分钟用，不超发 */
    private const val SCAN_ASK_GAP_MS = 28_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(HubState())
    val state: StateFlow<HubState> = _state

    private val _frames = MutableStateFlow<List<Frame>>(emptyList())
    val frames: StateFlow<List<Frame>> = _frames

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log

    private val _quotaRows = MutableStateFlow<List<String>>(emptyList())
    val quotaRows: StateFlow<List<String>> = _quotaRows

    private val _heartRows = MutableStateFlow<List<Int>>(emptyList())
    val heartRows: StateFlow<List<Int>> = _heartRows

    private val _bcTimes = MutableStateFlow<List<Long>>(emptyList())
    val bcTimes: StateFlow<List<Long>> = _bcTimes

    private var newestTsSeen: Long = 0
    private var ownScanPending = false
    private var recv: BroadcastReceiver? = null
    private var pollJob: Job? = null
    private var quotaJob: Job? = null
    private var heartJob: Job? = null

    private var sessionFile: File? = null
    private var sessionName: String = ""
    private var sessionSeq: Int = 0
    private var eventsFile: File? = null

    val tsFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    val dayFmt = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US)

    fun dir(ctx: Context): File {
        val d = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "probe")
        if (!d.exists()) d.mkdirs()
        return d
    }

    fun init(context: Context) {
        if (ctx != null) return
        ctx = context.applicationContext
        wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        eventsFile = File(dir(context.applicationContext), "events.jsonl")
        register()
    }

    private fun register() {
        val c = ctx ?: return
        if (recv != null) return
        recv = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) return
                val upd = if (Build.VERSION.SDK_INT >= 26)
                    intent.hasExtra(WifiManager.EXTRA_RESULTS_UPDATED) &&
                        intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false)
                else null
                _bcTimes.value = (_bcTimes.value + SystemClock.elapsedRealtime()).takeLast(400)
                _state.value = _state.value.copy(
                    bcCount = _state.value.bcCount + 1,
                    bcWithNew = _state.value.bcWithNew + (if (upd == true) 1 else 0)
                )
                log("收到扫描广播 resultsUpdated=$upd")
                snapshot("broadcast", upd)
            }
        }
        val f = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        try {
            if (Build.VERSION.SDK_INT >= 33) c.registerReceiver(recv, f, Context.RECEIVER_NOT_EXPORTED)
            else c.registerReceiver(recv, f)
        } catch (t: Throwable) {
            log("注册广播失败: ${t.message}")
        }
    }

    fun lastFrame(): Frame? = _frames.value.firstOrNull()

    /** 首帧兜底：缓存为空就主动扫一次并等结果（新装 App 首次进来常这样） */
    suspend fun ensureFresh(maxWaitMs: Long = 15000): Frame? {
        lastFrame()?.let { return it }
        try { wifi?.startScan() } catch (t: Throwable) { }
        val end = SystemClock.elapsedRealtime() + maxWaitMs
        while (SystemClock.elapsedRealtime() < end) {
            delay(700)
            val n = runCatching { wifi?.scanResults?.size ?: 0 }.getOrDefault(0)
            if (n > 0) snapshot("ensure", null)?.let { return it }
        }
        log("等 ${maxWaitMs / 1000}s 仍拿不到任何扫描结果：请检查 Wi-Fi 是否打开、系统定位开关是否打开、定位权限是否授予")
        return null
    }

    fun latestResults(): List<android.net.wifi.ScanResult> = try {
        wifi?.scanResults ?: emptyList()
    } catch (t: SecurityException) {
        log("读扫描结果被拒（缺定位权限）: ${t.message}")
        emptyList()
    } catch (t: Throwable) {
        log("读扫描结果异常: ${t.javaClass.simpleName} ${t.message}")
        emptyList()
    }

    fun connOf(): ConnSnap? = try {
        Frames.connOf(wifi?.connectionInfo)
    } catch (t: Throwable) {
        null
    }

    /** 取一帧指纹，并维护「不靠自己扫描有没有刷新」的计数 */
    fun snapshot(source: String, resultsUpdated: Boolean?): Frame? {
        val rs = latestResults()
        if (rs.isEmpty()) {
            log("$source: 扫描结果为空（Wi-Fi 未开/权限未给/定位未开/被限流）")
            return null
        }
        val f = Frames.fromScan(rs, source, resultsUpdated, connOf())
        if (source == "poll") _state.value = _state.value.copy(pollCount = _state.value.pollCount + 1)
        _state.value = _state.value.copy(
            lastAgeMs = f.ageMs,
            lastFrameAt = f.tWall,
            apCount = f.aps.size,
            ftmCount = f.aps.count { it.mc || it.az }
        )
        _frames.value = (listOf(f) + _frames.value).take(300)
        if (sessionFile != null) writeFrame(f)
        return f
    }

    /**
     * 被动观察：完全不主动扫描，只按间隔读缓存。
     * 回答 Q5——系统的 Wi-Fi 扫描（定位服务在用）会不会替我们把缓存刷新。
     */
    fun startPassiveObserve(durationMs: Long, intervalMs: Long) {
        stopPassiveObserve()
        pollJob = scope.launch {
            val end = SystemClock.elapsedRealtime() + durationMs
            val tsSeen = mutableSetOf<Long>()
            var refreshes = 0
            _state.value = _state.value.copy(busy = "被动观察中")
            log("被动观察开始：${durationMs / 1000}s，每 ${intervalMs / 1000}s 读一次缓存，期间不主动扫描")
            var lastMax = -1L
            while (SystemClock.elapsedRealtime() < end) {
                val rs = latestResults()
                if (rs.isNotEmpty()) {
                    val mx = rs.mapNotNull { runCatching { it.timestamp }.getOrNull() }.maxOrNull() ?: -1L
                    if (lastMax >= 0 && mx > lastMax + 100_000) {
                        refreshes++
                        log("缓存在未主动扫描的情况下前进了 ${(mx - lastMax) / 1000} ms")
                    }
                    lastMax = if (mx > 0) mx else lastMax
                    tsSeen.add(mx)
                    val f = Frames.fromScan(rs, "poll", null, connOf())
                    _frames.value = (listOf(f) + _frames.value).take(300)
                    if (sessionFile != null) writeFrame(f)
                    _state.value = _state.value.copy(
                        pollCount = _state.value.pollCount + 1,
                        refreshWithoutOwnScan = refreshes,
                        lastAgeMs = f.ageMs,
                        lastFrameAt = f.tWall,
                        apCount = f.aps.size,
                        ftmCount = f.aps.count { it.mc || it.az }
                    )
                }
                delay(intervalMs)
            }
            _state.value = _state.value.copy(busy = null)
            log("被动观察结束：${tsSeen.size} 个不同时间戳，刷新 $refreshes 次")
            event("passive", mapOf("distinct_ts" to tsSeen.size, "refreshes" to refreshes,
                "duration_ms" to durationMs, "interval_ms" to intervalMs))
        }
    }

    fun stopPassiveObserve() {
        pollJob?.cancel(); pollJob = null
    }

    private val cbExec by lazy { java.util.concurrent.Executors.newSingleThreadExecutor() }
    private val _cbCount = MutableStateFlow(0)
    val cbCount: StateFlow<Int> = _cbCount

    /**
     * Android 13+ 的 registerScanResultsCallback：比轮询更干净地回答
     * 「系统到底有没有替我们扫」——回调一来就是新结果，不用猜。
     */
    fun startCallbackWatch(seconds: Int) {
        val w = wifi ?: return
        if (Build.VERSION.SDK_INT < 33) {
            log("扫描回调监听需要 Android 13+（本机 API ${Build.VERSION.SDK_INT}），请改用「被动观察」")
            return
        }
        scope.launch {
            _cbCount.value = 0
            _state.value = _state.value.copy(busy = "扫描回调监听中")
            val cb = object : WifiManager.ScanResultsCallback() {
                override fun onScanResultsAvailable() {
                    scope.launch {
                        _cbCount.value = _cbCount.value + 1
                        log("扫描回调第 ${_cbCount.value} 次到达")
                        snapshot("cbwatch", null)
                    }
                }
            }
            val reg = runCatching { w.registerScanResultsCallback(cbExec, cb) }
            if (reg.isFailure) {
                val e = reg.exceptionOrNull()
                log("注册扫描回调失败：${e?.javaClass?.simpleName} ${e?.message}（部分 ROM 要求额外特权，属正常）")
                _state.value = _state.value.copy(busy = null)
                return@launch
            }
            log("扫描回调监听开始：${seconds}s")
            delay(seconds * 1000L)
            runCatching { w.unregisterScanResultsCallback(cb) }
            _state.value = _state.value.copy(busy = null)
            log("扫描回调监听结束：${seconds}s 内收到 ${_cbCount.value} 次")
            event("cbwatch", mapOf("seconds" to seconds, "callbacks" to _cbCount.value))
        }
    }

    /**
     * 主动扫描配额实测（Q4）：连续调用 startScan() 看第几次开始返回 false，
     * 并记录广播到达时间以还原真实窗口。官方口径是前台 4 次/2 分钟，但 OEM 常改。
     */
    fun runQuotaTest(calls: Int = 10, gapMs: Long = 500, withFgs: Boolean) {
        quotaJob = scope.launch { quotaTestSuspend(calls, gapMs, withFgs) }
    }

    /** 挂起版：一键体检要按顺序等它跑完再往下走 */
    suspend fun quotaTestSuspend(calls: Int = 10, gapMs: Long = 500, withFgs: Boolean) {
        quotaJob?.cancel()
        run {
            _quotaRows.value = emptyList()
            _bcTimes.value = emptyList()
            _state.value = _state.value.copy(busy = "配额实测中")
            log("配额实测开始：${calls} 次 startScan，间隔 ${gapMs}ms，前台服务=${if (withFgs) "开" else "关"}")
            val t0 = SystemClock.elapsedRealtime()
            var ok = 0
            var fail = 0
            for (i in 1..calls) {
                val before = _bcTimes.value.size
                val c0 = SystemClock.elapsedRealtime()
                val ret = try {
                    wifi?.startScan() ?: false
                } catch (t: SecurityException) {
                    log("startScan 被拒：缺权限 ${t.message}")
                    false
                } catch (t: Throwable) {
                    log("startScan 异常：${t.javaClass.simpleName} ${t.message}")
                    false
                }
                val ms = SystemClock.elapsedRealtime() - c0
                if (ret) ok++ else fail++
                // 等一会儿看广播有没有来（限流时不会来）
                delay(1200)
                val bcArrived = _bcTimes.value.size > before
                ownScanPending = ret
                val row = "#%02d  +%4dms  startScan=%s  1.2s内广播=%s  耗时=%dms".format(
                    i, SystemClock.elapsedRealtime() - t0, if (ret) "TRUE " else "false",
                    if (bcArrived) "YES" else "no ", ms
                )
                _quotaRows.value = _quotaRows.value + row
                event("quota", mapOf("i" to i, "ret" to ret, "bc" to bcArrived,
                    "elapsed_ms" to SystemClock.elapsedRealtime() - t0, "fgs" to withFgs))
                delay(gapMs)
            }
            ownScanPending = false
            _state.value = _state.value.copy(busy = null)
            log("配额实测结束：成功 $ok / 失败 $fail（失败基本就是撞限流）")
            event("quota_summary", mapOf("calls" to calls, "ok" to ok, "fail" to fail,
                "gap_ms" to gapMs, "fgs" to withFgs))
        }
    }

    /**
     * 已连接 AP 的 RSSI 心跳（Q6）：这条通道不触发扫描，理论上可以秒级读，
     * 是后台省电设计的关键假设，必须量出来。
     */
    fun startHeartbeat(seconds: Int = 60, intervalMs: Long = 1000) {
        heartJob?.cancel()
        heartJob = scope.launch {
            _heartRows.value = emptyList()
            _state.value = _state.value.copy(busy = "心跳采集中")
            val c = connOf()
            if (c == null || c.bssid.isEmpty() || c.rssi == 0) {
                log("心跳：当前未连接 Wi-Fi，读不到 WifiInfo.rssi（请先连上宿舍网络再测）")
            }
            log("心跳开始：${seconds}s，每 ${intervalMs}ms 读一次连接 RSSI")
            val t0 = SystemClock.elapsedRealtime()
            var n = 0
            var invalid = 0
            repeat(seconds * (1000 / intervalMs.toInt())) {
                val cc = connOf()
                val r = cc?.rssi ?: 0
                if (r == 0 || r > -30) invalid++
                _heartRows.value = _heartRows.value + r
                n++
                delay(intervalMs)
            }
            _state.value = _state.value.copy(busy = null)
            val vals = _heartRows.value.filter { it != 0 }.map { it.toDouble() }
            log("心跳结束：$n 个样本，无效 $invalid 个；" +
                com.mingh.wifianchor.util.Stats.one("连接RSSI", vals, "dBm"))
            event("heartbeat", mapOf("n" to n, "invalid" to invalid,
                "interval_ms" to intervalMs,
                "values" to _heartRows.value.take(180),
                "elapsed_ms" to SystemClock.elapsedRealtime() - t0))
        }
    }

    // ---------- 会话与落盘 ----------

    /**
     * 会话录制。这里必须主动挤扫描——真机实测（Xiaomi 23127PN0CC / Android 16）前台配额
     * 就是官方口径的 4 次/2 分钟，而且不主动扫描时缓存 24 秒只前进 1 次：
     * 光读缓存连拍 40 帧，实际只捞到约 5 次真刷新，等于白录。
     * 所以按 28 秒一次的节奏贴着配额申请扫描（用满但不超发，省点电），
     * 用 ScanResult.timestamp 去重，界面上把「帧数」和「真刷新数」分开显示。
     */
    fun startSession(name: String, frames: Int, intervalMs: Long, note: String) {
        val c = ctx ?: return
        sessionName = name.ifBlank { "point" }
        sessionSeq = 0
        val f = File(dir(c), "frames-${dayFmt.format(Date())}-$sessionName.jsonl")
        sessionFile = f
        _state.value = _state.value.copy(
            recording = sessionName, sessionFrames = 0, sessionDistinct = 0
        )
        appendLine(
            f, Frames.metaLine(
                c,
                "session=$sessionName frames=$frames interval_ms=$intervalMs note=$note"
            )
        )
        log("会话开始录制：${f.name}")
        scope.launch {
            val seenTs = HashSet<Long>()
            var lastScanAsk = -999_000L
            var scans = 0
            var throttled = 0
            var i = 0
            while (i < frames) {
                val now = SystemClock.elapsedRealtime()
                if (now - lastScanAsk >= SCAN_ASK_GAP_MS) {
                    lastScanAsk = now
                    scans++
                    val ret = runCatching { wifi?.startScan() == true }.getOrDefault(false)
                    if (ret) delay(2000) else {
                        throttled++
                        log("会话内第 $scans 次 startScan 被限流（配额已用满），这一轮只读缓存")
                    }
                }
                val fr = snapshot("session", null)
                i++
                if (fr != null && fr.newestTsUs > 0) seenTs.add(fr.newestTsUs)
                _state.value = _state.value.copy(sessionFrames = i, sessionDistinct = seenTs.size)
                if (i < frames) delay(intervalMs)
            }
            _state.value = _state.value.copy(recording = null)
            log(
                "会话结束：$sessionSeq 帧，其中 ${seenTs.size} 次是真刷新" +
                    "（其余是同一份缓存的重复读），申请扫描 $scans 次、被限流 $throttled 次"
            )
            event(
                "session_done", mapOf(
                    "name" to sessionName, "frames" to sessionSeq, "distinct" to seenTs.size,
                    "scans" to scans, "throttled" to throttled, "note" to note
                )
            )
            sessionFile = null
        }
    }

    fun stopSession() {
        _state.value = _state.value.copy(recording = null, sessionFrames = 0)
        sessionFile = null
    }

    private fun writeFrame(f: Frame) {
        val file = sessionFile ?: return
        sessionSeq++
        appendLine(file, f.toJson(sessionName, sessionSeq).toString())
    }

    fun event(ev: String, kv: Map<String, Any?>) {
        val c = ctx ?: return
        val o = org.json.JSONObject()
        o.put("ev", ev)
        o.put("t", System.currentTimeMillis())
        for ((k, v) in kv) o.put(k, v ?: org.json.JSONObject.NULL)
        appendLine(File(dir(c), "events.jsonl"), o.toString())
    }

    private fun appendLine(f: File, line: String) {
        try {
            f.appendText(line + "\n")
        } catch (t: Throwable) {
            log("写文件失败 ${f.name}: ${t.message}")
        }
    }

    fun files(): List<File> {
        val c = ctx ?: return emptyList()
        return dir(c).listFiles()?.sortedBy { it.name } ?: emptyList()
    }

    fun log(msg: String) {
        val line = "${tsFmt.format(Date())} $msg"
        _log.value = (_log.value + line).takeLast(500)
    }

    fun refreshFlags() {
        val c = ctx ?: return
        _state.value = _state.value.copy(
            wifiOn = wifi?.isWifiEnabled == true,
            locOn = com.mingh.wifianchor.util.Env.locationServicesOn(c),
            hasPerm = com.mingh.wifianchor.util.Env.hasPerm(
                c, android.Manifest.permission.ACCESS_FINE_LOCATION)
        )
    }

    /** 屏上一眼能看懂的状态行，也是「复制摘要」的正文 */
    fun statusText(): String {
        val s = _state.value
        return buildString {
            appendLine("Wi-Fi=${onoff(s.wifiOn)} 定位=${onoff(s.locOn)} 定位权限=${onoff(s.hasPerm)}")
            appendLine("最近一帧: ${s.apCount} 台 AP（其中 FTM 标志 ${s.ftmCount} 台） 缓存年龄=${s.lastAgeMs}ms")
            appendLine("广播次数=${s.bcCount}（其中 resultsUpdated=true ${s.bcWithNew} 次）")
            appendLine("读缓存次数=${s.pollCount}（未主动扫描却刷新 ${s.refreshWithoutOwnScan} 次）")
            if (s.recording != null) appendLine("正在录制会话: ${s.recording}（${s.sessionFrames} 帧）")
            if (s.busy != null) appendLine("任务: ${s.busy}")
        }
    }

    private fun onoff(b: Boolean) = if (b) "开" else "关/未给"
}
