package com.junkfood.seal.ui.page.settings.network

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Build
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.accompanist.web.AccompanistWebChromeClient
import com.google.accompanist.web.AccompanistWebViewClient
import com.google.accompanist.web.LoadingState
import com.google.accompanist.web.WebView
import com.google.accompanist.web.rememberWebViewNavigator
import com.google.accompanist.web.rememberWebViewState
import com.junkfood.seal.R
import com.junkfood.seal.util.PreferenceUtil.updateString
import com.junkfood.seal.util.USER_AGENT_STRING
import com.junkfood.seal.util.makeToast
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.launch

private const val BLANK_PAGE = "about:blank"
private const val SEARCH_URL = "https://www.google.com/search?q="

internal fun resolveCookieBrowserInput(input: String): String? {
    val value = input.trim()
    if (value.isEmpty()) return null

    val explicitUrl =
        runCatching { URI(value) }
            .getOrNull()
            ?.takeIf {
                (it.scheme.equals("http", ignoreCase = true) ||
                    it.scheme.equals("https", ignoreCase = true)) && !it.host.isNullOrBlank()
            }
    if (explicitUrl != null) return value

    if (value.none(Char::isWhitespace)) {
        val website = "https://${value.removePrefix("//")}"
        val host = runCatching { URI(website).host }.getOrNull()
        if (!host.isNullOrBlank() && (host.contains('.') || host == "localhost")) return website
    }

    return SEARCH_URL + URLEncoder.encode(value, StandardCharsets.UTF_8.toString())
}

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebViewPage(cookiesViewModel: CookiesViewModel, onDismissRequest: () -> Unit) {
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    val state by cookiesViewModel.stateFlow.collectAsStateWithLifecycle()
    val cookieManager = CookieManager.getInstance()
    val profileUrl = state.editingCookieProfile.url
    val initialUrl = resolveCookieBrowserInput(profileUrl) ?: BLANK_PAGE
    val webViewState = rememberWebViewState(initialUrl)
    val navigator = rememberWebViewNavigator()
    val visitedUrls = remember(profileUrl) { mutableStateListOf<String>() }
    var latestUrl by remember(profileUrl) { mutableStateOf(initialUrl) }
    var addressText by
        remember(profileUrl) { mutableStateOf(if (initialUrl == BLANK_PAGE) "" else initialUrl) }
    var addressBarFocused by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }

    fun rememberVisitedUrl(url: String?) {
        if (url.isNullOrBlank() || (!url.startsWith("http://") && !url.startsWith("https://"))) {
            return
        }
        latestUrl = url
        if (visitedUrls.lastOrNull() != url) visitedUrls.add(url)
        if (!addressBarFocused) addressText = url
    }

    fun navigateFromAddressBar() {
        val destination = resolveCookieBrowserInput(addressText) ?: return
        addressText = destination
        keyboardController?.hide()
        navigator.loadUrl(destination)
    }

    fun saveCookies() {
        if (isSaving) return
        isSaving = true
        cookieManager.flush()
        scope.launch {
            val result =
                cookiesViewModel.captureWebViewCookies(
                    (visitedUrls + latestUrl + profileUrl).distinct()
                )
            isSaving = false
            if (result.saved) {
                context.makeToast(
                    context.getString(
                        R.string.cookies_saved_for_site,
                        result.count,
                        result.profileUrl,
                    )
                )
                onDismissRequest()
            } else {
                context.makeToast(R.string.cookies_not_found)
            }
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.cookie_browser_title), maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onDismissRequest) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = stringResource(R.string.close),
                        )
                    }
                },
                actions = {
                    TextButton(
                        enabled = !isSaving && visitedUrls.isNotEmpty(),
                        onClick = ::saveCookies,
                    ) {
                        Text(
                            text =
                                stringResource(
                                    if (isSaving) R.string.saving_cookies else R.string.save_cookies
                                )
                        )
                    }
                },
            )
        },
    ) { paddingValues ->
        val webViewClient = remember {
            object : AccompanistWebViewClient() {
                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    rememberVisitedUrl(url)
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    super.onPageFinished(view, url)
                    rememberVisitedUrl(url)
                    cookieManager.flush()
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): Boolean {
                    val scheme = request?.url?.scheme
                    return !scheme.equals("http", ignoreCase = true) &&
                        !scheme.equals("https", ignoreCase = true)
                }
            }
        }
        val webViewChromeClient = remember { object : AccompanistWebChromeClient() {} }

        Column(modifier = Modifier.padding(paddingValues).fillMaxSize()) {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                IconButton(enabled = navigator.canGoBack, onClick = navigator::navigateBack) {
                    Icon(
                        Icons.Outlined.ArrowBack,
                        contentDescription = stringResource(R.string.browser_back),
                    )
                }
                IconButton(enabled = navigator.canGoForward, onClick = navigator::navigateForward) {
                    Icon(
                        Icons.Outlined.ArrowForward,
                        contentDescription = stringResource(R.string.browser_forward),
                    )
                }
                IconButton(onClick = navigator::reload) {
                    Icon(
                        Icons.Outlined.Refresh,
                        contentDescription = stringResource(R.string.browser_refresh),
                    )
                }
                OutlinedTextField(
                    value = addressText,
                    onValueChange = { addressText = it },
                    modifier =
                        Modifier.weight(1f).onFocusChanged { addressBarFocused = it.isFocused },
                    placeholder = { Text(stringResource(R.string.cookie_browser_address_hint)) },
                    trailingIcon = {
                        IconButton(onClick = ::navigateFromAddressBar) {
                            Icon(
                                Icons.Outlined.Search,
                                contentDescription = stringResource(R.string.go),
                            )
                        }
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { navigateFromAddressBar() }),
                )
            }

            val loadingState = webViewState.loadingState
            if (loadingState is LoadingState.Loading) {
                LinearProgressIndicator(
                    progress = { loadingState.progress },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            WebView(
                state = webViewState,
                navigator = navigator,
                client = webViewClient,
                chromeClient = webViewChromeClient,
                modifier = Modifier.fillMaxWidth().weight(1f),
                captureBackPresses = true,
                factory = { webContext ->
                    WebView(webContext).apply {
                        settings.run {
                            javaScriptCanOpenWindowsAutomatically = true
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            databaseEnabled = true
                            USER_AGENT_STRING.updateString(userAgentString)
                        }
                        cookieManager.setAcceptCookie(true)
                        cookieManager.setAcceptThirdPartyCookies(this, true)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_YES
                        }
                    }
                },
            )
        }
    }
}
