package com.junkfood.seal.download

import com.junkfood.seal.download.Task.TypeInfo.PixivArtwork
import com.junkfood.seal.download.Task.TypeInfo.PixivMediaItem
import org.junit.Assert.assertEquals
import org.junit.Test

class PixivMediaDownloaderTest {
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
