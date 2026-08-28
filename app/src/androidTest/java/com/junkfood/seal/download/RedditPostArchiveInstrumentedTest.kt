package com.junkfood.seal.download

import android.content.ContentUris
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.Q)
class RedditPostArchiveInstrumentedTest {
    @Test
    fun embedsCommentsAndPublishesOrderedTranscriptThroughMediaStore() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = context.contentResolver
        val downloads = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/Walrus Downloader/Walrus Reddit QA/"
        val imageName = "walrus-reddit-comments-device-qa.jpg"
        val post =
            Task.RedditPostMetadata(
                postId = "walrus_device_qa",
                postTitle = "Walrus Reddit comments device QA",
                author = "walrus_qa",
                sourceUrl = "https://www.reddit.com/comments/walrus_device_qa",
                createdUtc = 0,
                comments =
                    listOf(
                        Task.RedditComment(
                            id = "first",
                            author = "first_commenter",
                            body = "First comment with Unicode: こんにちは",
                            score = 42,
                            createdUtc = 0,
                            depth = 0,
                        ),
                        Task.RedditComment(
                            id = "reply",
                            author = "reply_commenter",
                            body = "Ordered nested reply",
                            score = 7,
                            createdUtc = 0,
                            depth = 1,
                        ),
                    ),
                totalCommentCount = 2,
            )
        val transcriptName = RedditPostArchive.transcriptFileName(post)
        var imageUri: android.net.Uri? = null
        var transcriptUri: android.net.Uri? = null

        try {
            imageUri =
                resolver.insert(
                    downloads,
                    ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, imageName)
                        put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    },
                )
            assertNotNull("Unable to create QA image", imageUri)
            resolver.openOutputStream(requireNotNull(imageUri), "w")!!.use { output ->
                val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
                try {
                    bitmap.eraseColor(Color.rgb(52, 94, 135))
                    assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output))
                } finally {
                    bitmap.recycle()
                }
            }
            resolver.update(
                requireNotNull(imageUri),
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null,
            )

            RedditPostArchive.attach(
                post = post,
                mediaPaths = listOf(requireNotNull(imageUri).toString()),
                privateDirectory = false,
            )

            val metadata =
                resolver.openFileDescriptor(requireNotNull(imageUri), "r")!!.use { descriptor ->
                    val exif = ExifInterface(descriptor.fileDescriptor)
                    assertEquals("Walrus Downloader", exif.getAttribute(ExifInterface.TAG_SOFTWARE))
                    assertEquals("u/walrus_qa", exif.getAttribute(ExifInterface.TAG_ARTIST))
                    exif.getAttribute(ExifInterface.TAG_USER_COMMENT) to
                        exif.getAttribute(ExifInterface.TAG_XMP)
                }
            val description = metadata.first
            val xmp = metadata.second
            assertNotNull("Reddit comment metadata was not embedded", description)
            assertTrue(
                "Unexpected EXIF user comment: $description",
                requireNotNull(description).contains("First comment with Unicode:"),
            )
            assertTrue(description.contains("Ordered nested reply"))
            assertNotNull("Unicode XMP Reddit comment metadata was not embedded", xmp)
            val decodedXmp =
                DocumentBuilderFactory.newInstance()
                    .apply { isNamespaceAware = true }
                    .newDocumentBuilder()
                    .parse(
                        ByteArrayInputStream(
                            requireNotNull(xmp).toByteArray(StandardCharsets.UTF_8)
                        )
                    )
                    .documentElement
                    .textContent
            assertTrue(
                "Unexpected XMP description: $xmp",
                decodedXmp.contains("First comment with Unicode: こんにちは"),
            )

            transcriptUri = findDownload(relativePath, transcriptName)
            assertNotNull("Companion Reddit comment transcript was not published", transcriptUri)
            val transcript =
                resolver.openInputStream(requireNotNull(transcriptUri))!!.use { input ->
                    input.readBytes().toString(StandardCharsets.UTF_8)
                }
            assertTrue(transcript.contains("Comments (Reddit top order)"))
            assertTrue(
                transcript.indexOf("u/first_commenter") < transcript.indexOf("u/reply_commenter")
            )
            assertTrue(transcript.contains("  2. u/reply_commenter"))
        } finally {
            transcriptUri?.let { resolver.delete(it, null, null) }
            imageUri?.let { resolver.delete(it, null, null) }
            var attempts = 0
            while (attempts < 20) {
                val directoryUri =
                    findDownload(
                        "${Environment.DIRECTORY_DOWNLOADS}/Walrus Downloader/",
                        "Walrus Reddit QA",
                    )
                if (directoryUri != null) {
                    resolver.delete(directoryUri, null, null)
                    break
                }
                Thread.sleep(50)
                attempts++
            }
        }
    }

    private fun findDownload(relativePath: String, displayName: String): android.net.Uri? {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        return context.contentResolver
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
    }
}
