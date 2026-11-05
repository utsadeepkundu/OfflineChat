package com.offlinechat.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import com.offlinechat.app.domain.ImportedFile
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Copies a picked document into private app storage (streaming, never loading it into memory) while hashing it,
 * and optionally shrinks big camera photos first so they cross Bluetooth much faster.
 */
@Singleton
class FileImporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: AppPreferences,
) {
    sealed interface Result {
        class Ok(val file: ImportedFile) : Result
        class TooLarge(val limitBytes: Long) : Result
        class Failed(val message: String) : Result
    }

    suspend fun import(uri: Uri, limitBytes: Long): Result = withContext(Dispatchers.IO) {
        try {
            val resolver = context.contentResolver
            var name = "file"
            var declaredSize = -1L
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getString(0)?.let { name = it }
                    if (!c.isNull(1)) declaredSize = c.getLong(1)
                }
            }
            name = sanitize(name)
            var mime = resolver.getType(uri)
                ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
                ?: "application/octet-stream"

            val dir = File(context.filesDir, "outgoing").apply { mkdirs() }
            val target = File(dir, "${System.nanoTime()}_$name")

            val shrink = preferences.settings.value.compressImages &&
                (mime == "image/jpeg" || mime == "image/heic" || mime == "image/heif") &&
                (declaredSize < 0 || declaredSize > SHRINK_ABOVE_BYTES)
            if (shrink && shrinkImage(uri, target)) {
                mime = "image/jpeg"
                name = name.substringBeforeLast('.', name) + ".jpg"
                val renamed = File(dir, "${System.nanoTime()}_$name")
                target.renameTo(renamed)
                return@withContext finish(renamed, name, mime, limitBytes)
            }

            if (declaredSize > limitBytes) return@withContext Result.TooLarge(limitBytes)
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            val input = resolver.openInputStream(uri) ?: return@withContext Result.Failed("Could not open the file")
            input.use { src ->
                FileOutputStream(target).use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = src.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > limitBytes) {
                            target.delete()
                            return@withContext Result.TooLarge(limitBytes)
                        }
                        digest.update(buffer, 0, n)
                        out.write(buffer, 0, n)
                    }
                }
            }
            if (total == 0L) {
                target.delete()
                return@withContext Result.Failed("The file is empty")
            }
            Result.Ok(ImportedFile(target, name, mime, total, digest.digest()))
        } catch (e: Exception) {
            Result.Failed(e.message ?: "Could not read the file")
        }
    }

    private fun finish(file: File, name: String, mime: String, limitBytes: Long): Result {
        val size = file.length()
        if (size > limitBytes) {
            file.delete()
            return Result.TooLarge(limitBytes)
        }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { s ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = s.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return Result.Ok(ImportedFile(file, name, mime, size, digest.digest()))
    }

    /** Downscales to [MAX_IMAGE_SIDE] and re-encodes as JPEG. Returns false when the image cannot be decoded. */
    private fun shrinkImage(uri: Uri, target: File): Boolean {
        val resolver = context.contentResolver
        val bitmap: Bitmap = try {
            if (Build.VERSION.SDK_INT >= 28) {
                // ImageDecoder also applies the EXIF rotation for us.
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val longest = max(info.size.width, info.size.height)
                    if (longest > MAX_IMAGE_SIDE) {
                        val scale = MAX_IMAGE_SIDE.toFloat() / longest
                        decoder.setTargetSize(
                            (info.size.width * scale).toInt().coerceAtLeast(1),
                            (info.size.height * scale).toInt().coerceAtLeast(1),
                        )
                    }
                }
            } else {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                var sample = 1
                while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_IMAGE_SIDE) sample *= 2
                val options = BitmapFactory.Options().apply { inSampleSize = sample }
                resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) } ?: return false
            }
        } catch (e: Exception) {
            return false
        }
        return try {
            FileOutputStream(target).use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
        } finally {
            bitmap.recycle()
        }
    }

    private fun sanitize(raw: String): String =
        raw.substringAfterLast('/').replace(Regex("[^\\p{L}\\p{N}._ ()\\-]"), "_").trim().take(80).ifBlank { "file" }

    private companion object {
        const val MAX_IMAGE_SIDE = 1600
        const val JPEG_QUALITY = 82
        const val SHRINK_ABOVE_BYTES = 600_000L
    }
}
