package com.labtest.serviceapp.protocols.qualcomm

import com.hoho.android.usbserial.driver.UsbSerialPort
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Qualcomm EDL Protocol Implementation
 * Handles Sahara mode handshakes and Firehose XML commands
 * 
 * Full command set ported from edlclient/Library/firehose.py
 */
object QualcommProtocol {

    object Constants {
        // Sahara protocol
        val SAHARA_HELLO = byteArrayOf(0x01, 0x00, 0x00, 0x00)
        val SAHARA_HELLO_ACK = byteArrayOf(0x01, 0x00, 0x00, 0x02)
        val SAHARA_READY = byteArrayOf(0x02, 0x00, 0x00, 0x00)
        val SAHARA_DONE = byteArrayOf(0x03, 0x00, 0x00, 0x00)
        
        // USB IDs for EDL mode
        const val EDL_VID = 0x05C6
        const val EDL_PID = 0x9008
        
        // Firehose config
        const val FIREHOSE_VERSION = "1.31"
        const val MAX_PACKET_SIZE = 1024 * 1024 // 1MB max
        const val TIMEOUT_MS = 30000L
        
        // Response codes
        const val RESP_SUCCESS = 0x01
        const val RESP_ERROR = 0x0F
        const val RESP_UNKNOWN = 0xFF
        
        // Common partition names
        const val PARTITION_FRP = "frp"
        const val PARTITION_PERSIST = "persist"
        const val PARTITION_MODEMST1 = "modemst1"
        const val PARTITION_MODEMST2 = "modemst2"
        const val PARTITION_MODEM = "modem"
        const val PARTITION_BOOT = "boot"
        const val PARTITION_RECOVERY = "recovery"
        const val PARTITION_SYSTEM = "system"
        const val PARTITION_VENDOR = "vendor"
        const val PARTITION_USERDATA = "userdata"
    }

    /**
     * Initiate Sahara protocol handshake
     * @return true if handshake successful
     */
    suspend fun saharaHandshake(port: UsbSerialPort): Result<SaharaDeviceInfo> {
        return try {
            // Send hello
            port.write(Constants.SAHARA_HELLO, 0, Constants.SAHARA_HELLO.size)
            Thread.sleep(100)
            
            val response = ByteArray(256)
            val read = port.read(response, Constants.TIMEOUT_MS.toInt())
            
            if (read >= 4 && response[0] == Constants.SAHARA_HELLO_ACK[0]) {
                // Parse device info from response
                val info = parseSaharaHello(response)
                Result.success(info)
            } else {
                Result.failure(QualcommException("Sahara handshake failed"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun parseSaharaHello(data: ByteArray): SaharaDeviceInfo {
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        
        // Skip header (4 bytes)
        buffer.int
        
        val version = buffer.int
        val maxPacketSize = buffer.int
        val mode = buffer.int
        
        return SaharaDeviceInfo(
            protocolVersion = version,
            maxPacketSize = maxPacketSize,
            mode = mode
        )
    }

    /**
     * Switch from Sahara to Firehose mode
     */
    suspend fun switchToFirehose(port: UsbSerialPort, loaderData: ByteArray): Result<Unit> {
        return try {
            // Send loader data
            var offset = 0
            val chunkSize = 64 * 1024 // 64KB chunks
            
            while (offset < loaderData.size) {
                val size = minOf(chunkSize, loaderData.size - offset)
                port.write(loaderData, offset, size)
                offset += size
                Thread.sleep(50)
            }
            
            Thread.sleep(500)
            
            // Send done command
            port.write(Constants.SAHARA_DONE, 0, Constants.SAHARA_DONE.size)
            Thread.sleep(1000)
            
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Execute Firehose command
     */
    suspend fun firehoseExec(
        port: UsbSerialPort,
        command: FirehoseCommand
    ): Result<FirehoseResponse> {
        return try {
            val xml = command.toXml()
            val packet = buildFirehosePacket(xml)
            
            port.write(packet, 0, packet.size)
            Thread.sleep(100)
            
            val response = ByteArray(64 * 1024) // 64KB buffer
            val read = port.read(response, Constants.TIMEOUT_MS.toInt())
            
            if (read > 0) {
                val firehoseResponse = parseFirehoseResponse(response.sliceArray(0 until read))
                Result.success(firehoseResponse)
            } else {
                Result.failure(QualcommException("No Firehose response"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Build Firehose XML packet
     */
    private fun buildFirehosePacket(xml: String): ByteArray {
        val xmlBytes = xml.toByteArray()
        val length = xmlBytes.size + 12
        
        return ByteBuffer.allocate(length).apply {
            order(ByteOrder.LITTLE_ENDIAN)
            putInt(0x01) // Magic
            putInt(xmlBytes.size) // XML size
            put(xmlBytes)
            // Padding handled automatically
        }.array()
    }

    /**
     * Parse Firehose response
     */
    private fun parseFirehoseResponse(data: ByteArray): FirehoseResponse {
        // Simple XML parsing - in production use proper XML parser
        val responseStr = String(data).trimEnd('\u0000'.code.toByte())
        
        val status = if (responseStr.contains("<error>")) {
            FirehoseStatus.ERROR
        } else {
            FirehoseStatus.SUCCESS
        }
        
        val logLines = extractLogLines(responseStr)
        
        return FirehoseResponse(
            status = status,
            rawXml = responseStr,
            logLines = logLines
        )
    }

    private fun extractLogLines(xml: String): List<String> {
        val lines = mutableListOf<String>()
        val regex = "<log>(.*?)</log>".toRegex()
        regex.findAll(xml).forEach { match ->
            lines.add(match.groupValues[1])
        }
        return lines
    }

    /**
     * Read partition table via Firehose
     */
    suspend fun readPartitionTable(port: UsbSerialPort): Result<List<QualcommPartition>> {
        val cmd = FirehoseCommand(
            name = "GetPartitionTable",
            version = Constants.FIREHOSE_VERSION
        )
        return firehoseExec(port, cmd).map { response ->
            parsePartitionTable(response.rawXml)
        }
    }

    private fun parsePartitionTable(xml: String): List<QualcommPartition> {
        val partitions = mutableListOf<QualcommPartition>()
        val partitionRegex = "<partition label=\"([^\"]+)\" start_sector=\"(\\d+)\" num_sectors=\"(\\d+)\"".toRegex()
        
        partitionRegex.findAll(xml).forEach { match ->
            partitions.add(
                QualcommPartition(
                    label = match.groupValues[1],
                    startSector = match.groupValues[2].toLong(),
                    numSectors = match.groupValues[3].toLong()
                )
            )
        }
        
        return partitions
    }

    // ============================================================
    // FIREHOSE COMMANDS
    // ============================================================

    /**
     * Reset device
     * @param mode resetMode: "reset", "poweroff", "flash", "update"
     */
    suspend fun cmdReset(port: UsbSerialPort, mode: String = "reset"): Result<FirehoseResponse> {
        val cmd = FirehoseCommand(
            name = "reset",
            version = Constants.FIREHOSE_VERSION,
            arguments = mapOf("value" to mode)
        )
        return firehoseExec(port, cmd)
    }

    /**
     * NOP - No operation, check connection
     */
    suspend fun cmdNop(port: UsbSerialPort): Result<FirehoseResponse> {
        val cmd = FirehoseCommand("nop", Constants.FIREHOSE_VERSION)
        return firehoseExec(port, cmd)
    }

    /**
     * Get SHA256 digest of partition
     */
    suspend fun cmdGetSha256Digest(
        port: UsbSerialPort,
        physicalPartition: Int,
        startSector: Long,
        numSectors: Long
    ): Result<FirehoseResponse> {
        val cmd = FirehoseCommand(
            name = "getsha256digest",
            version = Constants.FIREHOSE_VERSION,
            arguments = mapOf(
                "physical_partition_number" to physicalPartition.toString(),
                "start_sector" to startSector.toString(),
                "num_partition_sectors" to numSectors.toString()
            )
        )
        return firehoseExec(port, cmd)
    }

    /**
     * Set bootable storage drive
     */
    suspend fun cmdSetBootableStorageDrive(
        port: UsbSerialPort,
        partitionNumber: Int
    ): Result<FirehoseResponse> {
        val cmd = FirehoseCommand(
            name = "setbootablestoragedrive",
            version = Constants.FIREHOSE_VERSION,
            arguments = mapOf("partition_number" to partitionNumber.toString())
        )
        return firehoseExec(port, cmd)
    }

    /**
     * Patch memory
     */
    suspend fun cmdPatch(
        port: UsbSerialPort,
        physicalPartition: Int,
        startSector: Long,
        byteOffset: Long,
        value: Long,
        sizeInBytes: Int
    ): Result<FirehoseResponse> {
        val cmd = FirehoseCommand(
            name = "patch",
            version = Constants.FIREHOSE_VERSION,
            arguments = mapOf(
                "physical_partition_number" to physicalPartition.toString(),
                "start_sector" to startSector.toString(),
                "byte_offset" to byteOffset.toString(),
                "value" to value.toString(),
                "size_in_bytes" to sizeInBytes.toString()
            )
        )
        return firehoseExec(port, cmd)
    }

    /**
     * Program/Write partition
     */
    suspend fun cmdProgram(
        port: UsbSerialPort,
        physicalPartition: Int,
        startSector: Long,
        filename: String
    ): Result<FirehoseResponse> {
        val cmd = FirehoseCommand(
            name = "program",
            version = Constants.FIREHOSE_VERSION,
            arguments = mapOf(
                "physical_partition_number" to physicalPartition.toString(),
                "start_sector" to startSector.toString(),
                "filename" to filename
            )
        )
        return firehoseExec(port, cmd)
    }

    /**
     * Program buffer (raw data)
     */
    suspend fun cmdProgramBuffer(
        port: UsbSerialPort,
        physicalPartition: Int,
        startSector: Long,
        data: ByteArray
    ): Result<FirehoseResponse> {
        val cmd = FirehoseCommand(
            name = "program",
            version = Constants.FIREHOSE_VERSION,
            arguments = mapOf(
                "physical_partition_number" to physicalPartition.toString(),
                "start_sector" to startSector.toString(),
                "filedata" to data.toString() // Base64 encoded in production
            )
        )
        return firehoseExec(port, cmd)
    }

    /**
     * Erase partition
     */
    suspend fun cmdErase(
        port: UsbSerialPort,
        physicalPartition: Int,
        startSector: Long,
        numSectors: Long
    ): Result<FirehoseResponse> {
        val cmd = FirehoseCommand(
            name = "erase",
            version = Constants.FIREHOSE_VERSION,
            arguments = mapOf(
                "physical_partition_number" to physicalPartition.toString(),
                "start_sector" to startSector.toString(),
                "num_partition_sectors" to numSectors.toString()
            )
        )
        return firehoseExec(port, cmd)
    }

    /**
     * Read partition to file
     */
    suspend fun cmdRead(
        port: UsbSerialPort,
        physicalPartition: Int,
        startSector: Long,
        numSectors: Long,
        filename: String
    ): Result<FirehoseResponse> {
        val cmd = FirehoseCommand(
            name = "read",
            version = Constants.FIREHOSE_VERSION,
            arguments = mapOf(
                "physical_partition_number" to physicalPartition.toString(),
                "start_sector" to startSector.toString(),
                "num_partition_sectors" to numSectors.toString(),
                "filename" to filename
            )
        )
        return firehoseExec(port, cmd)
    }

    /**
     * Read partition to buffer
     */
    suspend fun cmdReadBuffer(
        port: UsbSerialPort,
        physicalPartition: Int,
        startSector: Long,
        numSectors: Long
    ): Result<FirehoseResponse> {
        val cmd = FirehoseCommand(
            name = "read",
            version = Constants.FIREHOSE_VERSION,
            arguments = mapOf(
                "physical_partition_number" to physicalPartition.toString(),
                "start_sector" to startSector.toString(),
                "num_partition_sectors" to numSectors.toString()
            )
        )
        return firehoseExec(port, cmd)
    }

    /**
     * Get GPT partition table
     */
    suspend fun cmdGetGpt(
        port: UsbSerialPort,
        lun: Int,
        numPartEntries: Int,
        partEntrySize: Int,
        partEntryStartLba: Long
    ): Result<FirehoseResponse> {
        val cmd = FirehoseCommand(
            name = "get_gpt",
            version = Constants.FIREHOSE_VERSION,
            arguments = mapOf(
                "lun" to lun.toString(),
                "gpt_num_part_entries" to numPartEntries.toString(),
                "gpt_part_entry_size" to partEntrySize.toString(),
                "gpt_part_entry_start_lba" to partEntryStartLba.toString()
            )
        )
        return firehoseExec(port, cmd)
    }

    /**
     * Get storage info
     */
    suspend fun cmdGetStorageInfo(port: UsbSerialPort): Result<FirehoseResponse> {
        val cmd = FirehoseCommand("getstorageinfo", Constants.FIREHOSE_VERSION)
        return firehoseExec(port, cmd)
    }

    /**
     * Get supported functions
     */
    suspend fun cmdGetSupportedFunctions(port: UsbSerialPort): Result<FirehoseResponse> {
        val cmd = FirehoseCommand("getSupportedFunctions", Constants.FIREHOSE_VERSION)
        return firehoseExec(port, cmd)
    }

    /**
     * Set active slot (A/B partition)
     */
    suspend fun cmdSetActiveSlot(port: UsbSerialPort, slot: String): Result<FirehoseResponse> {
        val cmd = FirehoseCommand(
            name = "setactiveslot",
            version = Constants.FIREHOSE_VERSION,
            arguments = mapOf("slot" to slot)
        )
        return firehoseExec(port, cmd)
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
            val partitions = readPartitionTable(port).getOrNull()
            val frpPartition = partitions?.find { 
                it.label.equals(Constants.PARTITION_FRP, ignoreCase = true) 
            } ?: return Result.failure(QualcommException("FRP partition not found"))
            
            // Erase FRP partition
            cmdErase(port, 0, frpPartition.startSector, frpPartition.numSectors)
            
            Result.success(Unit)
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
            val partitions = readPartitionTable(port).getOrNull()
            val userdata = partitions?.find { 
                it.label.equals(Constants.PARTITION_USERDATA, ignoreCase = true) 
            } ?: return Result.failure(QualcommException("userdata partition not found"))
            
            // Erase userdata
            cmdErase(port, 0, userdata.startSector, userdata.numSectors)
            
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Remove/bypass screen lock
     */
    suspend fun removeScreenLock(port: UsbSerialPort): Result<Unit> {
        return try {
            // Clear FRP first
            removeFrp(port)
            
            // Also clear persist partition which may contain lock flags
            val partitions = readPartitionTable(port).getOrNull()
            val persist = partitions?.find { 
                it.label.equals(Constants.PARTITION_PERSIST, ignoreCase = true) 
            }
            persist?.let {
                cmdErase(port, 0, it.startSector, it.numSectors)
            }
            
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Wipe modem data
     * Useful for network-related issues
     */
    suspend fun wipeModemData(port: UsbSerialPort): Result<Unit> {
        return try {
            val partitions = readPartitionTable(port).getOrNull()
            
            // Wipe modemst1 and modemst2 (NV items)
            listOf(Constants.PARTITION_MODEMST1, Constants.PARTITION_MODEMST2).forEach { partName ->
                partitions?.find { it.label.equals(partName, ignoreCase = true) }?.let {
                    cmdErase(port, 0, it.startSector, it.numSectors)
                }
            }
            
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Reboot device
     */
    suspend fun reboot(port: UsbSerialPort): Result<Unit> {
        return try {
            cmdReset(port, "reset")
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
            cmdReset(port, "poweroff")
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ============================================================
    // LOADER OPERATIONS
    // ============================================================

    /**
     * Write IMEI
     */
    suspend fun cmdWriteImei(port: UsbSerialPort, imei: String): Result<FirehoseResponse> {
        val cmd = FirehoseCommand(
            name = "writeimei",
            version = Constants.FIREHOSE_VERSION,
            arguments = mapOf("imei" to imei)
        )
        return firehoseExec(port, cmd)
    }

    /**
     * Configure NAND/EMMC parameters
     */
    suspend fun cmdConfigure(port: UsbSerialPort, lvl: String): Result<FirehoseResponse> {
        val cmd = FirehoseCommand(
            name = "configure",
            version = Constants.FIREHOSE_VERSION,
            arguments = mapOf("lun" to lvl)
        )
        return firehoseExec(port, cmd)
    }

    /**
     * Get NAND pages attribute
     */
    suspend fun cmdNandPagesAttr(port: UsbSerialPort): Result<FirehoseResponse> {
        val cmd = FirehoseCommand("nand_pages_attr", Constants.FIREHOSE_VERSION)
        return firehoseExec(port, cmd)
    }
}

/**
 * Sahara mode device information
 */
data class SaharaDeviceInfo(
    val protocolVersion: Int,
    val maxPacketSize: Int,
    val mode: Int
)

/**
 * Firehose command
 */
data class FirehoseCommand(
    val name: String,
    val version: String,
    val arguments: Map<String, String> = emptyMap()
) {
    fun toXml(): String {
        val args = arguments.entries.joinToString("") { (key, value) ->
            "        <$key>$value</$key>\n"
        }
        
        return """
            |<?xml version="1.0" encoding="utf-8"?>
            |<request>
            |    <header>
            |        <name>$name</name>
            |        <version>$version</version>
            |    </header>
            |    <payload>
            |$args    </payload>
            |</request>
        """.trimMargin()
    }
}

/**
 * Firehose response
 */
data class FirehoseResponse(
    val status: FirehoseStatus,
    val rawXml: String,
    val logLines: List<String>
)

enum class FirehoseStatus {
    SUCCESS,
    ERROR
}

/**
 * Qualcomm partition entry
 */
data class QualcommPartition(
    val label: String,
    val startSector: Long,
    val numSectors: Long
) {
    val sizeBytes: Long get() = numSectors * 512
}

/**
 * Custom exception for Qualcomm operations
 */
class QualcommException(message: String) : Exception(message)