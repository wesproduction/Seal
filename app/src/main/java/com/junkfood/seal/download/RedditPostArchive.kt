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
import androidx.exifinterface.media.ExifInterface
import com.junkfood.seal.App.Companion.context
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Adds a portable Reddit discussion archive to media without changing its pixels or codecs. */
object RedditPostArchive {
    private const val TAG = "RedditPostArchive"
    private const val MAX_EMBEDDED_UTF8_BYTES = 16_000
    private const val MAX_IMAGE_DESCRIPTION_CHARS = 12_000
    private val transcriptMutex = Mutex()

    suspend fun attach(
        post: Task.RedditPostMetadata,
        mediaPaths: List<String>,
        privateDirectory: Boolean,
    ) =
        withContext(Dispatchers.IO) {
            if (mediaPaths.isEmpty()) return@withContext
            val transcriptName = transcriptFileName(post)
            val transcript = buildTranscript(post)
            val embedded =
                buildEmbeddedDescription(
                    post = post,
                    transcriptName = transcriptName,
                    maxUtf8Bytes = MAX_EMBEDDED_UTF8_BYTES,
                )

            mediaPaths.forEach { path ->
                runCatching { attachToMedia(path, post, embedded) }
                    .onFailure { throwable ->
                        Log.w(TAG, "Unable to embed Reddit comments in $path", throwable)
                    }
            }
            runCatching {
                    transcriptMutex.withLock {
                        writeTranscript(
                            post = post,
                            transcriptName = transcriptName,
                            transcript = transcript,
                            firstMediaPath = mediaPaths.first(),
                            privateDirectory = privateDirectory,
                        )
                    }
                }
                .onFailure { throwable ->
                    Log.w(
                        TAG,
                        "Unable to save Reddit comment transcript for ${post.postId}",
                        throwable,
                    )
                }
        }

    internal fun buildTranscript(post: Task.RedditPostMetadata): String = buildString {
        appendLine("Reddit post")
        appendLine("Title: ${post.postTitle}")
        appendLine("Author: ${redditAuthor(post.author)}")
        appendLine("Source: ${post.sourceUrl}")
        appendLine("Comments saved: ${post.comments.size} of ${post.totalCommentCount}")
        appendLine()
        if (post.comments.isEmpty()) {
            appendLine("No comments were available when Walrus downloaded this post.")
        } else {
            appendLine("Comments (Reddit top order)")
            appendLine("----------------------------------------")
            post.comments.forEachIndexed { index, comment ->
                val indent = "  ".repeat(comment.depth.coerceIn(0, 12))
                append(indent)
                append(index + 1)
                append(". ")
                append(redditAuthor(comment.author))
                append(" · ")
                append(comment.score)
                appendLine(" points")
                comment.body.lineSequence().forEach { line ->
                    append(indent)
                    append("   ")
                    appendLine(line)
                }
                if (comment.permalink.isNotBlank()) {
                    append(indent)
                    append("   ")
                    appendLine(comment.permalink)
                }
                appendLine()
            }
        }
        if (post.totalCommentCount > post.comments.size) {
            appendLine(
                "Reddit reported ${post.totalCommentCount} comments; this transcript contains the " +
                    "first ${post.comments.size} returned by its top-comment listing."
            )
        }
    }

    internal fun buildEmbeddedDescription(
        post: Task.RedditPostMetadata,
        transcriptName: String = transcriptFileName(post),
        maxUtf8Bytes: Int = MAX_EMBEDDED_UTF8_BYTES,
    ): String {
        val value = buildString {
            appendLine("Reddit comments · ${post.comments.size} of ${post.totalCommentCount} saved")
            appendLine(post.postTitle)
            appendLine("Posted by ${redditAuthor(post.author)}")
            appendLine(post.sourceUrl)
            appendLine("Full transcript: $transcriptName")
            appendLine()
            post.comments.forEach { comment ->
                append("  ".repeat(comment.depth.coerceIn(0, 12)))
                append(redditAuthor(comment.author))
                append(" · ")
                append(comment.score)
                appendLine(" points")
                append("  ".repeat(comment.depth.coerceIn(0, 12)))
                appendLine(comment.body)
                appendLine()
            }
            if (post.comments.isEmpty()) {
                append("No comments were available when Walrus downloaded this post.")
            }
        }
        return truncateUtf8(value, maxUtf8Bytes)
    }

    internal fun transcriptFileName(post: Task.RedditPostMetadata): String =
        "${RedditMediaDownloader.sanitizeFileName(post.postTitle).take(88)} " +
            "[${post.postId}] - comments.txt"

    private suspend fun attachToMedia(
        path: String,
        post: Task.RedditPostMetadata,
        embeddedDescription: String,
    ) {
        val target = resolveMediaTarget(path)
        when {
            target.mimeType.startsWith("image/") ->
                attachImageMetadata(target, post, embeddedDescription)
            target.mimeType.startsWith("video/") ->
                attachVideoMetadata(target, post, embeddedDescription)
        }
    }

    private fun attachImageMetadata(
        target: MediaTarget,
        post: Task.RedditPostMetadata,
        embeddedDescription: String,
    ) {
        if (target.mimeType !in setOf("image/jpeg", "image/png", "image/webp")) return
        val originalModified = target.file?.lastModified()?.takeIf { it > 0L }
        val imageDescription =
            embeddedDescription
                .map { character ->
                    if (
                        character == '\n' ||
                            character == '\r' ||
                            character == '\t' ||
                            character.code in 32..126
                    ) {
                        character
                    } else {
                        '?'
                    }
                }
                .joinToString("")
                .take(MAX_IMAGE_DESCRIPTION_CHARS)

        val descriptor = target.uri?.let { context.contentResolver.openFileDescriptor(it, "rw") }
        try {
            val exif =
                when {
                    descriptor != null -> ExifInterface(descriptor.fileDescriptor)
                    target.file != null -> ExifInterface(target.file)
                    else -> return
                }
            exif.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, imageDescription)
            // EXIF's UserComment encoding is inconsistently implemented by Android gallery
            // stacks. Keep an ASCII copy for broad compatibility and an XMP dc:description
            // copy whose XML character references preserve the complete Unicode text.
            exif.setAttribute(ExifInterface.TAG_USER_COMMENT, imageDescription)
            exif.setAttribute(ExifInterface.TAG_XMP, buildXmpDescription(embeddedDescription))
            post.author.takeIf(String::isNotBlank)?.let {
                exif.setAttribute(ExifInterface.TAG_ARTIST, redditAuthor(it))
            }
            exif.setAttribute(ExifInterface.TAG_COPYRIGHT, "Reddit post ${post.sourceUrl}")
            exif.setAttribute(ExifInterface.TAG_SOFTWARE, "Walrus Downloader")
            exif.saveAttributes()
        } finally {
            descriptor?.close()
        }
        rescan(target, originalModified)
    }

    private suspend fun attachVideoMetadata(
        target: MediaTarget,
        post: Task.RedditPostMetadata,
        embeddedDescription: String,
    ) {
        val input = target.file ?: return
        if (!input.isFile || input.length() <= 0L) return
        val originalModified = input.lastModified().takeIf { it > 0L }
        val extension = input.extension.ifBlank { "mp4" }
        val output =
            File(input.parentFile, ".${input.nameWithoutExtension}.walrus-comments.$extension")
        val arguments =
            mutableListOf(
                    "-hide_banner",
                    "-loglevel",
                    "warning",
                    "-y",
                    "-i",
                    input.absolutePath,
                    "-map",
                    "0",
                    "-map_metadata",
                    "0",
                    "-c",
                    "copy",
                    "-metadata",
                    "title=${post.postTitle}",
                    "-metadata",
                    "artist=${redditAuthor(post.author)}",
                    "-metadata",
                    "description=$embeddedDescription",
                    "-metadata",
                    "comment=$embeddedDescription",
                    "-metadata",
                    "purl=${post.sourceUrl}",
                )
                .apply {
                    if (extension.lowercase() in setOf("mp4", "m4v", "mov")) {
                        addAll(listOf("-movflags", "+faststart"))
                    }
                    add(output.absolutePath)
                }

        output.delete()
        val result = runFfmpeg(input.parentFile ?: context.cacheDir, arguments)
        if (result.exitCode != 0 || !output.isFile || output.length() <= 0L) {
            output.delete()
            throw IOException("Unable to embed Reddit comments: ${result.output.takeLast(2_000)}")
        }
        replaceAtomically(input, output)
        rescan(target.copy(file = input), originalModified)
    }

    private fun replaceAtomically(input: File, replacement: File) {
        val backup = File(input.parentFile, ".${input.name}.walrus-backup")
        backup.delete()
        if (!input.renameTo(backup)) {
            replacement.delete()
            throw IOException("Unable to prepare ${input.name} for Reddit metadata")
        }
        if (!replacement.renameTo(input)) {
            backup.renameTo(input)
            replacement.delete()
            throw IOException("Unable to finish Reddit metadata for ${input.name}")
        }
        backup.delete()
    }

    private fun writeTranscript(
        post: Task.RedditPostMetadata,
        transcriptName: String,
        transcript: String,
        firstMediaPath: String,
        privateDirectory: Boolean,
    ) {
        val target = resolveMediaTarget(firstMediaPath)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !privateDirectory) {
            val relativePath =
                target.relativePath
                    ?: target.file?.let(::relativePathForFile)
                    ?: "${Environment.DIRECTORY_DOWNLOADS}/Walrus Downloader/" +
                        "${RedditMediaDownloader.DIRECTORY_NAME}/"
            writeMediaStoreTranscript(relativePath, transcriptName, transcript)
            return
        }
        val directory = target.file?.parentFile ?: return
        directory.mkdirs()
        File(directory, transcriptName).writeText(transcript, StandardCharsets.UTF_8)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun writeMediaStoreTranscript(
        relativePath: String,
        displayName: String,
        transcript: String,
    ) {
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val normalizedPath = relativePath.trimEnd('/') + '/'
        var uri = findDownload(collection, normalizedPath, displayName)
        var inserted = false
        if (uri == null) {
            uri =
                context.contentResolver.insert(
                    collection,
                    ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                        put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, normalizedPath)
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    },
                ) ?: throw IOException("Unable to create $displayName")
            inserted = true
        }
        try {
            context.contentResolver.openOutputStream(uri, "wt")?.use { output ->
                output.write(transcript.toByteArray(StandardCharsets.UTF_8))
            } ?: throw IOException("Unable to write $displayName")
            context.contentResolver.update(
                uri,
                ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                    put(MediaStore.MediaColumns.DATE_MODIFIED, System.currentTimeMillis() / 1_000L)
                },
                null,
                null,
            )
        } catch (throwable: Throwable) {
            if (inserted) context.contentResolver.delete(uri, null, null)
            throw throwable
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun findDownload(collection: Uri, relativePath: String, displayName: String): Uri? =
        context.contentResolver
            .query(
                collection,
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND " +
                    "${MediaStore.MediaColumns.DISPLAY_NAME}=?",
                arrayOf(relativePath, displayName),
                "${MediaStore.MediaColumns._ID} DESC",
            )
            ?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                ContentUris.withAppendedId(
                    collection,
                    cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)),
                )
            }

    @Suppress("DEPRECATION")
    private fun resolveMediaTarget(path: String): MediaTarget {
        val parsed = runCatching { Uri.parse(path) }.getOrNull()
        if (parsed?.scheme == "content") {
            return context.contentResolver
                .query(
                    parsed,
                    arrayOf(
                        MediaStore.MediaColumns.DATA,
                        MediaStore.MediaColumns.MIME_TYPE,
                        MediaStore.MediaColumns.RELATIVE_PATH,
                    ),
                    null,
                    null,
                    null,
                )
                ?.use { cursor ->
                    if (!cursor.moveToFirst()) return@use null
                    val dataIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                    val mimeIndex = cursor.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
                    val relativeIndex = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                    MediaTarget(
                        uri = parsed,
                        file =
                            dataIndex
                                .takeIf { it >= 0 }
                                ?.let(cursor::getString)
                                ?.takeIf(String::isNotBlank)
                                ?.let(::File),
                        mimeType =
                            mimeIndex
                                .takeIf { it >= 0 }
                                ?.let(cursor::getString)
                                .orEmpty()
                                .ifBlank { mimeTypeForPath(path) },
                        relativePath =
                            relativeIndex
                                .takeIf { it >= 0 }
                                ?.let(cursor::getString)
                                ?.takeIf(String::isNotBlank),
                    )
                } ?: MediaTarget(parsed, null, mimeTypeForPath(path), null)
        }
        val file = File(path)
        return MediaTarget(
            uri = null,
            file = file,
            mimeType = mimeTypeForPath(file.name),
            relativePath = relativePathForFile(file),
        )
    }

    private fun rescan(target: MediaTarget, originalModified: Long?) {
        val file = target.file ?: return
        originalModified?.let(file::setLastModified)
        MediaScannerConnection.scanFile(
            context,
            arrayOf(file.absolutePath),
            arrayOf(target.mimeType),
            null,
        )
    }

    private fun relativePathForFile(file: File): String? {
        val root =
            Environment.getExternalStorageDirectory().absolutePath.trimEnd(File.separatorChar)
        val parent = file.parentFile?.absolutePath ?: return null
        if (!parent.startsWith(root, ignoreCase = true)) return null
        return parent.removePrefix(root).trimStart(File.separatorChar).replace('\\', '/') + '/'
    }

    private fun mimeTypeForPath(path: String): String =
        when (path.substringBefore('?').substringAfterLast('.', "").lowercase()) {
            "jpg",
            "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "mp4",
            "m4v" -> "video/mp4"
            "mov" -> "video/quicktime"
            "webm" -> "video/webm"
            "mkv" -> "video/x-matroska"
            else -> "application/octet-stream"
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
                        "Walrus-Reddit-Metadata",
                    )
                    .apply { isDaemon = true }
            continuation.invokeOnCancellation {
                process.destroy()
                worker.interrupt()
            }
            worker.start()
        }
    }

    private fun truncateUtf8(value: String, maxBytes: Int): String {
        if (maxBytes <= 0) return ""
        if (value.toByteArray(StandardCharsets.UTF_8).size <= maxBytes) return value
        val suffix = "\n\n[More comments are available in the companion transcript file.]"
        val suffixBytes = suffix.toByteArray(StandardCharsets.UTF_8).size
        val contentBudget = (maxBytes - suffixBytes).coerceAtLeast(0)
        val builder = StringBuilder()
        var byteCount = 0
        var index = 0
        while (index < value.length) {
            val codePoint = value.codePointAt(index)
            val text = String(Character.toChars(codePoint))
            val bytes = text.toByteArray(StandardCharsets.UTF_8).size
            if (byteCount + bytes <= contentBudget) {
                builder.append(text)
                byteCount += bytes
            }
            index += Character.charCount(codePoint)
        }
        return builder.append(suffix.takeIf { suffixBytes <= maxBytes }.orEmpty()).toString()
    }

    internal fun buildXmpDescription(description: String): String =
        """
        <x:xmpmeta xmlns:x="adobe:ns:meta/">
          <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
            <rdf:Description rdf:about="" xmlns:dc="http://purl.org/dc/elements/1.1/">
              <dc:description>
                <rdf:Alt><rdf:li xml:lang="x-default">${escapeXml(description)}</rdf:li></rdf:Alt>
              </dc:description>
            </rdf:Description>
          </rdf:RDF>
        </x:xmpmeta>
        """
            .trimIndent()

    private fun escapeXml(value: String): String =
        buildString(value.length) {
            var index = 0
            while (index < value.length) {
                val codePoint = value.codePointAt(index)
                when (codePoint) {
                    '&'.code -> append("&amp;")
                    '<'.code -> append("&lt;")
                    '>'.code -> append("&gt;")
                    '"'.code -> append("&quot;")
                    '\''.code -> append("&apos;")
                    '\t'.code,
                    '\n'.code,
                    '\r'.code,
                    in 32..126 -> appendCodePoint(codePoint)
                    else -> {
                        val xmlCodePoint =
                            codePoint.takeIf {
                                it in 0x20..0xD7FF ||
                                    it in 0xE000..0xFFFD ||
                                    it in 0x10000..0x10FFFF
                            } ?: 0xFFFD
                        append("&#").append(xmlCodePoint).append(';')
                    }
                }
                index += Character.charCount(codePoint)
            }
        }

    private fun redditAuthor(author: String): String =
        author.takeIf(String::isNotBlank)?.let { "u/$it" } ?: "u/[deleted]"

    private data class MediaTarget(
        val uri: Uri?,
        val file: File?,
        val mimeType: String,
        val relativePath: String?,
    )

    private data class ProcessResult(val exitCode: Int, val output: String)
}
