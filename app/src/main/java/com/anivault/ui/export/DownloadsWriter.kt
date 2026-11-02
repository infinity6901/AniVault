package com.anivault.ui.export

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

/**
 * Writes a complete file before publishing it in Downloads. Failed writes are
 * removed so users never see a truncated export.
 */
internal object DownloadsWriter {
    fun write(context: Context, fileName: String, mimeType: String, writer: (OutputStream) -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("Could not create Downloads entry")
            try {
                val stream = context.contentResolver.openOutputStream(uri)
                    ?: error("Could not open Downloads output stream")
                stream.use(writer)
                val publish = ContentValues().apply {
                    put(MediaStore.Downloads.IS_PENDING, 0)
                }
                check(context.contentResolver.update(uri, publish, null, null) > 0) {
                    "Could not publish exported file"
                }
            } catch (error: Throwable) {
                runCatching { context.contentResolver.delete(uri, null, null) }
                throw error
            }
        } else {
            @Suppress("DEPRECATION")
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            check(dir.exists() || dir.mkdirs()) { "Could not create Downloads directory" }
            FileOutputStream(File(dir, fileName)).use(writer)
        }
    }
}
