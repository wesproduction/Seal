package com.junkfood.seal.ui.page.downloadv2

import com.junkfood.seal.download.Task
import com.junkfood.seal.download.Task.DownloadState.Canceled
import com.junkfood.seal.download.Task.DownloadState.Completed
import com.junkfood.seal.download.Task.DownloadState.Running
import com.junkfood.seal.download.Task.RestartableAction.Download
import com.junkfood.seal.util.DownloadUtil
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class DownloadQueuePresentationTest {
    @Test
    fun completedTasksAreNewestFirstWithoutDisplacingActiveTasks() {
        val running = task("running") to state(Running(Job(), "running"))
        val canceled = task("canceled") to state(Canceled(Download))
        val older = task("older") to state(Completed("older.jpg", completedAt = 100L))
        val newer = task("newer") to state(Completed("newer.jpg", completedAt = 200L))

        val sorted = listOf(older, canceled, newer, running).sortedForQueueDisplay()

        assertEquals(listOf("running", "canceled", "newer", "older"), sorted.map { it.first.url })
    }

    @Test
    fun redditAlbumPreviewKeepsEveryDownloadedPageInOrder() {
        val album =
            Task(
                url = "https://www.reddit.com/gallery/album",
                type =
                    Task.TypeInfo.RedditAlbum(
                        postId = "album",
                        postTitle = "Album title",
                        author = "author",
                        sourceUrl = "https://www.reddit.com/gallery/album",
                        createdUtc = 1L,
                        items =
                            listOf(
                                albumItem(id = "first", index = 1),
                                albumItem(id = "second", index = 2),
                            ),
                    ),
                preferences = DownloadUtil.DownloadPreferences.EMPTY,
            )
        val completed =
            Completed(
                filePath = "content://media/first.jpg",
                filePaths = listOf("content://media/first.jpg", "content://media/second.jpg"),
                completedAt = 10L,
            )

        val preview = album.createMediaPreview(completed, title = "Album title")

        assertNotNull(preview)
        assertEquals(
            listOf("content://media/first.jpg", "content://media/second.jpg"),
            preview?.items?.map { it.path },
        )
        assertEquals(
            listOf(PreviewMediaKind.Image, PreviewMediaKind.Image),
            preview?.items?.map { it.kind },
        )
    }

    @Test
    fun completedPixivArtworkUsesFirstLocalFileAsItsTimelineThumbnail() {
        val pixiv = pixivTask("image/jpeg", "image/png")
        val state =
            Task.State(
                Completed(
                    filePath = "content://media/pixiv/first.jpg",
                    filePaths =
                        listOf(
                            "content://media/pixiv/first.jpg",
                            "content://media/pixiv/second.png",
                        ),
                ),
                null,
                Task.ViewState(thumbnailUrl = "https://i.pximg.net/remote.jpg"),
            )

        assertEquals("content://media/pixiv/first.jpg", pixiv.thumbnailModelForQueue(state))
    }

    @Test
    fun incompletePixivArtworkKeepsRemoteThumbnailUntilLocalMediaExists() {
        val pixiv = pixivTask("image/jpeg")
        val state =
            Task.State(
                Running(Job(), pixiv.id),
                null,
                Task.ViewState(thumbnailUrl = "https://i.pximg.net/remote.jpg"),
            )

        assertEquals("https://i.pximg.net/remote.jpg", pixiv.thumbnailModelForQueue(state))
    }

    @Test
    fun pixivImagesAndAnimationsAreCountedByTheirActualMediaTypes() {
        val pixiv = pixivTask("image/jpeg", "image/png", "video/mp4")
        val state = state(Completed("content://media/pixiv/first.jpg"))

        val counts = mapOf(pixiv to state).queueMediaCounts()

        assertEquals(2, counts.imageCount)
        assertEquals(1, counts.videoCount)
        assertEquals(0, counts.audioCount)
    }

    private fun task(name: String) =
        Task(url = name, preferences = DownloadUtil.DownloadPreferences.EMPTY)

    private fun state(downloadState: Task.DownloadState) =
        Task.State(downloadState, null, Task.ViewState(title = "Item"))

    private fun albumItem(id: String, index: Int) =
        Task.TypeInfo.RedditAlbumItem(
            mediaId = id,
            mediaUrl = "https://i.redd.it/$id.jpg",
            mimeType = "image/jpeg",
            extension = "jpg",
            caption = id,
            index = index,
            total = 2,
        )

    private fun pixivTask(vararg mimeTypes: String): Task =
        Task(
            url = "https://www.pixiv.net/artworks/12345",
            type =
                Task.TypeInfo.PixivArtwork(
                    artworkId = "12345",
                    title = "Artwork",
                    artist = "Artist",
                    artistId = "67890",
                    sourceUrl = "https://www.pixiv.net/artworks/12345",
                    createdAtMillis = 1L,
                    items =
                        mimeTypes.mapIndexed { index, mimeType ->
                            val extension = if (mimeType == "video/mp4") "mp4" else "jpg"
                            Task.TypeInfo.PixivMediaItem(
                                mediaId = "12345_${index + 1}",
                                mediaUrl = "https://i.pximg.net/$index.$extension",
                                mimeType = mimeType,
                                extension = extension,
                                index = index + 1,
                                total = mimeTypes.size,
                            )
                        },
                ),
            preferences = DownloadUtil.DownloadPreferences.EMPTY,
        )
}
