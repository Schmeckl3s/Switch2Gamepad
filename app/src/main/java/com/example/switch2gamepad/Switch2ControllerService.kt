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

    private val SERVICE_UUID = UUID.fromString("ab7de9be-89fe-49ad-828f-118f09df7fd0")
    private val INPUT_REPORT_UUID_1 = UUID.fromString("ab7de9be-89fe-49ad-828f-118f09df7fd2") // Primary input
    private val INPUT_REPORT_UUID_2 = UUID.fromString("cc1bbbb5-7354-4d32-a716-a81cb241a32a") // Secondary input
    private val CUSTOM_DESCRIPTOR_UUID = UUID.fromString("679d5510-5a24-4dee-9557-95df80486ecb")
    private val OUTPUT_REPORT_UUID = UUID.fromString("649d4ac9-8eb7-4e6c-af44-1ea54fe5f005") // For subcommands (gist command_handle 0x0016 value
    private val COMMAND_RESPONSE_UUID = UUID.fromString("c765a961-d9d8-4d36-a20a-5315b111836a") // For subcmd replies (gist 0x001A-1=0x0019)
    private val VIBRATION_OUTPUT_UUID = UUID.fromString("289326cb-a471-485d-a8f4-240c14f18241")  // Handle ~0x0012 value
    private val pairingSpiData = mutableListOf<ByteArray>() // To combine SPI chunks
    private val subcommandQueue: MutableList<Pair<Byte, ByteArray>> = mutableListOf() // List of (subcmd, data)
    private var timer: Byte = 0
    private var configState: Int = 0
    private var mainService: BluetoothGattService? = null

    // For chunked SPI pairing read
    private val pairingSpiChunks = mutableListOf<ByteArray>()
    private val pairingAddress = 0x1FA000L // Gist uses this for Switch 2

    private var gatt: BluetoothGatt? = null


    private var bluetoothGatt: BluetoothGatt? = null
    private var inputCharacteristic: BluetoothGattCharacteristic? = null
    private var commandCharacteristic: BluetoothGattCharacteristic? = null

    private var isPaired = true

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
                    Log.d(TAG, "Connected — Requesting MTU")
                    gatt.requestMtu(517)  // Max BLE MTU for large replies
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    Log.e(TAG, "Disconnected")
                    updateNotification("Disconnected")
                    bluetoothGatt = null
                    startFilteredNintendoScan()
                }
            }

            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.d(TAG, "MTU set to $mtu — Discovering services")
                    gatt.discoverServices()
                } else {
                    Log.e(TAG, "MTU request failed: $status — Discovering anyway")
                    gatt.discoverServices()
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    logAllCharacteristics(gatt)
                    Log.d(TAG, "Chars found — starting exact gist sequence")
                    mainService = gatt.getService(SERVICE_UUID)
                    if (mainService == null) {
                        Log.e(TAG, "Main service not found")
                        return
                    }
                    configureInputReports(gatt)
                    updateNotification("Configured — Move stick/press buttons")
                }
            }

            override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.d(TAG, "Descriptor write success for ${descriptor.uuid}")
                    if (descriptor.uuid == CUSTOM_DESCRIPTOR_UUID) {
                        if (configState == 1) {
                            configureSecondInputReport(gatt)
                        } else if (configState == 2) {
                            enableCommandResponseNotifications(gatt) // NEW: Enable command response before input
                        }
                    } else if (descriptor.uuid == CLIENT_CONFIG_UUID) {
                        if (configState == 3) { // Command response CCCD
                            enableNotifications(gatt) // Input CCCD
                        } else if (configState == 4) { // Input CCCD done
                            Log.d(TAG, "All notifications enabled — starting gist subcommand sequence")
                            sendSubcommandSequence(gatt)
                        }
                    }
                } else {
                    Log.e(TAG, "Descriptor write failed: $status")
                }
            }

            override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.d(TAG, "Subcommand write success for ${characteristic.uuid}")
                    sendNextSubcommand(gatt) // Send next from queue
                } else {
                    Log.e(TAG, "Subcommand write failed: $status")
                }
            }

            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                val data = characteristic.value ?: return
                val hex = data.joinToString(" ") { "%02x".format(it) }

                if (characteristic.uuid == COMMAND_RESPONSE_UUID) {
                    Log.d(TAG, "SUBCMD REPLY from c765a961...: $hex")  // Turn this ON!

                    if (data.size >= 16) {
                        val readAddress = (data[15].toInt() and 0xFF shl 24) or
                                (data[14].toInt() and 0xFF shl 16) or
                                (data[13].toInt() and 0xFF shl 8) or
                                (data[12].toInt() and 0xFF)  // <I little-endian

                        val dataLen = data[8].toInt() and 0xFF
                        if (data.size >= 16 + dataLen) {
                            val payload = data.sliceArray(16 until 16 + dataLen)

                            Log.d(TAG, "SPI read from 0x${readAddress.toString(16)} ($dataLen bytes)")

                            when (readAddress) {
                                0x13000 -> {
                                    val serialBytes = payload.sliceArray(0x02 until 0x12)
                                    val serial = serialBytes.toString(Charsets.UTF_8).trimEnd('\u0000')
                                    val vendorId = (payload[0x13].toInt() and 0xFF shl 8) or (payload[0x12].toInt() and 0xFF)
                                    val productId = (payload[0x15].toInt() and 0xFF shl 8) or (payload[0x14].toInt() and 0xFF)

                                    Log.d(TAG, "Device Info:")
                                    Log.d(TAG, "  Serial: $serial")
                                    Log.d(TAG, "  Vendor ID: 0x${vendorId.toString(16)}")
                                    Log.d(TAG, "  Product ID: 0x${productId.toString(16)}")
                                    // Colours: payload[0x19:0x1C] body, 0x1C:0x1F buttons, etc.
                                }
                                0x1FA000 -> {
                                    val host1Bytes = payload.sliceArray(0x08 until 0x0E)
                                    val host2Bytes = payload.sliceArray(0x30 until 0x36)
                                    val ltkBytes = payload.sliceArray(0x1A until 0x2A).reversedArray()  // [::-1]

                                    Log.d(TAG, "host address #1: ${host1Bytes.joinToString("") { "%02x".format(it) }}")
                                    Log.d(TAG, "host address #2: ${host2Bytes.joinToString("") { "%02x".format(it) }}")
                                    Log.d(TAG, "LTK: ${ltkBytes.joinToString("") { "%02x".format(it) }}")
                                }
                                // Add stick cal parsing if wanted (0x13080, 0x130C0, 0x1FC040)
                                else -> {
                                    // Optional: log other addresses
                                }
                            }
                        } else {
                            Log.w(TAG, "Truncated SPI payload (len $dataLen, got ${data.size - 16} bytes) — increase MTU!")
                        }
                    }
                } else if (characteristic.uuid == INPUT_REPORT_UUID_1) {
                    // Keep inputs suppressed to avoid flood
                    // Optional: Log only on button press or stick move for debugging
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

    private fun configureInputReports(gatt: BluetoothGatt) {
        val inputChar1 = mainService!!.getCharacteristic(INPUT_REPORT_UUID_1)
        if (inputChar1 == null) {
            Log.e(TAG, "Input char1 not found")
            return
        }
        val customDesc1 = inputChar1.getDescriptor(CUSTOM_DESCRIPTOR_UUID)
        if (customDesc1 == null) {
            Log.e(TAG, "Custom desc1 not found")
            return
        }
        customDesc1.value = byteArrayOf(0x85.toByte(), 0x00.toByte())
        if (gatt.writeDescriptor(customDesc1)) {
            Log.d(TAG, "Writing 0x8500 to custom descriptor for input report 1")
            configState = 1
        } else {
            Log.e(TAG, "Failed to initiate write to custom desc1")
        }
    }

    private fun configureSecondInputReport(gatt: BluetoothGatt) {
        val inputChar2 = mainService!!.getCharacteristic(INPUT_REPORT_UUID_2)
        if (inputChar2 == null) {
            enableNotifications(gatt)
            return
        }
        val customDesc2 = inputChar2.getDescriptor(CUSTOM_DESCRIPTOR_UUID)
        if (customDesc2 == null) {
            enableNotifications(gatt)
            return
        }
        customDesc2.value = byteArrayOf(0x85.toByte(), 0x00.toByte())
        if (gatt.writeDescriptor(customDesc2)) {
            Log.d(TAG, "Writing 0x8500 to custom descriptor for input report 2")
            configState = 2
        } else {
            Log.e(TAG, "Failed to initiate write to custom desc2")
            enableNotifications(gatt)
        }
    }

    private fun enableNotifications(gatt: BluetoothGatt) {
        inputCharacteristic = mainService!!.getCharacteristic(INPUT_REPORT_UUID_1)
        if (inputCharacteristic == null) {
            Log.e(TAG, "Input char1 not found")
            return
        }
        gatt.setCharacteristicNotification(inputCharacteristic!!, true)
        val cccd = inputCharacteristic!!.getDescriptor(CLIENT_CONFIG_UUID)
        if (cccd == null) {
            Log.e(TAG, "CCCD not found")
            return
        }
        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        if (gatt.writeDescriptor(cccd)) {
            Log.d(TAG, "Enabling CCCD for notifications")
            configState = 4
        } else {
            Log.e(TAG, "Failed to initiate CCCD write")
        }
    }
    private fun enableCommandResponseNotifications(gatt: BluetoothGatt) {
        val cmdResponseChar = mainService!!.getCharacteristic(COMMAND_RESPONSE_UUID)
        if (cmdResponseChar == null) {
            Log.e(TAG, "Command response char not found")
            enableNotifications(gatt) // Proceed anyway
            return
        }
        gatt.setCharacteristicNotification(cmdResponseChar, true)
        val cccd = cmdResponseChar.getDescriptor(CLIENT_CONFIG_UUID)
        if (cccd == null) {
            Log.e(TAG, "Command response CCCD not found")
            enableNotifications(gatt)
            return
        }
        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        if (gatt.writeDescriptor(cccd)) {
            Log.d(TAG, "Enabling CCCD for command response notifications")
            configState = 3
        } else {
            Log.e(TAG, "Failed to initiate command response CCCD write")
            enableNotifications(gatt)
        }
    }

    private fun sendSubcommand(gatt: BluetoothGatt, subcmd: Byte, data: ByteArray = byteArrayOf()) {
        val outputChar = mainService!!.getCharacteristic(OUTPUT_REPORT_UUID)
        if (outputChar == null) {
            Log.e(TAG, "Correct output char not found - check UUID")
            return
        }

        val packet = byteArrayOf(subcmd) + data  // Direct: subcmd first, then data bytes

        // NO 33-byte padding for this characteristic
        outputChar.value = packet
        outputChar.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        if (gatt.writeCharacteristic(outputChar)) {
            Log.d(TAG, "Sent subcommand 0x${"%02x".format(subcmd)} with data: ${data.joinToString(" ") { "%02x".format(it) }} to correct char")
        } else {
            Log.e(TAG, "Failed to send subcommand 0x${"%02x".format(subcmd)}")
        }
    }

    private fun sendNextSubcommand(gatt: BluetoothGatt) {
        if (subcommandQueue.isNotEmpty()) {
            val (subcmd, data) = subcommandQueue.removeAt(0)
            sendSubcommand(gatt, subcmd, data)
        } else {
            updateNotification("All gist commands sent — test input!")
        }
    }

    private fun sendSubcommandSequence(gatt: BluetoothGatt) {
        subcommandQueue.clear()

        // 1. Initial connection vibration sample (index 0x03 - exact from gist)
        subcommandQueue.add(Pair(0x0A.toByte(), byteArrayOf(0x91.toByte(), 0x01.toByte(), 0x02.toByte(), 0x00.toByte(), 0x04.toByte(), 0x00.toByte(), 0x00.toByte(), 0x03.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte())))

        // Gist order: LEDs first (mask 0b0110 = 0x06 for example)
        subcommandQueue.add(Pair(0x09.toByte(), byteArrayOf(0x91.toByte(), 0x01.toByte(), 0x07.toByte(), 0x00.toByte(), 0x08.toByte(), 0x00.toByte(), 0x00.toByte(), 0x06.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte())))

        // Features config (0xFF all)
        subcommandQueue.add(Pair(0x0C.toByte(), byteArrayOf(0x91.toByte(), 0x01.toByte(), 0x02.toByte(), 0x00.toByte(), 0x04.toByte(), 0x00.toByte(), 0x00.toByte(), 0xFF.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte())))

        // Features enable (0x37 for Joy-Con full: IMU/vibe/etc.)
        subcommandQueue.add(Pair(0x0C.toByte(), byteArrayOf(0x91.toByte(), 0x01.toByte(), 0x04.toByte(), 0x00.toByte(), 0x04.toByte(), 0x00.toByte(), 0x00.toByte(), 0x37.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte())))

        // Vibration sample (index 0x03)
        //subcommandQueue.add(Pair(0x0A.toByte(), byteArrayOf(0x91.toByte(), 0x01.toByte(), 0x02.toByte(), 0x00.toByte(), 0x04.toByte(), 0x00.toByte(), 0x00.toByte(), 0x03.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte())))

        // Version
        subcommandQueue.add(Pair(0x10.toByte(), byteArrayOf(0x91.toByte(), 0x01.toByte(), 0x01.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte())))

        sendNextSubcommand(gatt)
    }

    override fun onDestroy() {
        bluetoothGatt?.close()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}