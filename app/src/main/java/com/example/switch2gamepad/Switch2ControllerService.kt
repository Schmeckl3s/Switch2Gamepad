package com.example.switch2gamepad

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Intent
import android.os.IBinder
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

class Switch2ControllerService : Service() {

    private val TAG = "Switch2Service"
    private val CHANNEL_ID = "switch2_channel"

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Switch2 UHID Ready")
            .setContentText("Extracting dex... Check Logcat for ADB command")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .build()

        startForeground(1, notification)

        // Extract dex and print ADB command
        val dexPath = extractDexToExternal()
        if (dexPath != null) {
            val descriptorHex = "05010905A1018515090119012910150025017501951081020939150025073500463B016514750495018142093009310932093516008026FF7F751095048102C0"
            val adbCommand = """
                adb shell CLASSPATH=$dexPath app_process /system/bin com.android.commands.hid.Hid addDevice 1 "Switch2 Controller" "" 0x045E 0x028E 3 $descriptorHex
            """.trimIndent()

            Log.d(TAG, "=== SUCCESS ===")
            Log.d(TAG, "Dex extracted to: $dexPath")
            Log.d(TAG, "Run this ADB command to create virtual gamepad:")
            Log.d(TAG, adbCommand)
            Log.d(TAG, "===============")

            updateNotification("Virtual Gamepad Ready — Run ADB command (see Logcat)")
        } else {
            Log.e(TAG, "Failed to extract dex file")
            updateNotification("Error: Failed to extract dex")
        }

        // Start BLE scan (so app is ready when you launch virtual device)
        startBleScan()

        return START_STICKY
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Switch2 UHID", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun updateNotification(text: String) {
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Switch2 UHID")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .build()
        startForeground(1, notification)
    }

    private fun extractDexToExternal(): String? {
        val apkPath = packageCodePath
        val externalDir = getExternalFilesDir(null) ?: run {
            Log.e(TAG, "No external files dir")
            return null
        }
        val dexFile = File(externalDir, "switch2_uhid.dex")

        if (dexFile.exists()) {
            Log.d(TAG, "Dex already exists at ${dexFile.absolutePath}")
            return dexFile.absolutePath
        }

        try {
            ZipInputStream(FileInputStream(apkPath)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if ("classes.dex" == entry.name) {
                        FileOutputStream(dexFile).use { fos ->
                            val buffer = ByteArray(8192)
                            var len: Int
                            while (zis.read(buffer).also { len = it } > 0) {
                                fos.write(buffer, 0, len)
                            }
                        }
                        dexFile.setReadOnly()
                        return dexFile.absolutePath
                    }
                    entry = zis.nextEntry
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Extract failed", e)
        }
        return null
    }

    private fun startBleScan() {
        val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        val scanner = bluetoothAdapter.bluetoothLeScanner

        val scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device
                val name = device.name ?: return
                if (name.contains("Joy-Con", ignoreCase = true) || name.contains("Pro Controller", ignoreCase = true)) {
                    Log.d(TAG, "Found Switch 2 controller: $name (${device.address})")
                    updateNotification("Found: $name — Ready for inputs")
                    scanner.stopScan(this)
                    // In future: connect and parse here
                }
            }
        }

        scanner.startScan(scanCallback)
        Log.d(TAG, "Started scanning for Switch 2 controllers...")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}