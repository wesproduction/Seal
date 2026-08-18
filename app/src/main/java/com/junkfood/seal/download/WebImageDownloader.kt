package com.junkfood.seal.download

import android.content.ContentUris
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
import com.junkfood.seal.download.Task.TypeInfo.WebImageCollection
import com.junkfood.seal.download.Task.TypeInfo.WebImageItem
import com.junkfood.seal.util.DownloadUtil
import com.junkfood.seal.util.DownloadUtil.DownloadPreferences
import com.junkfood.seal.util.PreferenceUtil.getString
import com.junkfood.seal.util.USER_AGENT_STRING
import java.io.File
import java.io.IOException
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request

object WebImageDownloader {
    private const val TAG = "WebImageDownloader"
    private const val BUFFER_SIZE = 64 * 1024
    private const val GALLERY_ORDER_PREFERENCES = "web_image_gallery_order"
    private const val LAST_GALLERY_TOP_SECOND = "last_gallery_top_second"
    private const val LAST_GALLERY_BOTTOM_SECOND = "last_gallery_bottom_second"
    private const val DOWNLOAD_PHASE_PERCENT = 94f
    private const val PUBLISH_COPY_PHASE_PERCENT = 4f
    private const val PUBLISH_VISIBILITY_PHASE_PERCENT = 2f
    internal const val DIRECTORY_NAME = "Walrus Images"
    private val client =
        OkHttpClient.Builder().followRedirects(true).followSslRedirects(true).build()
    private val galleryPublishMutex = Mutex()

    suspend fun downloadCollection(
        collection: WebImageCollection,
        preferences: DownloadPreferences,
        progressCallback: ((Float, Long, String) -> Unit)?,
    ): Result<List<String>> {
        if (collection.items.isEmpty()) {
            return Result.failure(IOException("This webpage does not contain downloadable images"))
        }
        if (preferences.sdcard) {
            return Result.failure(
                IOException("Web image collections do not yet support an SD-card tree")
            )
        }

        val workDirectory =
            File(context.cacheDir, "web-images-${collection.pageId}-${System.nanoTime()}").apply {
                mkdirs()
            }
        val stagedItems = mutableListOf<StagedItem>()
        var completedBytes = 0L
        return try {
            collection.items.forEachIndexed { completedItems, item ->
                coroutineContext.ensureActive()
                var currentBytes = 0L
                val staged =
                    stageItem(collection, item, preferences, workDirectory) {
                        itemProgress,
                        downloadedBytes,
                        _ ->
                        currentBytes = downloadedBytes
                        val boundedProgress = itemProgress.coerceIn(0f, 100f)
                        val overall =
                            (completedItems + boundedProgress / 100f) * DOWNLOAD_PHASE_PERCENT /
                                collection.items.size
                        progressCallback?.invoke(
                            overall,
                            completedBytes + downloadedBytes,
                            "Preparing ${completedItems + 1}/${collection.items.size} - ${boundedProgress.toInt()}%",
                        )
                    }
                stagedItems += staged
                completedBytes += maxOf(currentBytes, staged.downloadedBytes)
                progressCallback?.invoke(
                    (completedItems + 1) * DOWNLOAD_PHASE_PERCENT / collection.items.size,
                    completedBytes,
                    "Prepared ${completedItems + 1}/${collection.items.size}",
                )
            }

            coroutineContext.ensureActive()
            val paths =
                galleryPublishMutex.withLock {
                    coroutineContext.ensureActive()
                    publishStagedCollection(
                        collection = collection,
                        stagedItems = stagedItems,
                        preferences = preferences,
                        completedBytes = completedBytes,
                        progressCallback = progressCallback,
                    )
                }
            Result.success(paths)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            Log.e(TAG, "Web image download failed for ${collection.pageId}", throwable)
            Result.failure(throwable)
        } finally {
            workDirectory.deleteRecursively()
        }
    }

    private suspend fun stageItem(
        collection: WebImageCollection,
        item: WebImageItem,
        preferences: DownloadPreferences,
        workDirectory: File,
        progressCallback: ((Float, Long, String) -> Unit)?,
    ): StagedItem {
        findExisting(collection, item, preferences)?.let {
            return StagedItem(item = item, existingPath = it)
        }
        val source = File(workDirectory, "${item.mediaId}.${item.extension}")
        val bytes = downloadToFile(collection, item, source, progressCallback)
        return StagedItem(item = item, preparedFile = source, downloadedBytes = bytes)
    }

    private suspend fun downloadToFile(
        collection: WebImageCollection,
        item: WebImageItem,
        destination: File,
        progressCallback: ((Float, Long, String) -> Unit)?,
    ): Long {
        val request = mediaRequest(collection, item)
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Website image returned HTTP ${response.code}")
            }
            val body = response.body
            val contentType = body.contentType()?.toString().orEmpty().substringBefore(';')
            if (
                contentType.startsWith("text/") ||
                    contentType == "application/xhtml+xml" ||
                    contentType == "application/json"
            ) {
                throw IOException("Website returned $contentType instead of an image")
            }
            if (
                contentType.isNotBlank() &&
                    !contentType.startsWith("image/") &&
                    contentType != "application/octet-stream"
            ) {
                throw IOException("Unsupported webpage media type: $contentType")
            }
            val totalBytes = body.contentLength()
            var downloaded = 0L
            destination.outputStream().buffered().use { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        val progress = if (totalBytes > 0L) downloaded * 100f / totalBytes else -1f
                        progressCallback?.invoke(progress, downloaded, "Downloading")
                    }
                }
            }
            if (downloaded <= 0L) throw IOException("Website returned an empty image")
            requireCompleteDownload(totalBytes, downloaded)
            return downloaded
        }
    }

    internal fun requireCompleteDownload(expectedBytes: Long, downloadedBytes: Long) {
        if (expectedBytes >= 0L && expectedBytes != downloadedBytes) {
            throw IOException(
                "Web image download was incomplete: expected $expectedBytes bytes, received " +
                    "$downloadedBytes bytes"
            )
        }
    }

    private fun publishStagedCollection(
        collection: WebImageCollection,
        stagedItems: List<StagedItem>,
        preferences: DownloadPreferences,
        completedBytes: Long,
        progressCallback: ((Float, Long, String) -> Unit)?,
    ): List<String> {
        val galleryTimestamp = reserveGalleryTimestamp(stagedItems.size)
        val paths = MutableList<String?>(stagedItems.size) { null }
        val createdUris = mutableListOf<Uri>()
        val pendingUris = mutableMapOf<String, Uri>()

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !preferences.privateDirectory) {
                stagedItems.asReversed().forEach { staged ->
                    if (staged.preparedFile != null) {
                        createPendingMediaStoreItem(collection, staged.item, galleryTimestamp)
                            .also { uri ->
                                pendingUris[staged.item.mediaId] = uri
                                createdUris += uri
                            }
                    }
                }

                // New files remain pending and invisible to gallery apps until every image has
                // been downloaded and copied successfully.
                stagedItems.forEachIndexed { index, staged ->
                    paths[index] =
                        staged.existingPath
                            ?: pendingUris
                                .getValue(staged.item.mediaId)
                                .also { uri ->
                                    writePreparedMediaStoreItem(
                                        uri,
                                        requireNotNull(staged.preparedFile),
                                        collection,
                                        staged.item,
                                    )
                                }
                                .toString()
                    progressCallback?.invoke(
                        DOWNLOAD_PHASE_PERCENT +
                            (index + 1f) / stagedItems.size * PUBLISH_COPY_PHASE_PERCENT,
                        completedBytes,
                        "Finalizing image collection",
                    )
                }

                stagedItems.forEachIndexed { index, staged ->
                    pendingUris[staged.item.mediaId]?.let { uri ->
                        publishMediaStoreItem(uri, staged.item, galleryTimestamp)
                    }
                    progressCallback?.invoke(
                        DOWNLOAD_PHASE_PERCENT +
                            PUBLISH_COPY_PHASE_PERCENT +
                            (index + 1f) / stagedItems.size * PUBLISH_VISIBILITY_PHASE_PERCENT,
                        completedBytes,
                        "Adding collection to Gallery",
                    )
                }
            } else {
                stagedItems.forEachIndexed { index, staged ->
                    paths[index] =
                        staged.existingPath
                            ?: publishPreparedLegacyFile(
                                collection = collection,
                                item = staged.item,
                                preferences = preferences,
                                preparedFile = requireNotNull(staged.preparedFile),
                                galleryTimestamp = galleryTimestamp,
                            )
                    progressCallback?.invoke(
                        DOWNLOAD_PHASE_PERCENT +
                            (index + 1f) / stagedItems.size *
                                (PUBLISH_COPY_PHASE_PERCENT + PUBLISH_VISIBILITY_PHASE_PERCENT),
                        completedBytes,
                        "Adding collection to Gallery",
                    )
                }
            }

            val completedPaths = paths.map { requireNotNull(it) }
            applyGalleryOrder(collection, completedPaths, galleryTimestamp)
            progressCallback?.invoke(100f, completedBytes, "Saved as one image collection")
            completedPaths
        } catch (throwable: Throwable) {
            createdUris.asReversed().forEach { uri ->
                runCatching { context.contentResolver.delete(uri, null, null) }
            }
            throw throwable
        }
    }

    private fun writePreparedMediaStoreItem(
        uri: Uri,
        preparedFile: File,
        collection: WebImageCollection,
        item: WebImageItem,
    ) {
        val output =
            context.contentResolver.openOutputStream(uri, "w")
                ?: throw IOException("Unable to save ${orderedFileName(collection, item)}")
        output.use { preparedFile.inputStream().buffered().use { input -> input.copyTo(it) } }
    }

    private fun publishPreparedLegacyFile(
        collection: WebImageCollection,
        item: WebImageItem,
        preferences: DownloadPreferences,
        preparedFile: File,
        galleryTimestamp: Long,
    ): String {
        val preferred = targetFile(collection, item, preferences)
        if (preferred.isFile && preferred.length() > 0L) return preferred.absolutePath
        val target = RedditMediaDownloader.availableTargetFile(preferred)
        target.parentFile?.mkdirs()
        preparedFile.inputStream().buffered().use { input ->
            target.outputStream().buffered().use { output -> input.copyTo(output) }
        }
        target.setLastModified(orderedTimestampMillis(galleryTimestamp, item))
        MediaScannerConnection.scanFile(
            context,
            arrayOf(target.absolutePath),
            arrayOf(item.mimeType),
            null,
        )
        return target.absolutePath
    }

    private fun reserveGalleryTimestamp(itemCount: Int): Long {
        val preferences =
            context.getSharedPreferences(
                GALLERY_ORDER_PREFERENCES,
                android.content.Context.MODE_PRIVATE,
            )
        val previousTop =
            preferences
                .takeIf { it.contains(LAST_GALLERY_TOP_SECOND) }
                ?.getLong(LAST_GALLERY_TOP_SECOND, 0L)
        val previousBottom =
            preferences
                .takeIf { it.contains(LAST_GALLERY_BOTTOM_SECOND) }
                ?.getLong(LAST_GALLERY_BOTTOM_SECOND, 0L)
        val block =
            PixivMediaDownloader.reserveGalleryTimestampBlock(
                nowMillis = System.currentTimeMillis(),
                itemCount = itemCount,
                previousTopSecond = previousTop,
                previousBottomSecond = previousBottom,
            )
        preferences
            .edit()
            .putLong(LAST_GALLERY_TOP_SECOND, block.topSecond)
            .putLong(LAST_GALLERY_BOTTOM_SECOND, block.bottomSecond)
            .apply()
        return block.baseSecond * 1_000L
    }

    private fun findExisting(
        collection: WebImageCollection,
        item: WebImageItem,
        preferences: DownloadPreferences,
    ): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !preferences.privateDirectory) {
            return findCompletedMediaStoreItem(collection, item)?.toString()
        }
        return targetFile(collection, item, preferences)
            .takeIf { it.isFile && it.length() > 0L }
            ?.absolutePath
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun createPendingMediaStoreItem(
        collection: WebImageCollection,
        item: WebImageItem,
        galleryTimestamp: Long,
    ): Uri {
        val mediaCollection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val displayName = orderedFileName(collection, item)
        val relativePath = relativePath()
        context.contentResolver.delete(
            mediaCollection,
            "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND " +
                "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND " +
                "${MediaStore.MediaColumns.IS_PENDING}=1",
            arrayOf(relativePath, displayName),
        )
        val timestampMillis = orderedTimestampMillis(galleryTimestamp, item)
        val values =
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, item.mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.DATE_ADDED, timestampMillis / 1_000L)
                put(MediaStore.MediaColumns.DATE_MODIFIED, timestampMillis / 1_000L)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        return context.contentResolver.insert(mediaCollection, values)
            ?: throw IOException("Unable to create $displayName")
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun findCompletedMediaStoreItem(
        collection: WebImageCollection,
        item: WebImageItem,
    ): Uri? {
        val mediaCollection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.SIZE)
        return context.contentResolver
            .query(
                mediaCollection,
                projection,
                "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND " +
                    "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND " +
                    "${MediaStore.MediaColumns.IS_PENDING}=0",
                arrayOf(relativePath(), orderedFileName(collection, item)),
                "${MediaStore.MediaColumns._ID} DESC",
            )
            ?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                while (cursor.moveToNext()) {
                    if (cursor.getLong(sizeColumn) > 0L) {
                        return@use ContentUris.withAppendedId(
                            mediaCollection,
                            cursor.getLong(idColumn),
                        )
                    }
                }
                null
            }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun publishMediaStoreItem(uri: Uri, item: WebImageItem, galleryTimestamp: Long) {
        val timestampMillis = orderedTimestampMillis(galleryTimestamp, item)
        val values =
            ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
                put(MediaStore.MediaColumns.DATE_MODIFIED, timestampMillis / 1_000L)
                put(MediaStore.Images.ImageColumns.DATE_TAKEN, timestampMillis)
            }
        if (context.contentResolver.update(uri, values, null, null) <= 0) {
            throw IOException("Unable to publish webpage image ${item.mediaId}")
        }
    }

    private fun applyGalleryOrder(
        collection: WebImageCollection,
        paths: List<String>,
        galleryTimestamp: Long,
    ) {
        collection.items.zip(paths).forEach { (item, path) ->
            val timestampMillis = orderedTimestampMillis(galleryTimestamp, item)
            val values =
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DATE_ADDED, timestampMillis / 1_000L)
                    put(MediaStore.MediaColumns.DATE_MODIFIED, timestampMillis / 1_000L)
                    put(MediaStore.Images.ImageColumns.DATE_TAKEN, timestampMillis)
                }
            val uri = runCatching { Uri.parse(path) }.getOrNull()
            if (uri?.scheme == "content") {
                context.contentResolver.update(uri, values, null, null)
                applyPhysicalFileTimestamp(uri, item, timestampMillis)
            } else {
                val file = File(path)
                if (file.setLastModified(timestampMillis)) {
                    MediaScannerConnection.scanFile(
                        context,
                        arrayOf(file.absolutePath),
                        arrayOf(item.mimeType),
                        null,
                    )
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun applyPhysicalFileTimestamp(uri: Uri, item: WebImageItem, timestampMillis: Long) {
        val path =
            context.contentResolver
                .query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)
                ?.use { cursor ->
                    if (!cursor.moveToFirst()) return@use null
                    cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA))
                } ?: return
        val file = File(path)
        if (!file.setLastModified(timestampMillis)) return
        MediaScannerConnection.scanFile(context, arrayOf(path), arrayOf(item.mimeType), null)
    }

    private fun orderedTimestampMillis(galleryTimestamp: Long, item: WebImageItem): Long =
        galleryTimestamp - item.index.coerceAtLeast(1) * 1_000L

    private suspend fun mediaRequest(collection: WebImageCollection, item: WebImageItem): Request {
        val userAgent =
            USER_AGENT_STRING.getString().ifBlank {
                System.getProperty("http.agent")
                    ?: "WalrusDownloader/${BuildConfig.VERSION_NAME} Android/${Build.VERSION.RELEASE}"
            }
        return Request.Builder()
            .url(item.mediaUrl)
            .header("User-Agent", userAgent)
            .header("Referer", collection.sourceUrl)
            .header("Accept", "${item.mimeType},image/avif,image/webp,image/*,*/*")
            .apply {
                DownloadUtil.getCookieHeaderFor(item.mediaUrl).takeIf(String::isNotBlank)?.let {
                    header("Cookie", it)
                }
            }
            .build()
    }

    internal fun orderedFileName(collection: WebImageCollection, item: WebImageItem): String {
        val extension = item.extension.lowercase().ifBlank { "jpg" }
        val base =
            "${sanitizeFileName(collection.pageTitle).take(88)} - " +
                "${sanitizeFileName(collection.siteName).take(36)} [${collection.pageId.take(10)}]"
        if (item.total <= 1) return "$base.$extension"
        val width = item.total.toString().length.coerceAtLeast(2)
        return "$base - %0${width}d.$extension".format(item.index)
    }

    internal fun sanitizeFileName(value: String): String =
        value
            .replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "_")
            .trim(' ', '.')
            .take(120)
            .ifBlank { "Web images" }

    private fun relativePath(): String =
        "${Environment.DIRECTORY_DOWNLOADS}/Walrus Downloader/$DIRECTORY_NAME/"

    private fun targetFile(
        collection: WebImageCollection,
        item: WebImageItem,
        preferences: DownloadPreferences,
    ): File {
        val root =
            File(if (preferences.privateDirectory) App.privateDownloadDir else videoDownloadDir)
        return File(File(root, DIRECTORY_NAME), orderedFileName(collection, item))
    }

    private data class StagedItem(
        val item: WebImageItem,
        val existingPath: String? = null,
        val preparedFile: File? = null,
        val downloadedBytes: Long = 0L,
    )
}
