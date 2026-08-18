package com.junkfood.seal.ui.page.downloadv2

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.junkfood.seal.R
import com.junkfood.seal.download.Task
import com.junkfood.seal.download.Task.DownloadState.Canceled
import com.junkfood.seal.download.Task.DownloadState.Completed
import com.junkfood.seal.download.Task.DownloadState.Error
import com.junkfood.seal.download.Task.DownloadState.FetchingInfo
import com.junkfood.seal.download.Task.DownloadState.Idle
import com.junkfood.seal.download.Task.DownloadState.ReadyWithInfo
import com.junkfood.seal.download.Task.DownloadState.Running
import com.junkfood.seal.music.ListenedTrack
import com.junkfood.seal.music.isHeardToday
import com.junkfood.seal.ui.common.AsyncImageImpl

@Composable
internal fun MusicLibraryContent(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues,
    musicTasks: Map<Task, Task.State>,
    listenedTracks: List<ListenedTrack>,
    listeningEnabled: Boolean,
    notificationAccessGranted: Boolean,
    onListeningEnabledChange: (Boolean) -> Unit,
    onOpenNotificationAccess: () -> Unit,
    onDownloadTrack: (ListenedTrack) -> Unit,
    onDownloadToday: (List<ListenedTrack>) -> Unit,
    onRemoveTrack: (ListenedTrack) -> Unit,
    onClearPending: () -> Unit,
    onOpenTrackPath: (ListenedTrack, String) -> Unit,
    onOpenTask: (Task, Completed) -> Unit,
    onShowTaskActions: (Task) -> Unit,
) {
    val todayTracks = listenedTracks.filter { isHeardToday(it.lastHeardAt) }
    val taskByUrl = musicTasks.entries.associateBy { it.key.url }
    val historyTargets = listenedTracks.mapTo(mutableSetOf(), ListenedTrack::downloadTarget)
    val standaloneTasks = musicTasks.filterKeys { it.url !in historyTargets }
    val downloadableToday =
        todayTracks.filter { track ->
            track.downloadedAt == null && taskByUrl[track.downloadTarget] == null
        }

    LazyColumn(
        modifier = modifier,
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "music_settings") {
            MusicHistorySettingsCard(
                enabled = listeningEnabled,
                notificationAccessGranted = notificationAccessGranted,
                onEnabledChange = onListeningEnabledChange,
                onOpenNotificationAccess = onOpenNotificationAccess,
            )
        }

        if (todayTracks.isNotEmpty()) {
            item(key = "today_header") {
                MusicSectionHeader(
                    title = stringResource(R.string.today_listening),
                    count = todayTracks.size,
                    action = {
                        FilledTonalButton(
                            onClick = { onDownloadToday(downloadableToday) },
                            enabled = downloadableToday.isNotEmpty(),
                        ) {
                            Icon(Icons.Outlined.Download, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.download_today_music))
                        }
                    },
                )
            }
            items(todayTracks, key = ListenedTrack::key) { track ->
                val taskEntry = taskByUrl[track.downloadTarget]
                ListeningHistoryCard(
                    track = track,
                    taskState = taskEntry?.value,
                    onDownload = { onDownloadTrack(track) },
                    onRemove = { onRemoveTrack(track) },
                    onOpen =
                        track.downloadedPath?.let { path -> { onOpenTrackPath(track, path) } }
                            ?: (taskEntry?.value?.downloadState as? Completed)?.let { completed ->
                                { onOpenTask(taskEntry.key, completed) }
                            },
                )
            }
            item(key = "clear_pending") {
                TextButton(onClick = onClearPending) {
                    Icon(Icons.Outlined.DeleteSweep, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.clear_pending_history))
                }
            }
        }

        if (standaloneTasks.isNotEmpty()) {
            item(key = "downloads_header") {
                MusicSectionHeader(
                    title = stringResource(R.string.music_downloads),
                    count = standaloneTasks.size,
                )
            }
            items(
                items = standaloneTasks.toList().sortedForQueueDisplay(),
                key = { (task, _) -> task.id },
            ) { (task, state) ->
                VideoListItem(
                    modifier = Modifier.fillMaxWidth(),
                    viewState =
                        state.viewState.copy(thumbnailUrl = task.thumbnailModelForQueue(state)),
                    stateIndicator = {
                        ListItemStateText(
                            modifier = Modifier.padding(top = 3.dp),
                            downloadState = state.downloadState,
                        )
                    },
                    onClick =
                        (state.downloadState as? Completed)?.let { completed ->
                            { onOpenTask(task, completed) }
                        },
                    onButtonClick = { onShowTaskActions(task) },
                )
            }
        }

        if (todayTracks.isEmpty() && standaloneTasks.isEmpty()) {
            item(key = "empty_music") {
                Column(
                    modifier =
                        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        Icons.Outlined.LibraryMusic,
                        contentDescription = null,
                        modifier = Modifier.size(72.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = stringResource(R.string.no_music_yet),
                        modifier = Modifier.padding(top = 16.dp),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun MusicHistorySettingsCard(
    enabled: Boolean,
    notificationAccessGranted: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onOpenNotificationAccess: () -> Unit,
) {
    Card(
        colors =
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.History, null)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(
                        stringResource(R.string.music_listening_history),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        stringResource(R.string.music_listening_history_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = enabled, onCheckedChange = onEnabledChange)
            }
            if (enabled && !notificationAccessGranted) {
                Text(
                    text = stringResource(R.string.notification_access_required),
                    modifier = Modifier.padding(top = 12.dp),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(
                    onClick = onOpenNotificationAccess,
                    modifier = Modifier.align(Alignment.End).padding(top = 8.dp),
                ) {
                    Icon(Icons.Outlined.Notifications, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.open_notification_access))
                }
            }
        }
    }
}

@Composable
private fun MusicSectionHeader(
    title: String,
    count: Int,
    action: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(
                count.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        action?.invoke()
    }
}

@Composable
private fun ListeningHistoryCard(
    track: ListenedTrack,
    taskState: Task.State?,
    onDownload: () -> Unit,
    onRemove: () -> Unit,
    onOpen: (() -> Unit)?,
) {
    val downloadState = taskState?.downloadState
    Card(
        onClick = onOpen ?: onDownload,
        colors =
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(72.dp).clip(RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                if (!track.artworkUri.isNullOrBlank()) {
                    AsyncImageImpl(
                        model = track.artworkUri,
                        contentDescription = null,
                        modifier = Modifier.size(72.dp),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Icon(
                        Icons.Outlined.Album,
                        contentDescription = null,
                        modifier = Modifier.size(44.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(
                    track.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    track.artist.ifBlank { track.sourcePackage },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (track.album.isNotBlank()) {
                    Text(
                        track.album,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    stringResource(R.string.played_times, track.playCount),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            when (downloadState) {
                is Running,
                is FetchingInfo ->
                    CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                is Error,
                is Canceled ->
                    IconButton(onClick = onDownload) {
                        Icon(Icons.Outlined.Refresh, stringResource(R.string.retry))
                    }
                is Completed ->
                    IconButton(onClick = onOpen ?: {}) {
                        Icon(Icons.Outlined.PlayArrow, stringResource(R.string.open_file))
                    }
                Idle,
                ReadyWithInfo,
                null ->
                    IconButton(onClick = onDownload) {
                        Icon(Icons.Outlined.Download, stringResource(R.string.download))
                    }
            }
            IconButton(onClick = onRemove) {
                Icon(Icons.Outlined.DeleteSweep, stringResource(R.string.remove_from_history))
            }
        }
    }
}
