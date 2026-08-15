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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
        cookieProfile: CookieProfile =
            CookieProfile(id = NEW_PROFILE_ID, url = "https://", content = "")
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

    fun captureWebViewCookies(urls: List<String>): Job {
        val profile = state.editingCookieProfile
        val targetUrls =
            (urls + profile.url)
                .filter { it.startsWith("http://") || it.startsWith("https://") }
                .distinct()
        val cookieManager = CookieManager.getInstance().apply { flush() }
        val cookies =
            targetUrls
                .flatMap { url -> Cookie.fromCookieHeader(url, cookieManager.getCookie(url)) }
                .distinctBy { Triple(it.domain, it.path, it.name) }

        return viewModelScope.launch(Dispatchers.IO) {
            if (cookies.isNotEmpty()) {
                val savedUrl =
                    if (cookies.any { it.domain.removePrefix(".").endsWith("reddit.com") }) {
                        REDDIT_LOGIN_URL
                    } else {
                        profile.url
                    }
                persistProfile(
                    profile.copy(url = savedUrl, content = cookies.toCookiesFileContent())
                )
                COOKIES.updateBoolean(true)
                DownloadUtil.refreshCookiesFile()
            }
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
