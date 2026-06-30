package com.labtest.serviceapp

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.labtest.serviceapp.loader.ChipType
import com.labtest.serviceapp.loader.LoaderManager
import com.labtest.serviceapp.notification.NotificationManager
import com.labtest.serviceapp.service.LabTestForegroundService
import com.labtest.serviceapp.usb.UsbDeviceManager
import com.labtest.serviceapp.ui.mtk.MtkActivity
import com.labtest.serviceapp.ui.qualcomm.QualcommActivity
import com.labtest.serviceapp.ui.spd.SpreadtrumActivity
import kotlinx.coroutines.*

class MainActivity : AppCompatActivity() {

    private lateinit var usbManager: UsbDeviceManager
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private lateinit var statusText: TextView
    private lateinit var detectButton: Button
    private lateinit var btnMtk: Button
    private lateinit var btnQc: Button
    private lateinit var btnSpd: Button
    private lateinit var btnImportLoader: Button
    private lateinit var btnKeepAlive: Button

    private val filePicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { handleLoaderImport(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        usbManager = UsbDeviceManager(this)
        NotificationManager.init(this)

        statusText = findViewById(R.id.statusText)
        detectButton = findViewById(R.id.detectButton)
        btnMtk = findViewById(R.id.btnMtk)
        btnQc = findViewById(R.id.btnQc)
        btnSpd = findViewById(R.id.btnSpd)
        btnImportLoader = findViewById(R.id.btnImportLoader)
        btnKeepAlive = findViewById(R.id.btnKeepAlive)

        detectButton.setOnClickListener { detectDevices() }
        btnMtk.setOnClickListener { startActivity(Intent(this, MtkActivity::class.java)) }
        btnQc.setOnClickListener { startActivity(Intent(this, QualcommActivity::class.java)) }
        btnSpd.setOnClickListener { startActivity(Intent(this, SpreadtrumActivity::class.java)) }
        btnImportLoader.setOnClickListener { openLoaderPicker() }
        btnKeepAlive.setOnClickListener { toggleKeepAlive() }
        
        updateKeepAliveButton()
    }

    private fun detectDevices() {
        scope.launch {
            statusText.text = "Detecting devices..."
            val devices = usbManager.getAllBootModeDevices()
            if (devices.isEmpty()) {
                statusText.text = "No devices found.\nConnect device in BootROM/EDL/FDL mode."
            } else {
                val info = StringBuilder("Found ${devices.size} device(s):\n")
                devices.forEach { d ->
                    info.append("${d.mode} | VID:0x${d.vendorId.toString(16)} PID:0x${d.productId.toString(16)}\n")
                }
                statusText.text = info.toString()
            }
        }
    }

    private fun openLoaderPicker() {
        filePicker.launch(arrayOf("*/*", "application/octet-stream"))
    }

    private fun handleLoaderImport(uri: Uri) {
        scope.launch(Dispatchers.IO) {
            val fileName = uri.lastPathSegment ?: "loader.bin"
            val result = LoaderManager.importLoader(this@MainActivity, uri, ChipType.QUALCOMM, fileName)
            result.onSuccess {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Loader imported: $fileName", Toast.LENGTH_SHORT).show()
                }
            }.onFailure { e ->
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Import failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun toggleKeepAlive() {
        if (LabTestForegroundService.isRunning(this)) {
            LabTestForegroundService.stopService(this)
            Toast.makeText(this, "Keep alive disabled", Toast.LENGTH_SHORT).show()
        } else {
            LabTestForegroundService.startService(this)
            Toast.makeText(this, "Keep alive enabled", Toast.LENGTH_SHORT).show()
        }
        updateKeepAliveButton()
    }

    private fun updateKeepAliveButton() {
        btnKeepAlive.text = if (LabTestForegroundService.isRunning(this)) "Disable Keep Alive" else "Enable Keep Alive"
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}