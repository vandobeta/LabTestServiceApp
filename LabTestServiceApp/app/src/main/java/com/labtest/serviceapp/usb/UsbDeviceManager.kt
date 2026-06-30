package com.labtest.serviceapp.usb

import android.content.Context
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbConstants
import com.labtest.serviceapp.protocols.mtk.MtkProtocol
import com.labtest.serviceapp.protocols.qualcomm.QualcommProtocol
import com.labtest.serviceapp.protocols.spd.SpreadtrumProtocol

/**
 * USB Device Manager for detecting and managing device connections
 * in various boot modes (BootROM, EDL, FDL)
 */
class UsbDeviceManager(private val context: Context) {

    private val usbManager: UsbManager by lazy {
        context.getSystemService(Context.USB_SERVICE) as UsbManager
    }

    /**
     * Known USB IDs for device boot modes
     */
    object DeviceIds {
        // MTK BootROM/Preloader
        const val MTK_VID = 0x0E8D
        val MTK_PIDS = listOf(0x2000, 0x2007, 0x2021, 0x2022, 0x2023, 0x2024, 0x2025)
        
        // Qualcomm EDL
        const val QUALCOMM_EDL_VID = 0x05C6
        val QUALCOMM_EDL_PIDS = listOf(0x9008, 0x900E, 0x9010, 0x9015, 0x901D)
        
        // Spreadtrum FDL
        const val SPD_VID = 0x1782
        val SPD_PIDS = listOf(0x4D00, 0x4D01, 0x4D02, 0x4D03, 0x4D04)
        
        // Standard ADB
        const val ADB_VID = 0x18D1
        const val ADB_PID = 0x4EE2
    }

    /**
     * Detect connected device boot mode
     * @return DeviceMode if detected, null otherwise
     */
    fun detectDeviceMode(): DeviceInfo? {
        val deviceList = usbManager.deviceList
        for ((_, device) in deviceList) {
            val vid = device.vendorId
            val pid = device.productId
            
            when {
                isMtkDevice(vid, pid) -> return DeviceInfo(
                    mode = DeviceMode.MTK_BOOTROM,
                    vendorId = vid,
                    productId = pid,
                    device = device
                )
                isQualcommEdl(vid, pid) -> return DeviceInfo(
                    mode = DeviceMode.QUALCOMM_EDL,
                    vendorId = vid,
                    productId = pid,
                    device = device
                )
                isSpreadtrumFdl(vid, pid) -> return DeviceInfo(
                    mode = DeviceMode.SPREADTRUM_FDL,
                    vendorId = vid,
                    productId = pid,
                    device = device
                )
            }
        }
        return null
    }

    /**
     * Get all connected devices in boot modes
     */
    fun getAllBootModeDevices(): List<DeviceInfo> {
        val devices = mutableListOf<DeviceInfo>()
        val deviceList = usbManager.deviceList
        
        for ((_, device) in deviceList) {
            val vid = device.vendorId
            val pid = device.productId
            
            when {
                isMtkDevice(vid, pid) -> devices.add(
                    DeviceInfo(DeviceMode.MTK_BOOTROM, vid, pid, device)
                )
                isQualcommEdl(vid, pid) -> devices.add(
                    DeviceInfo(DeviceMode.QUALCOMM_EDL, vid, pid, device)
                )
                isSpreadtrumFdl(vid, pid) -> devices.add(
                    DeviceInfo(DeviceMode.SPREADTRUM_FDL, vid, pid, device)
                )
            }
        }
        
        return devices
    }

    private fun isMtkDevice(vid: Int, pid: Int): Boolean {
        return vid == DeviceIds.MTK_VID && DeviceIds.MTK_PIDS.contains(pid)
    }

    private fun isQualcommEdl(vid: Int, pid: Int): Boolean {
        return vid == DeviceIds.QUALCOMM_EDL_VID && DeviceIds.QUALCOMM_EDL_PIDS.contains(pid)
    }

    private fun isSpreadtrumFdl(vid: Int, pid: Int): Boolean {
        return vid == DeviceIds.SPD_VID && DeviceIds.SPD_PIDS.contains(pid)
    }

    /**
     * Open USB device connection
     */
    fun openDevice(device: UsbDevice): UsbDeviceConnection? {
        return if (usbManager.hasPermission(device)) {
            usbManager.openDevice(device)
        } else {
            null
        }
    }

    /**
     * Find bulk endpoints for communication
     */
    fun findBulkEndpoints(device: UsbDevice): BulkEndpoints? {
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface?.interfaceClass == 0xFF ||
                iface?.interfaceClass == 0x00
            ) {
                var inEndpoint: UsbEndpoint? = null
                var outEndpoint: UsbEndpoint? = null
                
                for (j in 0 until iface.endpointCount) {
                    val ep = iface.getEndpoint(j)
                    val direction = ep?.direction?.toInt()
                    when (direction) {
                        UsbConstants.USB_DIR_IN -> inEndpoint = ep
                        UsbConstants.USB_DIR_OUT -> outEndpoint = ep
                    }
                }
                
                if (inEndpoint != null && outEndpoint != null) {
                    return BulkEndpoints(iface, inEndpoint, outEndpoint)
                }
            }
        }
        return null
    }

    /**
     * Request USB permission for device
     */
    fun requestPermission(device: UsbDevice) {
        // This would require an Activity/Fragment context in real app
        // Using PermissionManager in production
    }
}

/**
 * Device boot mode
 */
enum class DeviceMode {
    MTK_BOOTROM,      // MediaTek BootROM/Preloader
    MTK_FASTBOOT,      // MediaTek Fastboot
    QUALCOMM_EDL,     // Qualcomm EDL (Emergency Download)
    QUALCOMM_FASTBOOT, // Qualcomm Fastboot
    SPREADTRUM_FDL,    // Spreadtrum FDL1/FDL2
    ANDROID_NORMAL,    // Normal Android (ADB)
    UNKNOWN           // Unknown mode
}

/**
 * Detected device information
 */
data class DeviceInfo(
    val mode: DeviceMode,
    val vendorId: Int,
    val productId: Int,
    val device: UsbDevice
)

/**
 * Bulk endpoints for USB communication
 */
data class BulkEndpoints(
    val usbInterface: UsbInterface,
    val endpointIn: UsbEndpoint,
    val endpointOut: UsbEndpoint
)

/**
 * USB constants (Android API level 21+)
 */
object UsbConstants {
    const val USB_CLASS_PER_INTERFACE = 0x00
    const val USB_CLASS_VENDOR_SPECIFIC = 0xFF
    const val USB_DIR_IN = 0x80.toByte()
    const val USB_DIR_OUT = 0x00.toByte()
}