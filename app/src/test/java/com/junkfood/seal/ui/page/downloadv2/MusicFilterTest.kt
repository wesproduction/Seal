package com.junkfood.seal.ui.page.downloadv2

import com.junkfood.seal.download.Task
import com.junkfood.seal.util.DownloadUtil
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicFilterTest {
    @Test
    fun `music tab includes explicit audio downloads`() {
        val task =
            Task(
                url = "https://music.youtube.com/watch?v=BaW_jenozKc",
                preferences =
                    DownloadUtil.DownloadPreferences.EMPTY.copy(
                        extractAudio = true,
                        organizeMusicLibrary = true,
                    ),
            )
        val state =
            Task.State(
                downloadState = Task.DownloadState.Idle,
                videoInfo = null,
                viewState = Task.ViewState(),
            )

        assertTrue(Filter.Music.predict(task to state))
    }

    @Test
    fun `music tab excludes image collections`() {
        val task =
            Task(
                url = "https://example.com/gallery",
                preferences = DownloadUtil.DownloadPreferences.EMPTY,
                type =
                    Task.TypeInfo.WebImageCollection(
                        pageId = "page",
                        pageTitle = "Gallery",
                        siteName = "Example",
                        sourceUrl = "https://example.com/gallery",
                        items = emptyList(),
                    ),
            )
        val state =
            Task.State(
                downloadState = Task.DownloadState.Idle,
                videoInfo = null,
                viewState = Task.ViewState(),
            )

        assertFalse(Filter.Music.predict(task to state))
    }
}
