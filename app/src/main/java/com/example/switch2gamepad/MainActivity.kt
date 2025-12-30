package com.example.switch2gamepad

import android.Manifest
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import android.util.Log

class MainActivity : AppCompatActivity() {
    private val REQUEST_CODE = 1
    private val TAG = "MainActivity"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (checkBluetoothPermissions()) {
            startTheService()
        } else {
            requestBluetoothPermissions()
        }
    }

    private fun checkBluetoothPermissions(): Boolean {
        val connect = ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        val scan = ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        Log.d(TAG, "Permissions check: CONNECT=$connect, SCAN=$scan")
        return connect && scan
    }

    private fun requestBluetoothPermissions() {
        Log.d(TAG, "Requesting Bluetooth permissions")
        ActivityCompat.requestPermissions(this, arrayOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN
        ), REQUEST_CODE)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE && grantResults.size == 2 && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            Log.d(TAG, "Permissions granted – starting service")
            startTheService()
        } else {
            Log.e(TAG, "Permissions denied – can't start service")
            finish()  // Close app if denied
        }
    }

    private fun startTheService() {
        val serviceIntent = Intent(this, Switch2ControllerService::class.java)
        startForegroundService(serviceIntent)
        finish()  // Close activity
    }
}