package com.example.videocompressor

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream

object FileUtils {
    fun getPath(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                // 使用更具识别性的前缀
                val file = File(context.cacheDir, "input_tmp_${System.currentTimeMillis()}.mp4")
                FileOutputStream(file).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
                file.absolutePath
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * 清理缓存目录下的临时文件，整理内存
     */
    fun clearTempFiles(context: Context) {
        try {
            val folders = listOf(context.cacheDir, context.externalCacheDir)
            folders.forEach { folder ->
                folder?.listFiles { _, name ->
                    name.startsWith("input_tmp_") || name.startsWith("comp_tmp_")
                }?.forEach { it.delete() }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun getFileName(context: Context, uri: Uri): String {
        var result: String? = null
        if (uri.scheme == "content") {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index != -1) result = cursor.getString(index)
                }
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/') ?: -1
            if (cut != -1) result = result?.substring(cut + 1)
        }
        return result ?: "unknown_video"
    }
}