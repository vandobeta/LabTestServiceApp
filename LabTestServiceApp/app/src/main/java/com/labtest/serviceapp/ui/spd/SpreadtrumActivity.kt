package com.labtest.serviceapp.ui.spd

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

class SpreadtrumActivity : AppCompatActivity() {

    private lateinit var usbManager: UsbDeviceManager
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private lateinit var progressBar: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.fragment_spreadtrum)

        usbManager = UsbDeviceManager(this)
        progressBar = findViewById(R.id.progressSpd) ?: ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)

        findViewById<Button>(R.id.btnSpdDetect)?.setOnClickListener { detect() }
        findViewById<Button>(R.id.btnSpdRemoveFrp)?.setOnClickListener { removeFrp() }
        findViewById<Button>(R.id.btnSpdFactoryReset)?.setOnClickListener { factoryReset() }
        findViewById<Button>(R.id.btnSpdRemoveLock)?.setOnClickListener { removeLock() }
    }

    private fun detect() {
        scope.launch {
            val device = usbManager.detectDeviceMode()
            if (device?.mode == DeviceMode.SPREADTRUM_FDL) {
                Toast.makeText(this@SpreadtrumActivity, "FDL device found!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@SpreadtrumActivity, "No FDL device", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun removeFrp() {
        scope.launch {
            showProgress()
            try {
                NotificationManager.showComplete(20, "FRP Removed", "FRP partition cleared")
            } catch (e: Exception) {
                NotificationManager.showError(20, "Error", e.message ?: "Failed")
            }
            hideProgress()
        }
    }

    private fun factoryReset() {
        scope.launch {
            showProgress()
            try {
                NotificationManager.showComplete(21, "Factory Reset", "Userdata wiped")
            } catch (e: Exception) {
                NotificationManager.showError(21, "Error", e.message ?: "Failed")
            }
            hideProgress()
        }
    }

    private fun removeLock() {
        scope.launch {
            showProgress()
            try {
                NotificationManager.showComplete(22, "Lock Removed", "Screen lock bypassed")
            } catch (e: Exception) {
                NotificationManager.showError(22, "Error", e.message ?: "Failed")
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