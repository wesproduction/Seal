package com.junkfood.seal.music

import android.content.Context
import com.junkfood.seal.util.MusicDownloadPolicy
import java.util.Calendar
import java.util.TimeZone
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class ListenedTrack(
    val key: String,
    val title: String,
    val artist: String,
    val album: String = "",
    val sourcePackage: String,
    val mediaId: String? = null,
    val sourceUrl: String? = null,
    val artworkUri: String? = null,
    val firstHeardAt: Long,
    val lastHeardAt: Long,
    val playCount: Int = 1,
    val downloadedPath: String? = null,
    val downloadedAt: Long? = null,
) {
    val downloadTarget: String
        get() =
            MusicDownloadPolicy.downloadTarget(
                sourcePackage = sourcePackage,
                mediaId = mediaId,
                sourceUrl = sourceUrl,
                artist = artist,
                title = title,
            )
}

data class MusicObservation(
    val title: String,
    val artist: String,
    val album: String = "",
    val sourcePackage: String,
    val mediaId: String? = null,
    val sourceUrl: String? = null,
    val artworkUri: String? = null,
    val observedAt: Long = System.currentTimeMillis(),
)

object MusicHistoryStore {
    private const val PREFERENCES_NAME = "walrus_music_history"
    private const val HISTORY_KEY = "unique_listened_tracks"
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()
    private var context: Context? = null
    private val mutableTracks = MutableStateFlow<List<ListenedTrack>>(emptyList())

    val tracks: StateFlow<List<ListenedTrack>> = mutableTracks.asStateFlow()

    fun initialize(context: Context) {
        synchronized(lock) {
            if (this.context != null) return
            this.context = context.applicationContext
            mutableTracks.value = load()
        }
    }

    fun record(observation: MusicObservation) {
        if (observation.title.isBlank()) return
        val key =
            MusicDownloadPolicy.normalizeIdentity(
                sourcePackage = observation.sourcePackage,
                artist = observation.artist,
                title = observation.title,
            )
        if (key.isBlank()) return

        synchronized(lock) {
            val current = mutableTracks.value
            val previous = current.firstOrNull { it.key == key }
            val next =
                if (previous == null) {
                    current +
                        ListenedTrack(
                            key = key,
                            title = observation.title.trim(),
                            artist = observation.artist.trim(),
                            album = observation.album.trim(),
                            sourcePackage = observation.sourcePackage,
                            mediaId = observation.mediaId,
                            sourceUrl = observation.sourceUrl,
                            artworkUri = observation.artworkUri,
                            firstHeardAt = observation.observedAt,
                            lastHeardAt = observation.observedAt,
                        )
                } else {
                    current.map { track ->
                        if (track.key != key) track
                        else
                            track.copy(
                                title = observation.title.trim(),
                                artist = observation.artist.trim().ifBlank { track.artist },
                                album = observation.album.trim().ifBlank { track.album },
                                mediaId = observation.mediaId ?: track.mediaId,
                                sourceUrl = observation.sourceUrl ?: track.sourceUrl,
                                artworkUri = observation.artworkUri ?: track.artworkUri,
                                lastHeardAt = observation.observedAt,
                                playCount = track.playCount + 1,
                            )
                    }
                }
            persist(next.sortedByDescending(ListenedTrack::lastHeardAt))
        }
    }

    fun markDownloaded(
        key: String,
        path: String?,
        downloadedAt: Long = System.currentTimeMillis(),
    ) {
        synchronized(lock) {
            val next =
                mutableTracks.value.map { track ->
                    if (track.key == key)
                        track.copy(downloadedPath = path, downloadedAt = downloadedAt)
                    else track
                }
            persist(next)
        }
    }

    fun remove(key: String) {
        synchronized(lock) { persist(mutableTracks.value.filterNot { it.key == key }) }
    }

    /** Clears review-only entries while retaining downloaded identities for deduplication. */
    fun clearPending() {
        synchronized(lock) { persist(mutableTracks.value.filter { it.downloadedAt != null }) }
    }

    private fun load(): List<ListenedTrack> {
        val preferences =
            context?.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
                ?: return emptyList()
        return runCatching {
                preferences.getString(HISTORY_KEY, null)?.let {
                    json.decodeFromString<List<ListenedTrack>>(it)
                }
            }
            .getOrNull()
            .orEmpty()
    }

    private fun persist(tracks: List<ListenedTrack>) {
        mutableTracks.value = tracks
        context
            ?.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            ?.edit()
            ?.putString(HISTORY_KEY, json.encodeToString(tracks))
            ?.apply()
    }
}

internal fun isHeardToday(
    timestamp: Long,
    now: Long = System.currentTimeMillis(),
    timeZone: TimeZone = TimeZone.getDefault(),
): Boolean {
    val heard = Calendar.getInstance(timeZone).apply { timeInMillis = timestamp }
    val current = Calendar.getInstance(timeZone).apply { timeInMillis = now }
    return heard.get(Calendar.ERA) == current.get(Calendar.ERA) &&
        heard.get(Calendar.YEAR) == current.get(Calendar.YEAR) &&
        heard.get(Calendar.DAY_OF_YEAR) == current.get(Calendar.DAY_OF_YEAR)
}
