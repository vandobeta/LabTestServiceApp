package com.labtest.serviceapp.loader

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream

/**
 * Loader Manager - handles importing and selecting loaders from local storage
 */
object LoaderManager {

    private const val LOADER_DIR = "loaders"
    private const val MTK_DIR = "mtk"
    private const val QUALCOMM_DIR = "qualcomm"
    private const val SPD_DIR = "spd"

    fun getLoaderDir(context: Context): File {
        val dir = File(context.filesDir, LOADER_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getMtkDir(context: Context): File {
        val dir = File(getLoaderDir(context), MTK_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getQualcommDir(context: Context): File {
        val dir = File(getLoaderDir(context), QUALCOMM_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getSpdDir(context: Context): File {
        val dir = File(getLoaderDir(context), SPD_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun importLoader(
        context: Context,
        uri: Uri,
        chipType: ChipType,
        fileName: String
    ): Result<File> {
        return try {
            val targetDir = when (chipType) {
                ChipType.MTK -> getMtkDir(context)
                ChipType.QUALCOMM -> getQualcommDir(context)
                ChipType.SPREADTRUM -> getSpdDir(context)
            }

            val targetFile = File(targetDir, fileName)
            
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            }

            Result.success(targetFile)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun listMtkLoaders(context: Context): List<LoaderFile> {
        return listLoaders(getMtkDir(context))
    }

    fun listQualcommLoaders(context: Context): List<LoaderFile> {
        return listLoaders(getQualcommDir(context))
    }

    fun listSpdLoaders(context: Context): List<LoaderFile> {
        return listLoaders(getSpdDir(context))
    }

    private fun listLoaders(dir: File): List<LoaderFile> {
        if (!dir.exists()) return emptyList()
        return dir.listFiles()?.filter { it.isFile }?.map { file ->
            LoaderFile(file.name, file.absolutePath, file.length(), file.lastModified())
        } ?: emptyList()
    }

    fun getLoader(context: Context, chipType: ChipType, fileName: String): File? {
        val dir = when (chipType) {
            ChipType.MTK -> getMtkDir(context)
            ChipType.QUALCOMM -> getQualcommDir(context)
            ChipType.SPREADTRUM -> getSpdDir(context)
        }
        val file = File(dir, fileName)
        return if (file.exists()) file else null
    }

    fun getLoaderBytes(context: Context, chipType: ChipType, fileName: String): ByteArray? {
        val file = getLoader(context, chipType, fileName)
        return file?.readBytes()
    }

    fun deleteLoader(context: Context, chipType: ChipType, fileName: String): Boolean {
        val file = getLoader(context, chipType, fileName)
        return file?.delete() ?: false
    }

    fun copyFromAssets(context: Context, chipType: ChipType): Result<Int> {
        return try {
            val assetsDir = when (chipType) {
                ChipType.MTK -> "loaders/mtk"
                ChipType.QUALCOMM -> "loaders/qualcomm"
                ChipType.SPREADTRUM -> "loaders/spd"
            }

            val targetDir = when (chipType) {
                ChipType.MTK -> getMtkDir(context)
                ChipType.QUALCOMM -> getQualcommDir(context)
                ChipType.SPREADTRUM -> getSpdDir(context)
            }

            var copied = 0
            try {
                val assets = context.assets.list(assetsDir)
                assets?.forEach { fileName ->
                    val input = context.assets.open("$assetsDir/$fileName")
                    val targetFile = File(targetDir, fileName)
                    input.use { inp ->
                        FileOutputStream(targetFile).use { out ->
                            inp.copyTo(out)
                        }
                    }
                    copied++
                }
            } catch (e: Exception) {}
            Result.success(copied)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

enum class ChipType {
    MTK,
    QUALCOMM,
    SPREADTRUM
}

data class LoaderFile(
    val name: String,
    val path: String,
    val size: Long,
    val lastModified: Long
)