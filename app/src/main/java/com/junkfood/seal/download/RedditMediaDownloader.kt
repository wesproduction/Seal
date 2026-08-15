package com.junkfood.seal.download

import android.content.ContentValues
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.RequiresApi
import com.junkfood.seal.App
import com.junkfood.seal.App.Companion.context
import com.junkfood.seal.App.Companion.videoDownloadDir
import com.junkfood.seal.BuildConfig
import com.junkfood.seal.download.Task.TypeInfo.RedditMedia
import com.junkfood.seal.util.DownloadUtil
import com.junkfood.seal.util.DownloadUtil.DownloadPreferences
import com.junkfood.seal.util.PreferenceUtil.getString
import com.junkfood.seal.util.USER_AGENT_STRING
import java.io.File
import java.io.IOException
import java.io.OutputStream
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import okhttp3.OkHttpClient
import okhttp3.Request

object RedditMediaDownloader {
    private const val TAG = "RedditMediaDownloader"
    private const val BUFFER_SIZE = 64 * 1024
    private val client =
        OkHttpClient.Builder().followRedirects(true).followSslRedirects(true).build()

    suspend fun download(
        media: RedditMedia,
        preferences: DownloadPreferences,
        progressCallback: ((Float, Long, String) -> Unit)?,
    ): Result<List<String>> {
        var partialFile: File? = null
        var pendingMediaUri: Uri? = null
        return try {
            if (preferences.sdcard) {
                throw IOException("Reddit gallery downloads do not yet support an SD-card tree")
            }
            val userAgent =
                USER_AGENT_STRING.getString().ifBlank {
                    System.getProperty("http.agent")
                        ?: "Seal/${BuildConfig.VERSION_NAME} Android/${Build.VERSION.RELEASE}"
                }
            val request =
                Request.Builder()
                    .url(media.mediaUrl)
                    .header("User-Agent", userAgent)
                    .header("Referer", media.sourceUrl)
                    .header("Accept", "${media.mimeType},image/*,video/*,*/*")
                    .apply {
                        DownloadUtil.getCookieHeaderFor(media.mediaUrl)
                            .takeIf { it.isNotBlank() }
                            ?.let { header("Cookie", it) }
                    }
                    .build()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !preferences.privateDirectory) {
                val uri = createPendingMediaStoreItem(media)
                pendingMediaUri = uri
                val output =
                    context.contentResolver.openOutputStream(uri, "w")
                        ?: throw IOException("Unable to create ${orderedFileName(media)}")
                streamResponse(request, media, output, progressCallback)
                publishMediaStoreItem(uri, media)
                pendingMediaUri = null
                return Result.success(listOf(uri.toString()))
            }

            val preferredTarget = targetFile(media, preferences)
            if (preferredTarget.isFile && preferredTarget.length() > 0L) {
                MediaScannerConnection.scanFile(
                    context,
                    arrayOf(preferredTarget.absolutePath),
                    arrayOf(media.mimeType),
                    null,
                )
                return Result.success(listOf(preferredTarget.absolutePath))
            }
            val target = availableTargetFile(preferredTarget)
            partialFile = File(target.parentFile, "${target.name}.part")
            target.parentFile?.mkdirs()
            streamResponse(request, media, partialFile.outputStream(), progressCallback)

            val completedTarget = finishPartialFile(partialFile, target)
            completedTarget.setLastModified(orderedTimestampMillis(media))
            MediaScannerConnection.scanFile(
                context,
                arrayOf(completedTarget.absolutePath),
                arrayOf(media.mimeType),
                null,
            )
            Result.success(listOf(completedTarget.absolutePath))
        } catch (cancellation: CancellationException) {
            partialFile?.delete()
            pendingMediaUri?.let { context.contentResolver.delete(it, null, null) }
            throw cancellation
        } catch (throwable: Throwable) {
            partialFile?.delete()
            pendingMediaUri?.let { context.contentResolver.delete(it, null, null) }
            Log.e(TAG, "Reddit media download failed for ${media.mediaId}", throwable)
            Result.failure(throwable)
        }
    }

    private suspend fun streamResponse(
        request: Request,
        media: RedditMedia,
        destination: OutputStream,
        progressCallback: ((Float, Long, String) -> Unit)?,
    ) {
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Reddit media returned HTTP ${response.code}")
            }
            val body = response.body
            val contentType = body.contentType()?.toString().orEmpty()
            if (contentType.startsWith("text/html")) {
                throw IOException("Reddit returned a webpage instead of media")
            }
            val totalBytes = body.contentLength()
            var downloaded = 0L
            body.byteStream().use { input ->
                destination.buffered().use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        val progress = if (totalBytes > 0L) downloaded * 100f / totalBytes else -1f
                        progressCallback?.invoke(
                            progress,
                            downloaded,
                            if (totalBytes > 0L) {
                                "${media.index}/${media.total} - ${progress.toInt()}%"
                            } else {
                                "${media.index}/${media.total}"
                            },
                        )
                    }
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun createPendingMediaStoreItem(media: RedditMedia): Uri {
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val relativePath = buildString {
            append(Environment.DIRECTORY_DOWNLOADS)
            append("/Seal/Reddit")
            if (media.total > 1) {
                append("/${sanitizeFileName(media.postTitle)} [${media.postId}]")
            }
            append('/')
        }
        val values =
            ContentValues().apply {
                val timestampSeconds = orderedTimestampMillis(media) / 1000L
                put(MediaStore.MediaColumns.DISPLAY_NAME, orderedFileName(media))
                put(MediaStore.MediaColumns.MIME_TYPE, media.mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.DATE_ADDED, timestampSeconds)
                put(MediaStore.MediaColumns.DATE_MODIFIED, timestampSeconds)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        return context.contentResolver.insert(collection, values)
            ?: throw IOException("Unable to create ${orderedFileName(media)}")
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun publishMediaStoreItem(uri: Uri, media: RedditMedia) {
        val timestampMillis = orderedTimestampMillis(media)
        val values =
            ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
                put(MediaStore.MediaColumns.DATE_MODIFIED, timestampMillis / 1000L)
                if (media.mimeType.startsWith("video/")) {
                    put(MediaStore.Video.VideoColumns.DATE_TAKEN, timestampMillis)
                } else {
                    put(MediaStore.Images.ImageColumns.DATE_TAKEN, timestampMillis)
                }
            }
        if (context.contentResolver.update(uri, values, null, null) <= 0) {
            throw IOException("Unable to publish ${orderedFileName(media)}")
        }
        applyOrderedFileTimestamp(uri, media)
    }

    @Suppress("DEPRECATION")
    private fun applyOrderedFileTimestamp(uri: Uri, media: RedditMedia) {
        val path =
            context.contentResolver
                .query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)
                ?.use { cursor ->
                    if (!cursor.moveToFirst()) return@use null
                    cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA))
                } ?: return
        val file = File(path)
        if (!file.setLastModified(orderedTimestampMillis(media))) return
        MediaScannerConnection.scanFile(context, arrayOf(path), arrayOf(media.mimeType), null)
    }

    private fun orderedTimestampMillis(media: RedditMedia): Long {
        val baseTimestamp =
            if (media.createdUtc > 0L) media.createdUtc * 1000L else System.currentTimeMillis()
        return baseTimestamp + (media.total - media.index) * 1000L
    }

    internal fun orderedFileName(media: RedditMedia): String {
        val extension = media.extension.lowercase().ifBlank { "jpg" }
        if (media.total <= 1) {
            return "${sanitizeFileName(media.postTitle)} [${media.postId}].$extension"
        }
        val width = media.total.toString().length.coerceAtLeast(2)
        val itemName = sanitizeFileName(media.caption.ifBlank { media.mediaId })
        return "%0${width}d - %s.%s".format(media.index, itemName, extension)
    }

    internal fun sanitizeFileName(value: String): String =
        value
            .replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "_")
            .trim(' ', '.')
            .take(120)
            .ifBlank { "Reddit media" }

    internal fun availableTargetFile(preferred: File): File {
        if (!preferred.exists() || preferred.delete()) return preferred
        for (suffix in 2..999) {
            val candidate = suffixedTargetFile(preferred, suffix)
            if (!candidate.exists()) return candidate
        }
        throw IOException("Unable to choose a filename for ${preferred.name}")
    }

    internal fun finishPartialFile(partial: File, preferred: File): File {
        if (partial.renameTo(preferred)) return preferred
        for (suffix in 2..999) {
            val candidate = suffixedTargetFile(preferred, suffix)
            if (partial.renameTo(candidate)) return candidate
        }
        throw IOException("Unable to finish ${preferred.name}")
    }

    private fun suffixedTargetFile(preferred: File, suffix: Int): File {
        val parent = preferred.parentFile ?: return preferred
        val extension = preferred.extension
        val baseName = preferred.nameWithoutExtension
        return File(
            parent,
            if (extension.isBlank()) "$baseName ($suffix)" else "$baseName ($suffix).$extension",
        )
    }

    private fun targetFile(media: RedditMedia, preferences: DownloadPreferences): File {
        val root =
            File(if (preferences.privateDirectory) App.privateDownloadDir else videoDownloadDir)
        val redditRoot = File(root, "Reddit")
        val directory =
            if (media.total > 1) {
                File(redditRoot, "${sanitizeFileName(media.postTitle)} [${media.postId}]")
            } else {
                redditRoot
            }
        return File(directory, orderedFileName(media))
    }
}
