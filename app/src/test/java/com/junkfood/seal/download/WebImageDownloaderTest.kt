package com.junkfood.seal.download

import com.junkfood.seal.download.Task.TypeInfo.WebImageCollection
import com.junkfood.seal.download.Task.TypeInfo.WebImageItem
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class WebImageDownloaderTest {
    @Test
    fun orderedNamesKeepAWebCollectionTogether() {
        val collection = collection(total = 12)

        val names = collection.items.map { WebImageDownloader.orderedFileName(collection, it) }

        assertEquals(names.sorted(), names)
        assertEquals(12, names.distinct().size)
        assertEquals(true, names.first().contains(" - 01.jpg"))
        assertEquals(true, names.last().contains(" - 12.jpg"))
    }

    @Test
    fun incompleteHighResolutionImageIsRejected() {
        WebImageDownloader.requireCompleteDownload(19_000_000L, 19_000_000L)
        WebImageDownloader.requireCompleteDownload(-1L, 5_000_000L)

        assertThrows(IOException::class.java) {
            WebImageDownloader.requireCompleteDownload(19_000_000L, 5_000_000L)
        }
    }

    private fun collection(total: Int): WebImageCollection =
        WebImageCollection(
            pageId = "abcdef1234567890",
            pageTitle = "A gallery page",
            siteName = "example.com",
            sourceUrl = "https://example.com/gallery",
            items =
                (1..total).map { index ->
                    WebImageItem(
                        mediaId = "image-$index",
                        mediaUrl = "https://cdn.example.com/image-$index.jpg",
                        mimeType = "image/jpeg",
                        extension = "jpg",
                        caption = "Image $index",
                        index = index,
                        total = total,
                    )
                },
        )
}
