package com.shilapi.xcertplay

import android.app.Activity
import android.app.KeyguardManager
import android.os.Build
import android.os.PowerManager

/**
 * Car tablet: it boots on ignition to the lock screen. With no PIN, pattern or password the lock
 * screen only costs the driver a tap, so DiPlay wakes the screen and dismisses it. A secure lock
 * stays: only CarPlay shows over it (see CarPlayHostActivity), never DiPlay's settings.
 */
internal fun Activity.skipInsecureLockScreen(log: (String) -> Unit = {}) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) return
    val keyguard = getSystemService(KeyguardManager::class.java) ?: return
    log("Lock screen secure=${keyguard.isDeviceSecure} locked=${keyguard.isKeyguardLocked} " +
        "screenOn=${getSystemService(PowerManager::class.java)?.isInteractive}")
    if (keyguard.isDeviceSecure) return
    setShowWhenLocked(true)
    setTurnScreenOn(true)
    if (!keyguard.isKeyguardLocked) return
    keyguard.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
        override fun onDismissSucceeded() = log("Lock screen dismissed")
        override fun onDismissCancelled() = log("Lock screen dismiss cancelled")
        override fun onDismissError() = log("Lock screen dismiss failed")
    })
}

/** CarPlay's first picture can arrive after the screen timed out on the lock screen; light it again. */
@Suppress("DEPRECATION") // ACQUIRE_CAUSES_WAKEUP has no replacement for a screen that is already off.
internal fun Activity.wakeScreenForCarPlay(log: (String) -> Unit) {
    val power = getSystemService(PowerManager::class.java) ?: return
    if (power.isInteractive) return
    log("Screen was off at CarPlay's first picture; waking it")
    power.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "DiPlay:carplay")
        .acquire(3_000)
}
