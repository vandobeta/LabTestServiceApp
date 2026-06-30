package com.labtest.serviceapp.usb

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * USB Permission Manager - handles USB device permission requests
 */
object UsbPermissionManager {

    private const val ACTION_USB_PERMISSION = "com.labtest.serviceapp.USB_PERMISSION"
    
    /**
     * Request USB permission for a device
     */
    fun requestPermission(activity: Activity, device: UsbDevice, requestCode: Int = 1001) {
        val intent = PendingIntent.getBroadcast(
            activity,
            requestCode,
            Intent(ACTION_USB_PERMISSION).apply {
                putExtra(UsbManager.EXTRA_DEVICE, device)
            },
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        val usbManager = activity.getSystemService(Context.USB_SERVICE) as UsbManager
        usbManager.requestPermission(device, intent)
    }

    /**
     * Check if permission is granted
     */
    fun hasPermission(context: Context, device: UsbDevice): Boolean {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        return usbManager.hasPermission(device)
    }

    /**
     * Register for permission result
     */
    fun registerReceiver(
        context: Context,
        onGranted: (UsbDevice) -> Unit,
        onDenied: (UsbDevice) -> Unit
    ): BroadcastReceiver {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent?.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent?.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                }
                
                device?.let {
                    val usbManager = ctx?.getSystemService(Context.USB_SERVICE) as UsbManager
                    if (usbManager.hasPermission(it)) {
                        onGranted(it)
                    } else {
                        onDenied(it)
                    }
                }
            }
        }
        
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(receiver, filter)
        }
        
        return receiver
    }

    /**
     * Check for required permission
     */
    fun checkUsbPermission(activity: Activity): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            ContextCompat.checkSelfPermission(
                activity,
                Manifest.permission.USB_PERMISSION
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    /**
     * Request runtime permissions
     */
    fun requestRuntimePermissions(activity: Activity, requestCode: Int = 1002) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(Manifest.permission.USB_PERMISSION),
                requestCode
            )
        }
    }
}