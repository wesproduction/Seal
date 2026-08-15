package com.junkfood.seal.util

import android.os.Build
import com.junkfood.seal.BuildConfig
import com.junkfood.seal.util.PreferenceUtil.getString
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

object RedditMediaResolver {
    const val MAX_FEED_POSTS = 1_000
    private const val FEED_PAGE_SIZE = 100
    private const val FEED_PAGE_DELAY_MILLIS = 250L

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
        val isVideoPost: Boolean = false,
    ) {
        val isDirectMediaPost: Boolean
            get() = media.isNotEmpty()

        val isDownloadablePost: Boolean
            get() = isDirectMediaPost || isVideoPost
    }

    enum class FeedKind {
        Subreddit,
        User,
    }

    data class FeedTarget(val kind: FeedKind, val name: String, val canonicalUrl: String) {
        val displayName: String
            get() = if (kind == FeedKind.Subreddit) "r/$name" else "u/$name"

        internal val listingPath: String
            get() = if (kind == FeedKind.Subreddit) "r/$name/new" else "user/$name/submitted"
    }

    data class FeedProgress(
        val target: FeedTarget,
        val scannedPosts: Int,
        val mediaPosts: Int,
        val mediaItems: Int,
    )

    data class RedditFeed(
        val target: FeedTarget,
        val posts: List<RedditPost>,
        val scannedPosts: Int,
    )

    internal data class FeedPage(val posts: List<RedditPost>, val after: String?)

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

    fun extractFeedTarget(url: String): FeedTarget? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (uri.host?.lowercase() !in redditHosts) return null
        val segments = uri.path.split('/').filter(String::isNotBlank)
        if (segments.size < 2) return null
        val root = segments[0].lowercase()
        val name = segments[1].takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,64}")) } ?: return null
        val suffix = segments.getOrNull(2)?.lowercase()
        return when {
            root == "r" && suffix in setOf(null, "new", "hot", "top", "rising") ->
                FeedTarget(FeedKind.Subreddit, name, "https://www.reddit.com/r/$name/new/")

            root in setOf("u", "user") && suffix in setOf(null, "submitted", "overview") ->
                FeedTarget(FeedKind.User, name, "https://www.reddit.com/user/$name/submitted/")

            else -> null
        }
    }

    suspend fun resolve(sourceUrl: String): RedditPost =
        withContext(Dispatchers.IO) {
            require(isRedditUrl(sourceUrl)) { "Not a Reddit URL" }
            val userAgent =
                USER_AGENT_STRING.getString().ifBlank {
                    System.getProperty("http.agent")
                        ?: "WalrusDownloader/${BuildConfig.VERSION_NAME} Android/${Build.VERSION.RELEASE}"
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

    suspend fun resolveFeed(
        sourceUrl: String,
        onProgress: (FeedProgress) -> Unit = {},
    ): RedditFeed =
        withContext(Dispatchers.IO) {
            val target =
                extractFeedTarget(sourceUrl)
                    ?: throw IOException("Reddit did not return a subreddit or user profile link")
            val userAgent = userAgent()
            val cookieHeader = DownloadUtil.getCookieHeaderFor("https://www.reddit.com/")
            val posts = mutableListOf<RedditPost>()
            val seenPostIds = mutableSetOf<String>()
            val seenAfter = mutableSetOf<String>()
            var scannedPosts = 0
            var after: String? = null

            while (scannedPosts < MAX_FEED_POSTS) {
                coroutineContext.ensureActive()
                val pageLimit = minOf(FEED_PAGE_SIZE, MAX_FEED_POSTS - scannedPosts)
                val endpoints =
                    listOf("www.reddit.com", "old.reddit.com").map { host ->
                        buildListingEndpoint(host, target, pageLimit, scannedPosts, after)
                    }
                var lastFailure: Throwable? = null
                var page: FeedPage? = null
                for (endpoint in endpoints) {
                    try {
                        page =
                            parseFeedPage(
                                requestText(endpoint, target.canonicalUrl, userAgent, cookieHeader),
                                target,
                            )
                        break
                    } catch (throwable: Throwable) {
                        lastFailure = throwable
                    }
                }
                val resolvedPage =
                    page ?: throw lastFailure ?: IOException("Unable to read Reddit listing")
                if (resolvedPage.posts.isEmpty()) break

                scannedPosts += resolvedPage.posts.size
                resolvedPage.posts.forEach { post ->
                    if (post.isDownloadablePost && seenPostIds.add(post.id)) posts += post
                }
                onProgress(
                    FeedProgress(
                        target = target,
                        scannedPosts = scannedPosts,
                        mediaPosts = posts.size,
                        mediaItems = posts.sumOf { maxOf(1, it.media.size) },
                    )
                )

                val nextAfter = resolvedPage.after
                if (nextAfter.isNullOrBlank() || !seenAfter.add(nextAfter)) break
                after = nextAfter
                delay(FEED_PAGE_DELAY_MILLIS)
            }

            RedditFeed(target = target, posts = posts, scannedPosts = scannedPosts)
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

        return parsePostObject(post, canonicalUrl)
    }

    internal fun parseFeedPage(content: String, target: FeedTarget): FeedPage {
        val listing = json.parseToJsonElement(content).jsonObject
        val data = listing["data"]?.jsonObject ?: error("Reddit listing metadata is missing")
        val posts =
            data["children"]?.jsonArray.orEmpty().mapNotNull { childElement ->
                val post = childElement.jsonObject["data"]?.jsonObject ?: return@mapNotNull null
                val canonicalUrl =
                    post.string("permalink")?.let { "https://www.reddit.com$it" }
                        ?: target.canonicalUrl
                parsePostObject(post, canonicalUrl)
            }
        return FeedPage(posts = posts, after = data.string("after"))
    }

    private fun parsePostObject(post: JsonObject, canonicalUrl: String): RedditPost {
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
            isVideoPost = post.isVideoPost() || mediaPost.isVideoPost(),
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
        val postUrl = post.string("url_overridden_by_dest") ?: post.string("url")
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

    private fun userAgent(): String =
        USER_AGENT_STRING.getString().ifBlank {
            System.getProperty("http.agent")
                ?: "WalrusDownloader/${BuildConfig.VERSION_NAME} Android/${Build.VERSION.RELEASE}"
        }

    private fun buildListingEndpoint(
        host: String,
        target: FeedTarget,
        limit: Int,
        count: Int,
        after: String?,
    ): String = buildString {
        append("https://")
        append(host)
        append('/')
        append(target.listingPath)
        append(".json?raw_json=1&limit=")
        append(limit)
        append("&count=")
        append(count)
        append("&show=all")
        if (!after.isNullOrBlank()) {
            append("&after=")
            append(URLEncoder.encode(after, StandardCharsets.UTF_8.name()))
        }
    }

    private fun redditHttpError(code: Int): IOException =
        if (code == 401 || code == 403) {
            IOException(
                "Reddit requires a signed-in session. Open Reddit sign-in in Cookies settings."
            )
        } else {
            IOException("Reddit returned HTTP $code")
        }

    private fun JsonObject.string(key: String): String? = get(key)?.jsonPrimitive?.contentOrNull

    private fun JsonObject.isVideoPost(): Boolean {
        if (get("is_video")?.jsonPrimitive?.booleanOrNull == true) return true
        if (string("post_hint") in setOf("hosted:video", "rich:video")) return true
        return listOf("secure_media", "media").any { mediaKey ->
            get(mediaKey)?.let { runCatching { it.jsonObject }.getOrNull() }?.get("reddit_video") !=
                null
        }
    }

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
