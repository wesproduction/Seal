package com.junkfood.seal.download

import com.junkfood.seal.download.Task.TypeInfo.PixivArtwork
import com.junkfood.seal.download.Task.TypeInfo.PixivMediaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PixivMediaDownloaderTest {
    @Test
    fun completeOriginalAssetRequiresEveryAdvertisedByte() {
        PixivMediaDownloader.requireCompleteDownload(
            expectedBytes = 19_000_000L,
            downloadedBytes = 19_000_000L,
        )
        PixivMediaDownloader.requireCompleteDownload(
            expectedBytes = -1L,
            downloadedBytes = 3_000_000L,
        )

        assertThrows(java.io.IOException::class.java) {
            PixivMediaDownloader.requireCompleteDownload(
                expectedBytes = 19_000_000L,
                downloadedBytes = 5_000_000L,
            )
        }
    }

    @Test
    fun multipageNamesStayTogetherAndSortLexically() {
        val artwork = artwork(total = 22)

        assertEquals(
            "Artwork_ name - by Artist_ name [12345] - 01.jpg",
            PixivMediaDownloader.orderedFileName(artwork, artwork.items.first()),
        )
        assertEquals(
            "Artwork_ name - by Artist_ name [12345] - 22.jpg",
            PixivMediaDownloader.orderedFileName(artwork, artwork.items.last()),
        )
    }

    @Test
    fun singleAnimationUsesOneGalleryFriendlyVideoName() {
        val artwork = artwork(total = 1)
        val animation = artwork.items.single().copy(extension = "mp4", mimeType = "video/mp4")

        assertEquals(
            "Artwork_ name - by Artist_ name [12345].mp4",
            PixivMediaDownloader.orderedFileName(artwork, animation),
        )
    }

    @Test
    fun concurrentGalleryReservationsNeverOverlapOrInterleave() {
        val first =
            PixivMediaDownloader.reserveGalleryTimestampBlock(
                nowMillis = 1_000_000L,
                itemCount = 12,
                previousTopSecond = null,
                previousBottomSecond = null,
            )
        val second =
            PixivMediaDownloader.reserveGalleryTimestampBlock(
                nowMillis = 1_001_000L,
                itemCount = 12,
                previousTopSecond = first.topSecond,
                previousBottomSecond = first.bottomSecond,
            )

        assertTrue(second.topSecond < first.bottomSecond)
        val galleryOrder =
            buildList {
                    (1..12).forEach { page -> add("first" to (first.baseSecond - page)) }
                    (1..12).forEach { page -> add("second" to (second.baseSecond - page)) }
                }
                .sortedByDescending { it.second }
                .map { it.first }
        assertEquals(List(12) { "first" } + List(12) { "second" }, galleryOrder)
    }

    @Test
    fun laterGalleryUsesCurrentTimeAfterPreviousBlockCanNoLongerOverlap() {
        val previous =
            PixivMediaDownloader.reserveGalleryTimestampBlock(
                nowMillis = 1_000_000L,
                itemCount = 12,
                previousTopSecond = null,
                previousBottomSecond = null,
            )
        val later =
            PixivMediaDownloader.reserveGalleryTimestampBlock(
                nowMillis = 1_020_000L,
                itemCount = 12,
                previousTopSecond = previous.topSecond,
                previousBottomSecond = previous.bottomSecond,
            )

        assertEquals(1_020L, later.baseSecond)
        assertTrue(later.bottomSecond > previous.topSecond)
    }

    private fun artwork(total: Int): PixivArtwork =
        PixivArtwork(
            artworkId = "12345",
            title = "Artwork: name",
            artist = "Artist? name",
            artistId = "9",
            sourceUrl = "https://www.pixiv.net/artworks/12345",
            createdAtMillis = 0L,
            items =
                (1..total).map { index ->
                    PixivMediaItem(
                        mediaId = "12345_p${index - 1}",
                        mediaUrl = "https://i.pximg.net/img-original/12345_p${index - 1}.jpg",
                        mimeType = "image/jpeg",
                        extension = "jpg",
                        index = index,
                        total = total,
                    )
                },
        )
}
