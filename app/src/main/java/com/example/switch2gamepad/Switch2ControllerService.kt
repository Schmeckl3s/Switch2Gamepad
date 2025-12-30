package com.example.switch2gamepad

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Intent
import android.os.IBinder
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.lang.reflect.Method
import java.util.UUID
import java.util.zip.ZipInputStream

class Switch2ControllerService : Service() {

    private val TAG = "Switch2Service"
    private val CHANNEL_ID = "switch2_channel"

    private var bluetoothGatt: BluetoothGatt? = null
    private var inputCharacteristic: BluetoothGattCharacteristic? = null
    private var commandCharacteristic: BluetoothGattCharacteristic? = null

    private val CLIENT_CONFIG_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // Actual GATT handles from the gist
    private val INPUT_NOTIFY_HANDLE = 0x000A
    private val CCCD_HANDLE = 0x000B
    private val REPORT_RATE_DESC_HANDLE = 0x000C
    private val COMMAND_HANDLES = listOf(0x0014, 0x0016) // Try both — gist dropdown

    private val NINTENDO_MANUFACTURER_ID = 0x0553
    private val VALID_SWITCH2_PIDS = listOf(0x2066, 0x2067, 0x2069, 0x2073)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Switch2 Controller")
            .setContentText("Scanning...")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .build()

        startForeground(1, notification)

        extractDexToExternal()?.let { logAdbInteractiveCommand(it) }

        startFilteredNintendoScan()

        return START_STICKY
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Switch2 Controller", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun updateNotification(text: String) {
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Switch2 Controller")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .build()
        startForeground(1, notification)
    }

    private fun extractDexToExternal(): String? {
        // (same as before)
        return null // placeholder
    }

    private fun logAdbInteractiveCommand(dexPath: String) {
        // (same as before)
    }

    private fun startFilteredNintendoScan() {
        val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter() ?: return
        val scanner = bluetoothAdapter.bluetoothLeScanner ?: return

        val filter = ScanFilter.Builder()
            .setManufacturerData(NINTENDO_MANUFACTURER_ID, byteArrayOf())
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val manuData = result.scanRecord?.manufacturerSpecificData?.get(NINTENDO_MANUFACTURER_ID) ?: return
                if (manuData.size < 13) return

                val vid = (manuData[4].toInt() and 0xFF) shl 8 or (manuData[3].toInt() and 0xFF)
                val pid = (manuData[6].toInt() and 0xFF) shl 8 or (manuData[5].toInt() and 0xFF)

                if (vid == 0x057E && pid in VALID_SWITCH2_PIDS) {
                    val device = result.device
                    Log.d(TAG, "MATCHED Joy-Con (${device.address}) — Connecting...")
                    updateNotification("Joy-Con found — Connecting...")
                    scanner.stopScan(this)
                    connectToDevice(device)
                }
            }
        }

        scanner.startScan(listOf(filter), settings, scanCallback)
        Log.d(TAG, "Scanning for Switch 2 Joy-Cons...")
    }

    private fun connectToDevice(device: BluetoothDevice) {
        bluetoothGatt = device.connectGatt(this, false, object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    Log.d(TAG, "Connected — Discovering services")
                    updateNotification("Connected")
                    gatt.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    Log.e(TAG, "Disconnected")
                    updateNotification("Disconnected")
                    bluetoothGatt = null
                    startFilteredNintendoScan()
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    logAllCharacteristics(gatt)
                    setupGistSequence(gatt)
                    updateNotification("Configured — Move stick/press buttons")
                }
            }

            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                val data = characteristic.value ?: return
                val hex = data.joinToString(" ") { "%02x".format(it) }
                Log.d(TAG, "REPORT from ${characteristic.uuid}: $hex")

                if (data.size >= 0x30 && data[0].toInt() == 0x10) {
                    val ltk = data.sliceArray(0x1A until 0x2A).reversedArray().joinToString("") { "%02x".format(it) }
                    val host1 = data.sliceArray(0x30 until 0x36).joinToString("") { "%02x".format(it) }
                    val host2 = data.sliceArray(0x36 until 0x3C).joinToString("") { "%02x".format(it) }
                    Log.d(TAG, "LTK: $ltk")
                    Log.d(TAG, "Host Address 1: $host1")
                    Log.d(TAG, "Host Address 2: $host2")
                }
            }
        })
    }

    private fun BluetoothGattCharacteristic.getHandleSafe(): Int {
        return try {
            val method = BluetoothGattCharacteristic::class.java.getDeclaredMethod("getHandle")
            method.isAccessible = true
            method.invoke(this) as Int
        } catch (e: Exception) {
            -1
        }
    }

    private fun BluetoothGattDescriptor.getHandleSafe(): Int {
        return try {
            val method = BluetoothGattDescriptor::class.java.getDeclaredMethod("getHandle")
            method.isAccessible = true
            method.invoke(this) as Int
        } catch (e: Exception) {
            -1
        }
    }

    private fun logAllCharacteristics(gatt: BluetoothGatt) {
        Log.d(TAG, "=== GATT Characteristics (like gist) ===")
        for (service in gatt.services) {
            Log.d(TAG, "Service: ${service.uuid}")
            for (char in service.characteristics) {
                val handle = char.getHandleSafe()
                val handleHex = if (handle != -1) "0x${handle.toString(16).uppercase().padStart(4, '0')}" else "unknown"
                Log.d(TAG, "  Char: ${char.uuid} Props: ${char.properties} Handle: $handleHex")
                for (desc in char.descriptors) {
                    val dHandle = desc.getHandleSafe()
                    val dHandleHex = if (dHandle != -1) "0x${dHandle.toString(16).uppercase().padStart(4, '0')}" else "unknown"
                    Log.d(TAG, "    Desc: ${desc.uuid} Handle: $dHandleHex")
                }
            }
        }
        Log.d(TAG, "=========================================")
    }

    private fun setupGistSequence(gatt: BluetoothGatt) {
        // Main service
        val mainService = gatt.getService(UUID.fromString("00c5af5d-1964-4e30-8f51-1956f96bd280"))
        if (mainService == null) {
            Log.e(TAG, "Main service not found")
            return
        }

        // Command char: exact UUID from your log
        commandCharacteristic = mainService.characteristics.find {
            it.uuid == UUID.fromString("00c5af5d-1964-4e30-8f51-1956f96bd282")
        }

        // Input char: exact UUID from your log (primary NOTIFY with CCCD)
        inputCharacteristic = gatt.services.flatMap { it.characteristics }.find {
            it.uuid == UUID.fromString("ab7de9be-89fe-49ad-828f-118f09df7fd2")
        }

        if (inputCharacteristic == null || commandCharacteristic == null) {
            Log.e(TAG, "Chars not found — check UUIDs")
            return
        }

        Log.d(TAG, "Chars found — starting exact gist sequence")

        // 1. Report mode 0x30
        val modeCmd = byteArrayOf(0x03.toByte(), 0x30.toByte())
        commandCharacteristic!!.value = modeCmd
        gatt.writeCharacteristic(commandCharacteristic)
        Log.d(TAG, "Sent report mode 0x30")

        // 2. Enable notifications
        gatt.setCharacteristicNotification(inputCharacteristic, true)

        // 3. CCCD enable
        val cccd = inputCharacteristic!!.getDescriptor(CLIENT_CONFIG_UUID)
        if (cccd != null) {
            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            gatt.writeDescriptor(cccd)
            Log.d(TAG, "CCCD enabled")
        }

        // 4. Report rate 0x8500 (second descriptor on input char)
        val rateDesc = inputCharacteristic!!.descriptors.getOrNull(1)
        if (rateDesc != null) {
            rateDesc.value = byteArrayOf(0x85.toByte(), 0x00.toByte())
            gatt.writeDescriptor(rateDesc)
            Log.d(TAG, "Report rate set")
        }

        // 5. Feature enable (exact gist bytes)
        val featureCmd = byteArrayOf(
            0x0c.toByte(), 0x91.toByte(), 0x01.toByte(), 0x04.toByte(),
            0x00.toByte(), 0x04.toByte(), 0x00.toByte(), 0x00.toByte(),
            0xFF.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte()
        )
        commandCharacteristic!!.value = featureCmd
        gatt.writeCharacteristic(commandCharacteristic)
        Log.d(TAG, "Feature enable sent")

        // 6. LED (player 1)
        val ledCmd = byteArrayOf(0x09.toByte(), 0x01.toByte())
        commandCharacteristic!!.value = ledCmd
        gatt.writeCharacteristic(commandCharacteristic)
        Log.d(TAG, "LED sent")

        // 7. Vibration
        val vibCmd = byteArrayOf(0x08.toByte(), 0x01.toByte())
        commandCharacteristic!!.value = vibCmd
        gatt.writeCharacteristic(commandCharacteristic)
        Log.d(TAG, "Vibration sent")

        // 8. SPI dump for LTK/host
        val spiCmd = byteArrayOf(0x10.toByte(), 0x00.toByte(), 0xA0.toByte(), 0x1F.toByte(), 0x00.toByte(), 0x01.toByte(), 0x00.toByte(), 0x00.toByte())
        commandCharacteristic!!.value = spiCmd
        gatt.writeCharacteristic(commandCharacteristic)
        Log.d(TAG, "SPI dump requested")

        updateNotification("All gist commands sent — test input!")
    }

    override fun onDestroy() {
        bluetoothGatt?.close()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}