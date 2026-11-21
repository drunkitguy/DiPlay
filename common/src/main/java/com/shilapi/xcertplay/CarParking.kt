package com.shilapi.xcertplay

import android.app.admin.DevicePolicyManager
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Car tablet after the car turns off: when the ignition socket's power goes away (battery in), or the
 * car's or the iPhone's Bluetooth goes away, brightness goes to minimum and the screen turns off. A live
 * CarPlay session is paused and kept for a quick stop, then ends two minutes after the car went off.
 * Power or Bluetooth coming back restores the screen settings and CarPlay. A new boot also restores them.
 */
internal object CarParking {
    private const val PREFS = "diplay_parking"
    private const val DEBOUNCE_MILLIS = 10_000L
    private const val PARKED_SCREEN_OFF_MILLIS = "5000"
    /** With [DEBOUNCE_MILLIS], CarPlay ends two minutes after the car goes off. */
    private const val QUICK_STOP_MILLIS = 110_000L
    private val CAR_CLASSES = setOf(
        BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO, BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE)
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val connectedCars = mutableSetOf<String>()
    private var watchingPower = false

    /** Set by the running CarPlay host so a park can end the session. */
    @Volatile var stopCarPlay: (() -> Unit)? = null
    private var pausedForPark = false
    private val endCarPlay = Runnable { pausedForPark = false; stopCarPlay?.invoke() }

    /** The host lost CarPlay while parked: end it now instead of reconnecting with the car off. */
    fun endQuickStop(context: Context): Boolean {
        if (!parked(context)) return false
        main.removeCallbacks(endCarPlay)
        main.post(endCarPlay)
        return true
    }

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
            // With the battery in, the iPhone can rejoin while the car is off; only power or the car's link unparks then.
            if (car || plugged(app)) unpark(app)
            if (car) PhoneBluetoothReceiver.wake(app)
            return
        }
        if (car) connectedCars -= address
        main.postDelayed({
            // The car's own link going away means ignition off. The iPhone's counts only once CarPlay is
            // gone too, because it can drop Bluetooth after the Wi-Fi handoff while CarPlay keeps running.
            if (if (car) address !in connectedCars else !CarPlayBackgroundSession.active) park(app, "$who Bluetooth left")
        }, DEBOUNCE_MILLIS)
    }

    /** Battery in: the ignition socket going dead means the car is off, and power coming back means it started. */
    fun watchPower(context: Context) {
        if (watchingPower) return
        watchingPower = true
        val app = context.applicationContext
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_POWER_CONNECTED) {
                    record(app, "car power connected")
                    unpark(app)
                    PhoneBluetoothReceiver.wake(app)
                } else {
                    record(app, "car power disconnected")
                    // A cranking dip reconnects within the debounce.
                    main.postDelayed({ if (!plugged(app)) park(app, "car power went off") }, DEBOUNCE_MILLIS)
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else app.registerReceiver(receiver, filter)
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
        record(context, "parked: $reason")
        if (CarPlayBackgroundSession.active) {
            pausedForPark = CarPlayMediaKeys.pauseForPark()
            main.postDelayed(endCarPlay, QUICK_STOP_MILLIS)
        } else {
            stopCarPlay?.invoke()
        }
    }

    fun unpark(context: Context) {
        val prefs = prefs(context)
        if (!prefs.getBoolean("parked", false)) return
        applyScreen(context, mode = prefs.getInt("mode", 0).toString(),
            brightness = prefs.getInt("brightness", 128).toString(), timeout = prefs.getInt("timeout", 60_000).toString())
        prefs.edit().putBoolean("parked", false).commit()
        record(context, "unparked")
        main.removeCallbacks(endCarPlay)
        if (pausedForPark) { pausedForPark = false; CarPlayMediaKeys.resumeAfterPark() }
        // CarPlay kept through a quick stop: the screen went off, so bring CarPlay back up and wake it.
        if (CarPlayBackgroundSession.hasSession()) runCatching {
            context.startActivity(CarPlayScreen.intent(context).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        }
    }

    fun report(context: Context): String =
        "Car off detection: ${prefs(context).getString("last", "nothing recorded")}"

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

    /** Unknown counts as plugged, so a missing battery broadcast never parks a running CarPlay. */
    private fun plugged(context: Context) = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        ?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0

    private fun bootCount(context: Context) =
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, 0)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
