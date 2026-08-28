package com.junkfood.seal.download

import java.nio.charset.StandardCharsets
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
    fun embeddedDescriptionNamesTheFullTranscriptAndStaysWithinItsUtf8Budget() {
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
            RedditPostArchive.buildEmbeddedDescription(
                post = post,
                transcriptName = "A post [abc] - comments.txt",
                maxUtf8Bytes = 600,
            )

        assertTrue(description.contains("Full transcript: A post [abc] - comments.txt"))
        assertTrue(description.contains("More comments are available"))
        assertTrue(description.toByteArray(StandardCharsets.UTF_8).size <= 600)
        assertEquals("A post [abc] - comments.txt", RedditPostArchive.transcriptFileName(post))
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
