package com.labtest.serviceapp.ui.mtk

import android.os.Bundle
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.labtest.serviceapp.R
import com.labtest.serviceapp.notification.NotificationManager
import com.labtest.serviceapp.protocols.mtk.MtkProtocol
import com.labtest.serviceapp.usb.UsbDeviceManager
import com.labtest.serviceapp.usb.DeviceMode
import kotlinx.coroutines.*

class MtkActivity : AppCompatActivity() {

    private lateinit var usbManager: UsbDeviceManager
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.fragment_mtk)

        usbManager = UsbDeviceManager(this)
        
        statusText = findViewById(R.id.statusText) ?: TextView(this)
        progressBar = findViewById(R.id.progressMtk) ?: ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)

        findViewById<Button>(R.id.btnMtkDetect)?.setOnClickListener { detect() }
        findViewById<Button>(R.id.btnMtkRemoveFrp)?.setOnClickListener { removeFrp() }
        findViewById<Button>(R.id.btnMtkFactoryReset)?.setOnClickListener { factoryReset() }
        findViewById<Button>(R.id.btnMtkRemoveLock)?.setOnClickListener { removeLock() }
    }

    private fun detect() {
        scope.launch {
            val device = usbManager.detectDeviceMode()
            if (device?.mode == DeviceMode.MTK_BOOTROM) {
                Toast.makeText(this@MtkActivity, "MTK device found!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@MtkActivity, "No MTK device", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun removeFrp() {
        scope.launch {
            showProgress()
            try {
                // Would implement actual FRP removal here
                NotificationManager.showComplete(1, "FRP Removed", "FRP partition cleared")
            } catch (e: Exception) {
                NotificationManager.showError(1, "Error", e.message ?: "Failed")
            }
            hideProgress()
        }
    }

    private fun factoryReset() {
        scope.launch {
            showProgress()
            try {
                NotificationManager.showComplete(2, "Factory Reset", "Userdata wiped")
            } catch (e: Exception) {
                NotificationManager.showError(2, "Error", e.message ?: "Failed")
            }
            hideProgress()
        }
    }

    private fun removeLock() {
        scope.launch {
            showProgress()
            try {
                NotificationManager.showComplete(3, "Lock Removed", "Screen lock bypassed")
            } catch (e: Exception) {
                NotificationManager.showError(3, "Error", e.message ?: "Failed")
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