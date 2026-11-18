package com.shilapi.xcertplay

import com.shilapi.xcertplay.network.WirelessStartupPolicy

internal class WirelessStartupRetryBudget {
    var retries = 0
        private set
    private var frameSession: Any? = null
    private var firstFrameMillis = 0L

    /** On car power nobody is there to tap Retry, so [onExternalPower] keeps retrying every 30 s. */
    fun nextDelayMillis(onExternalPower: Boolean = false): Long? {
        if (retries >= WirelessStartupPolicy.MAX_STARTUP_RETRIES && !onExternalPower) return null
        return (2_000L * (1L shl retries++.coerceAtMost(4))).coerceAtMost(30_000L)
    }

    fun firstFrame(session: Any, nowMillis: Long): Boolean {
        if (frameSession === session) return false
        frameSession = session
        firstFrameMillis = nowMillis
        return true
    }

    fun resetIfStable(session: Any, nowMillis: Long): Boolean {
        if (frameSession !== session || nowMillis - firstFrameMillis < WirelessStartupPolicy.STABLE_SESSION_MILLIS) return false
        retries = 0
        return true
    }

    fun disconnected() { frameSession = null }
    fun manualRetry() { retries = 0; disconnected() }
}
