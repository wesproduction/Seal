package com.junkfood.seal

import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.junkfood.seal.download.DownloaderV2
import com.junkfood.seal.download.TaskFactory
import com.junkfood.seal.ui.common.LocalDarkTheme
import com.junkfood.seal.ui.common.SettingsProvider
import com.junkfood.seal.ui.page.downloadv2.configure.Config
import com.junkfood.seal.ui.page.downloadv2.configure.DownloadDialog
import com.junkfood.seal.ui.page.downloadv2.configure.DownloadDialogViewModel
import com.junkfood.seal.ui.page.downloadv2.configure.DownloadDialogViewModel.Action
import com.junkfood.seal.ui.page.downloadv2.configure.DownloadDialogViewModel.SelectionState
import com.junkfood.seal.ui.page.downloadv2.configure.FormatPage
import com.junkfood.seal.ui.page.downloadv2.configure.PlaylistSelectionPage
import com.junkfood.seal.ui.theme.SealTheme
import com.junkfood.seal.util.DownloadUtil
import com.junkfood.seal.util.PreferenceUtil
import com.junkfood.seal.util.RedditMediaResolver
import com.junkfood.seal.util.makeToast
import com.junkfood.seal.util.matchUrlFromSharedText
import com.junkfood.seal.util.setLanguage
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.getViewModel

private const val TAG = "QuickDownloadActivity"

class QuickDownloadActivity : ComponentActivity() {
    private var sharedUrlCached: String = ""
    private val downloader: DownloaderV2 by inject()
    private var redditJob: Job? = null
    private var redditFeedTarget: RedditMediaResolver.FeedTarget? = null
    private var redditShareState by mutableStateOf<RedditShareState>(RedditShareState.LoadingPost)

    private fun Intent.getSharedURL(): String? {
        val intent = this

        return when (intent.action) {
            Intent.ACTION_VIEW -> {
                intent.dataString
            }

            Intent.ACTION_SEND -> {
                intent.getStringExtra(Intent.EXTRA_TEXT)?.let { sharedContent ->
                    intent.removeExtra(Intent.EXTRA_TEXT)
                    matchUrlFromSharedText(sharedContent)
                }
            }

            else -> {
                null
            }
        }
    }

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class, ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent.getSharedURL()?.let { sharedUrlCached = it }

        if (sharedUrlCached.isEmpty()) {
            finish()
        }

        App.startService()

        enableEdgeToEdge()

        window.run {
            setBackgroundDrawable(ColorDrawable(0))
            setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
            } else {
                setType(WindowManager.LayoutParams.TYPE_SYSTEM_ALERT)
            }
        }

        if (Build.VERSION.SDK_INT < 33) {
            runBlocking { setLanguage(PreferenceUtil.getLocaleFromPreference()) }
        }

        if (RedditMediaResolver.isRedditUrl(sharedUrlCached)) {
            redditFeedTarget = RedditMediaResolver.extractFeedTarget(sharedUrlCached)
            redditShareState =
                redditFeedTarget?.let(RedditShareState::ConfirmFeed) ?: RedditShareState.LoadingPost
            showRedditShareHandler()
            if (redditFeedTarget == null) resolveRedditPost()
            return
        }

        val viewModel: DownloadDialogViewModel = getViewModel()
        viewModel.postAction(Action.ShowSheet(listOf(sharedUrlCached)))

        setContent {
            SettingsProvider(calculateWindowSizeClass(this).widthSizeClass) {
                SealTheme(
                    darkTheme = LocalDarkTheme.current.isDarkTheme(),
                    isHighContrastModeEnabled = LocalDarkTheme.current.isHighContrastModeEnabled,
                ) {
                    var preferences by remember {
                        mutableStateOf(DownloadUtil.DownloadPreferences.createFromPreferences())
                    }

                    val sheetValue = viewModel.sheetValueFlow.collectAsStateWithLifecycle().value

                    val state = viewModel.sheetStateFlow.collectAsStateWithLifecycle().value

                    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

                    val selectionState =
                        viewModel.selectionStateFlow.collectAsStateWithLifecycle().value

                    var showDialog by remember { mutableStateOf(false) }

                    LaunchedEffect(sheetValue, selectionState) {
                        if (sheetValue == DownloadDialogViewModel.SheetValue.Expanded) {
                            showDialog = true
                        } else if (sheetValue == DownloadDialogViewModel.SheetValue.Hidden) {
                            launch { sheetState.hide() }
                                .invokeOnCompletion {
                                    showDialog = false
                                    if (selectionState == SelectionState.Idle) {
                                        this@QuickDownloadActivity.finish()
                                    }
                                }
                        }
                    }

                    if (showDialog) {
                        DownloadDialog(
                            state = state,
                            sheetState = sheetState,
                            config = Config(),
                            preferences = preferences,
                            onPreferencesUpdate = { preferences = it },
                            onActionPost = { viewModel.postAction(it) },
                        )
                    }

                    when (selectionState) {
                        is SelectionState.FormatSelection ->
                            FormatPage(
                                state = selectionState,
                                onDismissRequest = {
                                    viewModel.postAction(Action.Reset)
                                    this.finish()
                                },
                            )

                        SelectionState.Idle -> {}
                        is SelectionState.PlaylistSelection -> {
                            PlaylistSelectionPage(
                                state = selectionState,
                                onDismissRequest = {
                                    viewModel.postAction(Action.Reset)
                                    this.finish()
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    private fun showRedditShareHandler() {
        setContent {
            SettingsProvider(calculateWindowSizeClass(this).widthSizeClass) {
                SealTheme(
                    darkTheme = LocalDarkTheme.current.isDarkTheme(),
                    isHighContrastModeEnabled = LocalDarkTheme.current.isHighContrastModeEnabled,
                ) {
                    when (val state = redditShareState) {
                        RedditShareState.LoadingPost ->
                            AlertDialog(
                                onDismissRequest = { finish() },
                                icon = { Icon(Icons.Outlined.Download, null) },
                                title = { Text(getString(R.string.reddit_preparing_download)) },
                                text = {
                                    Row {
                                        CircularProgressIndicator()
                                        Spacer(Modifier.width(16.dp))
                                        Text(getString(R.string.reddit_reading_post))
                                    }
                                },
                                dismissButton = {
                                    TextButton(onClick = { finish() }) {
                                        Text(getString(android.R.string.cancel))
                                    }
                                },
                                confirmButton = {},
                            )

                        is RedditShareState.ConfirmFeed ->
                            AlertDialog(
                                onDismissRequest = { finish() },
                                icon = { Icon(Icons.Outlined.Download, null) },
                                title = {
                                    Text(
                                        getString(
                                            R.string.reddit_feed_confirm_title,
                                            state.target.displayName,
                                        )
                                    )
                                },
                                text = {
                                    Text(
                                        getString(
                                            R.string.reddit_feed_confirm_desc,
                                            RedditMediaResolver.MAX_FEED_POSTS,
                                            state.target.name,
                                        )
                                    )
                                },
                                dismissButton = {
                                    TextButton(onClick = { finish() }) {
                                        Text(getString(android.R.string.cancel))
                                    }
                                },
                                confirmButton = {
                                    TextButton(onClick = { resolveRedditFeed(state.target) }) {
                                        Text(getString(R.string.reddit_feed_download_all))
                                    }
                                },
                            )

                        is RedditShareState.ScanningFeed ->
                            AlertDialog(
                                onDismissRequest = { finish() },
                                icon = { Icon(Icons.Outlined.Download, null) },
                                title = {
                                    Text(
                                        getString(
                                            R.string.reddit_feed_scanning_title,
                                            state.progress.target.displayName,
                                        )
                                    )
                                },
                                text = {
                                    Text(
                                        getString(
                                            R.string.reddit_feed_scanning_desc,
                                            state.progress.scannedPosts,
                                            RedditMediaResolver.MAX_FEED_POSTS,
                                            state.progress.mediaPosts,
                                            state.progress.mediaItems,
                                        )
                                    )
                                },
                                dismissButton = {
                                    TextButton(onClick = { finish() }) {
                                        Text(getString(android.R.string.cancel))
                                    }
                                },
                                confirmButton = {},
                            )

                        is RedditShareState.Error ->
                            AlertDialog(
                                onDismissRequest = { finish() },
                                icon = { Icon(Icons.Outlined.Download, null) },
                                title = { Text(getString(R.string.reddit_download_error)) },
                                text = { Text(state.throwable.message.orEmpty()) },
                                dismissButton = {
                                    TextButton(onClick = { finish() }) {
                                        Text(getString(android.R.string.cancel))
                                    }
                                },
                                confirmButton = {
                                    Row {
                                        TextButton(onClick = ::openRedditSignIn) {
                                            Text(getString(R.string.reddit_sign_in))
                                        }
                                        TextButton(onClick = ::retryRedditShare) {
                                            Text(getString(R.string.retry))
                                        }
                                    }
                                },
                            )
                    }
                }
            }
        }
    }

    private fun resolveRedditPost() {
        redditJob?.cancel()
        redditShareState = RedditShareState.LoadingPost
        redditJob =
            lifecycleScope.launch {
                try {
                    val post = RedditMediaResolver.resolve(sharedUrlCached)
                    val tasks =
                        TaskFactory.createFromRedditPost(
                            post = post,
                            preferences = DownloadUtil.DownloadPreferences.createFromPreferences(),
                        )
                    tasks.forEach(downloader::enqueue)
                    makeToast(
                        resources.getQuantityString(
                            R.plurals.reddit_items_queued,
                            tasks.size,
                            tasks.size,
                        )
                    )
                    finish()
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (throwable: Throwable) {
                    redditShareState = RedditShareState.Error(throwable)
                }
            }
    }

    private fun resolveRedditFeed(target: RedditMediaResolver.FeedTarget) {
        redditJob?.cancel()
        redditFeedTarget = target
        redditShareState =
            RedditShareState.ScanningFeed(
                RedditMediaResolver.FeedProgress(
                    target = target,
                    scannedPosts = 0,
                    mediaPosts = 0,
                    mediaItems = 0,
                )
            )
        redditJob =
            lifecycleScope.launch {
                try {
                    val feed =
                        RedditMediaResolver.resolveFeed(sharedUrlCached) { progress ->
                            runOnUiThread {
                                redditShareState = RedditShareState.ScanningFeed(progress)
                            }
                        }
                    if (feed.posts.isEmpty()) {
                        throw IOException(
                            getString(R.string.reddit_feed_no_media, target.displayName)
                        )
                    }
                    val tasks =
                        TaskFactory.createFromRedditFeed(
                            feed = feed,
                            preferences = DownloadUtil.DownloadPreferences.createFromPreferences(),
                        )
                    tasks.forEach(downloader::enqueue)
                    makeToast(
                        resources.getQuantityString(
                            R.plurals.reddit_items_queued,
                            tasks.size,
                            tasks.size,
                        )
                    )
                    finish()
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (throwable: Throwable) {
                    redditShareState = RedditShareState.Error(throwable)
                }
            }
    }

    private fun retryRedditShare() {
        redditFeedTarget?.let(::resolveRedditFeed) ?: resolveRedditPost()
    }

    private fun openRedditSignIn() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN_REDDIT_LOGIN, true)
        )
        finish()
    }

    override fun onDestroy() {
        redditJob?.cancel()
        super.onDestroy()
    }

    private sealed interface RedditShareState {
        data object LoadingPost : RedditShareState

        data class ConfirmFeed(val target: RedditMediaResolver.FeedTarget) : RedditShareState

        data class ScanningFeed(val progress: RedditMediaResolver.FeedProgress) : RedditShareState

        data class Error(val throwable: Throwable) : RedditShareState
    }
}
