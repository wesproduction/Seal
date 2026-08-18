package com.junkfood.seal.download

import com.junkfood.seal.download.Task.DownloadState.Canceled
import com.junkfood.seal.download.Task.DownloadState.Completed
import com.junkfood.seal.download.Task.DownloadState.Running
import com.junkfood.seal.download.Task.RestartableAction.Download
import com.junkfood.seal.util.DownloadUtil
import kotlinx.coroutines.Job
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskQueueStateStoreTest {
    private val task =
        Task(
            url = "https://example.com/video",
            preferences = DownloadUtil.DownloadPreferences.EMPTY,
        )

    @Test
    fun putPublishesANewImmutableSnapshot() {
        val store = TaskQueueStateStore()
        val before = store.state.value
        val state = Task.State(Running(Job(), task.id), null, Task.ViewState(title = "Video"))

        store.put(task, state)

        assertTrue(before.isEmpty())
        assertNotSame(before, store.state.value)
        assertEquals(state, store.state.value[task])
    }

    @Test
    fun lateProgressCallbackCannotResurrectCanceledTask() {
        val running = Running(Job(), task.id, progress = 0.2f)
        val store =
            TaskQueueStateStore(
                mapOf(task to Task.State(running, null, Task.ViewState(title = "Video")))
            )

        store.update(task) { it.copy(downloadState = Canceled(Download, progress = 0.2f)) }
        store.update(task) { current ->
            val currentDownloadState = current.downloadState
            if (currentDownloadState !is Running) current
            else current.copy(downloadState = currentDownloadState.copy(progress = 0.8f))
        }

        assertTrue(store.state.value.getValue(task).downloadState is Canceled)
    }

    @Test
    fun redditAlbumSurvivesQueueBackupSerialization() {
        val albumTask =
            Task(
                url = "https://www.reddit.com/gallery/album",
                type =
                    Task.TypeInfo.RedditAlbum(
                        postId = "album",
                        postTitle = "Album",
                        author = "author",
                        sourceUrl = "https://www.reddit.com/gallery/album",
                        createdUtc = 123L,
                        items =
                            listOf(
                                Task.TypeInfo.RedditAlbumItem(
                                    mediaId = "one",
                                    mediaUrl = "https://i.redd.it/one.jpg",
                                    mimeType = "image/jpeg",
                                    extension = "jpg",
                                    caption = "First",
                                    index = 1,
                                    total = 2,
                                ),
                                Task.TypeInfo.RedditAlbumItem(
                                    mediaId = "two",
                                    mediaUrl = "https://i.redd.it/two.jpg",
                                    mimeType = "image/jpeg",
                                    extension = "jpg",
                                    caption = "Second",
                                    index = 2,
                                    total = 2,
                                ),
                            ),
                    ),
                preferences = DownloadUtil.DownloadPreferences.EMPTY,
            )

        val restored = Json.decodeFromString<Task>(Json.encodeToString(albumTask))
        val restoredAlbum = restored.type as Task.TypeInfo.RedditAlbum

        assertEquals(listOf("one", "two"), restoredAlbum.items.map { it.mediaId })
        assertEquals(albumTask.id, restored.id)
    }

    @Test
    fun pixivArtworkAndUgoiraTimingSurviveQueueBackupSerialization() {
        val pixivTask =
            Task(
                url = "https://www.pixiv.net/artworks/72011782",
                type =
                    Task.TypeInfo.PixivArtwork(
                        artworkId = "72011782",
                        title = "Animation",
                        artist = "Artist",
                        artistId = "123",
                        sourceUrl = "https://www.pixiv.net/artworks/72011782",
                        createdAtMillis = 456L,
                        items =
                            listOf(
                                Task.TypeInfo.PixivMediaItem(
                                    mediaId = "72011782_ugoira",
                                    mediaUrl = "https://i.pximg.net/animation.zip",
                                    mimeType = "video/mp4",
                                    extension = "mp4",
                                    index = 1,
                                    total = 1,
                                    ugoiraFrames =
                                        listOf(
                                            Task.TypeInfo.PixivUgoiraFrame("000000.jpg", 42),
                                            Task.TypeInfo.PixivUgoiraFrame("000001.jpg", 83),
                                        ),
                                )
                            ),
                    ),
                preferences = DownloadUtil.DownloadPreferences.EMPTY,
            )

        val restored = Json.decodeFromString<Task>(Json.encodeToString(pixivTask))
        val artwork = restored.type as Task.TypeInfo.PixivArtwork

        assertEquals(pixivTask.id, restored.id)
        assertEquals(listOf(42, 83), artwork.items.single().ugoiraFrames.map { it.delayMillis })
    }

    @Test
    fun webImageCollectionSurvivesQueueBackupSerialization() {
        val webTask =
            Task(
                url = "https://example.com/gallery",
                type =
                    Task.TypeInfo.WebImageCollection(
                        pageId = "page-id",
                        pageTitle = "Gallery",
                        siteName = "example.com",
                        sourceUrl = "https://example.com/gallery",
                        items =
                            listOf(
                                Task.TypeInfo.WebImageItem(
                                    mediaId = "first",
                                    mediaUrl = "https://cdn.example.com/original.jpg",
                                    mimeType = "image/jpeg",
                                    extension = "jpg",
                                    caption = "First",
                                    index = 1,
                                    total = 1,
                                )
                            ),
                    ),
                preferences = DownloadUtil.DownloadPreferences.EMPTY,
            )

        val restored = Json.decodeFromString<Task>(Json.encodeToString(webTask))
        val collection = restored.type as Task.TypeInfo.WebImageCollection

        assertEquals(webTask.id, restored.id)
        assertEquals("https://cdn.example.com/original.jpg", collection.items.single().mediaUrl)
    }

    @Test
    fun completedAlbumKeepsItsOrderedPathsAndCompletionTimeWhenSerialized() {
        val completed =
            Completed(
                filePath = "content://media/one.jpg",
                filePaths = listOf("content://media/one.jpg", "content://media/two.jpg"),
                completedAt = 456L,
            )

        val restored =
            Json.decodeFromString<Task.DownloadState>(
                Json.encodeToString<Task.DownloadState>(completed)
            ) as Completed

        assertEquals(completed.filePaths, restored.orderedFilePaths)
        assertEquals(456L, restored.completedAt)
    }

    @Test
    fun completedDownloadsSurvivePersistentQueueBackupAndRestart() {
        val completed =
            Completed(
                filePath = "content://media/one.jpg",
                filePaths = listOf("content://media/one.jpg", "content://media/two.jpg"),
                completedAt = 456L,
            )
        val state = Task.State(completed, null, Task.ViewState(title = "Saved album"))

        val persisted = mapOf(task to state).persistentQueueSnapshot()
        val backupJson = Json { allowStructuredMapKeys = true }
        val encoded = backupJson.encodeToString(persisted)
        val decoded = backupJson.decodeFromString<Map<Task, Task.State>>(encoded)
        val restored = decoded.restoredAfterRestart().getValue(task).downloadState

        assertTrue(restored is Completed)
        restored as Completed
        assertEquals(completed.filePaths, restored.orderedFilePaths)
        assertEquals(456L, restored.completedAt)
    }

    @Test
    fun interruptedDownloadBecomesCanceledButIsNotForgotten() {
        val running = Running(Job(), task.id, progress = 0.43f, progressText = "43%")
        val state = Task.State(running, null, Task.ViewState(title = "Video"))

        val persisted = mapOf(task to state).persistentQueueSnapshot()
        val restored = persisted.restoredAfterRestart().getValue(task).downloadState

        assertTrue(restored is Canceled)
        restored as Canceled
        assertSame(Download, restored.action)
        assertEquals(0.45f, restored.progress)
    }
}
