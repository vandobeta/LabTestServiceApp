package com.labtest.serviceapp.protocols.spd

import com.hoho.android.usbserial.driver.UsbSerialPort
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Spreadtrum/Unisoc FDL Protocol Implementation
 * Handles FDL1/FDL2 handshakes and flash operations
 * 
 * Full command set ported from spreadtrum_flash/spd_dump.c and spd_cmd.h
 */
object SpreadtrumProtocol {

    object Constants {
        // FDL stage hellos
        val FDL1_HELLO = byteArrayOf(0x80.toByte(), 0x00, 0x00, 0x00)
        val FDL2_HELLO = byteArrayOf(0x81.toByte(), 0x00, 0x00, 0x00)
        
        // Acknowledgments
        val ACK_OK = byteArrayOf(0x00, 0x00, 0x00, 0x00)
        val ACK_ERROR = byteArrayOf(0xFF.toByte(), 0xFF, 0xFF, 0xFF)
        
        // Timeouts
        const val FDL_TIMEOUT_MS = 3000L
        const val FDL_LONG_TIMEOUT_MS = 10000L
        
        // Packet sizes
        const val FDL_PACKET_SIZE = 512
        const val FLASH_PAGE_SIZE = 2048
        
        // BSL Commands (from spd_cmd.h)
        const val BSL_CMD_CHECK_BAUD = 0x05
        const val BSL_CMD_START_DATA = 0x01
        const val BSL_CMD_MIDST_DATA = 0x02
        const val BSL_CMD_END_DATA = 0x03
        const val BSL_CMD_ERASE_FLASH = 0x04
        const val BSL_CMD_READ_FLASH = 0x05
        const val BSL_CMD_READ_PARTITION = 0x06
        const val BSL_CMD_REPARTITION = 0x07
        const val BSL_CMD_CONNECT = 0x08
        const val BSL_CMD_EXEC_DATA = 0x09
        const val BSL_CMD_READ_CHIP_UID = 0x10
        const val BSL_CMD_DISABLE_TRANSCODE = 0x12
        const val BSL_CMD_NORMAL_RESET = 0x0A
        const val BSL_CMD_POWER_OFF = 0x0B
        const val BSL_CMD_KEEP_CHARGE = 0x0C
        
        // Legacy FDL commands
        const val CMD_READ_FLASH = 0x80000003
        const val CMD_WRITE_FLASH = 0x80000004
        const val CMD_ERASE_FLASH = 0x80000005
        const val CMD_VERITY = 0x80000006
        const val CMD_GETChip_INFO = 0x80000007
        
        // Response codes
        const val RESP_SUCCESS = 0x00
        const val RESP_ERROR = 0x01
        const val RESP_TIMEOUT = 0x02
        
        // Common partition names
        const val PARTITION_FRP = "frp"
        const val PARTITION_BOOT = "boot"
        const val PARTITION_RECOVERY = "recovery"
        const val PARTITION_SYSTEM = "system"
        const val PARTITION_VENDOR = "vendor"
        const val PARTITION_USERDATA = "userdata"
        const val PARTITION_NVME = "nvme"
        const val PARTITION_PROINFO = "proinfo"
    }

    /**
     * Initiate FDL1 handshake
     * @return true if handshake successful
     */
    suspend fun fdl1Handshake(port: UsbSerialPort): Result<Unit> {
        return try {
            port.write(Constants.FDL1_HELLO, 0, Constants.FDL1_HELLO.size)
            Thread.sleep(200)
            
            val response = ByteArray(64)
            val read = port.read(response, Constants.FDL_TIMEOUT_MS.toInt())
            
            if (read >= 4 && response.sliceArray(0 until 4).contentEquals(Constants.ACK_OK)) {
                Result.success(Unit)
            } else {
                Result.failure(SpreadtrumException("FDL1 handshake failed"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Initiate FDL2 handshake (requires FDL1 to be loaded first)
     * @return true if handshake successful
     */
    suspend fun fdl2Handshake(port: UsbSerialPort): Result<Unit> {
        return try {
            port.write(Constants.FDL2_HELLO, 0, Constants.FDL2_HELLO.size)
            Thread.sleep(200)
            
            val response = ByteArray(64)
            val read = port.read(response, Constants.FDL_TIMEOUT_MS.toInt())
            
            if (read >= 4 && response.sliceArray(0 until 4).contentEquals(Constants.ACK_OK)) {
                Result.success(Unit)
            } else {
                Result.failure(SpreadtrumException("FDL2 handshake failed"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Load FDL (Firmware Downloader) to device
     * @param fdlData Raw FDL binary
     * @param baseAddress Memory address to load FDL
     */
    suspend fun loadFdl(
        port: UsbSerialPort,
        fdlData: ByteArray,
        baseAddress: Long
    ): Result<Unit> {
        return try {
            // Send FDL data in pages
            var offset = 0
            while (offset < fdlData.size) {
                val pageSize = minOf(Constants.FLASH_PAGE_SIZE, fdlData.size - offset)
                
                // Prepare header
                val header = ByteBuffer.allocate(16).apply {
                    order(ByteOrder.LITTLE_ENDIAN)
                    putInt(Constants.CMD_WRITE_FLASH)
                    putLong(baseAddress + offset)
                    putInt(pageSize)
                }
                
                port.write(header.array(), 0, 16)
                Thread.sleep(50)
                
                // Send page data
                port.write(fdlData, offset, pageSize)
                offset += pageSize
                Thread.sleep(50)
                
                // Wait for ACK
                val ack = ByteArray(4)
                port.read(ack, 500)
            }
            
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Read flash memory
     * @param address Starting address
     * @param size Number of bytes to read
     */
    suspend fun readFlash(
        port: UsbSerialPort,
        address: Long,
        size: Int
    ): Result<ByteArray> {
        return try {
            val cmd = ByteBuffer.allocate(20).apply {
                order(ByteOrder.LITTLE_ENDIAN)
                putInt(Constants.CMD_READ_FLASH)
                putLong(address)
                putInt(size)
            }
            
            port.write(cmd.array(), 0, 20)
            Thread.sleep(200)
            
            val data = ByteArray(size)
            var offset = 0
            while (offset < size) {
                val chunk = minOf(Constants.FDL_PACKET_SIZE, size - offset)
                val read = port.read(data, offset, chunk, Constants.FDL_LONG_TIMEOUT_MS.toInt())
                offset += read
            }
            
            Result.success(data)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Write flash memory
     * @param address Starting address
     * @param data Data to write
     */
    suspend fun writeFlash(
        port: UsbSerialPort,
        address: Long,
        data: ByteArray
    ): Result<Unit> {
        return try {
            var offset = 0
            while (offset < data.size) {
                val pageSize = minOf(Constants.FLASH_PAGE_SIZE, data.size - offset)
                
                val cmd = ByteBuffer.allocate(20).apply {
                    order(ByteOrder.LITTLE_ENDIAN)
                    putInt(Constants.CMD_WRITE_FLASH)
                    putLong(address + offset)
                    putInt(pageSize)
                }
                
                port.write(cmd.array(), 0, 20)
                Thread.sleep(50)
                
                port.write(data, offset, pageSize)
                offset += pageSize
                Thread.sleep(100)
            }
            
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Erase flash sectors
     * @param address Starting address
     * @param size Number of bytes to erase
     */
    suspend fun eraseFlash(
        port: UsbSerialPort,
        address: Long,
        size: Long
    ): Result<Unit> {
        return try {
            val cmd = ByteBuffer.allocate(20).apply {
                order(ByteOrder.LITTLE_ENDIAN)
                putInt(Constants.CMD_ERASE_FLASH)
                putLong(address)
                putLong(size)
            }
            
            port.write(cmd.array(), 0, 20)
            Thread.sleep(500) // Erase takes time
            
            val response = ByteArray(4)
            port.read(response, Constants.FDL_LONG_TIMEOUT_MS.toInt())
            
            if (response.contentEquals(Constants.ACK_OK)) {
                Result.success(Unit)
            } else {
                Result.failure(SpreadtrumException("Erase failed"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Get chip information
     */
    suspend fun getChipInfo(port: UsbSerialPort): Result<SpreadtrumChipInfo> {
        return try {
            val cmd = ByteBuffer.allocate(8).apply {
                order(ByteOrder.LITTLE_ENDIAN)
                putInt(Constants.CMD_GETChip_INFO)
                putInt(0)
            }
            
            port.write(cmd.array(), 0, 8)
            Thread.sleep(100)
            
            val response = ByteArray(64)
            val read = port.read(response, Constants.FDL_TIMEOUT_MS.toInt())
            
            if (read > 0) {
                val info = parseChipInfo(response.sliceArray(0 until read))
                Result.success(info)
            } else {
                Result.failure(SpreadtrumException("No chip info received"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun parseChipInfo(data: ByteArray): SpreadtrumChipInfo {
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        
        val chipId = buffer.int
        val chipVersion = buffer.int
        
        return SpreadtrumChipInfo(
            chipId = chipId,
            chipVersion = chipVersion
        )
    }

    /**
     * Enable/Disable verity (dm-verity)
     */
    suspend fun setVerity(
        port: UsbSerialPort,
        enabled: Boolean
    ): Result<Unit> {
        return try {
            val cmd = ByteBuffer.allocate(12).apply {
                order(ByteOrder.LITTLE_ENDIAN)
                putInt(Constants.CMD_VERITY)
                putInt(if (enabled) 1 else 0)
                putInt(0)
            }
            
            port.write(cmd.array(), 0, 12)
            Thread.sleep(200)
            
            val response = ByteArray(4)
            port.read(response, Constants.FDL_TIMEOUT_MS.toInt())
            
            if (response.contentEquals(Constants.ACK_OK)) {
                Result.success(Unit)
            } else {
                Result.failure(SpreadtrumException("Verity setting failed"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ============================================================
    // BSL COMMANDS (from spd_cmd.h)
    // ============================================================

    /**
     * Check baud rate (initialization)
     */
    suspend fun checkBaud(port: UsbSerialPort): Result<Unit> {
        return try {
            sendBsCommand(port, Constants.BSL_CMD_CHECK_BAUD, null)
            Thread.sleep(100)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Connect to device
     */
    suspend fun connect(port: UsbSerialPort): Result<Unit> {
        return try {
            sendBsCommand(port, Constants.BSL_CMD_CONNECT, null)
            Thread.sleep(200)
            
            val response = ByteArray(4)
            port.read(response, Constants.FDL_TIMEOUT_MS.toInt())
            
            if (response.contentEquals(Constants.ACK_OK)) {
                Result.success(Unit)
            } else {
                Result.failure(SpreadtrumException("Connect failed"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Read chip UID
     */
    suspend fun readChipUid(port: UsbSerialPort): Result<ByteArray> {
        return try {
            val result = sendBsCommand(port, Constants.BSL_CMD_READ_CHIP_UID, null)
            Thread.sleep(100)
            
            val response = ByteArray(32)
            port.read(response, Constants.FDL_TIMEOUT_MS.toInt())
            
            Result.success(response)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Disable transcode (enable direct flash access)
     */
    suspend fun disableTranscode(port: UsbSerialPort): Result<Unit> {
        return try {
            sendBsCommand(port, Constants.BSL_CMD_DISABLE_TRANSCODE, null)
            Thread.sleep(100)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Normal reset (reboot)
     */
    suspend fun normalReset(port: UsbSerialPort): Result<Unit> {
        return try {
            sendBsCommand(port, Constants.BSL_CMD_NORMAL_RESET, null)
            Thread.sleep(500)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Power off device
     */
    suspend fun powerOff(port: UsbSerialPort): Result<Unit> {
        return try {
            sendBsCommand(port, Constants.BSL_CMD_POWER_OFF, null)
            Thread.sleep(200)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Keep charge mode (stay in download mode)
     */
    suspend fun keepCharge(port: UsbSerialPort): Result<Unit> {
        return try {
            sendBsCommand(port, Constants.BSL_CMD_KEEP_CHARGE, null)
            Thread.sleep(100)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Execute data (run downloaded code)
     */
    suspend fun execData(port: UsbSerialPort, address: Long): Result<Unit> {
        return try {
            val data = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(address).array()
            sendBsCommand(port, Constants.BSL_CMD_EXEC_DATA, data)
            Thread.sleep(500)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Read partition table
     */
    suspend fun readPartitionTable(port: UsbSerialPort): Result<ByteArray> {
        return try {
            val result = sendBsCommand(port, Constants.BSL_CMD_READ_PARTITION, null)
            Thread.sleep(200)
            
            val response = ByteArray(4096)
            port.read(response, Constants.FDL_LONG_TIMEOUT_MS.toInt())
            
            Result.success(response)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Repartition (reconfigure partition table)
     */
    suspend fun repartition(port: UsbSerialPort, partitionData: ByteArray): Result<Unit> {
        return try {
            sendBsCommand(port, Constants.BSL_CMD_REPARTITION, partitionData)
            Thread.sleep(500)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun sendBsCommand(port: UsbSerialPort, command: Int, data: ByteArray?): Result<Unit> {
        return try {
            val size = if (data != null) data.size else 0
            val buffer = ByteBuffer.allocate(8 + size).apply {
                order(ByteOrder.LITTLE_ENDIAN)
                putInt(command)
                putInt(size)
                data?.let { put(it) }
            }
            port.write(buffer.array(), 0, buffer.remaining())
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ============================================================
    // SERVICE COMMANDS
    // ============================================================

    /**
     * Remove FRP (Factory Reset Protection)
     * Wipes FRP partition to bypass Google account lock
     */
    suspend fun removeFrp(port: UsbSerialPort): Result<Unit> {
        return try {
            // Find FRP partition address
            val partitionData = readPartitionTable(port).getOrNull()
            val frpAddress = findPartitionAddress(partitionData, Constants.PARTITION_FRP)
            
            if (frpAddress != null) {
                eraseFlash(port, frpAddress, 0x100000) // 1MB typical FRP size
            } else {
                Result.failure(SpreadtrumException("FRP partition not found"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Factory reset (wipe userdata)
     * WARNING: Erases all user data!
     */
    suspend fun factoryReset(port: UsbSerialPort): Result<Unit> {
        return try {
            val partitionData = readPartitionTable(port).getOrNull()
            val userdataAddress = findPartitionAddress(partitionData, Constants.PARTITION_USERDATA)
            
            if (userdataAddress != null) {
                eraseFlash(port, userdataAddress, 0x10000000) // Large erase for userdata
            } else {
                Result.failure(SpreadtrumException("userdata partition not found"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Remove/bypass screen lock
     */
    suspend fun removeScreenLock(port: UsbSerialPort): Result<Unit> {
        return try {
            // Clear FRP
            removeFrp(port)
            
            // Clear verity (set to 0)
            setVerity(port, false)
            
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Unlock bootloader
     * WARNING: Voids warranty!
     */
    suspend fun unlockBootloader(port: UsbSerialPort): Result<Unit> {
        return try {
            // Write unlock flag to nvme partition
            val partitionData = readPartitionTable(port).getOrNull()
            val nvmeAddress = findPartitionAddress(partitionData, Constants.PARTITION_NVME)
            
            if (nvmeAddress != null) {
                val unlockData = byteArrayOf(0x01) // unlock value
                writeFlash(port, nvmeAddress, unlockData)
            }
            
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Lock bootloader
     */
    suspend fun lockBootloader(port: UsbSerialPort): Result<Unit> {
        return try {
            val partitionData = readPartitionTable(port).getOrNull()
            val nvmeAddress = findPartitionAddress(partitionData, Constants.PARTITION_NVME)
            
            if (nvmeAddress != null) {
                val lockData = byteArrayOf(0x00) // lock value
                writeFlash(port, nvmeAddress, lockData)
            }
            
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ============================================================
    // HELPER FUNCTIONS
    // ============================================================

    private fun findPartitionAddress(partitionData: ByteArray?, name: String): Long? {
        if (partitionData == null) return null
        
        // Simple partition table parsing
        // In production, parse the full GPT table
        var offset = 0
        while (offset < partitionData.size - 64) {
            val entryName = String(partitionData.sliceArray(offset until offset + 32)).trimEnd('\u0000'.code.toByte())
            if (entryName.equals(name, ignoreCase = true)) {
                return ByteBuffer.wrap(partitionData.sliceArray(offset + 32 until offset + 40))
                    .order(ByteOrder.LITTLE_ENDIAN).long
            }
            offset += 64
        }
        return null
    }
}

/**
 * Spreadtrum chip information
 */
data class SpreadtrumChipInfo(
    val chipId: Int,
    val chipVersion: Int
)

/**
 * Custom exception for Spreadtrum operations
 */
class SpreadtrumException(message: String) : Exception(message)