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
import com.junkfood.seal.download.Task.TypeInfo.PixivArtwork
import com.junkfood.seal.download.Task.TypeInfo.PixivMediaItem
import com.junkfood.seal.util.DownloadUtil
import com.junkfood.seal.util.DownloadUtil.DownloadPreferences
import com.junkfood.seal.util.PreferenceUtil.getString
import com.junkfood.seal.util.USER_AGENT_STRING
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request

object PixivMediaDownloader {
    private const val TAG = "PixivMediaDownloader"
    private const val BUFFER_SIZE = 64 * 1024
    private const val MAX_EXTRACTED_BYTES = 2L * 1024L * 1024L * 1024L
    private const val GALLERY_ORDER_PREFERENCES = "pixiv_gallery_order"
    private const val LAST_GALLERY_TOP_SECOND = "last_gallery_top_second"
    private const val LAST_GALLERY_BOTTOM_SECOND = "last_gallery_bottom_second"
    private const val DOWNLOAD_PHASE_PERCENT = 94f
    private const val PUBLISH_COPY_PHASE_PERCENT = 4f
    private const val PUBLISH_VISIBILITY_PHASE_PERCENT = 2f
    internal const val DIRECTORY_NAME = "Walrus Pixiv"
    private val client =
        OkHttpClient.Builder().followRedirects(true).followSslRedirects(true).build()
    private val galleryPublishMutex = Mutex()

    suspend fun downloadArtwork(
        artwork: PixivArtwork,
        preferences: DownloadPreferences,
        progressCallback: ((Float, Long, String) -> Unit)?,
    ): Result<List<String>> {
        if (artwork.items.isEmpty())
            return Result.failure(IOException("This Pixiv artwork is empty"))
        if (preferences.sdcard) {
            return Result.failure(IOException("Pixiv downloads do not yet support an SD-card tree"))
        }

        val workDirectory =
            File(context.cacheDir, "pixiv-${artwork.artworkId}-${System.nanoTime()}").apply {
                mkdirs()
            }
        val stagedItems = mutableListOf<StagedItem>()
        var completedBytes = 0L
        return try {
            artwork.items.forEachIndexed { completedItems, item ->
                coroutineContext.ensureActive()
                var currentBytes = 0L
                val stagedItem =
                    stageItem(artwork, item, preferences, workDirectory) {
                        itemProgress,
                        downloadedBytes,
                        _ ->
                        currentBytes = downloadedBytes
                        val boundedProgress = itemProgress.coerceIn(0f, 100f)
                        val overall =
                            (completedItems + boundedProgress / 100f) * DOWNLOAD_PHASE_PERCENT /
                                artwork.items.size
                        progressCallback?.invoke(
                            overall,
                            completedBytes + downloadedBytes,
                            "Preparing ${completedItems + 1}/${artwork.items.size} - ${boundedProgress.toInt()}%",
                        )
                    }
                stagedItems += stagedItem
                completedBytes += maxOf(currentBytes, stagedItem.downloadedBytes)
                progressCallback?.invoke(
                    (completedItems + 1) * DOWNLOAD_PHASE_PERCENT / artwork.items.size,
                    completedBytes,
                    "Prepared ${completedItems + 1}/${artwork.items.size}",
                )
            }

            coroutineContext.ensureActive()
            val paths =
                galleryPublishMutex.withLock {
                    coroutineContext.ensureActive()
                    publishStagedArtwork(
                        artwork = artwork,
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
            Log.e(TAG, "Pixiv download failed for ${artwork.artworkId}", throwable)
            Result.failure(throwable)
        } finally {
            workDirectory.deleteRecursively()
        }
    }

    private suspend fun stageItem(
        artwork: PixivArtwork,
        item: PixivMediaItem,
        preferences: DownloadPreferences,
        workDirectory: File,
        progressCallback: ((Float, Long, String) -> Unit)?,
    ): StagedItem {
        findExisting(artwork, item, preferences)?.let {
            return StagedItem(item = item, existingPath = it)
        }
        val itemDirectory = File(workDirectory, item.mediaId).apply { mkdirs() }
        val mediaFile =
            if (item.isUgoira) {
                prepareUgoira(artwork, item, itemDirectory, progressCallback)
            } else {
                val source = File(itemDirectory, "source.${item.extension}")
                val bytes = downloadToFile(artwork, item, source, progressCallback)
                PreparedFile(source, bytes)
            }
        progressCallback?.invoke(100f, mediaFile.downloadedBytes, "Prepared")
        return StagedItem(
            item = item,
            preparedFile = mediaFile.file,
            downloadedBytes = mediaFile.downloadedBytes,
        )
    }

    private suspend fun prepareUgoira(
        artwork: PixivArtwork,
        item: PixivMediaItem,
        itemDirectory: File,
        progressCallback: ((Float, Long, String) -> Unit)?,
    ): PreparedFile {
        val archive = File(itemDirectory, "frames.zip")
        val archiveBytes =
            downloadToFile(artwork, item, archive) { progress, bytes, _ ->
                val scaled = if (progress < 0f) 20f else progress.coerceIn(0f, 100f) * 0.65f
                progressCallback?.invoke(scaled, bytes, "Downloading animation")
            }
        coroutineContext.ensureActive()
        progressCallback?.invoke(68f, archiveBytes, "Opening animation")

        val framesDirectory = File(itemDirectory, "frames").apply { mkdirs() }
        extractUgoiraFrames(archive, framesDirectory, item)
        val concatFile = File(framesDirectory, "frames.txt")
        concatFile.writeText(buildConcatFile(item))
        val output = File(itemDirectory, "animation.mp4")
        progressCallback?.invoke(72f, archiveBytes, "Creating video")
        encodeUgoira(framesDirectory, concatFile, output)
        progressCallback?.invoke(92f, archiveBytes, "Video ready")
        if (!output.isFile || output.length() <= 0L) {
            throw IOException("Pixiv animation conversion produced an empty video")
        }
        return PreparedFile(output, archiveBytes)
    }

    private suspend fun downloadToFile(
        artwork: PixivArtwork,
        item: PixivMediaItem,
        destination: File,
        progressCallback: ((Float, Long, String) -> Unit)?,
    ): Long {
        val request = mediaRequest(artwork, item)
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                if (response.code == 401 || response.code == 403) {
                    throw IOException(
                        "Pixiv requires a signed-in session. Open Pixiv sign-in in Cookies settings."
                    )
                }
                throw IOException("Pixiv media returned HTTP ${response.code}")
            }
            val body = response.body
            if (body.contentType()?.toString().orEmpty().startsWith("text/html")) {
                throw IOException("Pixiv returned a webpage instead of media")
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
            if (downloaded <= 0L) throw IOException("Pixiv returned an empty media file")
            return downloaded
        }
    }

    private suspend fun extractUgoiraFrames(
        archive: File,
        framesDirectory: File,
        item: PixivMediaItem,
    ) {
        val expectedNames = item.ugoiraFrames.mapTo(linkedSetOf()) { it.file }
        var extractedBytes = 0L
        ZipInputStream(archive.inputStream().buffered()).use { zip ->
            while (true) {
                coroutineContext.ensureActive()
                val entry = zip.nextEntry ?: break
                val name = entry.name
                if (!entry.isDirectory && name in expectedNames && isSafeFrameName(name)) {
                    val target = File(framesDirectory, name)
                    target.outputStream().buffered().use { output ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        while (true) {
                            coroutineContext.ensureActive()
                            val read = zip.read(buffer)
                            if (read < 0) break
                            extractedBytes += read
                            if (extractedBytes > MAX_EXTRACTED_BYTES) {
                                throw IOException("Pixiv animation is too large to unpack safely")
                            }
                            output.write(buffer, 0, read)
                        }
                    }
                }
                zip.closeEntry()
            }
        }
        val missing = expectedNames.filterNot { File(framesDirectory, it).isFile }
        if (missing.isNotEmpty()) {
            throw IOException("Pixiv animation is missing ${missing.size} frame(s)")
        }
    }

    private fun buildConcatFile(item: PixivMediaItem): String = buildString {
        item.ugoiraFrames.forEach { frame ->
            append("file '")
            append(frame.file)
            append("'\n")
            append("duration ")
            append(String.format(Locale.US, "%.6f", frame.delayMillis / 1000.0))
            append('\n')
        }
        item.ugoiraFrames.lastOrNull()?.let { frame ->
            append("file '")
            append(frame.file)
            append("'\n")
        }
    }

    private suspend fun encodeUgoira(framesDirectory: File, concatFile: File, output: File) {
        val commonArguments =
            listOf(
                "-hide_banner",
                "-loglevel",
                "warning",
                "-y",
                "-f",
                "concat",
                "-safe",
                "0",
                "-i",
                concatFile.absolutePath,
                "-vsync",
                "vfr",
                "-vf",
                "scale=trunc(iw/2)*2:trunc(ih/2)*2,format=yuv420p",
            )
        val attempts =
            listOf(
                commonArguments +
                    listOf(
                        "-c:v",
                        "libx264",
                        "-preset",
                        "veryfast",
                        "-crf",
                        "18",
                        "-movflags",
                        "+faststart",
                        output.absolutePath,
                    ),
                commonArguments +
                    listOf(
                        "-c:v",
                        "mpeg4",
                        "-q:v",
                        "2",
                        "-movflags",
                        "+faststart",
                        output.absolutePath,
                    ),
            )
        var lastLog = ""
        for (arguments in attempts) {
            output.delete()
            val result = runFfmpeg(framesDirectory, arguments)
            lastLog = result.output
            if (result.exitCode == 0 && output.isFile && output.length() > 0L) return
        }
        throw IOException("Unable to convert Pixiv animation: ${lastLog.takeLast(2_000)}")
    }

    private suspend fun runFfmpeg(directory: File, arguments: List<String>): ProcessResult {
        val nativeDirectory = File(context.applicationInfo.nativeLibraryDir)
        val binary = File(nativeDirectory, "libffmpeg.so")
        if (!binary.isFile) throw IOException("Walrus FFmpeg component is unavailable")
        val extractedLibraries =
            File(context.noBackupFilesDir, "youtubedl-android/packages/ffmpeg/usr/lib")
        val process =
            ProcessBuilder(listOf(binary.absolutePath) + arguments)
                .directory(directory)
                .redirectErrorStream(true)
                .apply {
                    environment()["LD_LIBRARY_PATH"] =
                        listOf(extractedLibraries, nativeDirectory)
                            .filter(File::isDirectory)
                            .joinToString(":") { it.absolutePath }
                }
                .start()
        return suspendCancellableCoroutine { continuation ->
            val worker =
                Thread(
                        {
                            try {
                                val output = StringBuilder()
                                process.inputStream.bufferedReader().use { reader ->
                                    reader.forEachLine { line ->
                                        if (output.length < 16_000) output.appendLine(line)
                                    }
                                }
                                val exitCode = process.waitFor()
                                if (continuation.isActive) {
                                    continuation.resume(ProcessResult(exitCode, output.toString()))
                                }
                            } catch (throwable: Throwable) {
                                process.destroy()
                                if (continuation.isActive) {
                                    continuation.resumeWithException(throwable)
                                }
                            }
                        },
                        "Walrus-Pixiv-FFmpeg",
                    )
                    .apply { isDaemon = true }
            continuation.invokeOnCancellation {
                process.destroy()
                worker.interrupt()
            }
            worker.start()
        }
    }

    private fun publishStagedArtwork(
        artwork: PixivArtwork,
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
                        createPendingMediaStoreItem(artwork, staged.item, galleryTimestamp).also {
                            uri ->
                            pendingUris[staged.item.mediaId] = uri
                            createdUris += uri
                        }
                    }
                }

                // Keep every new page pending and invisible until the complete artwork has been
                // copied. Publishing only starts after this loop succeeds for every page.
                stagedItems.forEachIndexed { index, staged ->
                    paths[index] =
                        staged.existingPath
                            ?: pendingUris
                                .getValue(staged.item.mediaId)
                                .also { uri ->
                                    writePreparedMediaStoreItem(
                                        uri,
                                        requireNotNull(staged.preparedFile),
                                        artwork,
                                        staged.item,
                                    )
                                }
                                .toString()
                    progressCallback?.invoke(
                        DOWNLOAD_PHASE_PERCENT +
                            (index + 1f) / stagedItems.size * PUBLISH_COPY_PHASE_PERCENT,
                        completedBytes,
                        "Finalizing artwork",
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
                        "Adding artwork to Gallery",
                    )
                }
            } else {
                stagedItems.forEachIndexed { index, staged ->
                    paths[index] =
                        staged.existingPath
                            ?: publishPreparedLegacyFile(
                                artwork = artwork,
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
                        "Adding artwork to Gallery",
                    )
                }
            }

            val completedPaths = paths.map { requireNotNull(it) }
            applyGalleryOrder(artwork, completedPaths, galleryTimestamp)
            progressCallback?.invoke(100f, completedBytes, "Saved as one artwork")
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
        artwork: PixivArtwork,
        item: PixivMediaItem,
    ) {
        val output =
            context.contentResolver.openOutputStream(uri, "w")
                ?: throw IOException("Unable to save ${orderedFileName(artwork, item)}")
        output.use { preparedFile.inputStream().buffered().use { input -> input.copyTo(it) } }
    }

    private fun publishPreparedLegacyFile(
        artwork: PixivArtwork,
        item: PixivMediaItem,
        preferences: DownloadPreferences,
        preparedFile: File,
        galleryTimestamp: Long,
    ): String {
        val preferred = targetFile(artwork, item, preferences)
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
            if (preferences.contains(LAST_GALLERY_TOP_SECOND)) {
                preferences.getLong(LAST_GALLERY_TOP_SECOND, 0L)
            } else {
                null
            }
        val previousBottom =
            if (preferences.contains(LAST_GALLERY_BOTTOM_SECOND)) {
                preferences.getLong(LAST_GALLERY_BOTTOM_SECOND, 0L)
            } else {
                null
            }
        val block =
            reserveGalleryTimestampBlock(
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

    internal fun reserveGalleryTimestampBlock(
        nowMillis: Long,
        itemCount: Int,
        previousTopSecond: Long?,
        previousBottomSecond: Long?,
    ): GalleryTimestampBlock {
        val count = itemCount.coerceAtLeast(1).toLong()
        val naturalBase = nowMillis / 1_000L
        val naturalTop = naturalBase - 1L
        val naturalBottom = naturalBase - count
        val previousIsValid =
            previousTopSecond != null &&
                previousBottomSecond != null &&
                previousBottomSecond <= previousTopSecond
        val overlapsPrevious =
            previousIsValid &&
                naturalBottom <= requireNotNull(previousTopSecond) &&
                naturalTop >= requireNotNull(previousBottomSecond)
        val base = if (overlapsPrevious) requireNotNull(previousBottomSecond) else naturalBase
        return GalleryTimestampBlock(
            baseSecond = base,
            topSecond = base - 1L,
            bottomSecond = base - count,
        )
    }

    private fun findExisting(
        artwork: PixivArtwork,
        item: PixivMediaItem,
        preferences: DownloadPreferences,
    ): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !preferences.privateDirectory) {
            return findCompletedMediaStoreItem(artwork, item)?.toString()
        }
        return targetFile(artwork, item, preferences)
            .takeIf { it.isFile && it.length() > 0L }
            ?.absolutePath
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun createPendingMediaStoreItem(
        artwork: PixivArtwork,
        item: PixivMediaItem,
        galleryTimestamp: Long,
    ): Uri {
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val displayName = orderedFileName(artwork, item)
        val relativePath = relativePath()
        context.contentResolver.delete(
            collection,
            "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND " +
                "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND " +
                "${MediaStore.MediaColumns.IS_PENDING}=1",
            arrayOf(relativePath, displayName),
        )
        val values =
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, item.mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                val timestampMillis = orderedTimestampMillis(galleryTimestamp, item)
                put(MediaStore.MediaColumns.DATE_ADDED, timestampMillis / 1_000L)
                put(MediaStore.MediaColumns.DATE_MODIFIED, timestampMillis / 1_000L)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        return context.contentResolver.insert(collection, values)
            ?: throw IOException("Unable to create $displayName")
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun findCompletedMediaStoreItem(artwork: PixivArtwork, item: PixivMediaItem): Uri? {
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.SIZE)
        return context.contentResolver
            .query(
                collection,
                projection,
                "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND " +
                    "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND " +
                    "${MediaStore.MediaColumns.IS_PENDING}=0",
                arrayOf(relativePath(), orderedFileName(artwork, item)),
                "${MediaStore.MediaColumns._ID} DESC",
            )
            ?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                while (cursor.moveToNext()) {
                    if (cursor.getLong(sizeColumn) > 0L) {
                        return@use ContentUris.withAppendedId(collection, cursor.getLong(idColumn))
                    }
                }
                null
            }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun publishMediaStoreItem(uri: Uri, item: PixivMediaItem, galleryTimestamp: Long) {
        val timestampMillis = orderedTimestampMillis(galleryTimestamp, item)
        val values =
            ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
                put(MediaStore.MediaColumns.DATE_MODIFIED, timestampMillis / 1_000L)
                if (item.mimeType.startsWith("video/")) {
                    put(MediaStore.Video.VideoColumns.DATE_TAKEN, timestampMillis)
                } else {
                    put(MediaStore.Images.ImageColumns.DATE_TAKEN, timestampMillis)
                }
            }
        if (context.contentResolver.update(uri, values, null, null) <= 0) {
            throw IOException("Unable to publish Pixiv media ${item.mediaId}")
        }
    }

    private fun applyGalleryOrder(
        artwork: PixivArtwork,
        paths: List<String>,
        galleryTimestamp: Long,
    ) {
        artwork.items.zip(paths).forEach { (item, path) ->
            val timestampMillis = orderedTimestampMillis(galleryTimestamp, item)
            val values =
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DATE_ADDED, timestampMillis / 1_000L)
                    put(MediaStore.MediaColumns.DATE_MODIFIED, timestampMillis / 1_000L)
                    if (item.mimeType.startsWith("video/")) {
                        put(MediaStore.Video.VideoColumns.DATE_TAKEN, timestampMillis)
                    } else {
                        put(MediaStore.Images.ImageColumns.DATE_TAKEN, timestampMillis)
                    }
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
    private fun applyPhysicalFileTimestamp(uri: Uri, item: PixivMediaItem, timestampMillis: Long) {
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

    private fun orderedTimestampMillis(galleryTimestamp: Long, item: PixivMediaItem): Long =
        galleryTimestamp - item.index.coerceAtLeast(1) * 1_000L

    private suspend fun mediaRequest(artwork: PixivArtwork, item: PixivMediaItem): Request {
        val userAgent =
            USER_AGENT_STRING.getString().ifBlank {
                System.getProperty("http.agent")
                    ?: "WalrusDownloader/${BuildConfig.VERSION_NAME} Android/${Build.VERSION.RELEASE}"
            }
        return Request.Builder()
            .url(item.mediaUrl)
            .header("User-Agent", userAgent)
            .header("Referer", artwork.sourceUrl)
            .header("Accept", "image/*,video/*,application/zip,*/*")
            .apply {
                DownloadUtil.getCookieHeaderFor(item.mediaUrl).takeIf(String::isNotBlank)?.let {
                    header("Cookie", it)
                }
            }
            .build()
    }

    internal fun orderedFileName(artwork: PixivArtwork, item: PixivMediaItem): String {
        val extension = item.extension.lowercase().ifBlank { "jpg" }
        val base =
            "${sanitizeFileName(artwork.title).take(82)} - by " +
                "${sanitizeFileName(artwork.artist).take(48)} [${artwork.artworkId}]"
        if (item.total <= 1) return "$base.$extension"
        val width = item.total.toString().length.coerceAtLeast(2)
        return "$base - %0${width}d.$extension".format(item.index)
    }

    internal fun sanitizeFileName(value: String): String =
        value
            .replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "_")
            .trim(' ', '.')
            .take(120)
            .ifBlank { "Pixiv artwork" }

    private fun relativePath(): String =
        "${Environment.DIRECTORY_DOWNLOADS}/Walrus Downloader/$DIRECTORY_NAME/"

    private fun targetFile(
        artwork: PixivArtwork,
        item: PixivMediaItem,
        preferences: DownloadPreferences,
    ): File {
        val root =
            File(if (preferences.privateDirectory) App.privateDownloadDir else videoDownloadDir)
        return File(File(root, DIRECTORY_NAME), orderedFileName(artwork, item))
    }

    private fun isSafeFrameName(file: String): Boolean =
        file.isNotBlank() &&
            !file.contains('/') &&
            !file.contains('\\') &&
            file != "." &&
            file != ".."

    private data class PreparedFile(val file: File, val downloadedBytes: Long)

    private data class StagedItem(
        val item: PixivMediaItem,
        val existingPath: String? = null,
        val preparedFile: File? = null,
        val downloadedBytes: Long = 0L,
    )

    internal data class GalleryTimestampBlock(
        val baseSecond: Long,
        val topSecond: Long,
        val bottomSecond: Long,
    )

    private data class ProcessResult(val exitCode: Int, val output: String)
}
