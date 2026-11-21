package com.shilapi.xcertplay

import android.app.Activity
import android.app.KeyguardManager
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Build
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.provider.Settings
import com.shilapi.xcertplay.update.CarTabletUpdates

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

@Volatile private var wifiOffAsked = false

/** After an update turned the Wi-Fi client on, the next CarPlay start turns it off again. */
internal fun allowWifiClientOffAgain() { wifiOffAsked = false }

/**
 * Car tablet: a Wi-Fi client left on, joined to nothing, keeps scanning and pulls the radio off the
 * hotspot's channel, which stalls CarPlay for up to a second. Once per boot DiPlay turns it off:
 * silently where Android allows it (device owner), otherwise by opening the Wi-Fi panel over CarPlay.
 */
@Suppress("DEPRECATION") // setWifiEnabled only works for privileged callers on targetSdk 29+.
internal fun Activity.turnOffWifiClientOnce(log: (String) -> Unit) {
    val wifi = applicationContext.getSystemService(WifiManager::class.java) ?: return
    if (wifiOffAsked || !wifi.isWifiEnabled) return
    wifiOffAsked = true
    if (runCatching { wifi.setWifiEnabled(false) }.getOrDefault(false)) {
        log("Wi-Fi client turned off for CarPlay")
        return
    }
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
    log("Wi-Fi client is on; opening the Wi-Fi panel so the driver can turn it off")
    runCatching { startActivity(Intent(Settings.Panel.ACTION_WIFI)) }
}

/** Car tablet: never portrait. Landscape at start, then held at the landscape it has, so a bump never turns CarPlay. */
internal fun Activity.holdLandscape() {
    if (CarTabletUpdates.setup(this) == null) return
    val wanted = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)
        ActivityInfo.SCREEN_ORIENTATION_LOCKED else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    if (requestedOrientation != wanted) requestedOrientation = wanted
}
