package com.junkfood.seal.download

import com.junkfood.seal.download.Task.TypeInfo.RedditMedia
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Test

class RedditMediaDownloaderTest {
    @Test
    fun albumFileNamesAreZeroPaddedInGalleryOrder() {
        val tenth = media(index = 10, total = 12, caption = "A: caption?")
        val first = media(index = 1, total = 12, caption = "")

        assertEquals("10 - A_ caption_.jpg", RedditMediaDownloader.orderedFileName(tenth))
        assertEquals("01 - media-1.jpg", RedditMediaDownloader.orderedFileName(first))
    }

    @Test
    fun inaccessibleCollisionUsesAUniqueNameInsteadOfFailing() {
        val directory = Files.createTempDirectory("reddit-media-test").toFile()
        try {
            val occupied = directory.resolve("01 - photo.jpg").apply { mkdir() }
            occupied.resolve("owned-by-another-install").writeText("keep")

            assertEquals(
                "01 - photo (2).jpg",
                RedditMediaDownloader.availableTargetFile(occupied).name,
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun rejectedFinalRenameFallsBackToAUniqueName() {
        val directory = Files.createTempDirectory("reddit-media-finish-test").toFile()
        try {
            val occupied = directory.resolve("01 - photo.jpg").apply { writeText("existing") }
            val partial = directory.resolve("01 - photo.jpg.part").apply { writeText("new") }

            assertEquals(
                "01 - photo (2).jpg",
                RedditMediaDownloader.finishPartialFile(partial, occupied).name,
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun media(index: Int, total: Int, caption: String) =
        RedditMedia(
            mediaId = "media-$index",
            mediaUrl = "https://i.redd.it/media-$index.jpg",
            mimeType = "image/jpeg",
            extension = "jpg",
            postId = "post",
            postTitle = "Album",
            author = "author",
            caption = caption,
            sourceUrl = "https://www.reddit.com/comments/post",
            index = index,
            total = total,
            createdUtc = 0,
        )
}
