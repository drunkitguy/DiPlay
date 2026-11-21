package com.shilapi.xcertplay

import android.app.admin.DevicePolicyManager
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Car tablet on cable power after the car turns off: when the car's or the iPhone's Bluetooth goes
 * away, CarPlay stops, brightness goes to minimum and the screen turns off. The car's or the iPhone's
 * Bluetooth coming back restores the screen settings and reopens CarPlay. A new boot also restores them.
 */
internal object CarParking {
    private const val PREFS = "diplay_parking"
    private const val DEBOUNCE_MILLIS = 10_000L
    private const val PARKED_SCREEN_OFF_MILLIS = "5000"
    private val CAR_CLASSES = setOf(
        BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO, BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE)
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val connectedCars = mutableSetOf<String>()

    /** Set by the running CarPlay host so a park can end the session. */
    @Volatile var stopCarPlay: (() -> Unit)? = null

    fun onAcl(context: Context, device: BluetoothDevice, connected: Boolean) {
        val app = context.applicationContext
        val address = runCatching { device.address }.getOrNull() ?: return
        val phone = DiPlayPreferences.phoneAddress(app)?.equals(address, ignoreCase = true) == true
        val car = !phone && runCatching { device.bluetoothClass?.deviceClass }.getOrNull() in CAR_CLASSES
        if (!phone && !car) return
        val who = if (car) "car" else "iPhone"
        record(app, "$who Bluetooth ${if (connected) "connected" else "disconnected"}")
        if (connected) {
            if (car) connectedCars += address
            unpark(app)
            if (car) PhoneBluetoothReceiver.wake(app)
            return
        }
        if (car) connectedCars -= address
        main.postDelayed({
            // The car's own link going away means ignition off. The iPhone's counts only once CarPlay is
            // gone too, because it can drop Bluetooth after the Wi-Fi handoff while CarPlay keeps running.
            if (if (car) address !in connectedCars else !CarPlayBackgroundSession.active) park(app, who)
        }, DEBOUNCE_MILLIS)
    }

    /** True while parked in this boot; a parked state left from before a power cut is restored here. */
    fun parked(context: Context): Boolean {
        val prefs = prefs(context)
        if (!prefs.getBoolean("parked", false)) return false
        if (prefs.getInt("boot", -1) == bootCount(context)) return true
        unpark(context)
        return false
    }

    fun park(context: Context, reason: String) {
        val prefs = prefs(context)
        if (prefs.getBoolean("parked", false)) return
        val resolver = context.contentResolver
        prefs.edit().putBoolean("parked", true).putInt("boot", bootCount(context))
            .putInt("brightness", Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS, 128))
            .putInt("mode", Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, 0))
            .putInt("timeout", Settings.System.getInt(resolver, Settings.System.SCREEN_OFF_TIMEOUT, 60_000))
            .commit()
        applyScreen(context, mode = "0", brightness = "1", timeout = PARKED_SCREEN_OFF_MILLIS)
        record(context, "parked after $reason Bluetooth left")
        stopCarPlay?.invoke()
    }

    fun unpark(context: Context) {
        val prefs = prefs(context)
        if (!prefs.getBoolean("parked", false)) return
        applyScreen(context, mode = prefs.getInt("mode", 0).toString(),
            brightness = prefs.getInt("brightness", 128).toString(), timeout = prefs.getInt("timeout", 60_000).toString())
        prefs.edit().putBoolean("parked", false).commit()
        record(context, "unparked")
    }

    fun report(context: Context): String =
        "Car off detection: ${prefs(context).getString("last", "no Bluetooth event recorded")}"

    /** Only the device owner may change these system settings without the user's Modify system settings grant. */
    private fun applyScreen(context: Context, mode: String, brightness: String, timeout: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || !CarTabletOwner.isOwner(context)) return
        val dpm = context.getSystemService(DevicePolicyManager::class.java) ?: return
        val admin = CarTabletOwner.admin(context)
        runCatching {
            dpm.setSystemSetting(admin, Settings.System.SCREEN_BRIGHTNESS_MODE, mode)
            dpm.setSystemSetting(admin, Settings.System.SCREEN_BRIGHTNESS, brightness)
            dpm.setSystemSetting(admin, Settings.System.SCREEN_OFF_TIMEOUT, timeout)
        }
    }

    private fun record(context: Context, event: String) {
        val at = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        prefs(context).edit().putString("last", "$at $event").apply()
    }

    private fun bootCount(context: Context) =
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, 0)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
