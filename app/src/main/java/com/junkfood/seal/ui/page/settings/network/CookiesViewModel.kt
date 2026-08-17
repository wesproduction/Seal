package com.junkfood.seal.ui.page.settings.network

import android.webkit.CookieManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.junkfood.seal.database.objects.CookieProfile
import com.junkfood.seal.util.COOKIES
import com.junkfood.seal.util.DatabaseUtil
import com.junkfood.seal.util.DownloadUtil
import com.junkfood.seal.util.DownloadUtil.toCookiesFileContent
import com.junkfood.seal.util.PreferenceUtil.updateBoolean
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CookiesViewModel : ViewModel() {
    companion object {
        const val NEW_PROFILE_ID = 0
    }

    data class ViewState(
        val editingCookieProfile: CookieProfile =
            CookieProfile(id = NEW_PROFILE_ID, url = "", content = "")
    )

    val cookiesFlow = DatabaseUtil.getCookiesFlow()

    private val mutableStateFlow = MutableStateFlow(ViewState())
    val stateFlow = mutableStateFlow.asStateFlow()
    private val state
        get() = stateFlow.value

    fun setEditingProfile(
        cookieProfile: CookieProfile = CookieProfile(id = NEW_PROFILE_ID, url = "", content = "")
    ) {
        mutableStateFlow.update { it.copy(editingCookieProfile = cookieProfile) }
    }

    fun deleteCookieProfile(cookieProfile: CookieProfile = state.editingCookieProfile) {
        viewModelScope.launch(Dispatchers.IO) { DatabaseUtil.deleteCookieProfile(cookieProfile) }
    }

    fun generateNewCookies(content: String) {
        viewModelScope.launch(Dispatchers.IO) {
            persistProfile(state.editingCookieProfile.copy(content = content))
            DownloadUtil.refreshCookiesFile()
        }
    }

    suspend fun captureWebViewCookies(urls: List<String>): CookieCaptureResult {
        val profile = state.editingCookieProfile
        val targetUrls =
            (urls + profile.url)
                .filter { it.startsWith("http://") || it.startsWith("https://") }
                .distinct()
        val cookieManager = CookieManager.getInstance().apply { flush() }
        val cookies =
            targetUrls
                .flatMap { url -> Cookie.fromCookieHeader(url, cookieManager.getCookie(url)) }
                .keepLatestValues()

        if (cookies.isEmpty()) return CookieCaptureResult(saved = false)

        val savedUrl = cookieProfileUrl(targetUrls, cookies, profile.url)
        return withContext(Dispatchers.IO) {
            persistProfile(profile.copy(url = savedUrl, content = cookies.toCookiesFileContent()))
            COOKIES.updateBoolean(true)
            DownloadUtil.refreshCookiesFile()
            CookieCaptureResult(saved = true, count = cookies.size, profileUrl = savedUrl)
        }
    }

    fun importCookieFile(content: String): Boolean {
        val cookies = Cookie.fromNetscapeFile(content)
        if (cookies.isEmpty()) return false
        val normalizedContent = cookies.toCookiesFileContent()
        val primaryDomain = cookies.first().domain.removePrefix(".")
        val profileUrl =
            if (cookies.any { it.domain.removePrefix(".").endsWith("reddit.com") }) {
                REDDIT_LOGIN_URL
            } else if (cookies.any { it.domain.removePrefix(".").endsWith("pixiv.net") }) {
                PIXIV_LOGIN_URL
            } else {
                "https://$primaryDomain/"
            }

        val cookieManager = CookieManager.getInstance()
        cookies.forEach { cookie ->
            val domain = cookie.domain.removePrefix(".")
            val attributes = buildString {
                append("${cookie.name}=${cookie.value}; Path=${cookie.path}; Domain=$domain")
                if (cookie.secure) append("; Secure")
            }
            cookieManager.setCookie("https://$domain/", attributes)
        }
        cookieManager.flush()

        viewModelScope.launch(Dispatchers.IO) {
            persistProfile(
                CookieProfile(id = NEW_PROFILE_ID, url = profileUrl, content = normalizedContent)
            )
            COOKIES.updateBoolean(true)
            DownloadUtil.refreshCookiesFile()
        }
        return true
    }

    fun updateUrl(url: String) {
        setEditingProfile(cookieProfile = state.editingCookieProfile.copy(url = url))
    }

    fun updateContent(content: String) =
        mutableStateFlow.update {
            it.copy(editingCookieProfile = it.editingCookieProfile.copy(content = content))
        }

    fun updateCookieProfile(profile: CookieProfile = state.editingCookieProfile) {
        viewModelScope.launch(Dispatchers.IO) { persistProfile(profile) }
    }

    private suspend fun persistProfile(profile: CookieProfile): CookieProfile {
        val savedProfile =
            if (profile.id == NEW_PROFILE_ID) {
                profile.copy(id = DatabaseUtil.insertCookieProfile(profile).toInt())
            } else {
                DatabaseUtil.updateCookieProfile(profile)
                profile
            }
        mutableStateFlow.update { it.copy(editingCookieProfile = savedProfile) }
        return savedProfile
    }
}

const val REDDIT_LOGIN_URL = "https://www.reddit.com/login/"
const val PIXIV_LOGIN_URL = "https://accounts.pixiv.net/login?lang=en"

data class CookieCaptureResult(val saved: Boolean, val count: Int = 0, val profileUrl: String = "")

internal fun List<Cookie>.keepLatestValues(): List<Cookie> =
    asReversed().distinctBy { Triple(it.domain, it.path, it.name) }.asReversed()

internal fun cookieProfileUrl(
    visitedUrls: List<String>,
    cookies: List<Cookie>,
    fallbackUrl: String,
): String {
    if (cookies.any { it.domain.removePrefix(".").endsWith("reddit.com") }) {
        return REDDIT_LOGIN_URL
    }
    if (cookies.any { it.domain.removePrefix(".").endsWith("pixiv.net") }) {
        return PIXIV_LOGIN_URL
    }

    val matchingUrl =
        visitedUrls.asReversed().firstOrNull { url ->
            cookies.any { cookie -> cookie.matches(url) }
        }
    val preferredUrl = matchingUrl ?: visitedUrls.lastOrNull() ?: fallbackUrl
    return runCatching {
            val uri = URI(preferredUrl)
            check(
                (uri.scheme.equals("http", ignoreCase = true) ||
                    uri.scheme.equals("https", ignoreCase = true)) && !uri.host.isNullOrBlank()
            )
            buildString {
                append(uri.scheme.lowercase())
                append("://")
                append(uri.host.lowercase())
                if (uri.port >= 0) append(":${uri.port}")
                append('/')
            }
        }
        .getOrDefault(fallbackUrl)
}
