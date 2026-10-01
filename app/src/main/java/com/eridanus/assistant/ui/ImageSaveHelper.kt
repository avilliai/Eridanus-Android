package com.eridanus.assistant.ui

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

object ImageSaveHelper {

    fun detectMimeAndExt(bytes: ByteArray): Pair<String, String> {
        if (bytes.size >= 8 &&
            bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()
        ) {
            return "image/png" to ".png"
        }
        if (bytes.size >= 3 &&
            bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()
        ) {
            return "image/jpeg" to ".jpg"
        }
        if (bytes.size >= 6 &&
            bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() && bytes[2] == 'F'.code.toByte()
        ) {
            return "image/gif" to ".gif"
        }
        if (bytes.size >= 12 &&
            bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
            bytes[8] == 'W'.code.toByte() && bytes[9] == 'E'.code.toByte() &&
            bytes[10] == 'B'.code.toByte() && bytes[11] == 'P'.code.toByte()
        ) {
            return "image/webp" to ".webp"
        }
        return "image/jpeg" to ".jpg"
    }

    suspend fun saveBytesToAlbum(
        context: Context,
        bytes: ByteArray,
        fileNamePrefix: String = "Eridanus_"
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val (mimeType, ext) = detectMimeAndExt(bytes)
            val fileName = "${fileNamePrefix}${System.currentTimeMillis()}$ext"

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val contentValues = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Images.Media.MIME_TYPE, mimeType)
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Eridanus")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }

                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                    ?: return@withContext Result.failure(Exception("\u65e0\u6cd5\u521b\u5efa MediaStore \u8bb0\u5f55"))

                try {
                    resolver.openOutputStream(uri)?.use { os ->
                        os.write(bytes)
                        os.flush()
                    } ?: return@withContext Result.failure(Exception("\u65e0\u6cd5\u6253\u5f00\u8f93\u51fa\u6d41"))

                    contentValues.clear()
                    contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                    resolver.update(uri, contentValues, null, null)
                    Result.success("Pictures/Eridanus/$fileName")
                } catch (e: Exception) {
                    try { resolver.delete(uri, null, null) } catch (_: Exception) {}
                    Result.failure(e)
                }
            } else {
                val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                val eridanusDir = File(picturesDir, "Eridanus").apply { if (!exists()) mkdirs() }
                val targetFile = File(eridanusDir, fileName)

                FileOutputStream(targetFile).use { fos ->
                    fos.write(bytes)
                    fos.flush()
                }

                MediaScannerConnection.scanFile(
                    context,
                    arrayOf(targetFile.absolutePath),
                    arrayOf(mimeType),
                    null
                )
                Result.success(targetFile.absolutePath)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun saveBitmapToAlbum(
        context: Context,
        bitmap: Bitmap,
        fileNamePrefix: String = "Eridanus_"
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val bos = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, bos)
            val bytes = bos.toByteArray()
            saveBytesToAlbum(context, bytes, fileNamePrefix)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
