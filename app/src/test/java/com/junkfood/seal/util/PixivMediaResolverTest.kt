package com.junkfood.seal.util

import com.junkfood.seal.download.Task
import com.junkfood.seal.download.TaskFactory
import com.junkfood.seal.ui.page.settings.network.Cookie
import com.junkfood.seal.ui.page.settings.network.toCookieHeader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PixivMediaResolverTest {
    @Test
    fun pixivShareAndLegacyArtworkUrlsAreRecognized() {
        assertEquals(
            "146189949",
            PixivMediaResolver.extractArtworkId("https://www.pixiv.net/en/artworks/146189949"),
        )
        assertEquals(
            "72011782",
            PixivMediaResolver.extractArtworkId(
                "https://www.pixiv.net/member_illust.php?mode=medium&illust_id=72011782"
            ),
        )
        assertTrue(PixivMediaResolver.isPixivArtworkUrl("https://www.pixiv.net/artworks/12345"))
        assertFalse(PixivMediaResolver.isPixivArtworkUrl("https://www.pixiv.net/users/12345"))
        assertFalse(PixivMediaResolver.isPixivArtworkUrl("https://example.com/artworks/12345"))
    }

    @Test
    fun multipageArtworkKeepsPixivPageOrderInOneQueueTask() {
        val artwork =
            PixivMediaResolver.parseArtwork(
                metadataContent = ILLUSTRATION_METADATA,
                pagesContent = MULTIPAGE_RESPONSE,
                ugoiraContent = null,
                canonicalUrl = "https://www.pixiv.net/artworks/12345",
            )

        assertEquals(listOf("12345_p0", "12345_p1", "12345_p2"), artwork.media.map { it.id })
        assertEquals(listOf(1, 2, 3), artwork.media.map { it.index })
        assertEquals(listOf("jpg", "png", "gif"), artwork.media.map { it.extension })
        assertTrue(artwork.media.all { "/img-original/" in it.mediaUrl })

        val task =
            TaskFactory.createFromPixivArtwork(artwork, DownloadUtil.DownloadPreferences.EMPTY)
        val type = task.task.type as Task.TypeInfo.PixivArtwork
        assertEquals(3, type.items.size)
        assertEquals(listOf("12345_p0", "12345_p1", "12345_p2"), type.items.map { it.mediaId })
        assertEquals(Task.DownloadState.ReadyWithInfo, task.state.downloadState)
        assertEquals("Pixiv", task.state.viewState.extractorKey)
    }

    @Test
    fun ugoiraUsesOriginalArchiveAndExactFrameTiming() {
        val artwork =
            PixivMediaResolver.parseArtwork(
                metadataContent = UGOIRA_METADATA,
                pagesContent = null,
                ugoiraContent = UGOIRA_RESPONSE,
                canonicalUrl = "https://www.pixiv.net/artworks/72011782",
            )

        val media = artwork.media.single()
        assertTrue(media.isUgoira)
        assertEquals("video/mp4", media.mimeType)
        assertEquals("https://i.pximg.net/img-zip-ugoira/test.zip", media.mediaUrl)
        assertEquals(listOf("000000.jpg", "000001.jpg"), media.ugoiraFrames.map { it.file })
        assertEquals(listOf(42, 83), media.ugoiraFrames.map { it.delayMillis })
    }

    @Test
    fun capturedPixivCookiesMatchAccountsAndArtworkSubdomains() {
        val cookies =
            Cookie.fromCookieHeader(
                "https://accounts.pixiv.net/login",
                "PHPSESSID=user_session; device_token=abc==",
            )

        assertTrue(cookies.all { it.domain == ".pixiv.net" })
        assertEquals(
            "PHPSESSID=user_session; device_token=abc==",
            cookies.toCookieHeader("https://www.pixiv.net/ajax/illust/12345"),
        )
    }

    private companion object {
        val ILLUSTRATION_METADATA =
            """
            {
              "error": false,
              "body": {
                "id": "12345",
                "illustType": 0,
                "title": "Ordered artwork",
                "userName": "Artist",
                "userId": "54321",
                "pageCount": 3,
                "createDate": "2026-08-16T12:00:00+00:00",
                "urls": {"regular": "https://i.pximg.net/img-master/12345_p0_master1200.jpg"}
              }
            }
            """
                .trimIndent()

        val MULTIPAGE_RESPONSE =
            """
            {
              "error": false,
              "body": [
                {"urls": {"original": "https://i.pximg.net/img-original/12345_p0.jpg"}},
                {"urls": {"original": "https://i.pximg.net/img-original/12345_p1.png"}},
                {"urls": {"original": "https://i.pximg.net/img-original/12345_p2.gif"}}
              ]
            }
            """
                .trimIndent()

        val UGOIRA_METADATA =
            """
            {
              "error": false,
              "body": {
                "id": "72011782",
                "illustType": 2,
                "title": "Animated work",
                "userName": "Animator",
                "userId": "111",
                "pageCount": 1,
                "createDate": "2019-01-01T00:00:00+00:00",
                "urls": {"regular": "https://i.pximg.net/img-master/72011782_p0_master1200.jpg"}
              }
            }
            """
                .trimIndent()

        val UGOIRA_RESPONSE =
            """
            {
              "error": false,
              "body": {
                "src": "https://i.pximg.net/img-zip-ugoira/test600x600.zip",
                "originalSrc": "https://i.pximg.net/img-zip-ugoira/test.zip",
                "frames": [
                  {"file": "000000.jpg", "delay": 42},
                  {"file": "000001.jpg", "delay": 83}
                ]
              }
            }
            """
                .trimIndent()
    }
}
