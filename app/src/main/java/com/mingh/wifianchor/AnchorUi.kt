package com.mingh.wifianchor

import android.content.Context
import com.mingh.wifianchor.probe.ScanHub
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** 前台服务开关的状态桥，供 Compose 观察（服务本身只用于对照测扫描配额） */
object AnchorUi {
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running

    fun toggle(ctx: Context, on: Boolean) {
        if (on) {
            AnchorService.start(ctx)
            _running.value = true
        } else {
            AnchorService.stop(ctx)
            _running.value = false
        }
        ScanHub.log(if (on) "开关：前台服务 已开" else "开关：前台服务 已关")
    }
}
