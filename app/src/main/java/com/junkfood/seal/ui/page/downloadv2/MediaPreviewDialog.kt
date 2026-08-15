package com.junkfood.seal.ui.page.downloadv2

import android.net.Uri
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.junkfood.seal.R
import com.junkfood.seal.download.Task
import com.junkfood.seal.download.Task.DownloadState.Completed
import com.junkfood.seal.ui.common.AsyncImageImpl
import java.io.File

internal enum class PreviewMediaKind {
    Image,
    Video,
    Audio,
    Unsupported,
}

internal data class PreviewMediaItem(val path: String, val mimeType: String) {
    val kind: PreviewMediaKind
        get() =
            when {
                mimeType.startsWith("image/") -> PreviewMediaKind.Image
                mimeType.startsWith("video/") -> PreviewMediaKind.Video
                mimeType.startsWith("audio/") -> PreviewMediaKind.Audio
                else -> PreviewMediaKind.Unsupported
            }
}

internal data class MediaPreviewState(val title: String, val items: List<PreviewMediaItem>)

internal fun Task.createMediaPreview(completed: Completed, title: String): MediaPreviewState? {
    val paths = completed.orderedFilePaths
    if (paths.isEmpty()) return null

    val declaredMimeTypes =
        when (val taskType = type) {
            is Task.TypeInfo.RedditAlbum -> taskType.items.map { it.mimeType }
            is Task.TypeInfo.RedditMedia -> listOf(taskType.mimeType)
            else -> emptyList()
        }
    val items =
        paths.mapIndexed { index, path ->
            PreviewMediaItem(
                path = path,
                mimeType =
                    declaredMimeTypes.getOrNull(index).orEmpty().ifBlank { mimeTypeFromPath(path) },
            )
        }
    return MediaPreviewState(title = title.ifBlank { titleForPreview() }, items = items)
}

private fun Task.titleForPreview(): String =
    when (val taskType = type) {
        is Task.TypeInfo.RedditAlbum -> taskType.postTitle
        is Task.TypeInfo.RedditMedia -> taskType.postTitle
        else -> url
    }

internal fun mimeTypeFromPath(path: String): String {
    val pathWithoutQuery = path.substringBefore('?').substringBefore('#')
    return when (pathWithoutQuery.substringAfterLast('.', "").lowercase()) {
        "jpg",
        "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "avif" -> "image/avif"
        "bmp" -> "image/bmp"
        "heic",
        "heif" -> "image/heif"
        "mp4",
        "m4v" -> "video/mp4"
        "webm" -> "video/webm"
        "mkv" -> "video/x-matroska"
        "mov" -> "video/quicktime"
        "avi" -> "video/x-msvideo"
        "3gp" -> "video/3gpp"
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "aac" -> "audio/aac"
        "opus" -> "audio/opus"
        "ogg" -> "audio/ogg"
        "wav" -> "audio/wav"
        "flac" -> "audio/flac"
        else -> "application/octet-stream"
    }
}

@Composable
internal fun MediaPreviewDialog(
    preview: MediaPreviewState,
    onDismissRequest: () -> Unit,
    onOpenExternal: (String) -> Unit,
) {
    val pagerState = rememberPagerState(pageCount = { preview.items.size })

    Dialog(
        onDismissRequest = onDismissRequest,
        properties =
            DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(
            modifier =
                Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(8.dp),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismissRequest) {
                        Icon(Icons.Outlined.Close, stringResource(R.string.close))
                    }
                    Text(
                        text = preview.title,
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    IconButton(
                        onClick = { onOpenExternal(preview.items[pagerState.currentPage].path) }
                    ) {
                        Icon(Icons.Outlined.OpenInNew, stringResource(R.string.open_file))
                    }
                }

                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxWidth().weight(1f).background(Color.Black),
                    beyondViewportPageCount = 1,
                ) { page ->
                    val item = preview.items[page]
                    key(item.path) {
                        when (item.kind) {
                            PreviewMediaKind.Image -> ImagePreview(item)
                            PreviewMediaKind.Video -> VideoPreview(item, showAudioArtwork = false)
                            PreviewMediaKind.Audio -> VideoPreview(item, showAudioArtwork = true)
                            PreviewMediaKind.Unsupported -> UnsupportedPreview(item, onOpenExternal)
                        }
                    }
                }

                if (preview.items.size > 1) {
                    Text(
                        text = "${pagerState.currentPage + 1} / ${preview.items.size}",
                        modifier = Modifier.align(Alignment.CenterHorizontally).padding(12.dp),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
    }
}

@Composable
private fun ImagePreview(item: PreviewMediaItem) {
    val model =
        remember(item.path) {
            if (item.path.startsWith("content://") || item.path.startsWith("file://"))
                Uri.parse(item.path)
            else File(item.path)
        }
    AsyncImageImpl(
        model = model,
        contentDescription = null,
        modifier = Modifier.fillMaxSize(),
        contentScale = ContentScale.Fit,
    )
}

@Composable
private fun VideoPreview(item: PreviewMediaItem, showAudioArtwork: Boolean) {
    val context = LocalContext.current
    val videoViewHolder = remember(item.path) { arrayOfNulls<VideoView>(1) }
    val mediaUri =
        remember(item.path) {
            if (item.path.startsWith("content://") || item.path.startsWith("file://"))
                Uri.parse(item.path)
            else Uri.fromFile(File(item.path))
        }

    DisposableEffect(item.path) { onDispose { videoViewHolder[0]?.stopPlayback() } }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (showAudioArtwork) {
            Icon(
                imageVector = Icons.Outlined.Headphones,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(0.24f),
                tint = Color.White.copy(alpha = 0.72f),
            )
        }
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = {
                VideoView(context).apply {
                    videoViewHolder[0] = this
                    val controller = MediaController(context)
                    controller.setAnchorView(this)
                    setMediaController(controller)
                    setVideoURI(mediaUri)
                    setOnPreparedListener {
                        seekTo(1)
                        controller.show(0)
                    }
                }
            },
        )
    }
}

@Composable
private fun UnsupportedPreview(item: PreviewMediaItem, onOpenExternal: (String) -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.preview_unavailable),
            color = Color.White,
            style = MaterialTheme.typography.bodyLarge,
        )
        IconButton(onClick = { onOpenExternal(item.path) }) {
            Icon(Icons.Outlined.OpenInNew, stringResource(R.string.open_file), tint = Color.White)
        }
    }
}
