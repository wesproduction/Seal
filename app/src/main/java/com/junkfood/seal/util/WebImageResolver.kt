package com.junkfood.seal.util

import android.os.Build
import com.junkfood.seal.BuildConfig
import com.junkfood.seal.util.PreferenceUtil.getString
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

object WebImageResolver {
    private const val MAX_HTML_BYTES = 8 * 1024 * 1024
    internal const val MAX_IMAGES = 100
    private val client =
        OkHttpClient.Builder().followRedirects(true).followSslRedirects(true).build()
    private val highQualityAttributes =
        listOf(
            "data-original",
            "data-orig-file",
            "data-full",
            "data-full-src",
            "data-high-res-src",
            "data-hires",
            "data-large-file",
            "data-zoom-image",
        )
    private val lazySourceAttributes = listOf("data-src", "data-lazy-src", "data-image", "data-url")
    private val sourceSetAttributes = listOf("srcset", "data-srcset", "data-lazy-srcset")
    private val skippedUiHint =
        Regex(
            "(?:^|[-_/\\s])(avatar|badge|button|control|emoji|favicon|icon|logo|menu|navigation|pixel|placeholder|search|spinner|sprite|toolbar|tracking)(?:[-_/\\s.]|$)",
            RegexOption.IGNORE_CASE,
        )
    private val imageExtensions =
        setOf("jpg", "jpeg", "png", "webp", "gif", "avif", "bmp", "heic", "heif")

    data class WebImagePage(
        val id: String,
        val title: String,
        val siteName: String,
        val canonicalUrl: String,
        val media: List<MediaItem>,
        val hasVideoMetadata: Boolean,
        val isImageFocused: Boolean,
    )

    data class MediaItem(
        val id: String,
        val mediaUrl: String,
        val mimeType: String,
        val extension: String,
        val caption: String,
        val index: Int,
        val total: Int,
    )

    suspend fun resolve(sourceUrl: String): WebImagePage =
        withContext(Dispatchers.IO) {
            requireWebUrl(sourceUrl)
            val userAgent = userAgent()
            val request =
                Request.Builder()
                    .url(sourceUrl)
                    .header("User-Agent", userAgent)
                    .header(
                        "Accept",
                        "text/html,application/xhtml+xml,image/avif,image/webp,image/*,*/*;q=0.8",
                    )
                    .header("Referer", sourceUrl)
                    .apply {
                        DownloadUtil.getCookieHeaderFor(sourceUrl).takeIf(String::isNotBlank)?.let {
                            header("Cookie", it)
                        }
                    }
                    .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("Website returned HTTP ${response.code}")
                }
                val finalUrl = response.request.url.toString()
                val contentType =
                    response.body
                        .contentType()
                        ?.toString()
                        ?.substringBefore(';')
                        ?.lowercase()
                        .orEmpty()
                if (contentType.startsWith("image/")) {
                    return@withContext directImagePage(finalUrl, contentType)
                }
                if (
                    contentType.isNotBlank() &&
                        contentType != "text/html" &&
                        contentType != "application/xhtml+xml"
                ) {
                    throw IOException("Shared link is not an HTML page or image")
                }
                parsePage(readHtml(response), finalUrl)
            }
        }

    internal fun parsePage(html: String, sourceUrl: String): WebImagePage {
        requireWebUrl(sourceUrl)
        val document = Jsoup.parse(html, sourceUrl)
        val canonicalUrl =
            document.selectFirst("link[rel=canonical]")?.absUrl("href").orEmpty().takeIf {
                it.isWebUrl()
            } ?: sourceUrl
        val siteName =
            metadataContent(document, "meta[property=og:site_name]")
                .ifBlank { runCatching { URI(canonicalUrl).host }.getOrNull().orEmpty() }
                .removePrefix("www.")
                .ifBlank { "Website" }
        val title =
            metadataContent(document, "meta[property=og:title]")
                .ifBlank { document.title() }
                .replace(Regex("\\s+"), " ")
                .trim()
                .ifBlank { siteName }
        val hasVideoMetadata = document.hasVideoMetadata()
        val pageId = stableId(canonicalUrl)
        val candidates = collectCandidates(document, canonicalUrl)
        val unique = LinkedHashMap<String, ImageCandidate>()
        candidates.forEach { candidate -> unique.putIfAbsent(candidate.url, candidate) }
        val selected = unique.values.take(MAX_IMAGES)
        val total = selected.size
        val media =
            selected.mapIndexed { index, candidate ->
                val extension = extensionFrom(candidate.url, candidate.mimeHint)
                MediaItem(
                    id = "${pageId}_${stableId(candidate.url).take(10)}",
                    mediaUrl = candidate.url,
                    mimeType = mimeTypeFrom(extension, candidate.mimeHint),
                    extension = extension,
                    caption = candidate.caption.ifBlank { "Image ${index + 1}" },
                    index = index + 1,
                    total = total,
                )
            }
        val hasMainImage =
            document.select("main img, article img, [role=main] img").any { !it.isUiImage() }
        val oneProminentImage =
            candidates.count { it.fromImageElement } == 1 && document.text().length < 500
        val imageFocused =
            !hasVideoMetadata &&
                media.isNotEmpty() &&
                (candidates.any { it.strong } ||
                    candidates.count { it.fromImageElement } > 1 ||
                    hasMainImage ||
                    oneProminentImage)
        return WebImagePage(
            id = pageId,
            title = title,
            siteName = siteName,
            canonicalUrl = canonicalUrl,
            media = media,
            hasVideoMetadata = hasVideoMetadata,
            isImageFocused = imageFocused,
        )
    }

    private fun collectCandidates(document: Document, baseUrl: String): List<ImageCandidate> {
        val candidates = mutableListOf<ImageCandidate>()
        document.select("img").forEach { image ->
            if (image.isUiImage()) return@forEach
            val options = mutableListOf<ImageCandidate>()
            val caption = image.attr("alt").ifBlank { image.attr("title") }.trim()
            image
                .closest("a[href]")
                ?.absUrl("href")
                ?.takeIf { it.isDirectImageUrl() }
                ?.let {
                    options +=
                        ImageCandidate(
                            url = it,
                            caption = caption,
                            mimeHint = "",
                            score = 6_000_000,
                            strong = true,
                            fromImageElement = true,
                        )
                }
            highQualityAttributes.forEachIndexed { priority, attribute ->
                image.resolveAttribute(attribute, baseUrl)?.let {
                    options +=
                        ImageCandidate(
                            url = it,
                            caption = caption,
                            mimeHint = "",
                            score = 5_000_000 - priority,
                            strong = true,
                            fromImageElement = true,
                        )
                }
            }
            val sourceElements = image.closest("picture")?.select("source") ?: emptyList()
            (sourceElements + image).forEach { source ->
                sourceSetAttributes.forEach { attribute ->
                    options +=
                        parseSourceSet(
                            value = source.attr(attribute),
                            baseUrl = baseUrl,
                            caption = caption,
                            mimeHint = source.attr("type"),
                        )
                }
            }
            lazySourceAttributes.forEachIndexed { priority, attribute ->
                image.resolveAttribute(attribute, baseUrl)?.let {
                    options +=
                        ImageCandidate(
                            url = it,
                            caption = caption,
                            mimeHint = "",
                            score = 2_000_000 - priority,
                            strong = false,
                            fromImageElement = true,
                        )
                }
            }
            image.resolveAttribute("src", baseUrl)?.let {
                options +=
                    ImageCandidate(
                        url = it,
                        caption = caption,
                        mimeHint = "",
                        score = 1_000_000,
                        strong = false,
                        fromImageElement = true,
                    )
            }
            options
                .filter { it.url.isDownloadableImageUrl(it.mimeHint) }
                .maxByOrNull(ImageCandidate::score)
                ?.let(candidates::add)
        }

        document.select("a[href]").forEach { anchor ->
            if (anchor.selectFirst("img") != null) return@forEach
            anchor
                .absUrl("href")
                .takeIf { it.isDirectImageUrl() }
                ?.let {
                    candidates +=
                        ImageCandidate(
                            url = it,
                            caption = anchor.attr("title").ifBlank { anchor.text() }.trim(),
                            mimeHint = "",
                            score = 5_500_000,
                            strong = true,
                            fromImageElement = false,
                        )
                }
        }

        listOf(
                "meta[property=og:image:secure_url]",
                "meta[property=og:image]",
                "meta[name=twitter:image:src]",
                "meta[name=twitter:image]",
                "link[rel=image_src]",
            )
            .forEach { selector ->
                document.select(selector).forEach { element ->
                    val attribute = if (element.tagName() == "link") "href" else "content"
                    element.resolveAttribute(attribute, baseUrl)?.let {
                        if (it.isDownloadableImageUrl("")) {
                            candidates +=
                                ImageCandidate(
                                    url = it,
                                    caption = titleForMetadata(document),
                                    mimeHint = "",
                                    score = 900_000,
                                    strong = false,
                                    fromImageElement = false,
                                )
                        }
                    }
                }
            }
        return candidates
    }

    private fun parseSourceSet(
        value: String,
        baseUrl: String,
        caption: String,
        mimeHint: String,
    ): List<ImageCandidate> =
        value.split(',').mapNotNull { raw ->
            val parts = raw.trim().split(Regex("\\s+"), limit = 2)
            val url = resolveUrl(baseUrl, parts.firstOrNull().orEmpty()) ?: return@mapNotNull null
            val descriptor = parts.getOrNull(1).orEmpty().trim().lowercase()
            val descriptorScore =
                when {
                    descriptor.endsWith('w') ->
                        descriptor.dropLast(1).toIntOrNull()?.coerceAtLeast(1) ?: 1
                    descriptor.endsWith('x') ->
                        ((descriptor.dropLast(1).toDoubleOrNull() ?: 1.0) * 1_000).toInt()
                    else -> 1
                }
            ImageCandidate(
                url = url,
                caption = caption,
                mimeHint = mimeHint,
                score = 3_000_000 + descriptorScore,
                strong = descriptorScore >= 1_200,
                fromImageElement = true,
            )
        }

    private fun Element.resolveAttribute(attribute: String, baseUrl: String): String? =
        attr(attribute).trim().takeIf(String::isNotBlank)?.let { resolveUrl(baseUrl, it) }

    private fun Element.isUiImage(): Boolean {
        if (attr("aria-hidden").equals("true", ignoreCase = true)) return true
        val hasHigherQualitySource =
            (highQualityAttributes + lazySourceAttributes + sourceSetAttributes).any {
                attr(it).isNotBlank()
            } ||
                closest("picture")?.select("source")?.any { source ->
                    sourceSetAttributes.any { source.attr(it).isNotBlank() }
                } == true ||
                closest("a[href]")?.absUrl("href")?.isDirectImageUrl() == true
        val hint =
            buildList {
                    add(id())
                    add(className())
                    add(attr("role"))
                    add(attr("alt"))
                    if (!hasHigherQualitySource) add(attr("src"))
                }
                .joinToString(" ")
        if (skippedUiHint.containsMatchIn(hint)) return true
        if (hasHigherQualitySource) return false
        val width = dimension("width")
        val height = dimension("height")
        return width in 1..96 && height in 1..96
    }

    private fun Element.dimension(attribute: String): Int =
        attr(attribute).trim().removeSuffix("px").toIntOrNull() ?: 0

    private fun Document.hasVideoMetadata(): Boolean {
        val ogType = metadataContent(this, "meta[property=og:type]")
        return ogType.contains("video", ignoreCase = true) ||
            selectFirst(
                "meta[property^=og:video], meta[name^=twitter:player], video, " +
                    "source[type^=video/]"
            ) != null ||
            select("iframe[src]").any {
                val source = it.attr("src")
                source.contains("youtube", true) ||
                    source.contains("vimeo", true) ||
                    source.contains("dailymotion", true) ||
                    source.contains("twitch", true)
            }
    }

    private fun directImagePage(url: String, mimeType: String): WebImagePage {
        val pageId = stableId(url)
        val extension = extensionFrom(url, mimeType)
        val title =
            runCatching { URI(url).path.substringAfterLast('/').substringBeforeLast('.') }
                .getOrNull()
                .orEmpty()
                .ifBlank { "Website image" }
        val siteName = runCatching { URI(url).host }.getOrNull().orEmpty().removePrefix("www.")
        return WebImagePage(
            id = pageId,
            title = title,
            siteName = siteName.ifBlank { "Website" },
            canonicalUrl = url,
            media =
                listOf(
                    MediaItem(
                        id = "${pageId}_${stableId(url).take(10)}",
                        mediaUrl = url,
                        mimeType = mimeTypeFrom(extension, mimeType),
                        extension = extension,
                        caption = title,
                        index = 1,
                        total = 1,
                    )
                ),
            hasVideoMetadata = false,
            isImageFocused = true,
        )
    }

    private fun readHtml(response: Response): String {
        val expectedLength = response.body.contentLength()
        if (expectedLength > MAX_HTML_BYTES) throw IOException("Webpage is too large to inspect")
        val output = ByteArrayOutputStream()
        response.body.byteStream().use { input ->
            val buffer = ByteArray(32 * 1024)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > MAX_HTML_BYTES) throw IOException("Webpage is too large to inspect")
                output.write(buffer, 0, read)
            }
        }
        val charset =
            response.body.contentType()?.charset(StandardCharsets.UTF_8) ?: StandardCharsets.UTF_8
        return output.toString(charset.name())
    }

    private fun extensionFrom(url: String, mimeHint: String): String {
        val fromMime = extensionFromMime(mimeHint)
        val uri = runCatching { URI(url) }.getOrNull()
        val pathExtension =
            uri?.path.orEmpty().substringAfterLast('.', "").lowercase().takeIf {
                it in imageExtensions
            }
        val queryExtension =
            uri?.rawQuery
                ?.split('&')
                ?.mapNotNull { parameter ->
                    val name = parameter.substringBefore('=').lowercase()
                    val value = parameter.substringAfter('=', "").lowercase()
                    value.takeIf { name in setOf("format", "fm", "ext") && it in imageExtensions }
                }
                ?.firstOrNull()
        return pathExtension ?: queryExtension ?: fromMime ?: "jpg"
    }

    private fun mimeTypeFrom(extension: String, mimeHint: String): String {
        val normalizedHint = mimeHint.substringBefore(';').lowercase()
        if (normalizedHint.startsWith("image/") && normalizedHint != "image/svg+xml") {
            return normalizedHint
        }
        return when (extension) {
            "jpg",
            "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "avif" -> "image/avif"
            "bmp" -> "image/bmp"
            "heic" -> "image/heic"
            "heif" -> "image/heif"
            else -> "image/jpeg"
        }
    }

    private fun extensionFromMime(mimeType: String): String? =
        when (mimeType.substringBefore(';').lowercase()) {
            "image/jpeg" -> "jpg"
            "image/png" -> "png"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            "image/avif" -> "avif"
            "image/bmp" -> "bmp"
            "image/heic" -> "heic"
            "image/heif" -> "heif"
            else -> null
        }

    private fun String.isDownloadableImageUrl(mimeHint: String): Boolean {
        if (!isWebUrl()) return false
        if (mimeHint.equals("image/svg+xml", ignoreCase = true)) return false
        val extension =
            runCatching { URI(this).path.substringAfterLast('.', "").lowercase() }.getOrDefault("")
        return extension != "svg"
    }

    private fun String.isDirectImageUrl(): Boolean {
        if (!isWebUrl()) return false
        val uri = runCatching { URI(this) }.getOrNull() ?: return false
        val extension = uri.path.substringAfterLast('.', "").lowercase()
        return extension in imageExtensions
    }

    private fun String.isWebUrl(): Boolean {
        val uri = runCatching { URI(this) }.getOrNull() ?: return false
        return (uri.scheme == "https" || uri.scheme == "http") && !uri.host.isNullOrBlank()
    }

    private fun resolveUrl(baseUrl: String, rawUrl: String): String? {
        val cleaned = rawUrl.trim().replace("&amp;", "&")
        if (
            cleaned.isBlank() ||
                cleaned.startsWith("data:", true) ||
                cleaned.startsWith("blob:", true) ||
                cleaned.startsWith("javascript:", true)
        ) {
            return null
        }
        return runCatching { URI(baseUrl).resolve(cleaned).toString().substringBefore('#') }
            .getOrNull()
            ?.takeIf { it.isWebUrl() }
    }

    private fun metadataContent(document: Document, selector: String): String =
        document.selectFirst(selector)?.attr("content").orEmpty().trim()

    private fun titleForMetadata(document: Document): String =
        metadataContent(document, "meta[property=og:title]").ifBlank { document.title() }.trim()

    private fun stableId(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            .take(16)

    private fun requireWebUrl(url: String) {
        if (!url.isWebUrl()) throw IOException("Only HTTP and HTTPS webpages are supported")
    }

    private fun userAgent(): String =
        USER_AGENT_STRING.getString().ifBlank {
            System.getProperty("http.agent")
                ?: "WalrusDownloader/${BuildConfig.VERSION_NAME} Android/${Build.VERSION.RELEASE}"
        }

    private data class ImageCandidate(
        val url: String,
        val caption: String,
        val mimeHint: String,
        val score: Int,
        val strong: Boolean,
        val fromImageElement: Boolean,
    )
}
