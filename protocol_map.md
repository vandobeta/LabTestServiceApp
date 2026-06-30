# Protocol Mapping Document

## Overview
This document maps the Python protocol implementations to Android Kotlin equivalents for device flashing in bootrom, FDL, and EDL modes.

---

## 1. USB Serial Driver Layer

### Source: `usb-serial-for-android`

**Java/Kotlin Package**: `com.hoho.android.usbserial.driver`

| Python (pyserial) | Kotlin (UsbSerialPort) |
|------------------|------------------------|
| `Serial.read(size)` | `inputStream.read(buffer)` |
| `Serial.write(data)` | `outputStream.write(buffer)` |
| `Serial.timeout` | `UsbSerialPort.setParameters(baudRate, dataBits, stopBits, parity)` |
| `Serial.flush()` | `outputStream.flush()` |
| `serial.PARITY_NONE` | `UsbSerialPort.PARITY_NONE` |

**Supported Drivers**:
- `CdcAcmSerialDriver` - CDC ACM (most Android devices)
- `Cp21xxSerialDriver` - Silicon Labs CP2110
- `FtdiSerialDriver` - FTDI FT232R
- `ProlificSerialDriver` - Prolific PL2303
- `Ch34xSerialDriver` - CH340/CH341

---

## 2. MediaTek (MTK) BootROM Protocol

### Source: `mtkclient`

**Files**: `mtk.py`, `stage2.py`

#### Handshake Sequence
```python
# Python (original)
import struct
# Brom mode handshake
pack1 = b"\xa0"
pack2 = b"\x0"
ack = serial.read(512)  # Wait for response
```

#### Kotlin Translation
```kotlin
// Kotlin (target)
object MtkConstants {
    val BROM_HANDSHAKE_1 = byteArrayOf(0xA0.toByte())
    val BROM_HANDSHAKE_2 = byteArrayOf(0x00.toByte())
    val BROM_TIMEOUT_MS = 5000
}

suspend fun bromHandshake(port: UsbSerialPort): Boolean {
    val buffer = ByteBuffer.allocate(512)
    outputStream.write(MtkConstants.BROM_HANDSHAKE_1)
    delay(100)
    inputStream.read(buffer)
    return verifyAck(buffer)
}
```

#### Key Functions to Port
| Python | Kotlin |
|--------|---------|
| `mtk.preloader_handshake()` | `MtkProtocol.preloaderHandshake()` |
| `mtk.hwreset()` | `MtkProtocol.hardwareReset()` |
| `stage2.upload()` | `MtkStage2.upload()` |
| `mtk.da_handler()` | `MtkDaHandler.send()` |

#### Packet Framing
- **Magic**: `0xA0` (preloader), `0xFE` (stage2)
- **CRC**: CRC-16 (modem)
- **Response delay**: 100-500ms

---

## 3. Qualcomm EDL Protocol

### Source: `edl`

**Files**: `edlclient/Library/firehose.py`, `edlclient/Library/sahara.py`

#### Sahara Mode Handshake
```python
# Python (original)
SAHARA_HELLO = b"\x01\x00\x00\x00"
# Firehose XML commands
SEND_XML = b"<data>\n%s\n</data>"
```

#### Kotlin Translation
```kotlin
object QualcommConstants {
    val SAHARA_HELLO = byteArrayOf(0x01, 0x00, 0x00, 0x00)
    val SAHARA_HELLO_ACK = byteArrayOf(0x01, 0x00, 0x00, 0x02)
    val EDL_MODE_VID = 0x05C6.toInt()
    val EDL_MODE_PID = 0x9008.toInt()
    const val FIREHOSE_VERSION = "1.0"
}

suspend fun saharaHandshake(port: UsbSerialPort): Boolean {
    outputStream.write(QualcommConstants.SAHARA_HELLO)
    val response = readResponse(512)
    return response[0] == 0x01.toByte() && response[1] == 0x02.toByte()
}
```

#### Firehose Packet Structure
```xml
<!-- XML Packet Format -->
<request>
    <header>
        <name>%s</name>
        <version>%s</version>
    </header>
    <payload>
        <%s>%s</%s>
    </payload>
</request>
```

#### Key Functions to Port
| Python | Kotlin |
|--------|---------|
| `firehose.connect()` | `QualcommFirehose.connect()` |
| `firehose.exec_cmd()` | `QualcommFirehose.execute()` |
| `firehose.read_pat()` | `QualcommFirehose.readPartitionTable()` |
| `firehose.write()` | `QualcommFirehose.flashPartition()` |

---

## 4. Spreadtrum/Unisoc FDL Protocol

### Source: `spreadtrum_flash`

**Files**: `spd_dump.py`, protocol implementation

#### FDL1/FDL2 Handshake
```python
# Python (original)
FDL1_HELLO = b"\x80\x00\x00\x00"
FDL2_HELLO = b"\x81\x00\x00\x00"
# Response format
ACK_OK = b"\x00\x00\x00\x00"
```

#### Kotlin Translation
```kotlin
object SpreadtrumConstants {
    val FDL1_HELLO = byteArrayOf(0x80.toByte(), 0x00, 0x00, 0x00)
    val FDL2_HELLO = byteArrayOf(0x81.toByte(), 0x00, 0x00, 0x00)
    val FDL_ACK_OK = byteArrayOf(0x00, 0x00, 0x00, 0x00)
    val FDL_ACK_ERROR = byteArrayOf(0xFF.toByte(), 0xFF, 0xFF, 0xFF)
    const val FDL_TIMEOUT_MS = 3000
}

suspend fun fdlHandshake(port: UsbSerialPort, stage: Int): Boolean {
    val hello = if (stage == 1) SpreadtrumConstants.FDL1_HELLO 
                else SpreadtrumConstants.FDL2_HELLO
    outputStream.write(hello)
    delay(200)
    val response = readResponse(64)
    return response.contentEquals(SpreadtrumConstants.FDL_ACK_OK)
}
```

#### FDL Commands
| Command | Code | Description |
|---------|------|-------------|
| READ_FLASH | `0x80000003` | Read flash memory |
| WRITE_FLASH | `0x80000004` | Write flash memory |
| ERASE_FLASH | `0x80000005` | Erase flash sectors |
| VERITY | `0x80000006` | Enable/Disable verity |

---

## 5. Qualcomm Firehose Loaders Index

### Source: `Loaders/` submodule

| HWID | PK_HASH | Loader File | Device Examples |
|------|---------|------------|-----------------|
| `0x000B` | `MD5:...` | `prog_firehose_ddr.elf` | Qualcomm generic |
| `0x0046` | `MD5:...` | `prog_firehose_8953.elf` | Snapdragon 430 |
| `0x0067` | `MD5:...` | `prog_firehose_845.elf` | Snapdragon 845 |

**Loader Manifest Structure**:
```kotlin
data class FirehoseLoader(
    val hwid: Int,
    val pkHash: String,
    val fileName: String,
    val chipName: String,
    val version: String
)
```

---

## 6. USB Endpoint Mapping

| Device Mode | USB Class | Endpoint IN | Endpoint OUT |
|------------|----------|------------|--------------|
| MTK Preloader | CDC/ACM | EP1 | EP1 |
| Qualcomm EDL | Bulk | EP1 | EP1 |
| Spreadtrum FDL | CDC/ACM | EP2 | EP1 |

---

## 7. Error Codes

### MTK
| Code | Meaning |
|------|--------|
| `0x4000` | ACK OK |
| `0x4001` | ERROR |
| `0x4002` | INVALID_DA |

### Qualcomm
| Code | Meaning |
|------|--------|
| `0x01` | SUCCESS |
| `0x0F` | ERROR |
| `0xFF` | UNKNOWN |

### Spreadtrum
| Code | Meaning |
|------|--------|
| `0x00` | SUCCESS |
| `0x01` | ERROR |
| `0x02` | TIMEOUT |

---

## 8. Device Detection Patterns

```kotlin
data class DeviceInfo(
    val chipVendor: ChipVendor,
    val chipId: Int,
    val secureBoot: Boolean,
    val daLoaded: Boolean
)

enum class ChipVendor {
    MEDIATEK,
    QUALCOMM,
    SPREADTRUM
}
```

---

*Generated from repository analysis*