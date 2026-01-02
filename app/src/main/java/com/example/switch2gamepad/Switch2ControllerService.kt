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
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import java.security.SecureRandom

import android.os.Handler
import android.os.Looper
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class Switch2ControllerService : Service() {

    private val TAG = "Switch2Service"
    private val CHANNEL_ID = "switch2_channel"

    private val SERVICE_UUID = UUID.fromString("ab7de9be-89fe-49ad-828f-118f09df7fd0")
    private val INPUT_REPORT_UUID_1 = UUID.fromString("ab7de9be-89fe-49ad-828f-118f09df7fd2")
    private val INPUT_REPORT_UUID_2 = UUID.fromString("cc1bbbb5-7354-4d32-a716-a81cb241a32a")
    private val CUSTOM_DESCRIPTOR_UUID = UUID.fromString("679d5510-5a24-4dee-9557-95df80486ecb")
    private val OUTPUT_REPORT_UUID = UUID.fromString("649d4ac9-8eb7-4e6c-af44-1ea54fe5f005")
    private val COMMAND_RESPONSE_UUID = UUID.fromString("c765a961-d9d8-4d36-a20a-5315b111836a")

    private val VIBRATION_UUID = UUID.fromString("289326cb-a471-485d-a8f4-240c14f18241")
    private var vibrationChar: BluetoothGattCharacteristic? = null
    private var vibrationCounter: Int = 0
    private val handler = Handler(Looper.getMainLooper())

    private val songNotes: List<Pair<Double, Int>> = listOf(
        // We're no strangers to love
        493.88 to 250,  // B
        554.37 to 250,  // ^C#
        587.33 to 650,  // ^D (held)
        587.33 to 250,  // ^D
        659.25 to 250,  // ^E
        554.37 to 650,  // ^C# (held)
        493.88 to 650,  // B (held)
        440.00 to 800,  // A (phrase end, longer)

        0.0 to 300,     // rest between phrases

        // You know the rules and so do I
        493.88 to 250,  // B
        493.88 to 250,  // B
        554.37 to 250,  // ^C#
        587.33 to 650,  // ^D (held)
        493.88 to 250,  // B
        440.00 to 250,  // A
        880.00 to 250,  // ^A
        880.00 to 250,  // ^A
        659.25 to 800,  // ^E (longer for "I" hold)

        0.0 to 300,     // rest

        // A full commitment's what I'm thinking of
        493.88 to 250,  // B
        493.88 to 250,  // B
        554.37 to 650,  // ^C# (held)
        587.33 to 650,  // ^D (held)
        493.88 to 250,  // B
        587.33 to 250,  // ^D
        659.25 to 250,  // ^E
        554.37 to 650,  // ^C# (held)
        493.88 to 250,  // B
        554.37 to 650,  // ^C# (held)
        493.88 to 650,  // B (held)
        440.00 to 800,  // A (phrase end)

        0.0 to 300,     // rest

        // You wouldn't get this from any other guy
        493.88 to 250,  // B
        493.88 to 650,  // B (held)
        554.37 to 250,  // ^C#
        587.33 to 250,  // ^D
        493.88 to 250,  // B
        440.00 to 250,  // A
        659.25 to 650,  // ^E (held)
        659.25 to 250,  // ^E
        659.25 to 650,  // ^E (held)
        739.99 to 250,  // ^F#
        659.25 to 800,  // ^E (longer end)

        0.0 to 400,     // longer rest into pre-chorus

        // I just wanna tell you how I'm feeling
        587.33 to 250,  // ^D
        659.25 to 250,  // ^E
        739.99 to 650,  // ^F# (held)
        587.33 to 250,  // ^D
        659.25 to 250,  // ^E
        659.25 to 250,  // ^E
        659.25 to 250,  // ^E
        739.99 to 250,  // ^F#
        659.25 to 650,  // ^E (held)
        440.00 to 1200, // A (extra long for "feeeeeeling")

        0.0 to 300,     // rest

        // Gotta make you understand
        493.88 to 650,  // B (held)
        554.37 to 250,  // ^C#
        587.33 to 250,  // ^D
        493.88 to 250,  // B
        659.25 to 650,  // ^E (held)
        739.99 to 650,  // ^F# (held)
        659.25 to 800,  // ^E (longer)

        0.0 to 500,     // pause into chorus

        // Chorus x2 feel (full recognizable part)
        440.00 to 650,  // A (held)
        493.88 to 250,  // B
        587.33 to 650,  // ^D (held)
        493.88 to 250,  // B
        739.99 to 250,  // ^F#
        739.99 to 250,  // ^F#
        659.25 to 800,  // ^E (long)

        440.00 to 650,  // A
        493.88 to 250,  // B
        587.33 to 650,  // ^D
        493.88 to 250,  // B
        659.25 to 250,  // ^E
        659.25 to 250,  // ^E
        587.33 to 650,  // ^D (held)
        554.37 to 650,  // ^C# (held)
        493.88 to 800,  // B (long)

        440.00 to 650,  // A
        493.88 to 250,  // B
        587.33 to 650,  // ^D
        493.88 to 250,  // B
        587.33 to 250,  // ^D
        659.25 to 650,  // ^E (held)
        554.37 to 650,  // ^C# (held)
        440.00 to 250,  // A
        440.00 to 250,  // A
        659.25 to 250,  // ^E
        587.33 to 800,  // ^D (long)

        // Repeat some chorus lines for full feel
        440.00 to 650,  // A
        493.88 to 250,  // B
        587.33 to 650,  // ^D
        493.88 to 250,  // B
        739.99 to 250,  // ^F#
        739.99 to 250,  // ^F#
        659.25 to 800,  // ^E

        440.00 to 650,  // A
        493.88 to 250,  // B
        587.33 to 650,  // ^D
        493.88 to 250,  // B
        880.00 to 250,  // ^A
        554.37 to 650,  // ^C# (held)
        587.33 to 650,  // ^D (held)
        554.37 to 650,  // ^C# (held)
        493.88 to 1000, // B (long "you")

        440.00 to 650,  // A
        493.88 to 250,  // B
        587.33 to 650,  // ^D
        493.88 to 250,  // B
        587.33 to 250,  // ^D
        659.25 to 250,  // ^E
        554.37 to 650,  // ^C# (held)
        440.00 to 250,  // A
        440.00 to 250,  // A
        659.25 to 250,  // ^E
        587.33 to 1000, // ^D (final long hold)

        0.0 to 1500     // final fade/rest
    )
    private val subcommandQueue: MutableList<Pair<Byte, ByteArray>> = mutableListOf()
    private var mainService: BluetoothGattService? = null
    private var bluetoothGatt: BluetoothGatt? = null

    private val CLIENT_CONFIG_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private val NINTENDO_MANUFACTURER_ID = 0x0553
    private val VALID_SWITCH2_PIDS = listOf(0x2066, 0x2067, 0x2069, 0x2073)

    private var controllerName: String = "Unknown"

    private var configState: Int = 0

    // Pairing related
    private val secureRandom = SecureRandom()

    private val CONTROLLER_PUBLIC_KEY_B1 = byteArrayOf(
        0x5C.toByte(), 0xF6.toByte(), 0xEE.toByte(), 0x79.toByte(), 0x2C.toByte(), 0xDF.toByte(), 0x05.toByte(), 0xE1.toByte(),
        0xBA.toByte(), 0x2B.toByte(), 0x63.toByte(), 0x25.toByte(), 0xC4.toByte(), 0x1A.toByte(), 0x5F.toByte(), 0x10.toByte()
    ).map { it.toByte() }.toByteArray()

    private var pendingA1: ByteArray? = null
    private var pendingA2: ByteArray? = null
    private var pendingLtk: ByteArray? = null
    private var pairingState: Int = 0 // 0=idle, 1=sent addresses, 2=sent A1, 3=sent A2

    private fun getHostBluetoothAddressReversed(): ByteArray {
        var macStr = BluetoothAdapter.getDefaultAdapter()?.address ?: "02:00:00:00:00:00"
        if (macStr == "02:00:00:00:00:00") {
            macStr = "INSERT_YOUR_BLUETOOTH_MAC"
        }
        val macBytes = macStr.split(":").map { it.toInt(16).toByte() }.toByteArray()
        return reverseBytes(macBytes)

    }

    private fun reverseBytes(input: ByteArray): ByteArray = input.reversedArray()

    private fun aesEcbEncrypt(key: ByteArray, data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        return cipher.doFinal(data)
    }

    private fun sendPairingPacket(gatt: BluetoothGatt, subCmd: Byte, payload: ByteArray) {
        val length = (payload.size + 1).toByte()
        val packet = byteArrayOf(0x15.toByte(), 0x91.toByte(), 0x01.toByte(), subCmd, 0x00.toByte(), length, 0x00.toByte(), 0x00.toByte(), 0x00.toByte()) + payload

        val outputChar = mainService?.getCharacteristic(OUTPUT_REPORT_UUID) ?: return
        outputChar.value = packet
        outputChar.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        gatt.writeCharacteristic(outputChar)
        Log.d(TAG, "Sent pairing subcmd 0x${"%02x".format(subCmd)} (length 0x${"%02x".format(length)}): ${packet.joinToString(" ") { "%02x".format(it) }}")
    }
    private fun performFullPairing(gatt: BluetoothGatt) {
        Log.d(TAG, "=== STARTING PROPRIETARY PAIRING ===")


        val hostRev = getHostBluetoothAddressReversed()
        val hostRevVariant = hostRev.copyOf()
        hostRevVariant[0] = (hostRevVariant[0].toInt() - 1).toByte()


        val addressesPayload = byteArrayOf(0x02.toByte()) + hostRev + hostRevVariant // 13 bytes

        val a1 = ByteArray(16).apply { secureRandom.nextBytes(this) }
        pendingA1 = a1

        val a2 = ByteArray(16).apply { secureRandom.nextBytes(this) }
        pendingA2 = a2

        val androidId = android.provider.Settings.Secure.getString(
            contentResolver,
            android.provider.Settings.Secure.ANDROID_ID
        )
        Log.d(TAG, "Unique Device ID: $androidId")

        Log.d(TAG, "Host MAC: INSERT_YOUR_BLUETOOTH_MAC")
        Log.d(TAG, "Host reversed: ${hostRev.joinToString(" ") { "%02x".format(it) }}")
        Log.d(TAG, "Variant reversed: ${hostRevVariant.joinToString(" ") { "%02x".format(it) }}")
        Log.d(TAG, "A1 (normal): ${a1.joinToString(" ") { "%02x".format(it) }}")
        Log.d(TAG, "A2 (normal): ${a2.joinToString(" ") { "%02x".format(it) }}")


        sendPairingPacket(gatt, 0x01.toByte(), addressesPayload)
        pairingState = 1
    }

    private fun handlePairingResponse(gatt: BluetoothGatt, data: ByteArray, characteristic: BluetoothGattCharacteristic) {
        if (characteristic.uuid != COMMAND_RESPONSE_UUID) return

        val hex = data.joinToString(" ") { "%02x".format(it) }
        Log.d(TAG, "Pairing/finalize response: $hex (size ${data.size})")

        if (data.size < 9 || data[0] != 0x15.toByte() || data[1] != 0x01.toByte() || data[4] != 0x10.toByte() || data[5] != 0x78.toByte()) {
            Log.e(TAG, "Invalid response during pairing/finalize")
            pairingState = 0
            return
        }

        val subCmd = data[3]
        val payload = data.copyOfRange(9, data.size)

        when (pairingState) {
            1 -> if (subCmd == 0x01.toByte()) {
                Log.d(TAG, "Addresses accepted")
                sendPairingPacket(gatt, 0x04.toByte(), pendingA1!!)
                pairingState = 2
            }
            2 -> if (subCmd == 0x04.toByte() && payload.size >= 16) {
                val receivedB1 = payload.copyOfRange(0, 16)
                Log.d(TAG, "Received B1: ${receivedB1.joinToString(" ") { "%02x".format(it) }}")

                pendingLtk = ByteArray(16) { i -> (pendingA1!![i].toInt() xor receivedB1[i].toInt()).toByte() }
                Log.d(TAG, "Computed LTK: ${pendingLtk!!.joinToString(" ") { "%02x".format(it) }}")

                sendPairingPacket(gatt, 0x02.toByte(), pendingA2!!)
                pairingState = 3
            }
            3 -> if (subCmd == 0x02.toByte() && payload.size >= 16) {
                val receivedB2 = payload.copyOfRange(0, 16)
                Log.d(TAG, "Received B2: ${receivedB2.joinToString(" ") { "%02x".format(it) }}")

                val ltkRev = reverseBytes(pendingLtk!!)
                val a2Rev = reverseBytes(pendingA2!!)
                val computedB2 = aesEcbEncrypt(ltkRev, a2Rev)

                Log.d(TAG, "Computed B2: ${computedB2.joinToString(" ") { "%02x".format(it) }}")

                if (computedB2.contentEquals(receivedB2)) {
                    Log.d(TAG, "PAIRING SUCCESS - verification passed!")
                    sendPairingPacket(gatt, 0x03.toByte(), byteArrayOf(0x00.toByte())) // match Python example
                    pairingState = 4
                    updateNotification("Pairing finalized - waiting for ack...")
                } else {
                    Log.e(TAG, "PAIRING FAILED - B2 mismatch")
                    updateNotification("Pairing failed")
                    pairingState = 0
                }
            }
            4 -> if (subCmd == 0x03.toByte()) {
                Log.d(TAG, "Finalize acknowledged by controller")
                updateNotification("Paired and configured!")
                pairingState = 0
                pendingA1 = null
                pendingA2 = null
                pendingLtk = null
                sendSubcommandSequence(gatt) // Now send config only after finalize ack
            }
        }
    }


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

                val vid = (manuData[4].toInt() and 0xFF shl 8) or (manuData[3].toInt() and 0xFF)
                val pid = (manuData[6].toInt() and 0xFF shl 8) or (manuData[5].toInt() and 0xFF)

                if (vid == 0x057E && pid in VALID_SWITCH2_PIDS) {
                    val device = result.device
                    controllerName = device.name ?: "Unknown"

                    scanner.stopScan(this)
                    connectToDevice(device)
                    Log.d(TAG, "Found Switch 2 controller ($controllerName) - connecting...")
                }
            }
        }

        scanner.startScan(listOf(filter), settings, scanCallback)
        Log.d(TAG, "Scanning... Hold SYNC button on controller for fresh pairing mode.")
    }

    private fun connectToDevice(device: BluetoothDevice) {
        bluetoothGatt = device.connectGatt(this, false, object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    Log.d(TAG, "Connected - requesting large MTU")
                    gatt.requestMtu(517)
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    Log.e(TAG, "Disconnected")
                    updateNotification("Disconnected")
                    bluetoothGatt = null
                    startFilteredNintendoScan()
                }
            }

            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                Log.d(TAG, "MTU $mtu")
                gatt.discoverServices()
            }
            private fun triggerBondingViaRead(gatt: BluetoothGatt) {
                val char = mainService?.getCharacteristic(COMMAND_RESPONSE_UUID)
                if (char != null) {
                    Log.d(TAG, "Triggering bonding by reading protected char")
                    gatt.readCharacteristic(char)  // This should fail with INSUFFICIENT_AUTHENTICATION, prompting bond
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    logAllCharacteristics(gatt)
                    mainService = gatt.getService(SERVICE_UUID)
                    vibrationChar = mainService?.getCharacteristic(VIBRATION_UUID)
                    if (mainService == null) {
                        Log.e(TAG, "Main service not found")
                        return
                    }
                    if (vibrationChar == null) {
                        Log.e(TAG, "Vibration characteristic not found")
                        return
                    }
                    configState = 0
                    configureInputReports(gatt)
                    updateNotification("Connected - configuring...")
                }
            }

            override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.d(TAG, "Descriptor written: ${descriptor.uuid}")
                    when (descriptor.uuid) {
                        CUSTOM_DESCRIPTOR_UUID -> {
                            if (configState == 1) configureSecondInputReport(gatt)
                            else if (configState == 2) enableCommandResponseNotifications(gatt)
                        }
                        CLIENT_CONFIG_UUID -> {
                            if (configState == 3) enableNotifications(gatt)
                            else if (configState == 4) {
                                Log.d(TAG, "All notifications enabled - STARTING PAIRING FIRST")
                                performFullPairing(gatt)
                            }
                        }
                    }
                } else {
                    Log.e(TAG, "Descriptor write failed: $status")
                }
            }

            override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.d(TAG, "Characteristic write success")
                    if (subcommandQueue.isNotEmpty()) {
                        sendNextSubcommand(gatt)  // Only chain if more in queue
                    }
                } else {
                    Log.e(TAG, "Write failed: $status — queue stopped")
                    // Optional: clear queue or retry logic
                }
            }

            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                val data = characteristic.value ?: return
                val hex = data.joinToString(" ") { "%02x".format(it) }

                if (characteristic.uuid == COMMAND_RESPONSE_UUID) {
                    if (pairingState > 0) {
                        handlePairingResponse(gatt, data, characteristic)
                    } else {
                        Log.d(TAG, "SUBCMD REPLY: $hex (length: ${data.size})")

                        // Parse SPI read replies (subcommand 0x02 echo)
                        if (data.size >= 20 && data[0] == 0x02.toByte()) {
                            val dataLen = data[8].toInt() and 0xFF
                            val readAddress = (data[15].toInt() and 0xFF shl 24) or
                                    (data[14].toInt() and 0xFF shl 16) or
                                    (data[13].toInt() and 0xFF shl 8) or
                                    (data[12].toInt() and 0xFF)

                            Log.d(TAG, "SPI READ REPLY -> address: 0x${readAddress.toString(16).uppercase()} length: $dataLen")

                            if (data.size >= 16 + dataLen) {
                                val payload = data.sliceArray(16 until 16 + dataLen)

                                when (readAddress) {
                                    // Adjust these if your offsets differ slightly
                                    0x13000, 0x013000 -> {  // Device info / serial
                                        // Example parsing (adapt as needed)
                                        val serialBytes = payload.sliceArray(0x02 until 0x12)
                                        val serial = serialBytes.toString(Charsets.UTF_8).trimEnd('\u0000')
                                        val productId = (payload.getOrNull(0x15)?.toInt() ?: 0 and 0xFF shl 8) or
                                                (payload.getOrNull(0x14)?.toInt() ?: 0 and 0xFF)

                                        Log.d(TAG, "=== DEVICE INFO ===")
                                        Log.d(TAG, "Serial: $serial")
                                        Log.d(TAG, "Product ID: 0x${productId.toString(16).uppercase()}")
                                    }
                                    0x1FA000, 0xA01F00 -> {  // Pairing data
                                        val host1 = payload.sliceArray(0x08 until 0x0E).joinToString(":") { "%02x".format(it) }.uppercase()
                                        val host2 = payload.sliceArray(0x30 until 0x36).joinToString(":") { "%02x".format(it) }.uppercase()
                                        val ltkBytes = payload.sliceArray(0x1A until 0x2A).reversedArray()  // Often reversed or XOR'd
                                        val ltk = ltkBytes.joinToString(" ") { "%02x".format(it) }

                                        Log.d(TAG, "=== PAIRING DATA ===")
                                        Log.d(TAG, "Host address #1: $host1")
                                        Log.d(TAG, "Host address #2: $host2")
                                        Log.d(TAG, "LTK: $ltk")
                                    }
                                }
                            }
                        }
                    }
                } else if (characteristic.uuid == INPUT_REPORT_UUID_1 || characteristic.uuid == INPUT_REPORT_UUID_2) {
                    // Input processing...
                }
            }
        })
    }

    private fun logAllCharacteristics(gatt: BluetoothGatt) {
        Log.d(TAG, "=== GATT Characteristics ===")
        for (service in gatt.services) {
            Log.d(TAG, "Service: ${service.uuid}")
            for (char in service.characteristics) {
                Log.d(TAG, "  Char: ${char.uuid} Props: ${char.properties}")
                for (desc in char.descriptors) {
                    Log.d(TAG, "    Desc: ${desc.uuid}")
                }
            }
        }
    }

    private fun configureInputReports(gatt: BluetoothGatt) {
        configState = 1
        val char = mainService?.getCharacteristic(INPUT_REPORT_UUID_1) ?: return configureSecondInputReport(gatt)
        val desc = char.getDescriptor(CUSTOM_DESCRIPTOR_UUID) ?: return configureSecondInputReport(gatt)
        desc.value = byteArrayOf(0x85.toByte(), 0x00.toByte())
        gatt.writeDescriptor(desc)
    }

    private fun configureSecondInputReport(gatt: BluetoothGatt) {
        configState = 2
        val char = mainService?.getCharacteristic(INPUT_REPORT_UUID_2)
        if (char == null || char.getDescriptor(CUSTOM_DESCRIPTOR_UUID) == null) {
            enableCommandResponseNotifications(gatt)
            return
        }
        val desc = char.getDescriptor(CUSTOM_DESCRIPTOR_UUID)!!
        desc.value = byteArrayOf(0x85.toByte(), 0x00.toByte())
        gatt.writeDescriptor(desc)
    }

    private fun enableCommandResponseNotifications(gatt: BluetoothGatt) {
        configState = 3
        val char = mainService?.getCharacteristic(COMMAND_RESPONSE_UUID) ?: return enableNotifications(gatt)
        gatt.setCharacteristicNotification(char, true)
        val cccd = char.getDescriptor(CLIENT_CONFIG_UUID) ?: return enableNotifications(gatt)
        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        gatt.writeDescriptor(cccd)
    }

    private fun enableNotifications(gatt: BluetoothGatt) {
        configState = 4
        val char = mainService?.getCharacteristic(INPUT_REPORT_UUID_1) ?: return
        gatt.setCharacteristicNotification(char, true)
        val cccd = char.getDescriptor(CLIENT_CONFIG_UUID) ?: return
        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        gatt.writeDescriptor(cccd)
    }

    private fun sendSubcommand(gatt: BluetoothGatt, firstByte: Byte, data: ByteArray = byteArrayOf()) {
        val outputChar = mainService?.getCharacteristic(OUTPUT_REPORT_UUID) ?: return
        val packet = byteArrayOf(firstByte) + data
        outputChar.value = packet
        outputChar.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        gatt.writeCharacteristic(outputChar)
        Log.d(TAG, "Sent packet starting with 0x${"%02x".format(firstByte)}: ${packet.joinToString(" ") { "%02x".format(it) }}")
    }

    private fun sendNextSubcommand(gatt: BluetoothGatt) {
        if (subcommandQueue.isNotEmpty()) {
            val (firstByte, data) = subcommandQueue.removeAt(0)
            sendSubcommand(gatt, firstByte, data)
        } else {
            updateNotification("All setup commands sent — LEDs/vibration should work!")
              // Play the song after setup
        }
    }

    private fun sendVibration(freq0: Int, amp0: Int, freq1: Int, amp1: Int) {
        val char = vibrationChar ?: return
        val leftPacked = ((amp1.toLong() and 0x3FF) shl 30) or
                ((freq1.toLong() and 0x3FF) shl 20) or
                ((amp0.toLong() and 0x3FF) shl 10) or
                (freq0.toLong() and 0x3FF)
        val leftData = ByteArray(16)
        leftData[0] = ((0x05 shl 4) or vibrationCounter).toByte()
        for (i in 0..4) {
            leftData[i + 1] = ((leftPacked shr (i * 8)) and 0xFF).toByte()
        }

        val rightPacked = leftPacked  // Mirror to right motor for fuller sound; customize if needed
        val rightData = ByteArray(16)
        rightData[0] = leftData[0]
        for (i in 0..4) {
            rightData[i + 1] = ((rightPacked shr (i * 8)) and 0xFF).toByte()
        }

        val packet = byteArrayOf(0x01.toByte()) + leftData + rightData + ByteArray(9)
        char.value = packet
        char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        bluetoothGatt?.writeCharacteristic(char)
        Log.d(TAG, "Sent vibration: freq0=$freq0 amp0=$amp0 freq1=$freq1 amp1=$amp1")
        vibrationCounter = (vibrationCounter + 1) and 0x0F
    }

    // Play the song sequence
    // Keep simple single-packet playback for natural, safe sound
    private fun playNote(index: Int) {
        if (index >= songNotes.size) {
            sendVibration(0, 0, 0, 0)
            updateNotification("Rickroll vocal chorus complete! 🕺")
            return
        }
        val (freqHz, durationMs) = songNotes[index]
        val freqCode = if (freqHz <= 0.0) 0 else min(1023, max(0, (128 * log2(freqHz / 10.0)).roundToInt()))
        val amp = if (freqHz <= 0.0) 0 else 800  // Slightly higher amp for high notes clarity (safe)
        sendVibration(freqCode, amp, freqCode, amp)  // Strong mono tone
        handler.postDelayed({ playNote(index + 1) }, durationMs.toLong())
    }

    private fun playSong() {
        vibrationCounter = 0
        updateNotification("Rickrolling with vocal melody (high octave)...")
        playNote(0)
    }

    // Helper extension for Long to ByteArray (little-endian)
    private fun Long.toByteArray(size: Int, order: ByteOrder): ByteArray {
        val buffer = ByteBuffer.allocate(8).order(order).putLong(this)
        return buffer.array().copyOf(size)
    }

    private fun sendSubcommandSequence(gatt: BluetoothGatt) {
        subcommandQueue.clear()

        subcommandQueue.add(0x0C.toByte() to byteArrayOf(
            0x91.toByte(), 0x01.toByte(), 0x02.toByte(), 0x00.toByte(),
            0x04.toByte(), 0x00.toByte(), 0x00.toByte(), 0xFF.toByte()
        ))
        subcommandQueue.add(0x0C.toByte() to byteArrayOf(
            0x91.toByte(), 0x01.toByte(), 0x04.toByte(), 0x00.toByte(),
            0x04.toByte(), 0x00.toByte(), 0x00.toByte(), 0x37.toByte()
        ))
        subcommandQueue.add(0x09.toByte() to byteArrayOf(
            0x91.toByte(), 0x01.toByte(), 0x07.toByte(), 0x00.toByte(),
            0x08.toByte(), 0x00.toByte(), 0x00.toByte(), 0x06.toByte(),
            0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
            0x00.toByte(), 0x00.toByte(), 0x00.toByte()
        ))
        subcommandQueue.add(0x0A.toByte() to byteArrayOf(
            0x91.toByte(), 0x01.toByte(), 0x02.toByte(), 0x00.toByte(),
            0x04.toByte(), 0x00.toByte(), 0x00.toByte(), 0x03.toByte(),
            0x00.toByte(), 0x00.toByte(), 0x00.toByte()
        ))
        subcommandQueue.add(0x10.toByte() to byteArrayOf(
            0x91.toByte(), 0x01.toByte(), 0x01.toByte(), 0x00.toByte(),
            0x00.toByte(), 0x00.toByte(), 0x00.toByte()
        ))
        subcommandQueue.add(0x10.toByte() to byteArrayOf(
            0x91.toByte(), 0x01.toByte(), 0x01.toByte(), 0x00.toByte(),
            0x00.toByte(), 0x00.toByte(), 0x00.toByte()
        ))
        subcommandQueue.add(0x02.toByte() to byteArrayOf(
            0x91.toByte(), 0x01.toByte(), 0x04.toByte(), 0x00.toByte(),
            0x08.toByte(), 0x00.toByte(), 0x00.toByte(), 0x40.toByte(),
            0x7E.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
            0x30.toByte(), 0x01.toByte(), 0x00.toByte()
        ))
        subcommandQueue.add(0x02.toByte() to byteArrayOf(
            0x91.toByte(), 0x01.toByte(), 0x04.toByte(), 0x00.toByte(),
            0x08.toByte(), 0x00.toByte(), 0x00.toByte(), 0x40.toByte(),
            0x7E.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
            0xA0.toByte(), 0x1F.toByte(), 0x00.toByte()
        ))

        // Send the first one (the rest can be chained if needed, but for now just send all)

        sendNextSubcommand(gatt)
        playSong()
        updateNotification("Normal setup commands sent")

    }

    override fun onDestroy() {
        bluetoothGatt?.close()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}