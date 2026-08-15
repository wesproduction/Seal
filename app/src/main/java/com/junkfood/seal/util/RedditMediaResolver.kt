package com.junkfood.seal.util

import android.os.Build
import com.junkfood.seal.BuildConfig
import com.junkfood.seal.util.PreferenceUtil.getString
import java.io.IOException
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

object RedditMediaResolver {
    private val redditHosts =
        setOf(
            "reddit.com",
            "www.reddit.com",
            "old.reddit.com",
            "new.reddit.com",
            "np.reddit.com",
            "redd.it",
        )

    private val client =
        OkHttpClient.Builder().followRedirects(true).followSslRedirects(true).build()
    private val json = Json { ignoreUnknownKeys = true }

    data class RedditPost(
        val id: String,
        val title: String,
        val author: String,
        val canonicalUrl: String,
        val createdUtc: Long,
        val media: List<MediaItem>,
    ) {
        val isDirectMediaPost: Boolean
            get() = media.isNotEmpty()
    }

    data class MediaItem(
        val id: String,
        val url: String,
        val mimeType: String,
        val extension: String,
        val caption: String,
        val index: Int,
        val total: Int,
    )

    fun isRedditUrl(url: String): Boolean =
        runCatching { URI(url).host?.lowercase() in redditHosts }.getOrDefault(false)

    suspend fun resolve(sourceUrl: String): RedditPost =
        withContext(Dispatchers.IO) {
            require(isRedditUrl(sourceUrl)) { "Not a Reddit URL" }
            val userAgent =
                USER_AGENT_STRING.getString().ifBlank {
                    System.getProperty("http.agent")
                        ?: "Seal/${BuildConfig.VERSION_NAME} Android/${Build.VERSION.RELEASE}"
                }
            val cookieHeader = DownloadUtil.getCookieHeaderFor("https://www.reddit.com/")
            val canonicalUrl = resolveShareRedirect(sourceUrl, userAgent, cookieHeader)
            val postId =
                extractPostId(canonicalUrl)
                    ?: throw IOException("Reddit did not return a recognizable post link")

            val endpoints =
                listOf(
                    "https://www.reddit.com/comments/$postId/.json?raw_json=1&limit=0",
                    "https://old.reddit.com/comments/$postId/.json?raw_json=1&limit=0",
                )
            var lastFailure: Throwable? = null
            for (endpoint in endpoints) {
                try {
                    return@withContext parsePostJson(
                        requestText(endpoint, canonicalUrl, userAgent, cookieHeader),
                        canonicalUrl,
                    )
                } catch (throwable: Throwable) {
                    lastFailure = throwable
                }
            }
            throw lastFailure ?: IOException("Unable to read Reddit post")
        }

    internal fun parsePostJson(content: String, canonicalUrl: String): RedditPost {
        val root = json.parseToJsonElement(content)
        val listing =
            when (root) {
                is JsonArray -> root.firstOrNull()?.jsonObject
                is JsonObject -> root
                else -> null
            } ?: error("Reddit returned an unexpected response")
        val post =
            listing["data"]
                ?.jsonObject
                ?.get("children")
                ?.jsonArray
                ?.firstOrNull()
                ?.jsonObject
                ?.get("data")
                ?.jsonObject ?: error("Reddit post metadata is missing")

        val mediaPost = post["crosspost_parent_list"]?.jsonArray?.lastOrNull()?.jsonObject ?: post
        val id = post.string("id") ?: extractPostId(canonicalUrl) ?: "reddit"
        val title = post.string("title").orEmpty().ifBlank { "Reddit $id" }
        val author = post.string("author").orEmpty()
        val createdUtc = post["created_utc"]?.jsonPrimitive?.doubleOrNull?.toLong() ?: 0L

        val orderedMedia = parseGallery(mediaPost)
        val directMedia =
            if (orderedMedia.isNotEmpty()) {
                orderedMedia
            } else {
                parseSingleOrEmbeddedMedia(mediaPost)
            }
        val finalizedMedia =
            directMedia.mapIndexed { index, item ->
                item.copy(index = index + 1, total = directMedia.size)
            }

        return RedditPost(
            id = id,
            title = title,
            author = author,
            canonicalUrl = canonicalUrl,
            createdUtc = createdUtc,
            media = finalizedMedia,
        )
    }

    internal fun extractPostId(url: String): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val host = uri.host?.lowercase() ?: return null
        if (host == "redd.it") return uri.path.trim('/').substringBefore('/').ifBlank { null }
        return Regex("/(?:comments|gallery)/([a-z0-9]+)", RegexOption.IGNORE_CASE)
            .find(uri.path)
            ?.groupValues
            ?.get(1)
    }

    private fun parseGallery(post: JsonObject): List<MediaItem> {
        val items = post["gallery_data"]?.jsonObject?.get("items")?.jsonArray ?: return emptyList()
        val metadata = post["media_metadata"]?.jsonObject ?: return emptyList()
        return items.mapNotNull { itemElement ->
            val item = itemElement.jsonObject
            val mediaId = item.string("media_id") ?: return@mapNotNull null
            parseMediaItem(
                mediaId = mediaId,
                data = metadata[mediaId]?.jsonObject ?: return@mapNotNull null,
                caption = item.string("caption").orEmpty(),
            )
        }
    }

    private fun parseSingleOrEmbeddedMedia(post: JsonObject): List<MediaItem> {
        val postUrl = post.string("url")
        if (postUrl.isRedditImageUrl()) {
            return listOf(
                MediaItem(
                    id = post.string("id").orEmpty(),
                    url = normalizeMediaUrl(requireNotNull(postUrl)),
                    mimeType = mimeTypeFromUrl(postUrl),
                    extension = extensionFrom(postUrl, null),
                    caption = "",
                    index = 1,
                    total = 1,
                )
            )
        }

        val metadata = post["media_metadata"]?.jsonObject ?: return emptyList()
        return metadata.mapNotNull { (mediaId, data) ->
            parseMediaItem(mediaId, data.jsonObject, caption = "")
        }
    }

    private fun parseMediaItem(mediaId: String, data: JsonObject, caption: String): MediaItem? {
        if (data.string("status")?.equals("valid", ignoreCase = true) == false) return null
        val source = data["s"]?.jsonObject ?: return null
        val sourceUrl =
            source.string("u") ?: source.string("gif") ?: source.string("mp4") ?: return null
        val mimeType = data.string("m") ?: mimeTypeFromUrl(sourceUrl)
        return MediaItem(
            id = mediaId,
            url = normalizeMediaUrl(sourceUrl),
            mimeType = mimeType,
            extension = extensionFrom(sourceUrl, mimeType),
            caption = caption,
            index = 0,
            total = 0,
        )
    }

    private fun resolveShareRedirect(url: String, userAgent: String, cookies: String): String {
        if (extractPostId(url) != null) return url
        val request = requestBuilder(url, url, userAgent, cookies).build()
        client.newCall(request).execute().use { response ->
            val finalUrl = response.request.url.toString()
            if (extractPostId(finalUrl) != null) return finalUrl
            if (!response.isSuccessful) throw redditHttpError(response.code)
            return finalUrl
        }
    }

    private fun requestText(
        url: String,
        referer: String,
        userAgent: String,
        cookies: String,
    ): String {
        val request =
            requestBuilder(url, referer, userAgent, cookies)
                .header("Accept", "application/json,text/plain,*/*")
                .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw redditHttpError(response.code)
            val body = response.body.string()
            if (body.isBlank() || body.trimStart().startsWith("<!DOCTYPE", ignoreCase = true)) {
                throw IOException("Reddit returned a webpage instead of post metadata")
            }
            return body
        }
    }

    private fun requestBuilder(
        url: String,
        referer: String,
        userAgent: String,
        cookies: String,
    ): Request.Builder =
        Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Referer", referer)
            .apply { if (cookies.isNotBlank()) header("Cookie", cookies) }

    private fun redditHttpError(code: Int): IOException =
        if (code == 401 || code == 403) {
            IOException(
                "Reddit requires a signed-in session. Open Reddit sign-in in Cookies settings."
            )
        } else {
            IOException("Reddit returned HTTP $code")
        }

    private fun JsonObject.string(key: String): String? = get(key)?.jsonPrimitive?.contentOrNull

    private fun String?.isRedditImageUrl(): Boolean {
        val host = this?.let { runCatching { URI(it).host?.lowercase() }.getOrNull() }
        return host == "i.redd.it" || host == "preview.redd.it" || host == "i.reddituploads.com"
    }

    private fun normalizeMediaUrl(url: String): String {
        val unescaped = url.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        val uri = runCatching { URI(unescaped) }.getOrNull()
        return if (uri?.host.equals("preview.redd.it", ignoreCase = true)) {
            "https://i.redd.it/${requireNotNull(uri).path.substringAfterLast('/')}"
        } else {
            unescaped
        }
    }

    private fun extensionFrom(url: String, mimeType: String?): String {
        val fromMime =
            mimeType
                ?.substringAfter('/', "")
                ?.substringBefore(';')
                ?.lowercase()
                ?.replace("jpeg", "jpg")
        if (!fromMime.isNullOrBlank() && fromMime.length <= 5) return fromMime
        val fromPath =
            runCatching { URI(url).path.substringAfterLast('.', "") }.getOrDefault("").lowercase()
        return fromPath.takeIf { it.matches(Regex("[a-z0-9]{2,5}")) } ?: "jpg"
    }

    private fun mimeTypeFromUrl(url: String): String =
        when (extensionFrom(url, null)) {
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "mp4" -> "video/mp4"
            else -> "image/jpeg"
        }
}
