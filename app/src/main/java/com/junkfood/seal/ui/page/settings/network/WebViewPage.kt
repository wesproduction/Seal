package com.junkfood.seal.ui.page.settings.network

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.accompanist.web.AccompanistWebChromeClient
import com.google.accompanist.web.AccompanistWebViewClient
import com.google.accompanist.web.WebView
import com.google.accompanist.web.rememberWebViewState
import com.junkfood.seal.R
import com.junkfood.seal.util.PreferenceUtil.updateString
import com.junkfood.seal.util.USER_AGENT_STRING

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebViewPage(cookiesViewModel: CookiesViewModel, onDismissRequest: (List<String>) -> Unit) {

    val state by cookiesViewModel.stateFlow.collectAsStateWithLifecycle()

    val cookieManager = CookieManager.getInstance()
    val websiteUrl = state.editingCookieProfile.url
    val webViewState = rememberWebViewState(websiteUrl)
    var latestUrl by remember(websiteUrl) { mutableStateOf(websiteUrl) }

    fun saveAndDismiss() {
        cookieManager.flush()
        onDismissRequest(
            listOf(websiteUrl, latestUrl, "https://www.reddit.com/", "https://old.reddit.com/")
                .filter { it.startsWith("http") }
                .distinct()
        )
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(webViewState.pageTitle.toString(), maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = ::saveAndDismiss) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            stringResource(id = androidx.appcompat.R.string.abc_action_mode_done),
                        )
                    }
                },
                actions = {
                    TextButton(onClick = ::saveAndDismiss) {
                        Text(text = stringResource(id = R.string.save_cookies))
                    }
                },
            )
        },
    ) { paddingValues ->
        val webViewClient = remember {
            object : AccompanistWebViewClient() {
                override fun onPageFinished(view: WebView, url: String?) {
                    super.onPageFinished(view, url)
                    if (url.isNullOrEmpty()) return
                    latestUrl = url
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): Boolean {
                    return if (request?.url?.scheme?.contains("http") == true)
                        super.shouldOverrideUrlLoading(view, request)
                    else true
                }
            }
        }
        val webViewChromeClient = remember { object : AccompanistWebChromeClient() {} }
        WebView(
            state = webViewState,
            client = webViewClient,
            chromeClient = webViewChromeClient,
            modifier = Modifier.padding(paddingValues).fillMaxSize(),
            captureBackPresses = true,
            factory = { context ->
                WebView(context).apply {
                    settings.run {
                        javaScriptCanOpenWindowsAutomatically = true
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        USER_AGENT_STRING.updateString(userAgentString)
                    }
                    cookieManager.setAcceptCookie(true)
                    cookieManager.setAcceptThirdPartyCookies(this, true)
                }
            },
        )
    }
}
