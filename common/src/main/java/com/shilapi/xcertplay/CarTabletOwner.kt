package com.shilapi.xcertplay

import android.app.Activity
import android.app.ActivityManager
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Build

/** Car tablet: lets DiPlay become device owner (adb dpm set-device-owner) for silent Wi-Fi off and pinning. */
class CarTabletAdminReceiver : DeviceAdminReceiver()

internal object CarTabletOwner {
    private const val PREFS = "diplay_car_tablet_owner"
    private const val KEY_PIN = "pin_carplay"

    fun isOwner(context: Context): Boolean =
        context.getSystemService(DevicePolicyManager::class.java)?.isDeviceOwnerApp(context.packageName) == true

    fun pinEnabled(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_PIN, true)

    fun savePinEnabled(context: Context, enabled: Boolean) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_PIN, enabled).apply()

    fun admin(context: Context) = ComponentName(context, CarTabletAdminReceiver::class.java)

    /** Gives device owner back, so the tablet returns to a normal setup without a reset. */
    @Suppress("DEPRECATION")
    fun release(context: Context) {
        val dpm = context.getSystemService(DevicePolicyManager::class.java) ?: return
        if (!dpm.isDeviceOwnerApp(context.packageName)) return
        runCatching { dpm.setLockTaskPackages(admin(context), emptyArray()) }
        dpm.clearDeviceOwnerApp(context.packageName)
    }
}

/**
 * While CarPlay is connected, pins DiPlay's task so recents can't switch away. The status bar,
 * the notification shade, Home (DiPlay itself) and the power menu keep working. Only as device owner,
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
            dpm.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO or
                DevicePolicyManager.LOCK_TASK_FEATURE_HOME or DevicePolicyManager.LOCK_TASK_FEATURE_NOTIFICATIONS or
                DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS)
            startLockTask()
        }.onSuccess { log("CarPlay pinned while connected") }
            .onFailure { log("CarPlay pin failed failureClass=${it.javaClass.simpleName}") }
    } else if (!pin && pinned) {
        runCatching { stopLockTask() }.onSuccess { log("CarPlay unpinned") }
    }
}
