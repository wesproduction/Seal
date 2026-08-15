package com.junkfood.seal.download

import androidx.annotation.CheckResult
import com.junkfood.seal.download.Task.DownloadState.Idle
import com.junkfood.seal.download.Task.DownloadState.ReadyWithInfo
import com.junkfood.seal.util.DownloadUtil.DownloadPreferences
import com.junkfood.seal.util.Format
import com.junkfood.seal.util.PlaylistResult
import com.junkfood.seal.util.RedditMediaResolver
import com.junkfood.seal.util.VideoClip
import com.junkfood.seal.util.VideoInfo
import kotlin.math.roundToInt

object TaskFactory {
    @CheckResult
    fun createFromRedditPost(
        post: RedditMediaResolver.RedditPost,
        preferences: DownloadPreferences,
    ): List<TaskWithState> {
        if (!post.isDirectMediaPost) {
            return listOf(
                TaskWithState(
                    task = Task(url = post.canonicalUrl, preferences = preferences),
                    state =
                        Task.State(
                            downloadState = Idle,
                            videoInfo = null,
                            viewState =
                                Task.ViewState(
                                    url = post.canonicalUrl,
                                    title = post.title,
                                    uploader = post.author,
                                    extractorKey = "Reddit",
                                ),
                        ),
                )
            )
        }

        return post.media.map { media ->
            val label =
                if (media.total > 1) {
                    "%0${media.total.toString().length}d/%d - %s"
                        .format(media.index, media.total, post.title)
                } else {
                    post.title
                }
            val type =
                Task.TypeInfo.RedditMedia(
                    mediaId = media.id,
                    mediaUrl = media.url,
                    mimeType = media.mimeType,
                    extension = media.extension,
                    postId = post.id,
                    postTitle = post.title,
                    author = post.author,
                    caption = media.caption,
                    sourceUrl = post.canonicalUrl,
                    index = media.index,
                    total = media.total,
                    createdUtc = post.createdUtc,
                )
            val task = Task(url = post.canonicalUrl, type = type, preferences = preferences)
            TaskWithState(
                task = task,
                state =
                    Task.State(
                        downloadState = ReadyWithInfo,
                        videoInfo = null,
                        viewState =
                            Task.ViewState(
                                url = post.canonicalUrl,
                                title = label,
                                uploader = post.author,
                                extractorKey = "Reddit",
                                thumbnailUrl =
                                    media.url.takeIf { media.mimeType.startsWith("image/") },
                            ),
                    ),
            )
        }
    }

    /**
     * @return A [TaskWithState] with extra configurations made by user in the custom format
     *   selection page
     */
    @CheckResult
    fun createWithConfigurations(
        videoInfo: VideoInfo,
        formatList: List<Format>,
        videoClips: List<VideoClip>,
        splitByChapter: Boolean,
        newTitle: String,
        selectedSubtitles: List<String>,
        selectedAutoCaptions: List<String>,
    ): TaskWithState {
        val fileSize =
            formatList.fold(.0) { acc, format ->
                acc + (format.fileSize ?: format.fileSizeApprox ?: .0)
            }

        val info =
            videoInfo
                .run { if (fileSize != .0) copy(fileSize = fileSize) else this }
                .run { if (newTitle.isNotEmpty()) copy(title = newTitle) else this }

        val audioOnlyFormats = formatList.filter { it.isAudioOnly() }
        val videoFormats = formatList.filter { it.containsVideo() }
        val audioOnly = audioOnlyFormats.isNotEmpty() && videoFormats.isEmpty()
        val mergeAudioStream = audioOnlyFormats.size > 1
        val formatId = formatList.joinToString(separator = "+") { it.formatId.toString() }

        val subtitleLanguage =
            (selectedSubtitles + selectedAutoCaptions).joinToString(separator = ",")

        val preferences =
            DownloadPreferences.createFromPreferences()
                .run {
                    copy(
                        formatIdString = formatId,
                        videoClips = videoClips,
                        splitByChapter = splitByChapter,
                        newTitle = newTitle,
                        mergeAudioStream = mergeAudioStream,
                        extractAudio = extractAudio || audioOnly,
                    )
                }
                .run {
                    if (subtitleLanguage.isNotEmpty()) {
                        copy(
                            downloadSubtitle = true,
                            autoSubtitle = selectedAutoCaptions.isNotEmpty(),
                            subtitleLanguage = subtitleLanguage,
                        )
                    } else {
                        this
                    }
                }

        val task = Task(url = info.originalUrl.toString(), preferences = preferences)
        val state =
            Task.State(
                downloadState = ReadyWithInfo,
                videoInfo = info,
                viewState =
                    Task.ViewState.fromVideoInfo(info = info)
                        .copy(videoFormats = videoFormats, audioOnlyFormats = audioOnlyFormats),
            )

        return TaskWithState(task, state)
    }

    /** @return List of [TaskWithState]s created from playlist items */
    @CheckResult
    fun createWithPlaylistResult(
        playlistUrl: String,
        indexList: List<Int>,
        playlistResult: PlaylistResult,
        preferences: DownloadPreferences,
    ): List<TaskWithState> {
        checkNotNull(playlistResult.entries)
        val indexEntryMap = indexList.associateWith { index -> playlistResult.entries[index - 1] }

        val taskList =
            indexEntryMap.map { (index, entry) ->
                val viewState =
                    Task.ViewState(
                        url = entry.url ?: "",
                        title = entry.title ?: "${playlistResult.title} - $index",
                        duration = entry.duration?.roundToInt() ?: 0,
                        uploader = entry.uploader ?: entry.channel ?: playlistResult.channel ?: "",
                        thumbnailUrl = (entry.thumbnails?.lastOrNull()?.url) ?: "",
                    )
                val task =
                    Task(
                        url = playlistUrl,
                        preferences = preferences,
                        type = Task.TypeInfo.Playlist(index),
                    )
                val state =
                    Task.State(downloadState = Idle, videoInfo = null, viewState = viewState)
                TaskWithState(task, state)
            }

        return taskList
    }

    data class TaskWithState(val task: Task, val state: Task.State)
}
