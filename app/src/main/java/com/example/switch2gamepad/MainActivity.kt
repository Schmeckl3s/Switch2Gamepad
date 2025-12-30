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

    private val REQUIRED_PERMISSIONS = arrayOf(
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.BLUETOOTH_SCAN
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (checkBluetoothPermissions()) {
            startTheService()
        } else {
            requestBluetoothPermissions()
        }
    }

    private fun checkBluetoothPermissions(): Boolean {
        return REQUIRED_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestBluetoothPermissions() {
        ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            startTheService()
        } else {
            Log.e("MainActivity", "Permissions denied")
            // Show explanation
        }
    }

    private fun startTheService() {
        val serviceIntent = Intent(this, Switch2ControllerService::class.java)
        startForegroundService(serviceIntent)
        finish()  // Close activity
    }
}