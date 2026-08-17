package com.junkfood.seal.ui.page.settings.network

import com.junkfood.seal.util.connectWithDelimiter
import java.net.URI

data class Cookie(
    val domain: String = "",
    val name: String = "",
    val value: String = "",
    val includeSubdomains: Boolean = true,
    val path: String = "/",
    val secure: Boolean = true,
    val expiry: Long = 0L,
) {
    fun toNetscapeCookieString(): String =
        connectWithDelimiter(
            domain,
            includeSubdomains.toString().uppercase(),
            path,
            secure.toString().uppercase(),
            expiry.toString(),
            name,
            value,
            delimiter = "\u0009",
        )

    fun matches(url: String, nowSeconds: Long = System.currentTimeMillis() / 1000): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val host = uri.host?.lowercase() ?: return false
        val cookieDomain = domain.removePrefix("#HttpOnly_").removePrefix(".").lowercase()
        val requestPath = uri.path.ifEmpty { "/" }
        val domainMatches =
            if (includeSubdomains || domain.startsWith(".")) {
                host == cookieDomain || host.endsWith(".$cookieDomain")
            } else {
                host == cookieDomain
            }
        return domainMatches &&
            requestPath.startsWith(path.ifEmpty { "/" }) &&
            (!secure || uri.scheme.equals("https", ignoreCase = true)) &&
            (expiry == 0L || expiry > nowSeconds)
    }

    companion object {
        fun fromCookieHeader(url: String, header: String?): List<Cookie> {
            if (header.isNullOrBlank()) return emptyList()
            val host = runCatching { URI(url).host }.getOrNull()?.lowercase() ?: return emptyList()
            val domain =
                when {
                    host == "reddit.com" || host.endsWith(".reddit.com") -> ".reddit.com"
                    host == "pixiv.net" || host.endsWith(".pixiv.net") -> ".pixiv.net"
                    else -> ".${host.removePrefix("www.")}"
                }
            return header
                .split(';')
                .mapNotNull { raw ->
                    val value = raw.trim()
                    val separator = value.indexOf('=')
                    if (separator <= 0) return@mapNotNull null
                    Cookie(
                        domain = domain,
                        name = value.substring(0, separator).trim(),
                        value = value.substring(separator + 1),
                        includeSubdomains = true,
                        secure = url.startsWith("https://", ignoreCase = true),
                    )
                }
                .distinctBy { Triple(it.domain, it.path, it.name) }
        }

        fun fromNetscapeFile(content: String): List<Cookie> =
            content.lineSequence().mapNotNull(::fromNetscapeLine).toList()

        private fun fromNetscapeLine(line: String): Cookie? {
            val trimmed = line.trim()
            if (
                trimmed.isEmpty() || (trimmed.startsWith('#') && !trimmed.startsWith("#HttpOnly_"))
            ) {
                return null
            }
            val columns = trimmed.split('\t')
            if (columns.size < 7) return null
            val rawDomain = columns[0]
            return Cookie(
                domain = rawDomain.removePrefix("#HttpOnly_"),
                includeSubdomains = columns[1].equals("TRUE", ignoreCase = true),
                path = columns[2].ifEmpty { "/" },
                secure = columns[3].equals("TRUE", ignoreCase = true),
                expiry = columns[4].toLongOrNull() ?: 0L,
                name = columns[5],
                value = columns.subList(6, columns.size).joinToString("\t"),
            )
        }
    }
}

fun List<Cookie>.toCookieHeader(url: String): String =
    asSequence()
        .filter { it.matches(url) }
        .distinctBy { Triple(it.domain, it.path, it.name) }
        .joinToString("; ") { "${it.name}=${it.value}" }
