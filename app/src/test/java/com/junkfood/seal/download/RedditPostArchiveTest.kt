package com.junkfood.seal.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RedditPostArchiveTest {
    @Test
    fun transcriptPreservesCommentOrderRepliesScoresAndSource() {
        val transcript = RedditPostArchive.buildTranscript(post())

        assertTrue(transcript.contains("Title: A post"))
        assertTrue(transcript.contains("Author: u/poster"))
        assertTrue(transcript.contains("Comments saved: 2 of 3"))
        assertTrue(
            transcript.indexOf("u/first · 42 points") < transcript.indexOf("u/reply · 7 points")
        )
        assertTrue(transcript.contains("  2. u/reply · 7 points"))
        assertTrue(transcript.contains("Reddit reported 3 comments"))
    }

    @Test
    fun portableCaptionIsMultilineReadableAndStaysWithinGooglePhotosLimit() {
        val post =
            post()
                .copy(
                    comments =
                        List(20) { index ->
                            Task.RedditComment(
                                id = "$index",
                                author = "user$index",
                                body = "Long Unicode body こんにちは ".repeat(20),
                                score = index,
                                createdUtc = 0,
                                depth = 0,
                            )
                        },
                    totalCommentCount = 20,
                )
        val description =
            RedditPostArchive.buildPortableCaption(
                post = post,
                transcriptName = "A post [abc] - comments.txt",
                maxCharacters = 600,
            )

        assertTrue(description.contains("Comments (20 of 20, top order)"))
        assertTrue(description.contains("\n1. u/user0 (0 points)\n"))
        assertTrue(description.contains("\n   Long Unicode body"))
        assertTrue(description.contains("[More comments are in the full transcript.]"))
        assertTrue(description.contains("Full comments: A post [abc] - comments.txt"))
        assertTrue(description.contains("Source: https://www.reddit.com/comments/abc"))
        assertTrue(description.codePointCount(0, description.length) <= 600)
        assertEquals("A post [abc] - comments.txt", RedditPostArchive.transcriptFileName(post))
    }

    @Test
    fun portableCaptionIndentsEveryLineOfNestedReplies() {
        val description = RedditPostArchive.buildPortableCaption(post())

        assertTrue(description.contains("\n1. u/first (42 points)\n   Top comment\n"))
        assertTrue(description.contains("\n  2. u/reply (7 points)\n     Reply comment\n"))
        assertTrue(description.contains("Full comments: A post [abc] - comments.txt"))
    }

    @Test
    fun xmpDescriptionPreservesUnicodeAndEscapesXml() {
        val xmp = RedditPostArchive.buildXmpDescription("Unicode こんにちは & <comments>")

        assertTrue(
            xmp.contains("Unicode &#12371;&#12435;&#12395;&#12385;&#12399; &amp; &lt;comments&gt;")
        )
        assertTrue(xmp.contains("<dc:description>"))
    }

    private fun post() =
        Task.RedditPostMetadata(
            postId = "abc",
            postTitle = "A post",
            author = "poster",
            sourceUrl = "https://www.reddit.com/comments/abc",
            createdUtc = 0,
            comments =
                listOf(
                    Task.RedditComment(
                        id = "first",
                        author = "first",
                        body = "Top comment",
                        score = 42,
                        createdUtc = 0,
                        depth = 0,
                    ),
                    Task.RedditComment(
                        id = "reply",
                        author = "reply",
                        body = "Reply comment",
                        score = 7,
                        createdUtc = 0,
                        depth = 1,
                    ),
                ),
            totalCommentCount = 3,
        )
}
