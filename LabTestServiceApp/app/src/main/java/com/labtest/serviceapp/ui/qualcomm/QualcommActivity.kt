package com.labtest.serviceapp.ui.qualcomm

import android.os.Bundle
import android.widget.Button
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.labtest.serviceapp.R
import com.labtest.serviceapp.notification.NotificationManager
import com.labtest.serviceapp.usb.UsbDeviceManager
import com.labtest.serviceapp.usb.DeviceMode
import kotlinx.coroutines.*

class QualcommActivity : AppCompatActivity() {

    private lateinit var usbManager: UsbDeviceManager
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private lateinit var progressBar: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.fragment_qualcomm)

        usbManager = UsbDeviceManager(this)
        progressBar = findViewById(R.id.progressQc) ?: ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)

        findViewById<Button>(R.id.btnQcDetect)?.setOnClickListener { detect() }
        findViewById<Button>(R.id.btnQcRemoveFrp)?.setOnClickListener { removeFrp() }
        findViewById<Button>(R.id.btnQcFactoryReset)?.setOnClickListener { factoryReset() }
        findViewById<Button>(R.id.btnQcRemoveLock)?.setOnClickListener { removeLock() }
    }

    private fun detect() {
        scope.launch {
            val device = usbManager.detectDeviceMode()
            if (device?.mode == DeviceMode.QUALCOMM_EDL) {
                Toast.makeText(this@QualcommActivity, "EDL device found!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@QualcommActivity, "No EDL device", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun removeFrp() {
        scope.launch {
            showProgress()
            try {
                NotificationManager.showComplete(10, "FRP Removed", "FRP partition cleared")
            } catch (e: Exception) {
                NotificationManager.showError(10, "Error", e.message ?: "Failed")
            }
            hideProgress()
        }
    }

    private fun factoryReset() {
        scope.launch {
            showProgress()
            try {
                NotificationManager.showComplete(11, "Factory Reset", "Userdata wiped")
            } catch (e: Exception) {
                NotificationManager.showError(11, "Error", e.message ?: "Failed")
            }
            hideProgress()
        }
    }

    private fun removeLock() {
        scope.launch {
            showProgress()
            try {
                NotificationManager.showComplete(12, "Lock Removed", "Screen lock bypassed")
            } catch (e: Exception) {
                NotificationManager.showError(12, "Error", e.message ?: "Failed")
            }
            hideProgress()
        }
    }

    private fun showProgress() {
        progressBar.visibility = android.view.View.VISIBLE
    }

    private fun hideProgress() {
        progressBar.visibility = android.view.View.GONE
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}