package com.labtest.serviceapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.labtest.serviceapp.service.LabTestForegroundService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            context?.let {
                // Optionally start service on boot
                // LabTestForegroundService.startService(it)
            }
        }
    }
}