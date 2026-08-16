package com.junkfood.seal.ui.page.settings.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CookieBrowserTest {
    @Test
    fun addressBarAcceptsFullUrlsAndBareDomains() {
        assertEquals(
            "https://example.com/login?next=%2Fmedia",
            resolveCookieBrowserInput("https://example.com/login?next=%2Fmedia"),
        )
        assertEquals("https://example.com", resolveCookieBrowserInput("example.com"))
        assertNull(resolveCookieBrowserInput("   "))
    }

    @Test
    fun addressBarTurnsWordsIntoASearch() {
        assertEquals(
            "https://www.google.com/search?q=example+video+website",
            resolveCookieBrowserInput("example video website"),
        )
    }

    @Test
    fun savedProfileUsesTheLastVisitedSiteThatOwnsCapturedCookies() {
        val cookies = listOf(Cookie(domain = ".example.com", name = "session", value = "signed-in"))

        assertEquals(
            "https://members.example.com/",
            cookieProfileUrl(
                visitedUrls =
                    listOf(
                        "https://www.google.com/search?q=example",
                        "https://auth.example.com/login",
                        "https://members.example.com/gallery/42",
                    ),
                cookies = cookies,
                fallbackUrl = "",
            ),
        )
    }

    @Test
    fun newerCookieValuesReplaceEarlierValues() {
        val cookies =
            listOf(
                    Cookie(domain = ".example.com", name = "session", value = "old"),
                    Cookie(domain = ".example.com", name = "theme", value = "dark"),
                    Cookie(domain = ".example.com", name = "session", value = "new"),
                )
                .keepLatestValues()

        assertEquals(2, cookies.size)
        assertEquals("new", cookies.first { it.name == "session" }.value)
        assertEquals("dark", cookies.first { it.name == "theme" }.value)
    }
}
