package com.junkfood.seal.util

import android.os.Build
import com.junkfood.seal.BuildConfig
import com.junkfood.seal.util.PreferenceUtil.getString
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

object PixivMediaResolver {
    private val pixivHosts =
        setOf("pixiv.net", "www.pixiv.net", "touch.pixiv.net", "accounts.pixiv.net")
    private val client =
        OkHttpClient.Builder().followRedirects(true).followSslRedirects(true).build()
    private val json = Json { ignoreUnknownKeys = true }

    data class PixivArtwork(
        val id: String,
        val title: String,
        val artist: String,
        val artistId: String,
        val canonicalUrl: String,
        val createdAtMillis: Long,
        val thumbnailUrl: String?,
        val media: List<MediaItem>,
    )

    data class MediaItem(
        val id: String,
        val mediaUrl: String,
        val mimeType: String,
        val extension: String,
        val index: Int,
        val total: Int,
        val ugoiraFrames: List<UgoiraFrame> = emptyList(),
    ) {
        val isUgoira: Boolean
            get() = ugoiraFrames.isNotEmpty()
    }

    data class UgoiraFrame(val file: String, val delayMillis: Int)

    fun isPixivArtworkUrl(url: String): Boolean = extractArtworkId(url) != null

    fun extractArtworkId(url: String): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (uri.host?.lowercase() !in pixivHosts) return null
        Regex("/(?:[a-z]{2}/)?artworks/(\\d+)(?:/|$)", RegexOption.IGNORE_CASE)
            .find(uri.path.orEmpty())
            ?.groupValues
            ?.get(1)
            ?.let {
                return it
            }
        Regex("/(?:i|illust)/(\\d+)(?:/|$)", RegexOption.IGNORE_CASE)
            .find(uri.path.orEmpty())
            ?.groupValues
            ?.get(1)
            ?.let {
                return it
            }
        return uri.rawQuery
            ?.split('&')
            ?.mapNotNull { parameter ->
                val (name, value) =
                    parameter.split('=', limit = 2).let {
                        it.firstOrNull().orEmpty() to it.getOrNull(1).orEmpty()
                    }
                if (!name.equals("illust_id", ignoreCase = true)) return@mapNotNull null
                URLDecoder.decode(value, StandardCharsets.UTF_8.name()).takeIf {
                    it.matches(Regex("\\d+"))
                }
            }
            ?.firstOrNull()
    }

    suspend fun resolve(sourceUrl: String): PixivArtwork =
        withContext(Dispatchers.IO) {
            val artworkId =
                extractArtworkId(sourceUrl) ?: throw IOException("Not a Pixiv artwork link")
            val canonicalUrl = "https://www.pixiv.net/artworks/$artworkId"
            val userAgent = userAgent()
            val cookies = DownloadUtil.getCookieHeaderFor("https://www.pixiv.net/")
            val metadata =
                requestJson("$PIXIV_AJAX_ROOT/$artworkId?lang=en", canonicalUrl, userAgent, cookies)
            val metadataBody = requireBodyObject(metadata)
            val illustType = metadataBody.int("illustType") ?: 0
            val pages =
                if (illustType == UGOIRA_TYPE) {
                    null
                } else {
                    requestJson(
                        "$PIXIV_AJAX_ROOT/$artworkId/pages?lang=en",
                        canonicalUrl,
                        userAgent,
                        cookies,
                        loginRequiredOnFailure = true,
                    )
                }
            val ugoira =
                if (illustType == UGOIRA_TYPE) {
                    requestJson(
                        "$PIXIV_AJAX_ROOT/$artworkId/ugoira_meta?lang=en",
                        canonicalUrl,
                        userAgent,
                        cookies,
                        loginRequiredOnFailure = true,
                    )
                } else {
                    null
                }
            parseArtwork(metadata, pages, ugoira, canonicalUrl)
        }

    internal fun parseArtwork(
        metadataContent: String,
        pagesContent: String?,
        ugoiraContent: String?,
        canonicalUrl: String,
    ): PixivArtwork =
        parseArtwork(
            metadata = parseApiResponse(metadataContent),
            pages = pagesContent?.let(::parseApiResponse),
            ugoira = ugoiraContent?.let(::parseApiResponse),
            canonicalUrl = canonicalUrl,
        )

    private fun parseArtwork(
        metadata: JsonObject,
        pages: JsonObject?,
        ugoira: JsonObject?,
        canonicalUrl: String,
    ): PixivArtwork {
        val body = requireBodyObject(metadata)
        val id = body.string("id") ?: extractArtworkId(canonicalUrl) ?: "pixiv"
        val title = body.string("title").orEmpty().ifBlank { "Pixiv $id" }
        val artist = body.string("userName").orEmpty().ifBlank { "Pixiv artist" }
        val artistId = body.string("userId").orEmpty()
        val createdAtMillis = body.string("createDate")?.let(::parsePixivDateMillis) ?: 0L
        val illustType = body.int("illustType") ?: 0
        val media =
            if (illustType == UGOIRA_TYPE) {
                parseUgoira(requireNotNull(ugoira) { "Pixiv animation metadata is missing" }, id)
            } else {
                parsePages(requireNotNull(pages) { "Pixiv page metadata is missing" }, id)
            }
        if (media.isEmpty()) throw pixivSignInError()
        val thumbnail =
            body["urls"]?.asObjectOrNull()?.let { urls ->
                urls.string("regular") ?: urls.string("original")
            } ?: media.firstOrNull { !it.isUgoira }?.mediaUrl
        return PixivArtwork(
            id = id,
            title = title,
            artist = artist,
            artistId = artistId,
            canonicalUrl = canonicalUrl,
            createdAtMillis = createdAtMillis,
            thumbnailUrl = thumbnail,
            media = media,
        )
    }

    private fun parsePages(response: JsonObject, artworkId: String): List<MediaItem> {
        val body = requireBodyArray(response)
        val total = body.size
        return body.mapIndexedNotNull { index, element ->
            val page = element.asObjectOrNull() ?: return@mapIndexedNotNull null
            val original =
                page["urls"]?.asObjectOrNull()?.string("original") ?: return@mapIndexedNotNull null
            validateMediaUrl(original)
            val extension = extensionFrom(original)
            MediaItem(
                id = "${artworkId}_p$index",
                mediaUrl = original,
                mimeType = mimeTypeFromExtension(extension),
                extension = extension,
                index = index + 1,
                total = total,
            )
        }
    }

    private fun parseUgoira(response: JsonObject, artworkId: String): List<MediaItem> {
        val body = requireBodyObject(response)
        val source = body.string("originalSrc") ?: body.string("src") ?: return emptyList()
        validateMediaUrl(source)
        val frames =
            body["frames"]?.asArrayOrNull().orEmpty().mapNotNull { element ->
                val frame = element.asObjectOrNull() ?: return@mapNotNull null
                val file = frame.string("file") ?: return@mapNotNull null
                val delay = frame.int("delay") ?: return@mapNotNull null
                if (!isSafeFrameName(file) || delay <= 0) return@mapNotNull null
                UgoiraFrame(file = file, delayMillis = delay)
            }
        if (frames.isEmpty()) return emptyList()
        return listOf(
            MediaItem(
                id = "${artworkId}_ugoira",
                mediaUrl = source,
                mimeType = "video/mp4",
                extension = "mp4",
                index = 1,
                total = 1,
                ugoiraFrames = frames,
            )
        )
    }

    private fun requestJson(
        url: String,
        referer: String,
        userAgent: String,
        cookies: String,
        loginRequiredOnFailure: Boolean = false,
    ): JsonObject {
        val request =
            Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
                .header("Referer", referer)
                .header("Accept", "application/json,text/plain,*/*")
                .apply { if (cookies.isNotBlank()) header("Cookie", cookies) }
                .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                if (
                    response.code == 401 ||
                        response.code == 403 ||
                        (loginRequiredOnFailure && response.code == 404)
                ) {
                    throw pixivSignInError()
                }
                throw IOException("Pixiv returned HTTP ${response.code}")
            }
            val content = response.body.string()
            if (content.isBlank() || content.trimStart().startsWith('<')) {
                throw IOException("Pixiv returned a webpage instead of artwork metadata")
            }
            return parseApiResponse(content)
        }
    }

    private fun parseApiResponse(content: String): JsonObject {
        val response =
            runCatching { json.parseToJsonElement(content).jsonObject }
                .getOrElse { throw IOException("Pixiv returned unreadable artwork metadata", it) }
        if (response["error"]?.jsonPrimitive?.booleanOrNull == true) {
            val message = response.string("message").orEmpty()
            throw if (message.isBlank()) pixivSignInError() else IOException(message)
        }
        return response
    }

    private fun requireBodyObject(response: JsonObject): JsonObject =
        response["body"]?.asObjectOrNull() ?: throw pixivSignInError()

    private fun requireBodyArray(response: JsonObject): JsonArray =
        response["body"]?.asArrayOrNull() ?: throw pixivSignInError()

    private fun validateMediaUrl(url: String) {
        val uri = runCatching { URI(url) }.getOrNull()
        if (uri?.scheme != "https" || uri.host?.lowercase() !in PIXIV_MEDIA_HOSTS) {
            throw IOException("Pixiv returned an unsafe media address")
        }
    }

    private fun isSafeFrameName(file: String): Boolean =
        file.isNotBlank() &&
            !file.contains('/') &&
            !file.contains('\\') &&
            file != "." &&
            file != ".."

    private fun extensionFrom(url: String): String {
        val extension =
            runCatching { URI(url).path.substringAfterLast('.', "").lowercase() }.getOrDefault("")
        return extension.takeIf { it.matches(Regex("[a-z0-9]{2,5}")) } ?: "jpg"
    }

    private fun mimeTypeFromExtension(extension: String): String =
        when (extension) {
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "avif" -> "image/avif"
            else -> "image/jpeg"
        }

    private fun parsePixivDateMillis(value: String): Long =
        listOf("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", "yyyy-MM-dd'T'HH:mm:ssXXX").firstNotNullOfOrNull {
            pattern ->
            runCatching {
                    SimpleDateFormat(pattern, Locale.US)
                        .apply { isLenient = false }
                        .parse(value)
                        ?.time
                }
                .getOrNull()
        } ?: 0L

    private fun userAgent(): String =
        USER_AGENT_STRING.getString().ifBlank {
            System.getProperty("http.agent")
                ?: "WalrusDownloader/${BuildConfig.VERSION_NAME} Android/${Build.VERSION.RELEASE}"
        }

    private fun pixivSignInError(): IOException =
        IOException("Pixiv requires a signed-in session. Open Pixiv sign-in in Cookies settings.")

    private fun JsonObject.string(key: String): String? = get(key)?.jsonPrimitive?.contentOrNull

    private fun JsonObject.int(key: String): Int? = get(key)?.jsonPrimitive?.intOrNull

    private fun kotlinx.serialization.json.JsonElement.asObjectOrNull(): JsonObject? =
        runCatching { jsonObject }.getOrNull()

    private fun kotlinx.serialization.json.JsonElement.asArrayOrNull(): JsonArray? =
        runCatching { jsonArray }.getOrNull()

    private const val UGOIRA_TYPE = 2
    private const val PIXIV_AJAX_ROOT = "https://www.pixiv.net/ajax/illust"
    private val PIXIV_MEDIA_HOSTS = setOf("i.pximg.net")
}
