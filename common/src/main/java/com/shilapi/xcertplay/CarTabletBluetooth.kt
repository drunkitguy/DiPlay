package com.shilapi.xcertplay

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.app.admin.DevicePolicyManager
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.widget.ArrayAdapter
import android.widget.Toast
import com.shilapi.xcertplay.host.R

/**
 * Car tablet: pair a Bluetooth device, like the car's stereo, from DiPlay. Android connects a paired
 * audio device by itself, so media plays through the car. While CarPlay is pinned, Android's pairing
 * prompt is let through for as long as this list is open.
 */
@SuppressLint("MissingPermission")
internal fun Activity.showBluetoothDevices() {
    val bluetooth = getSystemService(BluetoothManager::class.java)?.adapter
    if (bluetooth == null || !bluetooth.isEnabled) {
        Toast.makeText(this, R.string.car_tablet_bluetooth_off, Toast.LENGTH_LONG).show()
        return
    }
    val dpm = getSystemService(DevicePolicyManager::class.java)
    val admin = CarTabletOwner.admin(this)
    if (Build.VERSION.SDK_INT >= 31) runCatching {
        dpm.setPermissionGrantState(admin, packageName, Manifest.permission.BLUETOOTH_SCAN,
            DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED)
    }
    val pinned = getSystemService(ActivityManager::class.java)?.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE
    if (pinned) runCatching { dpm.setLockTaskPackages(admin, arrayOf(packageName, "com.android.settings")) }

    val devices = linkedMapOf<String, BluetoothDevice>()
    runCatching { bluetooth.bondedDevices }.getOrNull()?.forEach { devices[it.address] = it }
    var audio: BluetoothA2dp? = null
    val rows = ArrayAdapter<String>(this, android.R.layout.simple_list_item_1)
    fun refresh() {
        val connected = runCatching { audio?.connectedDevices }.getOrNull().orEmpty().map { it.address }.toSet()
        rows.clear()
        rows.addAll(devices.values.map { device ->
            val state = when {
                device.address in connected -> R.string.car_tablet_bluetooth_connected
                device.bondState == BluetoothDevice.BOND_BONDED -> R.string.car_tablet_bluetooth_paired
                device.bondState == BluetoothDevice.BOND_BONDING -> R.string.car_tablet_bluetooth_pairing
                else -> R.string.car_tablet_bluetooth_tap_to_pair
            }
            "${runCatching { device.name }.getOrNull() ?: device.address}  ·  ${getString(state)}"
        })
    }
    val listener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) { audio = proxy as BluetoothA2dp; refresh() }
        override fun onServiceDisconnected(profile: Int) { audio = null }
    }
    bluetooth.getProfileProxy(this, listener, BluetoothProfile.A2DP)
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            @Suppress("DEPRECATION")
            val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
            // Unnamed finds are mostly phones' and watches' random addresses; a car stereo has a name.
            if (device != null && (device.address in devices || runCatching { device.name }.getOrNull() != null)) {
                devices[device.address] = device
            }
            refresh()
        }
    }
    val filter = IntentFilter().apply {
        addAction(BluetoothDevice.ACTION_FOUND)
        addAction(BluetoothDevice.ACTION_NAME_CHANGED)
        addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
    }
    if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED) else registerReceiver(receiver, filter)
    refresh()
    runCatching { bluetooth.startDiscovery() }

    AlertDialog.Builder(this)
        .setTitle(R.string.car_tablet_bluetooth)
        .setAdapter(rows, null)
        .setNegativeButton(R.string.car_tablet_bluetooth_done, null)
        .setOnDismissListener {
            runCatching { unregisterReceiver(receiver) }
            runCatching { bluetooth.cancelDiscovery() }
            audio?.let { bluetooth.closeProfileProxy(BluetoothProfile.A2DP, it) }
            if (pinned) runCatching { dpm.setLockTaskPackages(admin, arrayOf(packageName)) }
        }
        .create().apply {
            // The list keeps the dialog open on a tap, so pairing progress shows in place.
            listView.setOnItemClickListener { _, _, index, _ ->
                val device = devices.values.elementAtOrNull(index) ?: return@setOnItemClickListener
                if (device.bondState == BluetoothDevice.BOND_NONE) {
                    runCatching { bluetooth.cancelDiscovery() }
                    runCatching { device.createBond() }
                    refresh()
                }
            }
            show()
        }
}
