package com.junkfood.seal.download

import com.junkfood.seal.download.Task.DownloadState.Canceled
import com.junkfood.seal.download.Task.DownloadState.Running
import com.junkfood.seal.download.Task.RestartableAction.Download
import com.junkfood.seal.util.DownloadUtil
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
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
}
