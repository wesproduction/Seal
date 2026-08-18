package com.junkfood.seal.download

import androidx.annotation.CheckResult
import com.junkfood.seal.download.Task.DownloadState.Idle
import com.junkfood.seal.download.Task.DownloadState.ReadyWithInfo
import com.junkfood.seal.util.DownloadUtil.DownloadPreferences
import com.junkfood.seal.util.Format
import com.junkfood.seal.util.PixivMediaResolver
import com.junkfood.seal.util.PlaylistResult
import com.junkfood.seal.util.RedditMediaResolver
import com.junkfood.seal.util.VideoClip
import com.junkfood.seal.util.VideoInfo
import com.junkfood.seal.util.WebImageResolver
import kotlin.math.roundToInt

object TaskFactory {
    data class RedditCollection(val name: String, val index: Int, val total: Int)

    @CheckResult
    fun createFromRedditPost(
        post: RedditMediaResolver.RedditPost,
        preferences: DownloadPreferences,
        collection: RedditCollection? = null,
    ): List<TaskWithState> {
        if (!post.isDirectMediaPost) {
            val redditPreferences =
                preferences.copy(
                    outputTemplate =
                        redditVideoOutputTemplate(
                            post = post,
                            collection = collection,
                            separatePostFolders = preferences.redditSeparatePostFolders,
                        )
                )
            return listOf(
                TaskWithState(
                    task = Task(url = post.canonicalUrl, preferences = redditPreferences),
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

        if (post.media.size > 1) {
            val label =
                collectionLabel(
                    collection = collection,
                    label = "${post.title} · ${post.media.size}-item album",
                )
            val type =
                Task.TypeInfo.RedditAlbum(
                    postId = post.id,
                    postTitle = post.title,
                    author = post.author,
                    sourceUrl = post.canonicalUrl,
                    createdUtc = post.createdUtc,
                    items =
                        post.media.map { media ->
                            Task.TypeInfo.RedditAlbumItem(
                                mediaId = media.id,
                                mediaUrl = media.url,
                                mimeType = media.mimeType,
                                extension = media.extension,
                                caption = media.caption,
                                index = media.index,
                                total = media.total,
                            )
                        },
                    collectionName = collection?.name,
                    collectionIndex = collection?.index ?: 0,
                    collectionTotal = collection?.total ?: 0,
                )
            return listOf(
                TaskWithState(
                    task = Task(url = post.canonicalUrl, type = type, preferences = preferences),
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
                                        post.media
                                            .firstOrNull { it.mimeType.startsWith("image/") }
                                            ?.url,
                                ),
                        ),
                )
            )
        }

        return post.media.map { media ->
            val label = collectionLabel(collection, post.title)
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
                    collectionName = collection?.name,
                    collectionIndex = collection?.index ?: 0,
                    collectionTotal = collection?.total ?: 0,
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

    private fun collectionLabel(collection: RedditCollection?, label: String): String =
        collection?.let {
            "%0${it.total.toString().length}d/%d · %s".format(it.index, it.total, label)
        } ?: label

    @CheckResult
    fun createFromRedditFeed(
        feed: RedditMediaResolver.RedditFeed,
        preferences: DownloadPreferences,
    ): List<TaskWithState> {
        val total = feed.posts.size
        return feed.posts.flatMapIndexed { index, post ->
            createFromRedditPost(
                post = post,
                preferences = preferences,
                collection =
                    RedditCollection(name = feed.target.name, index = index + 1, total = total),
            )
        }
    }

    @CheckResult
    fun createFromPixivArtwork(
        artwork: PixivMediaResolver.PixivArtwork,
        preferences: DownloadPreferences,
    ): TaskWithState {
        val type =
            Task.TypeInfo.PixivArtwork(
                artworkId = artwork.id,
                title = artwork.title,
                artist = artwork.artist,
                artistId = artwork.artistId,
                sourceUrl = artwork.canonicalUrl,
                createdAtMillis = artwork.createdAtMillis,
                items =
                    artwork.media.map { media ->
                        Task.TypeInfo.PixivMediaItem(
                            mediaId = media.id,
                            mediaUrl = media.mediaUrl,
                            mimeType = media.mimeType,
                            extension = media.extension,
                            index = media.index,
                            total = media.total,
                            ugoiraFrames =
                                media.ugoiraFrames.map { frame ->
                                    Task.TypeInfo.PixivUgoiraFrame(
                                        file = frame.file,
                                        delayMillis = frame.delayMillis,
                                    )
                                },
                        )
                    },
            )
        val itemLabel =
            when {
                type.items.singleOrNull()?.isUgoira == true -> "${artwork.title} · animation"
                type.items.size > 1 -> "${artwork.title} · ${type.items.size}-page artwork"
                else -> artwork.title
            }
        return TaskWithState(
            task = Task(url = artwork.canonicalUrl, type = type, preferences = preferences),
            state =
                Task.State(
                    downloadState = ReadyWithInfo,
                    videoInfo = null,
                    viewState =
                        Task.ViewState(
                            url = artwork.canonicalUrl,
                            title = itemLabel,
                            uploader = artwork.artist,
                            extractorKey = "Pixiv",
                            thumbnailUrl = artwork.thumbnailUrl,
                        ),
                ),
        )
    }

    @CheckResult
    fun createFromWebImagePage(
        page: WebImageResolver.WebImagePage,
        preferences: DownloadPreferences,
    ): TaskWithState {
        val type =
            Task.TypeInfo.WebImageCollection(
                pageId = page.id,
                pageTitle = page.title,
                siteName = page.siteName,
                sourceUrl = page.canonicalUrl,
                items =
                    page.media.map { media ->
                        Task.TypeInfo.WebImageItem(
                            mediaId = media.id,
                            mediaUrl = media.mediaUrl,
                            mimeType = media.mimeType,
                            extension = media.extension,
                            caption = media.caption,
                            index = media.index,
                            total = media.total,
                        )
                    },
            )
        val label =
            if (type.items.size == 1) page.title
            else "${page.title} · ${type.items.size}-image page"
        return TaskWithState(
            task = Task(url = page.canonicalUrl, type = type, preferences = preferences),
            state =
                Task.State(
                    downloadState = ReadyWithInfo,
                    videoInfo = null,
                    viewState =
                        Task.ViewState(
                            url = page.canonicalUrl,
                            title = label,
                            uploader = page.siteName,
                            extractorKey = "Web images",
                            thumbnailUrl = page.media.firstOrNull()?.mediaUrl,
                        ),
                ),
        )
    }

    private fun redditVideoOutputTemplate(
        post: RedditMediaResolver.RedditPost,
        collection: RedditCollection?,
        separatePostFolders: Boolean,
    ): String {
        val postFolder = buildString {
            if (collection != null) {
                val width = collection.total.toString().length.coerceAtLeast(2)
                append("%0${width}d - ".format(collection.index))
            }
            append(RedditMediaDownloader.sanitizeFileName(post.title))
            append(" [${post.id}]")
        }
        return buildList {
                add(RedditMediaDownloader.DIRECTORY_NAME)
                collection?.name?.let(RedditMediaDownloader::sanitizeFileName)?.let(::add)
                if (separatePostFolders) {
                    add(postFolder)
                    add("%(title)s [%(id)s].%(ext)s")
                } else {
                    add("$postFolder.%(ext)s")
                }
            }
            .joinToString("/")
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
