package com.junkfood.seal.util

import com.junkfood.seal.download.Task
import com.junkfood.seal.download.TaskFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebImageResolverTest {
    @Test
    fun picksLargestKnownImageCandidatesAndKeepsDocumentOrder() {
        val page =
            WebImageResolver.parsePage(
                html =
                    """
                    <html>
                      <head>
                        <title>Fallback title</title>
                        <meta property="og:title" content="High resolution gallery">
                        <meta property="og:site_name" content="Example Gallery">
                        <meta property="og:image" content="/images/hero-2400.avif">
                      </head>
                      <body>
                        <img class="site-logo" width="64" height="64" src="/logo.png">
                        <main>
                          <picture>
                            <source type="image/avif"
                              srcset="/images/hero-1200.avif 1200w, /images/hero-2400.avif 2400w">
                            <img src="/images/hero-480.jpg" alt="Hero">
                          </picture>
                          <a href="/images/page-two-original.png">
                            <img data-src="/images/page-two-thumb.jpg" alt="Second">
                          </a>
                          <img data-original="https://cdn.example.net/page-three.webp"
                            src="/placeholder.jpg" alt="Third">
                        </main>
                      </body>
                    </html>
                    """
                        .trimIndent(),
                sourceUrl = "https://example.com/gallery/one",
            )

        assertEquals("High resolution gallery", page.title)
        assertEquals("Example Gallery", page.siteName)
        assertTrue(page.isImageFocused)
        assertFalse(page.hasVideoMetadata)
        assertEquals(
            listOf(
                "https://example.com/images/hero-2400.avif",
                "https://example.com/images/page-two-original.png",
                "https://cdn.example.net/page-three.webp",
            ),
            page.media.map { it.mediaUrl },
        )
        assertEquals(listOf("avif", "png", "webp"), page.media.map { it.extension })
        assertEquals(listOf(1, 2, 3), page.media.map { it.index })
    }

    @Test
    fun videoMetadataHandsPageBackToExistingVideoDownloader() {
        val page =
            WebImageResolver.parsePage(
                html =
                    """
                    <html>
                      <head>
                        <meta property="og:type" content="video.other">
                        <meta property="og:video" content="https://cdn.example.com/movie.mp4">
                      </head>
                      <body>
                        <main><img srcset="cover.jpg 600w, cover-large.jpg 1800w"></main>
                      </body>
                    </html>
                    """
                        .trimIndent(),
                sourceUrl = "https://video.example.com/watch/123",
            )

        assertTrue(page.hasVideoMetadata)
        assertFalse(page.isImageFocused)
        assertEquals(
            "https://video.example.com/watch/cover-large.jpg",
            page.media.single().mediaUrl,
        )
    }

    @Test
    fun repeatedMetadataAndInlineUrlsAreDownloadedOnlyOnce() {
        val page =
            WebImageResolver.parsePage(
                html =
                    """
                    <html>
                      <head><meta property="og:image" content="/full/image.jpg"></head>
                      <body><main><img data-original="/full/image.jpg" src="/thumb.jpg"></main></body>
                    </html>
                    """
                        .trimIndent(),
                sourceUrl = "https://example.com/post",
            )

        assertEquals(listOf("https://example.com/full/image.jpg"), page.media.map { it.mediaUrl })
    }

    @Test
    fun collectionIsCappedAndStoredAsOnePersistentQueueTask() {
        val images =
            (1..105).joinToString("") { index ->
                "<img data-original=\"https://cdn.example.com/image-$index.jpg\">"
            }
        val page =
            WebImageResolver.parsePage(
                html = "<html><head><title>Large gallery</title></head><body>$images</body></html>",
                sourceUrl = "https://example.com/large-gallery",
            )

        assertEquals(WebImageResolver.MAX_IMAGES, page.media.size)
        assertEquals("https://cdn.example.com/image-1.jpg", page.media.first().mediaUrl)
        assertEquals("https://cdn.example.com/image-100.jpg", page.media.last().mediaUrl)

        val task = TaskFactory.createFromWebImagePage(page, DownloadUtil.DownloadPreferences.EMPTY)
        val type = task.task.type as Task.TypeInfo.WebImageCollection
        assertEquals(100, type.items.size)
        assertEquals(Task.DownloadState.ReadyWithInfo, task.state.downloadState)
        assertEquals("Web images", task.state.viewState.extractorKey)
    }
}
