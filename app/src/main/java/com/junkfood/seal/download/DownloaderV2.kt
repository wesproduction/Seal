package com.junkfood.seal.download

import android.app.PendingIntent
import android.content.Context
import android.util.Log
import com.junkfood.seal.App
import com.junkfood.seal.R
import com.junkfood.seal.download.Task.DownloadState
import com.junkfood.seal.download.Task.DownloadState.Canceled
import com.junkfood.seal.download.Task.DownloadState.Completed
import com.junkfood.seal.download.Task.DownloadState.Error
import com.junkfood.seal.download.Task.DownloadState.FetchingInfo
import com.junkfood.seal.download.Task.DownloadState.Idle
import com.junkfood.seal.download.Task.DownloadState.ReadyWithInfo
import com.junkfood.seal.download.Task.DownloadState.Running
import com.junkfood.seal.download.Task.RestartableAction.Download
import com.junkfood.seal.download.Task.RestartableAction.FetchInfo
import com.junkfood.seal.download.Task.TypeInfo
import com.junkfood.seal.util.DownloadUtil
import com.junkfood.seal.util.FileUtil
import com.junkfood.seal.util.NotificationUtil
import com.junkfood.seal.util.PreferenceUtil
import com.junkfood.seal.util.VideoInfo
import com.yausername.youtubedl_android.YoutubeDL
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.set
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent

private const val TAG = "DownloaderV2"

private const val MAX_CONCURRENCY = 3

interface DownloaderV2 {
    val taskStateFlow: StateFlow<Map<Task, Task.State>>

    fun getTaskStateMap(): Map<Task, Task.State> = taskStateFlow.value

    fun cancel(task: Task): Boolean

    fun cancel(taskId: String): Boolean {
        return getTaskStateMap().keys.find { it.id == taskId }?.let { cancel(it) } ?: false
    }

    fun restart(task: Task)

    /** Enqueue a [Task] with an empty [Task.State] */
    fun enqueue(task: Task)

    fun enqueue(task: Task, state: Task.State)

    fun enqueue(taskWithState: TaskFactory.TaskWithState) {
        val (task, state) = taskWithState
        enqueue(task, state)
    }

    fun remove(task: Task): Boolean
}

internal object FakeDownloaderV2 : DownloaderV2 {
    override val taskStateFlow: StateFlow<Map<Task, Task.State>> = MutableStateFlow(emptyMap())

    override fun cancel(task: Task): Boolean {
        return false
    }

    override fun restart(task: Task) {}

    override fun enqueue(task: Task) {}

    override fun enqueue(task: Task, state: Task.State) {}

    override fun remove(task: Task): Boolean {
        return true
    }
}

/**
 * Owns queue state independently of Compose.
 *
 * yt-dlp invokes callbacks from worker threads, so a Compose snapshot-state map is not an
 * appropriate synchronization primitive here. Every mutation goes through one lock and each
 * published value is an immutable map, giving collectors a complete queue snapshot.
 */
internal class TaskQueueStateStore(initialState: Map<Task, Task.State> = emptyMap()) {
    private val lock = Any()
    private val mutableState = MutableStateFlow(initialState.toMap())

    val state: StateFlow<Map<Task, Task.State>> = mutableState.asStateFlow()

    fun get(task: Task): Task.State? = mutableState.value[task]

    fun put(task: Task, state: Task.State) {
        synchronized(lock) { mutableState.value = mutableState.value + (task to state) }
    }

    fun remove(task: Task): Boolean =
        synchronized(lock) {
            if (task !in mutableState.value) return@synchronized false
            mutableState.value = mutableState.value - task
            true
        }

    fun update(task: Task, transform: (Task.State) -> Task.State): StateUpdate? =
        synchronized(lock) {
            val previous = mutableState.value[task] ?: return@synchronized null
            val next = transform(previous)
            if (next != previous) {
                mutableState.value = mutableState.value + (task to next)
            }
            StateUpdate(previous = previous, next = next)
        }

    data class StateUpdate(val previous: Task.State, val next: Task.State)
}

/**
 * TODO:
 *     - Notification
 *     - Custom commands
 *     - States for ViewModels
 */
class DownloaderV2Impl(private val appContext: Context) : DownloaderV2, KoinComponent {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queueState = TaskQueueStateStore()
    override val taskStateFlow: StateFlow<Map<Task, Task.State>> = queueState.state

    init {
        // Restore synchronously so a share intent cannot enqueue a fresh task and then have the
        // same task overwritten by stale backup state during a cold start.
        enqueueFromBackup()

        scope.launch(Dispatchers.Default) {
            taskStateFlow
                .map { queue -> queue.mapValues { (_, state) -> state.downloadState.phase } }
                .distinctUntilChanged()
                .collect {
                    doYourWork()
                    if (taskStateFlow.value.countRunning() > 0) App.startService()
                    else App.stopService()
                }
        }

        scope.launch(Dispatchers.IO) {
            taskStateFlow
                .map { it.toBackupSnapshot() }
                .distinctUntilChanged()
                .collect {
                    it.forEach { Log.d(TAG, it.value.viewState.title) }
                    PreferenceUtil.encodeTaskListBackup(it)
                }
        }
    }

    private fun enqueueFromBackup() {
        val taskList =
            PreferenceUtil.decodeTaskListBackup()
                .filter { it.value.downloadState !is Completed }
                .mapValues { (_, state) ->
                    val preState = state.downloadState
                    val downloadState =
                        when (preState) {
                            is FetchingInfo,
                            Idle -> {
                                Canceled(action = FetchInfo)
                            }
                            is Running -> {
                                Canceled(action = Download, progress = preState.progress)
                            }

                            ReadyWithInfo -> {
                                Canceled(action = Download, progress = null)
                            }
                            else -> {
                                preState
                            }
                        }
                    state.copy(downloadState = downloadState)
                }
        taskList.forEach(::enqueue)
    }

    private fun Map<Task, Task.State>.countRunning(): Int = count { (_, state) ->
        state.downloadState is Running || state.downloadState is FetchingInfo
    }

    /**
     * Persist phase changes immediately, but coarsen noisy progress callbacks to 5% steps. This
     * keeps crash recovery current without writing preferences for every yt-dlp progress line.
     */
    private fun Map<Task, Task.State>.toBackupSnapshot(): Map<Task, Task.State> =
        filter { (_, state) -> state.downloadState !is Completed }
            .mapValues { (_, state) ->
                val downloadState = state.downloadState
                if (downloadState !is Running || downloadState.progress < 0f) state
                else {
                    val persistedProgress = (downloadState.progress * 20).roundToInt() / 20f
                    state.copy(
                        downloadState =
                            downloadState.copy(progress = persistedProgress, progressText = "")
                    )
                }
            }

    override fun enqueue(task: Task) {
        queueState.put(
            task,
            Task.State(Idle, null, Task.ViewState(url = task.url, title = task.url)),
        )
    }

    override fun enqueue(task: Task, state: Task.State) {
        queueState.put(task, state)
    }

    /**
     * Noted the caller is responsible for stopping the [task] before removing it
     *
     * @return true if the task was removed
     */
    override fun remove(task: Task): Boolean {
        return queueState.remove(task)
    }

    override fun cancel(task: Task): Boolean = task.cancelImpl()

    override fun restart(task: Task) {
        task.restartImpl()
    }

    private val Task.state: Task.State
        get() = queueState.get(this) ?: error("Task is no longer in the queue: $id")

    private var Task.downloadState: DownloadState
        get() = state.downloadState
        set(value) {
            queueState.update(this) { it.copy(downloadState = value) }
        }

    private val Task.info: VideoInfo?
        get() = state.videoInfo

    private val Task.notificationId: Int
        get() = id.hashCode()

    /** Processes pending tasks, prioritizing downloads. */
    private fun doYourWork() {
        val queue = taskStateFlow.value
        if (queue.countRunning() >= MAX_CONCURRENCY) return

        queue.entries
            .sortedBy { (_, state) -> state.downloadState }
            .firstOrNull { (_, state) ->
                state.downloadState == ReadyWithInfo || state.downloadState == Idle
            }
            ?.let { (task, state) ->
                when (state.downloadState) {
                    Idle -> task.prepare()
                    ReadyWithInfo -> task.download()
                    else -> {
                        throw IllegalStateException()
                    }
                }
            }
    }

    private fun Task.prepare() {
        check(downloadState == Idle)
        if (type is TypeInfo.CustomCommand) {
            execute()
        } else if (type is TypeInfo.RedditMedia || type is TypeInfo.RedditAlbum) {
            downloadState = ReadyWithInfo
        } else {
            fetchInfo()
        }
    }

    private fun Task.fetchInfo() {
        check(downloadState == Idle)
        val task = this
        val taskInfo = task.type
        val playlistIndex = if (taskInfo is TypeInfo.Playlist) taskInfo.index else null
        val job =
            scope.launch(Dispatchers.Default, start = CoroutineStart.LAZY) {
                DownloadUtil.fetchVideoInfoFromUrl(
                        url = url,
                        playlistIndex = playlistIndex,
                        preferences = preferences,
                        taskKey = id,
                    )
                    .onSuccess {
                        queueState.update(task) { current ->
                            if (current.downloadState !is FetchingInfo) current
                            else
                                current.copy(
                                    downloadState = ReadyWithInfo,
                                    videoInfo = it,
                                    viewState = Task.ViewState.fromVideoInfo(it),
                                )
                        }
                    }
                    .onFailure { throwable ->
                        if (throwable is YoutubeDL.CanceledException) {
                            return@onFailure
                        }
                        val update =
                            queueState.update(task) { current ->
                                if (current.downloadState !is FetchingInfo) current
                                else
                                    current.copy(
                                        downloadState =
                                            Error(throwable = throwable, action = FetchInfo)
                                    )
                            }
                        if (update?.previous?.downloadState is FetchingInfo) {
                            NotificationUtil.notifyError(
                                title = update.previous.viewState.title,
                                textId = R.string.fetch_info_error_msg,
                                notificationId = notificationId,
                                report = throwable.stackTraceToString(),
                            )
                        }
                    }
            }
        downloadState = FetchingInfo(job = job, taskId = id)
        job.start()
    }

    private fun Task.download() {
        check(downloadState == ReadyWithInfo)
        if (type is TypeInfo.RedditMedia || type is TypeInfo.RedditAlbum) {
            downloadReddit(type)
            return
        }
        check(info != null)
        if (type is TypeInfo.CustomCommand) {
            execute()
            return
        }
        val videoInfo = requireNotNull(info)
        val task = this
        val job =
            scope.launch(Dispatchers.Default, start = CoroutineStart.LAZY) {
                DownloadUtil.downloadVideo(
                        videoInfo = videoInfo,
                        taskId = id,
                        downloadPreferences = preferences,
                        progressCallback = { progressPercentage, downloadedBytes, text ->
                            val progress = progressPercentage / 100f
                            val update =
                                queueState.update(task) { current ->
                                    val preState = current.downloadState
                                    if (preState !is Running) current
                                    else
                                        current.copy(
                                            downloadState =
                                                preState.copy(
                                                    progress = progress,
                                                    progressText = text,
                                                ),
                                            viewState =
                                                current.viewState.copy(
                                                    fileSizeApprox = downloadedBytes.toDouble()
                                                ),
                                        )
                                }
                            if (update?.previous?.downloadState is Running) {
                                NotificationUtil.notifyProgress(
                                    notificationId = notificationId,
                                    progress = progressPercentage.toInt(),
                                    text = text,
                                    title = update.previous.viewState.title,
                                    taskId = id,
                                )
                            }
                        },
                    )
                    .onSuccess { pathList ->
                        val update =
                            queueState.update(task) { current ->
                                if (current.downloadState !is Running) current
                                else
                                    current.copy(
                                        downloadState =
                                            Completed(
                                                filePath = pathList.firstOrNull(),
                                                filePaths = pathList,
                                            )
                                    )
                            }
                        if (update?.previous?.downloadState !is Running) return@onSuccess

                        val text =
                            appContext.getString(
                                if (pathList.isEmpty()) R.string.status_completed
                                else R.string.download_finish_notification
                            )
                        FileUtil.createIntentForOpeningFile(pathList.firstOrNull()).run {
                            NotificationUtil.finishNotification(
                                notificationId,
                                title = update.previous.viewState.title,
                                text = text,
                                intent =
                                    if (this != null)
                                        PendingIntent.getActivity(
                                            appContext,
                                            0,
                                            this,
                                            PendingIntent.FLAG_IMMUTABLE,
                                        )
                                    else null,
                            )
                        }
                    }
                    .onFailure { throwable ->
                        if (throwable is YoutubeDL.CanceledException) {
                            return@onFailure
                        }
                        val update =
                            queueState.update(task) { current ->
                                if (current.downloadState !is Running) current
                                else
                                    current.copy(
                                        downloadState =
                                            Error(throwable = throwable, action = Download)
                                    )
                            }
                        if (update?.previous?.downloadState is Running) {
                            NotificationUtil.notifyError(
                                title = update.previous.viewState.title,
                                textId = R.string.download_error_msg,
                                notificationId = notificationId,
                                report = throwable.stackTraceToString(),
                            )
                        }
                    }
            }
        downloadState = Running(job = job, taskId = id)
        job.start()
    }

    private fun Task.downloadReddit(typeInfo: TypeInfo) {
        val task = this
        val job =
            scope.launch(Dispatchers.Default, start = CoroutineStart.LAZY) {
                val progressCallback =
                    { progressPercentage: Float, downloadedBytes: Long, text: String ->
                        val progress =
                            if (progressPercentage < 0f) -1f else progressPercentage / 100f
                        val update =
                            queueState.update(task) { current ->
                                val preState = current.downloadState
                                if (preState !is Running) current
                                else
                                    current.copy(
                                        downloadState =
                                            preState.copy(progress = progress, progressText = text),
                                        viewState =
                                            current.viewState.copy(
                                                fileSizeApprox = downloadedBytes.toDouble()
                                            ),
                                    )
                            }
                        if (update?.previous?.downloadState is Running) {
                            NotificationUtil.notifyProgress(
                                notificationId = notificationId,
                                progress = progressPercentage.coerceAtLeast(0f).toInt(),
                                text = text,
                                title = update.previous.viewState.title,
                                taskId = id,
                            )
                        }
                    }
                val result =
                    when (typeInfo) {
                        is TypeInfo.RedditAlbum ->
                            RedditMediaDownloader.downloadAlbum(
                                album = typeInfo,
                                preferences = preferences,
                                progressCallback = progressCallback,
                            )

                        is TypeInfo.RedditMedia ->
                            RedditMediaDownloader.download(
                                media = typeInfo,
                                preferences = preferences,
                                progressCallback = progressCallback,
                            )

                        else -> error("Unsupported Reddit task type")
                    }
                result
                    .onSuccess { pathList ->
                        val update =
                            queueState.update(task) { current ->
                                if (current.downloadState !is Running) current
                                else
                                    current.copy(
                                        downloadState =
                                            Completed(
                                                filePath = pathList.firstOrNull(),
                                                filePaths = pathList,
                                            )
                                    )
                            }
                        if (update?.previous?.downloadState !is Running) return@onSuccess
                        FileUtil.createIntentForOpeningFile(pathList.firstOrNull()).run {
                            NotificationUtil.finishNotification(
                                notificationId = notificationId,
                                title = update.previous.viewState.title,
                                text = appContext.getString(R.string.download_finish_notification),
                                intent =
                                    if (this != null)
                                        PendingIntent.getActivity(
                                            appContext,
                                            0,
                                            this,
                                            PendingIntent.FLAG_IMMUTABLE,
                                        )
                                    else null,
                            )
                        }
                    }
                    .onFailure { throwable ->
                        val update =
                            queueState.update(task) { current ->
                                if (current.downloadState !is Running) current
                                else
                                    current.copy(
                                        downloadState =
                                            Error(throwable = throwable, action = Download)
                                    )
                            }
                        if (update?.previous?.downloadState is Running) {
                            NotificationUtil.notifyError(
                                title = update.previous.viewState.title,
                                textId = R.string.download_error_msg,
                                notificationId = notificationId,
                                report = throwable.stackTraceToString(),
                            )
                        }
                    }
            }
        downloadState = Running(job = job, taskId = id)
        job.start()
    }

    private fun Task.cancelImpl(): Boolean {
        when (val preState = downloadState) {
            is DownloadState.Cancelable -> {
                YoutubeDL.destroyProcessById(preState.taskId)
                preState.job.cancel()
                val progress = if (preState is Running) preState.progress else null
                NotificationUtil.cancelNotification(notificationId)
                queueState.update(this) { current ->
                    val currentDownloadState = current.downloadState
                    if (
                        currentDownloadState !is DownloadState.Cancelable ||
                            currentDownloadState.taskId != preState.taskId
                    ) {
                        current
                    } else {
                        current.copy(
                            downloadState =
                                DownloadState.Canceled(
                                    action = preState.action,
                                    progress = progress,
                                )
                        )
                    }
                }
                return true
            }
            Idle -> {
                downloadState = DownloadState.Canceled(action = FetchInfo)
            }
            ReadyWithInfo -> {
                downloadState = DownloadState.Canceled(action = Download)
            }

            else -> {
                return false
            }
        }
        return true
    }

    private fun Task.restartImpl() {
        when (val preState = downloadState) {
            is DownloadState.Restartable -> {
                downloadState =
                    when (preState.action) {
                        Download -> ReadyWithInfo
                        FetchInfo -> Idle
                    }
            }
            else -> {
                throw IllegalStateException()
            }
        }
    }

    /**
     * Execute a custom command task
     *
     * @see Task.TypeInfo.CustomCommand
     */
    private fun Task.execute() {
        check(downloadState == Idle)
        check(type is TypeInfo.CustomCommand)
        val template = type.template
        val task = this
        val job =
            scope.launch(start = CoroutineStart.LAZY) {
                DownloadUtil.executeCustomCommandTask(url, id, template, preferences) {
                        progressPercentage,
                        _,
                        text ->
                        val progress = progressPercentage / 100f
                        val update =
                            queueState.update(task) { current ->
                                val preState = current.downloadState
                                if (preState !is Running) current
                                else
                                    current.copy(
                                        downloadState =
                                            preState.copy(progress = progress, progressText = text)
                                    )
                            }
                        if (update?.previous?.downloadState is Running) {
                            NotificationUtil.makeNotificationForCustomCommand(
                                notificationId = notificationId,
                                taskId = id,
                                progress = progressPercentage.toInt(),
                                templateName = template.name,
                                taskUrl = url,
                                text = text,
                            )
                        }
                    }
                    .onFailure { throwable ->
                        if (throwable is YoutubeDL.CanceledException) {
                            return@onFailure
                        }
                        val update =
                            queueState.update(task) { current ->
                                if (current.downloadState !is Running) current
                                else
                                    current.copy(
                                        downloadState =
                                            Error(throwable = throwable, action = Download)
                                    )
                            }
                        if (update?.previous?.downloadState is Running) {
                            NotificationUtil.notifyError(
                                title = update.previous.viewState.title,
                                textId = R.string.download_error_msg,
                                notificationId = notificationId,
                                report = throwable.stackTraceToString(),
                            )
                        }
                    }
                    .onSuccess {
                        val update =
                            queueState.update(task) { current ->
                                if (current.downloadState !is Running) current
                                else current.copy(downloadState = Completed(null))
                            }
                        if (update?.previous?.downloadState !is Running) return@onSuccess

                        val text = appContext.getString(R.string.status_completed)

                        NotificationUtil.finishNotification(
                            notificationId = notificationId,
                            title = update.previous.viewState.title,
                            text = text,
                            intent = null,
                        )
                    }
            }
        downloadState = Running(job = job, taskId = id)
        job.start()
    }
}

private enum class QueuePhase {
    Idle,
    FetchingInfo,
    ReadyWithInfo,
    Running,
    Restartable,
    Completed,
}

private val DownloadState.phase: QueuePhase
    get() =
        when (this) {
            Idle -> QueuePhase.Idle
            is FetchingInfo -> QueuePhase.FetchingInfo
            ReadyWithInfo -> QueuePhase.ReadyWithInfo
            is Running -> QueuePhase.Running
            is Canceled,
            is Error -> QueuePhase.Restartable
            is Completed -> QueuePhase.Completed
        }
