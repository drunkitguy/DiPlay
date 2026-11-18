package com.shilapi.xcertplay.media

import android.content.Context
import android.os.Build
import android.os.PerformanceHintManager
import java.io.Closeable

/**
 * ADPF hint (Android 12+): reports each frame's decode time against the frame interval, so the CPU governor
 * keeps the video threads on fast clocks instead of ramping down between frames. A no-op where unsupported.
 */
internal class FrameWorkHint(context: Context, tids: IntArray, targetNanos: Long) : Closeable {
    // Typed Any so older Android never resolves PerformanceHintManager.Session.
    private val session: Any? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) runCatching {
        context.getSystemService(PerformanceHintManager::class.java)
            ?.createHintSession(tids.filter { it > 0 }.toIntArray(), targetNanos)
    }.getOrNull() else null

    fun report(actualNanos: Long) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && actualNanos > 0) {
            runCatching { (session as? PerformanceHintManager.Session)?.reportActualWorkDuration(actualNanos) }
        }
    }

    override fun close() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) runCatching { (session as? PerformanceHintManager.Session)?.close() }
    }
}
