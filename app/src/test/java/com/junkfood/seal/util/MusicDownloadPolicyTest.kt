package com.junkfood.seal.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicDownloadPolicyTest {
    @Test
    fun `youtube music urls use the music library`() {
        val preferences = DownloadUtil.DownloadPreferences.EMPTY.copy(extractAudio = true)

        assertTrue(
            MusicDownloadPolicy.shouldOrganize(
                info = VideoInfo(vcodec = "none"),
                preferences = preferences,
                sourceUrl = "https://music.youtube.com/watch?v=BaW_jenozKc",
            )
        )
    }

    @Test
    fun `recognized music metadata uses the music library on normal youtube`() {
        val preferences = DownloadUtil.DownloadPreferences.EMPTY.copy(extractAudio = true)

        assertTrue(
            MusicDownloadPolicy.shouldOrganize(
                info = VideoInfo(track = "Song", artist = "Artist", album = "Album"),
                preferences = preferences,
                sourceUrl = "https://www.youtube.com/watch?v=BaW_jenozKc",
            )
        )
    }

    @Test
    fun `generic non-music audio keeps the existing output behavior`() {
        val preferences = DownloadUtil.DownloadPreferences.EMPTY.copy(extractAudio = true)

        assertFalse(
            MusicDownloadPolicy.shouldOrganize(
                info = VideoInfo(title = "Recorded lecture"),
                preferences = preferences,
                sourceUrl = "https://example.com/lecture.mp3",
            )
        )
    }

    @Test
    fun `explicit history download always uses music organization`() {
        val preferences =
            DownloadUtil.DownloadPreferences.EMPTY.copy(
                extractAudio = true,
                organizeMusicLibrary = true,
            )

        assertTrue(
            MusicDownloadPolicy.shouldOrganize(
                info = VideoInfo(title = "Song"),
                preferences = preferences,
                sourceUrl = "ytsearch1:Artist - Song",
            )
        )
    }

    @Test
    fun `music output template groups artist album and zero-padded track`() {
        assertEquals(
            "%(artist,album_artist,uploader,channel|Unknown Artist).120B/" +
                "%(album,playlist|Singles).120B/" +
                "%(track_number,playlist_index|01)02d - %(track,title).200B.%(ext)s",
            MusicDownloadPolicy.OUTPUT_TEMPLATE,
        )
    }

    @Test
    fun `square artwork postprocessor uses the android compatible ffmpeg crop`() {
        assertEquals(
            "--ppa \"ffmpeg: -c:v mjpeg -vf " +
                "crop=\\\"'if(gt(ih,iw),iw,ih)':'if(gt(iw,ih),ih,iw)'\\\"\"",
            MusicDownloadPolicy.SQUARE_ARTWORK_CONFIG,
        )
    }

    @Test
    fun `format labels expose actual container choices`() {
        val empty = DownloadUtil.DownloadPreferences.EMPTY
        assertEquals("Best available", MusicDownloadPolicy.audioFormatLabel(empty))
        assertEquals(
            "Opus",
            MusicDownloadPolicy.audioFormatLabel(
                empty.copy(useCustomAudioPreset = true, audioFormat = OPUS)
            ),
        )
        assertEquals(
            "M4A / AAC",
            MusicDownloadPolicy.audioFormatLabel(
                empty.copy(useCustomAudioPreset = true, audioFormat = M4A)
            ),
        )
        assertEquals(
            "MP3",
            MusicDownloadPolicy.audioFormatLabel(
                empty.copy(
                    useCustomAudioPreset = true,
                    convertAudio = true,
                    audioConvertFormat = CONVERT_MP3,
                )
            ),
        )
    }

    @Test
    fun `same repeated song normalizes to one stable identity`() {
        val first =
            MusicDownloadPolicy.normalizeIdentity(
                "com.google.android.apps.youtube.music",
                "The Artist",
                "The Song!",
            )
        val repeated =
            MusicDownloadPolicy.normalizeIdentity(
                "com.google.android.apps.youtube.music",
                "  the   artist ",
                "the song",
            )

        assertEquals(first, repeated)
    }

    @Test
    fun `youtube media id becomes exact music url and other players use search`() {
        assertEquals(
            "https://music.youtube.com/watch?v=BaW_jenozKc",
            MusicDownloadPolicy.downloadTarget(
                sourcePackage = "com.google.android.apps.youtube.music",
                mediaId = "BaW_jenozKc",
                sourceUrl = null,
                artist = "Artist",
                title = "Song",
            ),
        )
        assertEquals(
            "ytsearch1:Artist - Song",
            MusicDownloadPolicy.downloadTarget(
                sourcePackage = "com.spotify.music",
                mediaId = "spotify-id",
                sourceUrl = null,
                artist = "Artist",
                title = "Song",
            ),
        )
    }

    @Test
    fun `saved cookies are automatically matched by domain`() {
        val cookies =
            "# Netscape HTTP Cookie File\n" +
                ".youtube.com\tTRUE\t/\tTRUE\t0\tSID\tsecret\n" +
                ".reddit.com\tTRUE\t/\tTRUE\t0\treddit_session\tsecret\n"

        assertTrue(DownloadUtil.hasSavedCookiesFor("https://music.youtube.com/playlist", cookies))
        assertFalse(DownloadUtil.hasSavedCookiesFor("https://example.com/playlist", cookies))
    }
}
