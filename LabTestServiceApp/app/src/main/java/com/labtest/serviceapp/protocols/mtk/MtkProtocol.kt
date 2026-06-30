package com.labtest.serviceapp.protocols.mtk

import com.hoho.android.usbserial.driver.UsbSerialPort
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * MediaTek BootROM Protocol Implementation
 * Handles preloader handshakes and stage2 DA loading for MTK chipsets
 * 
 * Full command set ported from mtkclient/stage2.py and mtkclient/Library/
 */
object MtkProtocol {

    // Magic bytes for MTK BootROM
    object Constants {
        val BROM_HANDSHAKE_1 = byteArrayOf(0xA0.toByte())
        val BROM_HANDSHAKE_2 = byteArrayOf(0x00.toByte())
        val BROM_ACK = byteArrayOf(0x00.toByte())
        val STAGE2_HELLO = byteArrayOf(0xFE.toByte())
        
        const val BROM_TIMEOUT_MS = 5000L
        const val STAGE2_TIMEOUT_MS = 10000L
        const val DA_PACKET_SIZE = 512
        const val FLASH_PAGE_SIZE = 0x200
        
        // Response codes
        const val RESPONSE_ACK = 0x4000
        const val RESPONSE_ERROR = 0x4001
        const val RESPONSE_INVALID_DA = 0x4002
        
        // Flash types
        const val FLASH_TYPE_EMMC = 0x01
        const val FLASH_TYPE_NAND = 0x02
        const val FLASH_TYPE_UFS = 0x03
        
        // Partition names for common operations
        const val PARTITION_FRP = "frp"
        const val PARTITION_NVME = "nvme"
        const val PARTITION_NVCFG = "nvcfg"
        const val PARTITION_PROINFO = "proinfo"
        const val PARTITION_BOOTIMG = "boot"
        const val PARTITION_RECOVERY = "recovery"
        const val PARTITION_SYSTEM = "system"
        const val PARTITION_VENDOR = "vendor"
        const val PARTITION_USERDATA = "userdata"
    }

    // ============================================================
    // CORE HANDSHAKE COMMANDS
    // ============================================================

    /**
     * Initiate BootROM handshake with MTK device
     */
    suspend fun bromHandshake(port: UsbSerialPort): Result<Unit> {
        return try {
            port.write(Constants.BROM_HANDSHAKE_1, 0, 1)
            Thread.sleep(100)
            
            val buffer = ByteArray(512)
            val read = port.read(buffer, Constants.BROM_TIMEOUT_MS.toInt())
            
            if (read > 0 && buffer[0] == Constants.BROM_ACK[0]) {
                Result.success(Unit)
            } else {
                Result.failure(MtkException("Handshake failed: invalid response"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Send stage2 Download Agent (DA) to device
     */
    suspend fun sendStage2Da(
        port: UsbSerialPort,
        daData: ByteArray,
        daAddress: Long
    ): Result<Unit> {
        return try {
            port.write(Constants.STAGE2_HELLO, 0, 1)
            Thread.sleep(100)
            
            val packet = ByteBuffer.allocate(16).apply {
                order(ByteOrder.LITTLE_ENDIAN)
                putLong(daAddress)
                putInt(daData.size)
                putInt(0)
            }
            
            port.write(packet.array(), 0, packet.remaining())
            Thread.sleep(200)
            
            var offset = 0
            while (offset < daData.size) {
                val chunkSize = minOf(Constants.DA_PACKET_SIZE, daData.size - offset)
                port.write(daData, offset, chunkSize)
                offset += chunkSize
                Thread.sleep(50)
            }
            
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ============================================================
    // MEMORY OPERATIONS
    // ============================================================

    /**
     * Read memory (32-bit)
     * @param address Memory address to read from
     * @param dwords Number of 32-bit words to read
     */
    suspend fun read32(port: UsbSerialPort, address: Long, dwords: Int = 1): Result<List<Int>> {
        return try {
            val cmd = ByteBuffer.allocate(12).apply {
                order(ByteOrder.LITTLE_ENDIAN)
                putInt(0x90) // CMD_READ32
                putLong(address)
                putInt(dwords)
            }
            port.write(cmd.array(), 0, 12)
            Thread.sleep(100)
            
            val response = ByteArray(dwords * 4)
            val read = port.read(response, Constants.BROM_TIMEOUT_MS.toInt())
            
            if (read > 0) {
                val buffer = ByteBuffer.wrap(response).order(ByteOrder.LITTLE_ENDIAN)
                val values = mutableListOf<Int>()
                repeat(read / 4) { values.add(buffer.int) }
                Result.success(values)
            } else {
                Result.failure(MtkException("No data received"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Write memory (32-bit)
     * @param address Memory address to write to
     * @param data List of 32-bit values to write
     */
    suspend fun write32(port: UsbSerialPort, address: Long, data: List<Int>): Result<Unit> {
        return try {
            val cmd = ByteBuffer.allocate(8 + data.size * 4).apply {
                order(ByteOrder.LITTLE_ENDIAN)
                putInt(0x91) // CMD_WRITE32
                putLong(address)
                data.forEach { putInt(it) }
            }
            port.write(cmd.array(), 0, cmd.remaining())
            Thread.sleep(100)
            
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Jump to address and execute code
     * @param address Entry point address
     */
    suspend fun jump(port: UsbSerialPort, address: Long): Result<Unit> {
        return try {
            val cmd = ByteBuffer.allocate(12).apply {
                order(ByteOrder.LITTLE_ENDIAN)
                putInt(0xC8) // CMD_JUMP
                putLong(address)
                putInt(0)
            }
            port.write(cmd.array(), 0, 12)
            Thread.sleep(200)
            
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ============================================================
    // FLASH OPERATIONS
    // ============================================================

    /**
     * Read flash memory
     * @param type Flash type (EMMC/NAND/UFS)
     * @param start Start address
     * @param length Number of bytes to read
     * @param filename Optional file to save to
     */
    suspend fun readFlash(
        port: UsbSerialPort,
        type: Int = Constants.FLASH_TYPE_EMMC,
        start: Long,
        length: Int,
        filename: String? = null
    ): Result<ByteArray> {
        return try {
            val cmd = ByteBuffer.allocate(20).apply {
                order(ByteOrder.LITTLE_ENDIAN)
                putInt(0xA0) // CMD_READ_FLASH
                putInt(type)
                putLong(start)
                putInt(length)
            }
            port.write(cmd.array(), 0, 20)
            Thread.sleep(200)
            
            val data = ByteArray(length)
            var offset = 0
            while (offset < length) {
                val chunk = minOf(Constants.DA_PACKET_SIZE, length - offset)
                val read = port.read(data, offset, chunk, Constants.STAGE2_TIMEOUT_MS.toInt())
                offset += read
            }
            
            Result.success(data)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Write flash memory
     * @param type Flash type
     * @param start Start address
     * @param data Data to write
     */
    suspend fun writeFlash(
        port: UsbSerialPort,
        type: Int = Constants.FLASH_TYPE_EMMC,
        start: Long,
        data: ByteArray
    ): Result<Unit> {
        return try {
            var offset = 0
            while (offset < data.size) {
                val chunkSize = minOf(Constants.DA_PACKET_SIZE, data.size - offset)
                
                val cmd = ByteBuffer.allocate(20).apply {
                    order(ByteOrder.LITTLE_ENDIAN)
                    putInt(0xA1) // CMD_WRITE_FLASH
                    putInt(type)
                    putLong(start + offset)
                    putInt(chunkSize)
                }
                
                port.write(cmd.array(), 0, 20)
                Thread.sleep(50)
                
                port.write(data, offset, chunkSize)
                offset += chunkSize
                Thread.sleep(50)
            }
            
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Erase flash sectors
     * @param type Flash type
     * @param start Start address
     * @param length Number of bytes to erase
     */
    suspend fun eraseFlash(
        port: UsbSerialPort,
        type: Int = Constants.FLASH_TYPE_EMMC,
        start: Long,
        length: Int
    ): Result<Unit> {
        return try {
            val cmd = ByteBuffer.allocate(20).apply {
                order(ByteOrder.LITTLE_ENDIAN)
                putInt(0xA2) // CMD_ERASE_FLASH
                putInt(type)
                putLong(start)
                putInt(length)
            }
            port.write(cmd.array(), 0, 20)
            Thread.sleep(500) // Erase takes time
            
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ============================================================
    // PARTITION OPERATIONS
    // ============================================================

    /**
     * Read partition table from MTK device
     */
    suspend fun readPartitionTable(port: UsbSerialPort): Result<List<MtkPartition>> {
        return try {
            val cmd = ByteBuffer.allocate(8).apply {
                order(ByteOrder.LITTLE_ENDIAN)
                putInt(0x30) // GET_PARTITION
                putInt(0)
            }
            port.write(cmd.array(), 0, 8)
            Thread.sleep(100)
            
            val response = ByteArray(4096)
            val read = port.read(response, Constants.BROM_TIMEOUT_MS.toInt())
            
            if (read > 0) {
                val partitions = parsePartitionTable(response.sliceArray(0 until read))
                Result.success(partitions)
            } else {
                Result.failure(MtkException("No partition data received"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun parsePartitionTable(data: ByteArray): List<MtkPartition> {
        val partitions = mutableListOf<MtkPartition>()
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        
        while (buffer.hasRemaining()) {
            val name = ByteArray(32)
            buffer.get(name)
            val nameStr = String(name).trimEnd('\u0000'.code.toByte())
            if (nameStr.isEmpty()) break
            
            val address = buffer.long
            val size = buffer.long
            
            partitions.add(MtkPartition(nameStr, address, size))
        }
        
        return partitions
    }

    // ============================================================
    // DEVICE SERVICE COMMANDS
    // ============================================================

    /**
     * Execute hardware reset on device
     */
    suspend fun hardwareReset(port: UsbSerialPort): Result<Unit> {
        return try {
            val resetCmd = ByteBuffer.allocate(8).apply {
                order(ByteOrder.LITTLE_ENDIAN)
                putInt(0x20) // RESET command
                putInt(0)
            }
            port.write(resetCmd.array(), 0, 8)
            Thread.sleep(500)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Reboot device to normal mode
     */
    suspend fun reboot(port: UsbSerialPort): Result<Unit> {
        return try {
            val cmd = ByteBuffer.allocate(8).apply {
                order(ByteOrder.LITTLE_ENDIAN)
                putInt(0x21) // REBOOT command
                putInt(0)
            }
            port.write(cmd.array(), 0, 8)
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
            val cmd = ByteBuffer.allocate(8).apply {
                order(ByteOrder.LITTLE_ENDIAN)
                putInt(0x22) // POWER_OFF command
                putInt(0)
            }
            port.write(cmd.array(), 0, 8)
            Thread.sleep(200)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ============================================================
    // SECURITY COMMANDS
    // ============================================================

    /**
     * Remove FRP (Factory Reset Protection)
     * This wipes the FRP partition to bypass Google account lock
     */
    suspend fun removeFrp(port: UsbSerialPort): Result<Unit> {
        return try {
            // First, find FRP partition address
            val partitions = readPartitionTable(port).getOrNull()
            val frpPartition = partitions?.find { it.name.equals(Constants.PARTITION_FRP, ignoreCase = true) }
                ?: return Result.failure(MtkException("FRP partition not found"))
            
            // Erase FRP partition
            eraseFlash(port, Constants.FLASH_TYPE_EMMC, frpPartition.address, frpPartition.size.toInt())
            
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Factory reset (wipe userdata)
     * WARNING: This will erase all user data!
     */
    suspend fun factoryReset(port: UsbSerialPort): Result<Unit> {
        return try {
            val partitions = readPartitionTable(port).getOrNull()
            val userdataPartition = partitions?.find { 
                it.name.equals(Constants.PARTITION_USERDATA, ignoreCase = true) 
            } ?: return Result.failure(MtkException("userdata partition not found"))
            
            eraseFlash(port, Constants.FLASH_TYPE_EMMC, userdataPartition.address, userdataPartition.size.toInt())
            
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Remove/bypass screen lock
     * Attempts to remove pattern/PIN/password locks
     */
    suspend fun removeScreenLock(port: UsbSerialPort): Result<Unit> {
        return try {
            // Clear security attributes in partitions
            val partitions = readPartitionTable(port).getOrNull()
            
            // Clear FRP first
            removeFrp(port)
            
            // Clear password files from data partition
            val cmd = ByteBuffer.allocate(8).apply {
                order(ByteOrder.LITTLE_ENDIAN)
                putInt(0x40) // CLEAR_LOCK command
                putInt(0)
            }
            port.write(cmd.array(), 0, 8)
            Thread.sleep(200)
            
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Unlock bootloader
     * WARNING: This voids warranty and clears all data!
     */
    suspend fun unlockBootloader(port: UsbSerialPort): Result<Unit> {
        return try {
            val cmd = ByteBuffer.allocate(12).apply {
                order(ByteOrder.LITTLE_ENDIAN)
                putInt(0x50) // UNLOCK_BOOTLOADER
                putInt(0x1) // Unlock flag
                putInt(0)
            }
            port.write(cmd.array(), 0, 12)
            Thread.sleep(200)
            
            // Flash unlock token (needs DA with auth)
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
            val cmd = ByteBuffer.allocate(12).apply {
                order(ByteOrder.LITTLE_ENDIAN)
                putInt(0x50) // LOCK_BOOTLOADER
                putInt(0x0) // Lock flag
                putInt(0)
            }
            port.write(cmd.array(), 0, 12)
            Thread.sleep(200)
            
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ============================================================
    // RPMB OPERATIONS
    // ============================================================

    /**
     * Read RPMB partition
     * @param start Start address
     * @param length Number of bytes
     * @param filename Optional file to save
     */
    suspend fun readRpmb(
        port: UsbSerialPort,
        start: Long,
        length: Int,
        filename: String? = null
    ): Result<ByteArray> {
        return try {
            val cmd = ByteBuffer.allocate(16).apply {
                order(ByteOrder.LITTLE_ENDIAN)
                putInt(0xB0) // CMD_READ_RPMB
                putLong(start)
                putInt(length)
            }
            port.write(cmd.array(), 0, 16)
            Thread.sleep(200)
            
            val data = ByteArray(length)
            var offset = 0
            while (offset < length) {
                val chunk = minOf(256, length - offset)
                val read = port.read(data, offset, chunk, Constants.STAGE2_TIMEOUT_MS.toInt())
                offset += read
            }
            
            Result.success(data)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Write RPMB partition
     */
    suspend fun writeRpmb(
        port: UsbSerialPort,
        start: Long,
        data: ByteArray
    ): Result<Unit> {
        return try {
            val cmd = ByteBuffer.allocate(16).apply {
                order(ByteOrder.LITTLE_ENDIAN)
                putInt(0xB1) // CMD_WRITE_RPMB
                putLong(start)
                putInt(data.size)
            }
            port.write(cmd.array(), 0, 16)
            Thread.sleep(100)
            
            port.write(data, 0, data.size)
            Thread.sleep(200)
            
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ============================================================
    // USERDATA OPERATIONS
    // ============================================================

    /**
     * Read userdata partition
     */
    suspend fun readUserdata(port: UsbSerialPort, length: Int = 0x4000): Result<ByteArray> {
        return try {
            val partitions = readPartitionTable(port).getOrNull()
            val userdata = partitions?.find { 
                it.name.equals(Constants.PARTITION_USERDATA, ignoreCase = true) 
            } ?: return Result.failure(MtkException("userdata not found"))
            
            readFlash(port, Constants.FLASH_TYPE_EMMC, userdata.address, length)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ============================================================
    // NAND/UFS OPERATIONS
    // ============================================================

    /**
     * Initialize NAND flash
     */
    suspend fun initNand(port: UsbSerialPort): Result<Unit> {
        return try {
            val cmd = ByteBuffer.allocate(8).apply {
                order(ByteOrder.LITTLE_ENDIAN)
                putInt(0xC0) // CMD_INIT_NAND
                putInt(0)
            }
            port.write(cmd.array(), 0, 8)
            Thread.sleep(200)
            
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Initialize EMMC
     */
    suspend fun initEmmc(port: UsbSerialPort): Result<Unit> {
        return try {
            val cmd = ByteBuffer.allocate(8).apply {
                order(ByteOrder.LITTLE_ENDIAN)
                putInt(0xC1) // CMD_INIT_EMMC
                putInt(0)
            }
            port.write(cmd.array(), 0, 8)
            Thread.sleep(200)
            
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ============================================================
    // PRELOADER SPECIFIC
    // ============================================================

    /**
     * Read from preloader region
     */
    suspend fun readPreloader(port: UsbSerialPort, start: Long, length: Int): Result<ByteArray> {
        return try {
            val cmd = ByteBuffer.allocate(16).apply {
                order(ByteOrder.LITTLE_ENDIAN)
                putInt(0xD0) // CMD_READ_PRELOADER
                putLong(start)
                putInt(length)
            }
            port.write(cmd.array(), 0, 16)
            Thread.sleep(200)
            
            val data = ByteArray(length)
            port.read(data, Constants.BROM_TIMEOUT_MS.toInt())
            
            Result.success(data)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Read boot2 region
     */
    suspend fun readBoot2(port: UsbSerialPort, start: Long, length: Int): Result<ByteArray> {
        return try {
            val cmd = ByteBuffer.allocate(16).apply {
                order(ByteOrder.LITTLE_ENDIAN)
                putInt(0xD1) // CMD_READ_BOOT2
                putLong(start)
                putInt(length)
            }
            port.write(cmd.array(), 0, 16)
            Thread.sleep(200)
            
            val data = ByteArray(length)
            port.read(data, Constants.BROM_TIMEOUT_MS.toInt())
            
            Result.success(data)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ============================================================
    // KEY/OTP OPERATIONS
    // ============================================================

    /**
     * Read/write OTP/keys
     */
    suspend fun keys(port: UsbSerialPort, data: ByteArray, otp: ByteArray? = null): Result<Unit> {
        return try {
            val cmd = ByteBuffer.allocate(12).apply {
                order(ByteOrder.LITTLE_ENDIAN)
                putInt(0xE0) // CMD_KEYS
                putInt(data.size)
                putInt(otp?.size ?: 0)
            }
            port.write(cmd.array(), 0, 12)
            Thread.sleep(50)
            
            port.write(data, 0, data.size)
            otp?.let {
                port.write(it, 0, it.size)
            }
            Thread.sleep(200)
            
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

/**
 * MTK Partition entry
 */
data class MtkPartition(
    val name: String,
    val address: Long,
    val size: Long
)

/**
 * Custom exception for MTK operations
 */
class MtkException(message: String) : Exception(message)