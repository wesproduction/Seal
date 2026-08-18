package com.junkfood.seal.util

import java.net.URI
import java.util.Locale

/** Music-specific decisions kept separate from the generic yt-dlp download path. */
object MusicDownloadPolicy {
    const val OUTPUT_TEMPLATE =
        "%(artist,album_artist,uploader,channel|Unknown Artist).120B/" +
            "%(album,playlist|Singles).120B/" +
            "%(track_number,playlist_index|01)02d - %(track,title).200B.%(ext)s"

    const val SQUARE_ARTWORK_CONFIG =
        "--ppa \"ffmpeg: -c:v mjpeg -vf crop=\\\"'if(gt(ih,iw),iw,ih)':'if(gt(iw,ih),ih,iw)'\\\"\""

    private val youtubeVideoId = Regex("^[A-Za-z0-9_-]{11}$")

    fun isYoutubeUrl(url: String?): Boolean {
        val host = host(url) ?: return false
        return host == "youtu.be" || host == "youtube.com" || host.endsWith(".youtube.com")
    }

    fun isYoutubeMusicUrl(url: String?): Boolean = host(url) == "music.youtube.com"

    fun isMusicSearch(url: String?): Boolean =
        url?.startsWith("ytsearch", ignoreCase = true) == true

    fun shouldOrganize(
        info: VideoInfo,
        preferences: DownloadUtil.DownloadPreferences,
        sourceUrl: String? = null,
    ): Boolean {
        if (!preferences.extractAudio && info.vcodec != "none") return false
        return preferences.organizeMusicLibrary ||
            isYoutubeMusicUrl(sourceUrl) ||
            isYoutubeMusicUrl(info.originalUrl) ||
            isYoutubeMusicUrl(info.webpageUrl) ||
            isMusicSearch(sourceUrl) ||
            !info.track.isNullOrBlank() ||
            !info.artist.isNullOrBlank() ||
            !info.album.isNullOrBlank() ||
            !info.albumArtist.isNullOrBlank() ||
            !info.playlist.isNullOrBlank()
    }

    fun audioFormatLabel(preferences: DownloadUtil.DownloadPreferences): String =
        with(preferences) {
            when {
                !useCustomAudioPreset -> "Best available"
                convertAudio && audioConvertFormat == CONVERT_MP3 -> "MP3"
                convertAudio -> "M4A / AAC"
                audioFormat == OPUS -> "Opus"
                audioFormat == M4A -> "M4A / AAC"
                else -> "Best available"
            }
        }

    fun normalizeIdentity(sourcePackage: String, artist: String, title: String): String =
        listOf(sourcePackage, artist, title).joinToString("|") { value ->
            value
                .trim()
                .lowercase(Locale.ROOT)
                .replace(Regex("\\s+"), " ")
                .replace(Regex("[^\\p{L}\\p{N}]+"), "")
        }

    fun downloadTarget(
        sourcePackage: String,
        mediaId: String?,
        sourceUrl: String?,
        artist: String,
        title: String,
    ): String {
        val explicitUrl =
            sourceUrl?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        if (explicitUrl != null) return explicitUrl

        val id = mediaId?.trim()?.takeIf { youtubeVideoId.matches(it) }
        if (id != null && sourcePackage.contains("youtube", ignoreCase = true)) {
            return "https://music.youtube.com/watch?v=$id"
        }

        val terms = listOf(artist, title).filter(String::isNotBlank).joinToString(" - ")
        return "ytsearch1:$terms"
    }

    private fun host(url: String?): String? =
        url?.takeIf(String::isNotBlank)?.let {
            runCatching { URI(it).host?.lowercase(Locale.ROOT) }.getOrNull()
        }
}
