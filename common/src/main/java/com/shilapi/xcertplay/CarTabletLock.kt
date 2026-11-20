package com.shilapi.xcertplay

import android.app.Activity
import android.app.KeyguardManager
import android.os.Build

/**
 * Car tablet: it boots on ignition to the lock screen. With no PIN, pattern or password the lock
 * screen only costs the driver a tap, so DiPlay wakes the screen and dismisses it. A secure lock
 * stays: only CarPlay shows over it (see CarPlayHostActivity), never DiPlay's settings.
 */
internal fun Activity.skipInsecureLockScreen() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) return
    val keyguard = getSystemService(KeyguardManager::class.java) ?: return
    if (keyguard.isDeviceSecure) return
    setShowWhenLocked(true)
    setTurnScreenOn(true)
    if (keyguard.isKeyguardLocked) keyguard.requestDismissKeyguard(this, null)
}
