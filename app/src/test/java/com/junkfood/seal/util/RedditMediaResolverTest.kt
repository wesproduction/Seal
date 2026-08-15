package com.junkfood.seal.util

import com.junkfood.seal.download.Task
import com.junkfood.seal.download.TaskFactory
import com.junkfood.seal.ui.page.settings.network.Cookie
import com.junkfood.seal.ui.page.settings.network.toCookieHeader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RedditMediaResolverTest {
    @Test
    fun galleryUsesGalleryDataOrderInsteadOfMetadataMapOrder() {
        val post =
            RedditMediaResolver.parsePostJson(
                content = GALLERY_JSON,
                canonicalUrl = "https://www.reddit.com/r/pics/comments/abc123/example/",
            )

        assertEquals(listOf("second", "first"), post.media.map { it.id })
        assertEquals(listOf(1, 2), post.media.map { it.index })
        assertEquals("https://i.redd.it/second.jpg", post.media[0].url)

        val tasks = TaskFactory.createFromRedditPost(post, DownloadUtil.DownloadPreferences.EMPTY)
        assertEquals(
            listOf("second", "first"),
            tasks.map { (it.task.type as Task.TypeInfo.RedditMedia).mediaId },
        )
        assertTrue(
            tasks.all { (it.task.type as Task.TypeInfo.RedditMedia).mimeType.startsWith("image/") }
        )
    }

    @Test
    fun redditShareAndCanonicalUrlsAreRecognized() {
        assertTrue(RedditMediaResolver.isRedditUrl("https://www.reddit.com/r/pics/s/AbCdEf1234"))
        assertEquals(
            "abc123",
            RedditMediaResolver.extractPostId(
                "https://old.reddit.com/r/pics/comments/abc123/example/"
            ),
        )
    }

    @Test
    fun cookieParsingPreservesEqualsAndMatchesRedditSubdomains() {
        val cookies =
            Cookie.fromCookieHeader("https://www.reddit.com/", "reddit_session=abc==; token=value")

        assertEquals("abc==", cookies.first { it.name == "reddit_session" }.value)
        assertEquals(
            "reddit_session=abc==; token=value",
            cookies.toCookieHeader("https://old.reddit.com/comments/abc123"),
        )
    }

    private companion object {
        val GALLERY_JSON =
            """
            [
              {
                "data": {
                  "children": [
                    {
                      "data": {
                        "id": "abc123",
                        "title": "An ordered album",
                        "author": "tester",
                        "created_utc": 1700000000,
                        "url": "https://www.reddit.com/gallery/abc123",
                        "gallery_data": {
                          "items": [
                            {"media_id": "second", "caption": "Page one"},
                            {"media_id": "first", "caption": "Page two"}
                          ]
                        },
                        "media_metadata": {
                          "first": {
                            "status": "valid",
                            "m": "image/png",
                            "s": {"u": "https://preview.redd.it/first.png?width=1000&amp;format=png"}
                          },
                          "second": {
                            "status": "valid",
                            "m": "image/jpeg",
                            "s": {"u": "https://preview.redd.it/second.jpg?width=1000&amp;format=pjpg"}
                          }
                        }
                      }
                    }
                  ]
                }
              }
            ]
            """
                .trimIndent()
    }
}
