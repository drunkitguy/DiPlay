package com.shilapi.xcertplay

import android.app.Activity
import android.app.ActivityManager
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.provider.Settings

/** Car tablet: lets DiPlay become device owner (adb dpm set-device-owner) for silent Wi-Fi off and pinning. */
class CarTabletAdminReceiver : DeviceAdminReceiver()

internal object CarTabletOwner {
    private const val PREFS = "diplay_car_tablet_owner"
    private const val KEY_PIN = "pin_carplay"

    fun isOwner(context: Context): Boolean =
        context.getSystemService(DevicePolicyManager::class.java)?.isDeviceOwnerApp(context.packageName) == true

    fun pinEnabled(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_PIN, true)

    fun savePinEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_PIN, enabled).apply()
        applyLockScreen(context)
    }

    /** Car screen mode has no lock screen; turning it off brings the lock screen back. No-op with a PIN set. */
    fun applyLockScreen(context: Context, disabled: Boolean = pinEnabled(context)) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || !isOwner(context)) return
        runCatching { context.getSystemService(DevicePolicyManager::class.java)?.setKeyguardDisabled(admin(context), disabled) }
    }

    fun admin(context: Context) = ComponentName(context, CarTabletAdminReceiver::class.java)

    private const val KEY_APP_AUTO_BRIGHTNESS = "app_auto_brightness"

    /** Car tablet (car33): Auto is DiPlay's own light-sensor brightness, not the tablet's adaptive brightness. */
    fun appAutoBrightness(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_APP_AUTO_BRIGHTNESS, true)

    /** Screen brightness in percent, 0 meaning automatic. Parking saves and restores whatever is set here. */
    fun brightnessPercent(context: Context): Int {
        if (appAutoBrightness(context)) return 0
        val resolver = context.contentResolver
        if (Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, 0) == 1) return 0
        val level = Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS, 128)
        return ((level * 100 / 255 + 2) / 5 * 5).coerceIn(5, 100)
    }

    fun setBrightnessPercent(context: Context, percent: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_APP_AUTO_BRIGHTNESS, percent <= 0).apply()
        if (percent <= 0 || !isOwner(context)) return
        val dpm = context.getSystemService(DevicePolicyManager::class.java) ?: return
        runCatching {
            dpm.setSystemSetting(admin(context), Settings.System.SCREEN_BRIGHTNESS_MODE, "0")
            dpm.setSystemSetting(admin(context), Settings.System.SCREEN_BRIGHTNESS, (percent * 255 / 100).coerceIn(1, 255).toString())
        }
    }

    /** Gives device owner back, so the tablet returns to a normal setup without a reset. */
    @Suppress("DEPRECATION")
    fun release(context: Context) {
        val dpm = context.getSystemService(DevicePolicyManager::class.java) ?: return
        if (!dpm.isDeviceOwnerApp(context.packageName)) return
        runCatching { dpm.setLockTaskPackages(admin(context), emptyArray()) }
        applyLockScreen(context, disabled = false)
        dpm.clearDeviceOwnerApp(context.packageName)
    }
}

/**
 * While CarPlay is connected, pins DiPlay's task with no status bar, notification shade, Home or
 * Recents, so nothing of Android shows. Holding power still opens the power menu. Only as device owner,
 * where pinning needs no confirmation.
 */
internal fun Activity.pinForCarPlay(pin: Boolean, log: (String) -> Unit) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || !CarTabletOwner.isOwner(this)) return
    val pinned = getSystemService(ActivityManager::class.java)?.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE
    if (pin && !pinned && CarTabletOwner.pinEnabled(this)) {
        runCatching {
            val dpm = getSystemService(DevicePolicyManager::class.java)
            val admin = CarTabletOwner.admin(this)
            dpm.setLockTaskPackages(admin, arrayOf(packageName))
            // Power menu kept as the way out: Restart ends the pin.
            dpm.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS)
            startLockTask()
        }.onSuccess { log("CarPlay pinned while connected") }
            .onFailure { log("CarPlay pin failed failureClass=${it.javaClass.simpleName}") }
    } else if (!pin && pinned) {
        runCatching { stopLockTask() }.onSuccess { log("CarPlay unpinned") }
    }
}

/** Light-sensor lux to window brightness 0.03..1: about 13% at 10 lux (night), 35% at 100, 65% at 1000, full in sun. */
internal fun appBrightnessForLux(lux: Float): Float {
    val x = (kotlin.math.log10(lux.coerceAtLeast(0f) + 1f) / 4f).coerceIn(0f, 1f)
    return (x * kotlin.math.sqrt(x)).coerceAtLeast(0.03f)
}
