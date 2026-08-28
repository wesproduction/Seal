package com.junkfood.seal.util

import com.junkfood.seal.download.Task
import com.junkfood.seal.download.TaskFactory
import com.junkfood.seal.ui.page.settings.network.Cookie
import com.junkfood.seal.ui.page.settings.network.toCookieHeader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        assertEquals(1, tasks.size)
        val album = tasks.single().task.type as Task.TypeInfo.RedditAlbum
        assertEquals(listOf("second", "first"), album.items.map { it.mediaId })
        assertTrue(album.items.all { it.mimeType.startsWith("image/") })
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
        assertNull(
            RedditMediaResolver.extractFeedTarget(
                "https://www.reddit.com/r/pics/comments/abc123/example/"
            )
        )
        assertNull(
            RedditMediaResolver.extractFeedTarget("https://www.reddit.com/r/pics/s/AbCdEf1234")
        )
        assertEquals(
            RedditMediaResolver.FeedKind.Subreddit,
            RedditMediaResolver.extractFeedTarget("https://www.reddit.com/r/pics/")?.kind,
        )
        assertEquals(
            "Example_User",
            RedditMediaResolver.extractFeedTarget(
                    "https://www.reddit.com/user/Example_User/submitted/"
                )
                ?.name,
        )
    }

    @Test
    fun feedPagePreservesNewestFirstPostAndGalleryOrder() {
        val target =
            requireNotNull(RedditMediaResolver.extractFeedTarget("https://www.reddit.com/r/pics/"))
        val page = RedditMediaResolver.parseFeedPage(FEED_JSON, target)

        assertEquals("t3_next", page.after)
        assertEquals(listOf("gallery1", "video1", "text1"), page.posts.map { it.id })
        assertEquals(listOf("second", "first"), page.posts.first().media.map { it.id })
        assertTrue(page.posts[1].isVideoPost)
        assertFalse(page.posts[2].isDownloadablePost)

        val feed =
            RedditMediaResolver.RedditFeed(
                target = target,
                posts = page.posts.filter { it.isDownloadablePost },
                scannedPosts = page.posts.size,
            )
        val tasks = TaskFactory.createFromRedditFeed(feed, DownloadUtil.DownloadPreferences.EMPTY)
        assertEquals(2, tasks.size)
        val galleryAlbum = tasks.first().task.type as Task.TypeInfo.RedditAlbum
        assertEquals(listOf("second", "first"), galleryAlbum.items.map { it.mediaId })
        assertEquals("pics", galleryAlbum.collectionName)
        assertEquals(1, galleryAlbum.collectionIndex)
        assertEquals(2, galleryAlbum.collectionTotal)
        assertEquals(
            "Walrus Reddit/pics/02 - Native video [video1].%(ext)s",
            tasks.last().task.preferences.outputTemplate,
        )

        val separateTasks =
            TaskFactory.createFromRedditFeed(
                feed,
                DownloadUtil.DownloadPreferences.EMPTY.copy(redditSeparatePostFolders = true),
            )
        assertTrue(
            separateTasks
                .last()
                .task
                .preferences
                .outputTemplate
                .startsWith("Walrus Reddit/pics/02 - Native video [video1]/")
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

    @Test
    fun postCommentsKeepTopOrderAndReplyDepthInTheQueuedTask() {
        val post =
            RedditMediaResolver.parsePostJson(
                content = COMMENT_JSON,
                canonicalUrl = "https://www.reddit.com/r/pics/comments/withcomments/example/",
            )

        assertEquals(4, post.totalCommentCount)
        assertEquals(listOf("top", "reply", "second"), post.comments.map { it.id })
        assertEquals(listOf(0, 1, 0), post.comments.map { it.depth })
        assertEquals("Unicode comment: こんにちは", post.comments[1].body)

        val task =
            TaskFactory.createFromRedditPost(post, DownloadUtil.DownloadPreferences.EMPTY)
                .single()
                .task
        assertEquals(post.id, task.redditPost?.postId)
        assertEquals(post.comments.size, task.redditPost?.comments?.size)
        assertEquals(4, task.redditPost?.totalCommentCount)
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

        val FEED_JSON =
            """
            {
              "data": {
                "after": "t3_next",
                "children": [
                  {
                    "data": {
                      "id": "gallery1",
                      "title": "Newest gallery",
                      "author": "tester",
                      "created_utc": 1700000300,
                      "permalink": "/r/pics/comments/gallery1/newest_gallery/",
                      "url": "https://www.reddit.com/gallery/gallery1",
                      "gallery_data": {
                        "items": [
                          {"media_id": "second"},
                          {"media_id": "first"}
                        ]
                      },
                      "media_metadata": {
                        "first": {
                          "status": "valid",
                          "m": "image/png",
                          "s": {"u": "https://preview.redd.it/first.png"}
                        },
                        "second": {
                          "status": "valid",
                          "m": "image/jpeg",
                          "s": {"u": "https://preview.redd.it/second.jpg"}
                        }
                      }
                    }
                  },
                  {
                    "data": {
                      "id": "video1",
                      "title": "Native video",
                      "author": "tester",
                      "created_utc": 1700000200,
                      "permalink": "/r/pics/comments/video1/native_video/",
                      "is_video": true,
                      "post_hint": "hosted:video"
                    }
                  },
                  {
                    "data": {
                      "id": "text1",
                      "title": "Text only",
                      "author": "tester",
                      "created_utc": 1700000100,
                      "permalink": "/r/pics/comments/text1/text_only/",
                      "is_self": true
                    }
                  }
                ]
              }
            }
            """
                .trimIndent()

        val COMMENT_JSON =
            """
            [
              {
                "data": {
                  "children": [
                    {
                      "data": {
                        "id": "withcomments",
                        "title": "Post with comments",
                        "author": "poster",
                        "created_utc": 1700000000,
                        "num_comments": 4,
                        "url": "https://i.redd.it/comment-test.jpg"
                      }
                    }
                  ]
                }
              },
              {
                "data": {
                  "children": [
                    {
                      "kind": "t1",
                      "data": {
                        "id": "top",
                        "author": "first_user",
                        "body": "Top comment",
                        "score": 42,
                        "created_utc": 1700000100,
                        "permalink": "/r/pics/comments/withcomments/example/top/",
                        "replies": {
                          "data": {
                            "children": [
                              {
                                "kind": "t1",
                                "data": {
                                  "id": "reply",
                                  "author": "reply_user",
                                  "body": "Unicode comment: こんにちは",
                                  "score": 7,
                                  "created_utc": 1700000200,
                                  "replies": ""
                                }
                              }
                            ]
                          }
                        }
                      }
                    },
                    {
                      "kind": "t1",
                      "data": {
                        "id": "second",
                        "author": "second_user",
                        "body": "Second top-level comment",
                        "score": 5,
                        "created_utc": 1700000300,
                        "replies": ""
                      }
                    },
                    {
                      "kind": "more",
                      "data": {"count": 1}
                    }
                  ]
                }
              }
            ]
            """
                .trimIndent()
    }
}
