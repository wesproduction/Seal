package com.junkfood.seal.download

import com.junkfood.seal.database.objects.CommandTemplate
import com.junkfood.seal.download.Task.TypeInfo
import com.junkfood.seal.download.Task.ViewState
import com.junkfood.seal.util.DownloadUtil
import com.junkfood.seal.util.Format
import com.junkfood.seal.util.VideoInfo
import com.junkfood.seal.util.toHttpsUrl
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

private val TypeInfo.id: String
    get() =
        when (this) {
            is TypeInfo.CustomCommand -> "${template.id}_${template.name}"
            is TypeInfo.Playlist -> "$index"
            is TypeInfo.RedditAlbum ->
                "reddit_album_${collectionName.orEmpty()}_${collectionIndex}_${postId}"
            is TypeInfo.RedditMedia ->
                "reddit_${collectionName.orEmpty()}_${collectionIndex}_${postId}_${index}_${mediaId}"
            is TypeInfo.PixivArtwork -> "pixiv_$artworkId"
            TypeInfo.URL -> ""
        }

private fun makeId(
    url: String,
    type: TypeInfo,
    preferences: DownloadUtil.DownloadPreferences,
): String = "${url}_${type.id}_${preferences.hashCode()}"

@Serializable
data class Task(
    val url: String,
    val type: TypeInfo = TypeInfo.URL,
    val preferences: DownloadUtil.DownloadPreferences,
    val id: String = makeId(url, type, preferences),
) : Comparable<Task> {

    val timeCreated: Long = System.currentTimeMillis()

    override fun compareTo(other: Task): Int {
        return timeCreated.compareTo(other.timeCreated)
    }

    @Serializable
    sealed interface TypeInfo {

        @Serializable data class Playlist(val index: Int = 0) : TypeInfo

        @Serializable data class CustomCommand(val template: CommandTemplate) : TypeInfo

        @Serializable
        data class RedditAlbum(
            val postId: String,
            val postTitle: String,
            val author: String,
            val sourceUrl: String,
            val createdUtc: Long,
            val items: List<RedditAlbumItem>,
            val collectionName: String? = null,
            val collectionIndex: Int = 0,
            val collectionTotal: Int = 0,
        ) : TypeInfo

        @Serializable
        data class RedditAlbumItem(
            val mediaId: String,
            val mediaUrl: String,
            val mimeType: String,
            val extension: String,
            val caption: String,
            val index: Int,
            val total: Int,
        )

        @Serializable
        data class RedditMedia(
            val mediaId: String,
            val mediaUrl: String,
            val mimeType: String,
            val extension: String,
            val postId: String,
            val postTitle: String,
            val author: String,
            val caption: String,
            val sourceUrl: String,
            val index: Int,
            val total: Int,
            val createdUtc: Long,
            val collectionName: String? = null,
            val collectionIndex: Int = 0,
            val collectionTotal: Int = 0,
        ) : TypeInfo

        @Serializable
        data class PixivArtwork(
            val artworkId: String,
            val title: String,
            val artist: String,
            val artistId: String,
            val sourceUrl: String,
            val createdAtMillis: Long,
            val items: List<PixivMediaItem>,
        ) : TypeInfo

        @Serializable
        data class PixivMediaItem(
            val mediaId: String,
            val mediaUrl: String,
            val mimeType: String,
            val extension: String,
            val index: Int,
            val total: Int,
            val ugoiraFrames: List<PixivUgoiraFrame> = emptyList(),
        ) {
            val isUgoira: Boolean
                get() = ugoiraFrames.isNotEmpty()
        }

        @Serializable data class PixivUgoiraFrame(val file: String, val delayMillis: Int)

        @Serializable data object URL : TypeInfo
    }

    @Serializable
    data class State(
        val downloadState: DownloadState,
        val videoInfo: VideoInfo?,
        val viewState: ViewState,
    )

    @Serializable
    sealed interface DownloadState : Comparable<DownloadState> {

        interface Cancelable {
            val job: Job
            val taskId: String
            val action: RestartableAction
        }

        interface Restartable {
            val action: RestartableAction
        }

        @Serializable data object Idle : DownloadState

        @Serializable
        data class FetchingInfo(
            @Transient override val job: Job = Job(),
            override val taskId: String,
        ) : DownloadState, Cancelable {
            override val action: RestartableAction = RestartableAction.FetchInfo
        }

        @Serializable data object ReadyWithInfo : DownloadState

        @Serializable
        data class Running(
            @Transient override val job: Job = Job(),
            override val taskId: String,
            val progress: Float = PROGRESS_INDETERMINATE,
            val progressText: String = "",
        ) : DownloadState, Cancelable {
            override val action: RestartableAction = RestartableAction.Download
        }

        @Serializable
        data class Canceled(override val action: RestartableAction, val progress: Float? = null) :
            DownloadState, Restartable

        @Serializable
        data class Error(
            @Transient val throwable: Throwable = Throwable(),
            override val action: RestartableAction,
        ) : DownloadState, Restartable

        @Serializable
        data class Completed(
            val filePath: String?,
            val filePaths: List<String> = emptyList(),
            val completedAt: Long = System.currentTimeMillis(),
        ) : DownloadState {
            val orderedFilePaths: List<String>
                get() = filePaths.ifEmpty { filePath?.let(::listOf).orEmpty() }
        }

        override fun compareTo(other: DownloadState): Int {
            return ordinal - other.ordinal
        }

        private val ordinal: Int
            get() =
                when (this) {
                    is Canceled -> 4
                    is Error -> 5
                    is Completed -> 6
                    Idle -> 3
                    is FetchingInfo -> 2
                    ReadyWithInfo -> 1
                    is Running -> 0
                }
    }

    @Serializable
    sealed interface RestartableAction {
        @Serializable data object FetchInfo : RestartableAction

        @Serializable data object Download : RestartableAction
    }

    @Serializable
    data class ViewState(
        val url: String = "https://www.example.com",
        val title: String = "",
        val uploader: String = "",
        val extractorKey: String = "",
        val duration: Int = 0,
        val fileSizeApprox: Double = .0,
        val thumbnailUrl: String? = null,
        val videoFormats: List<Format>? = null,
        val audioOnlyFormats: List<Format>? = null,
    ) {
        companion object {
            fun fromVideoInfo(info: VideoInfo): ViewState {
                val formats =
                    info.requestedFormats
                        ?: info.requestedDownloads?.map { it.toFormat() }
                        ?: emptyList()

                val videoFormats = formats.filter { it.containsVideo() }
                val audioOnlyFormats = formats.filter { it.isAudioOnly() }

                return ViewState(
                    url = info.originalUrl.toString(),
                    title = info.title,
                    uploader = info.uploader ?: info.channel ?: info.uploaderId.toString(),
                    extractorKey = info.extractorKey,
                    duration = info.duration?.roundToInt() ?: 0,
                    thumbnailUrl = info.thumbnail.toHttpsUrl(),
                    fileSizeApprox = info.fileSize ?: info.fileSizeApprox ?: .0,
                    videoFormats = videoFormats,
                    audioOnlyFormats = audioOnlyFormats,
                )
            }
        }
    }

    companion object {
        private const val PROGRESS_INDETERMINATE = -1f
    }
}
