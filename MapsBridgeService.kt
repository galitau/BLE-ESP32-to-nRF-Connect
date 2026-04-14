package com.example.electrium

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.charset.StandardCharsets
import java.util.UUID

private val mapsBridgeConnectionState = MutableStateFlow(false)

/**
 * Foreground service (connectedDevice) that maintains a BLE GATT connection to the ESP32
 * and writes navigation strings to the NimBLE-style characteristic.
 */
class MapsBridgeService : Service() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var gatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var deviceAddress: String? = null
    private var intentionalDisconnect = false
    private var reconnectAttemptScheduled = false

    private var lastSentPayload: String? = null
    private var pendingNavText: String? = null
    private var foregroundStarted = false

    private val reconnectRunnable = Runnable {
        reconnectAttemptScheduled = false
        if (intentionalDisconnect) return@Runnable
        val addr = deviceAddress ?: return@Runnable
        if (gatt != null) return@Runnable
        Log.i(TAG, "Reconnecting to $addr")
        connectInternal(addr)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "GATT connected, requesting MTU 517")
                    updateConnected(true)
                    @SuppressLint("MissingPermission")
                    gatt.requestMtu(MTU_REQUEST)
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.w(TAG, "GATT disconnected (status=$status)")
                    updateConnected(false)
                    writeCharacteristic = null
                    mainHandler.post {
                        try {
                            @SuppressLint("MissingPermission")
                            gatt.close()
                        } catch (_: Exception) {
                        }
                    }
                    if (this@MapsBridgeService.gatt === gatt) {
                        this@MapsBridgeService.gatt = null
                    }
                    scheduleReconnect()
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            Log.i(TAG, "MTU changed: mtu=$mtu status=$status")
            @SuppressLint("MissingPermission")
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Service discovery failed: $status")
                return
            }
            val service: BluetoothGattService? = gatt.getService(SERVICE_UUID)
            if (service == null) {
                Log.e(TAG, "Service $SERVICE_UUID not found")
                return
            }
            val ch = service.getCharacteristic(CHARACTERISTIC_UUID)
            if (ch == null) {
                Log.e(TAG, "Characteristic $CHARACTERISTIC_UUID not found")
                return
            }
            writeCharacteristic = ch
            Log.i(TAG, "Write characteristic ready")
            flushPendingNav()
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Write failed: $status — will retry next update")
                mainHandler.post {
                    // Allow same text to be sent again after a failed write
                    lastSentPayload = null
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val manager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothAdapter = manager?.adapter
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                intentionalDisconnect = true
                mainHandler.removeCallbacks(reconnectRunnable)
                disconnectGatt()
                foregroundStarted = false
                stopForeground(Service.STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_START -> {
                intentionalDisconnect = false
                deviceAddress = intent.getStringExtra(EXTRA_DEVICE_ADDRESS)?.trim()?.takeIf { it.isNotEmpty() }
                    ?: loadSavedDeviceAddress()
                if (deviceAddress.isNullOrEmpty()) {
                    Log.e(TAG, "No device address; stop service")
                    stopSelf()
                    return START_NOT_STICKY
                }
                saveDeviceAddress(deviceAddress!!)
                ensureStartedAsForeground()
                if (gatt == null) {
                    connectInternal(deviceAddress!!)
                }
            }

            ACTION_NAV_UPDATE -> {
                // Notification listener may start the service before the user taps Start;
                // promoting to foreground here avoids crashing on Android 8+.
                ensureStartedAsForeground()
                if (deviceAddress.isNullOrEmpty()) {
                    deviceAddress = loadSavedDeviceAddress()
                }
                if (!intentionalDisconnect && gatt == null && !deviceAddress.isNullOrEmpty()) {
                    Log.i(TAG, "Auto-connect from nav update using saved address")
                    connectInternal(deviceAddress!!)
                }
                val text = intent.getStringExtra(EXTRA_NAV_TEXT)?.trim().orEmpty()
                if (text.isNotEmpty()) {
                    handleNavText(text)
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        intentionalDisconnect = true
        foregroundStarted = false
        mainHandler.removeCallbacks(reconnectRunnable)
        disconnectGatt()
        updateConnected(false)
        super.onDestroy()
    }

    private fun ensureStartedAsForeground() {
        if (foregroundStarted) return
        val notification = buildForegroundNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        foregroundStarted = true
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = getString(R.string.notification_channel_desc) }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    private fun buildForegroundNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply { flags = FLAG_ACTIVITY_SINGLE_TOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.fg_notification_title))
            .setContentText(getString(R.string.fg_notification_text))
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(openApp)
            .setOngoing(true)
            .build()
    }

    private fun handleNavText(text: String) {
        if (text == lastSentPayload) {
            Log.d(TAG, "Skip duplicate payload")
            return
        }
        if (writeCharacteristic == null || gatt == null) {
            pendingNavText = text
            Log.d(TAG, "Queued nav text (not connected yet)")
            return
        }
        writeNavToGatt(text)
    }

    private fun flushPendingNav() {
        val pending = pendingNavText ?: return
        pendingNavText = null
        handleNavText(pending)
    }

    @SuppressLint("MissingPermission")
    private fun writeNavToGatt(text: String) {
        val gatt = this.gatt ?: return
        val ch = this.writeCharacteristic ?: return
        if (lastSentPayload == text) {
            Log.d(TAG, "Skip duplicate at write")
            return
        }

        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT

        val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val result = gatt.writeCharacteristic(ch, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            result == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            ch.value = bytes
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(ch)
        }

        if (ok) {
            lastSentPayload = text
            Log.i(TAG, "Wrote ${bytes.size} bytes to GATT")
        } else {
            Log.e(TAG, "writeCharacteristic failed to start")
        }
    }

    private fun connectInternal(address: String) {
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            Log.e(TAG, "Bluetooth unavailable")
            return
        }
        try {
            val device: BluetoothDevice = adapter.getRemoteDevice(address)
            intentionalDisconnect = false
            @SuppressLint("MissingPermission")
            gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            } else {
                @Suppress("DEPRECATION")
                device.connectGatt(this, false, gattCallback)
            }
        } catch (e: Exception) {
            Log.e(TAG, "connectGatt failed", e)
            scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        if (intentionalDisconnect) return
        if (deviceAddress.isNullOrEmpty()) return
        if (reconnectAttemptScheduled) return
        reconnectAttemptScheduled = true
        mainHandler.postDelayed(reconnectRunnable, RECONNECT_DELAY_MS)
    }

    @SuppressLint("MissingPermission")
    private fun disconnectGatt() {
        val current = gatt
        gatt = null
        writeCharacteristic = null
        lastSentPayload = null
        if (current != null) {
            try {
                current.disconnect()
            } catch (_: Exception) {
            }
            try {
                current.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun updateConnected(connected: Boolean) {
        mapsBridgeConnectionState.value = connected
    }

    private fun loadSavedDeviceAddress(): String? {
        return getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_DEVICE_ADDRESS, null)
    }

    private fun saveDeviceAddress(address: String) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString(PREF_DEVICE_ADDRESS, address)
            .apply()
    }

    companion object {
        private const val TAG = "MapsBridgeService"

        const val ACTION_START = "com.example.electrium.action.START_BRIDGE"
        const val ACTION_STOP = "com.example.electrium.action.STOP_BRIDGE"
        const val ACTION_NAV_UPDATE = "com.example.electrium.action.NAV_UPDATE"

        const val EXTRA_DEVICE_ADDRESS = "extra_device_address"
        const val EXTRA_NAV_TEXT = "extra_nav_text"

        private const val CHANNEL_ID = "maps_ble_bridge"
        private const val NOTIFICATION_ID = 1001
        private const val PREFS = "maps_bridge_prefs"
        private const val PREF_DEVICE_ADDRESS = "device_address"

        private const val RECONNECT_DELAY_MS = 3_000L
        private const val MTU_REQUEST = 517

        // val SERVICE_UUID: UUID = UUID.fromString("4fafc201-1fb5-459e-8fcc-c5c9c331914b")
        // val CHARACTERISTIC_UUID: UUID = UUID.fromString("beb5483e-36e1-4688-b7f5-ea07361b26a8")
        val SERVICE_UUID: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
        val CHARACTERISTIC_UUID: UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E") // RX = phone writes here

        val connectionState: StateFlow<Boolean> = mapsBridgeConnectionState.asStateFlow()

        fun start(context: Context, deviceAddress: String) {
            val i = Intent(context, MapsBridgeService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_DEVICE_ADDRESS, deviceAddress.trim())
            }
            context.startForegroundService(i)
        }

        fun stop(context: Context) {
            val i = Intent(context, MapsBridgeService::class.java).apply { action = ACTION_STOP }
            context.startService(i)
        }

        fun navUpdateIntent(context: Context, text: String): Intent {
            return Intent(context, MapsBridgeService::class.java).apply {
                action = ACTION_NAV_UPDATE
                putExtra(EXTRA_NAV_TEXT, text)
            }
        }
    }
}
