package com.offlinechat.app.ui.components

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.core.content.FileProvider
import java.io.File

object FileActions {
    private fun uriFor(context: Context, path: String) =
        FileProvider.getUriForFile(context, "${context.packageName}.files", File(path))

    fun open(context: Context, path: String, mime: String?) {
        if (!File(path).exists()) {
            Toast.makeText(context, "This file is no longer on the phone", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uriFor(context, path), mime ?: "*/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, "No app can open this type of file", Toast.LENGTH_SHORT).show()
        }
    }

    fun share(context: Context, path: String, mime: String?) {
        if (!File(path).exists()) return
        val send = Intent(Intent.ACTION_SEND)
            .setType(mime ?: "*/*")
            .putExtra(Intent.EXTRA_STREAM, uriFor(context, path))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, "Share file").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

fun fileIcon(mime: String?, name: String?): ImageVector {
    val m = mime.orEmpty()
    return when {
        m.startsWith("image/") -> Icons.Filled.Image
        m.startsWith("video/") -> Icons.Filled.VideoFile
        m == "application/pdf" || name?.endsWith(".pdf", ignoreCase = true) == true -> Icons.Filled.PictureAsPdf
        else -> Icons.Filled.Description
    }
}

fun isImage(mime: String?): Boolean = mime?.startsWith("image/") == true
