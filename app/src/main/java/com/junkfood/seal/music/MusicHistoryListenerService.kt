package com.junkfood.seal.music

import android.app.Notification
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.junkfood.seal.util.MUSIC_LISTENING_HISTORY
import com.junkfood.seal.util.PreferenceUtil.getBoolean

class MusicHistoryListenerService : NotificationListenerService() {
    override fun onNotificationPosted(statusBarNotification: StatusBarNotification) {
        if (!MUSIC_LISTENING_HISTORY.getBoolean()) return
        if (statusBarNotification.packageName == packageName) return

        val notification = statusBarNotification.notification
        val extras = notification.extras ?: return
        val token =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                extras.getParcelable(
                    Notification.EXTRA_MEDIA_SESSION,
                    MediaSession.Token::class.java,
                )
            } else {
                @Suppress("DEPRECATION")
                extras.getParcelable(Notification.EXTRA_MEDIA_SESSION) as? MediaSession.Token
            } ?: return

        val controller = runCatching { MediaController(this, token) }.getOrNull() ?: return
        if (controller.playbackState?.state != PlaybackState.STATE_PLAYING) return

        val metadata = controller.metadata
        val title =
            metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
                ?: extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
                ?: return
        val artist =
            metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val album =
            metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM)
                ?: extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty()
        val description = metadata?.description
        val sourceUrl = description?.mediaUri?.toString()?.takeIf { it.startsWith("http") }
        val artworkUri =
            description?.iconUri?.toString()?.takeIf {
                it.startsWith("http") || it.startsWith("content://")
            }

        MusicHistoryStore.record(
            MusicObservation(
                title = title,
                artist = artist,
                album = album,
                sourcePackage = statusBarNotification.packageName,
                mediaId = metadata?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID),
                sourceUrl = sourceUrl,
                artworkUri = artworkUri,
                observedAt = statusBarNotification.postTime,
            )
        )
    }
}
