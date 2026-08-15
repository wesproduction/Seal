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
}
