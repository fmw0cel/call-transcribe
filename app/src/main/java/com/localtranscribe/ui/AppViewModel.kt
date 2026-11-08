package com.localtranscribe.ui

import android.app.Application
import android.content.Intent
import com.localtranscribe.BuildConfig
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.localtranscribe.audio.WavRecorder
import com.localtranscribe.data.Transcript
import com.localtranscribe.data.TranscriptStore
import com.localtranscribe.model.InstalledModel
import com.localtranscribe.model.ModelRepository
import com.localtranscribe.model.ModelSpec
import com.localtranscribe.watch.FolderWatchRunner
import com.localtranscribe.watch.FolderWatchScheduler
import com.localtranscribe.watch.TranscriptionPipeline
import com.localtranscribe.watch.WatchFolderRepository
import com.localtranscribe.update.AppRelease
import com.localtranscribe.update.AppReleaseChecker
import com.localtranscribe.update.AppUpdateInstaller
import com.localtranscribe.update.AppVersion
import com.localtranscribe.watch.WatchQueuedFile
import com.localtranscribe.whisper.Transcriber
import com.localtranscribe.whisper.TranscriptSegment
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface Screen {
    data object Home : Screen
    data class Detail(val id: String) : Screen
    data object Models : Screen
    data object WatchFolder : Screen
}

enum class Stage { DECODING, TRANSCRIBING, IMPORT }

data class BusyState(
    val stage: Stage,
    val progress: Float = 0f,
    val liveText: String = "",
    val title: String = "처리 중",
)

data class DownloadState(
    val spec: ModelSpec,
    val progress: Float = 0f,
)

data class PendingAudio(
    val uri: Uri,
    val displayName: String,
)

data class UiState(
    val screen: Screen = Screen.Home,
    val models: List<InstalledModel> = emptyList(),
    val selectedModel: InstalledModel? = null,
    val transcripts: List<Transcript> = emptyList(),
    val detail: Transcript? = null,
    val busy: BusyState? = null,
    val download: DownloadState? = null,
    val isRecording: Boolean = false,
    val recordDurationMs: Long = 0L,
    val level: Float = 0f,
    val language: String = LANG_AUTO,
    val translate: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val pendingAudio: PendingAudio? = null,
    val watch: WatchFolderUi = WatchFolderUi(
        hasFolder = false,
        folderLabel = "선택 안 됨",
        enabled = false,
        autoTranscribe = true,
        whileCharging = false,
        lastScanAt = 0L,
        processedCount = 0,
        queuedCount = 0,
        scanInProgress = false,
    ),
    val watchQueue: List<WatchQueuedFile> = emptyList(),
    val appUpdate: AppUpdateUi = AppUpdateUi(currentVersion = ""),
) {
    val ready: Boolean get() = selectedModel != null
}

data class AppUpdateUi(
    val currentVersion: String,
    val latestVersion: String? = null,
    val updateAvailable: Boolean = false,
    val checking: Boolean = false,
    val downloading: Boolean = false,
    val downloadProgress: Float = 0f,
)

const val LANG_AUTO = "auto"

data class LanguageOption(val code: String, val label: String)

val LANGUAGES = listOf(
    LanguageOption(LANG_AUTO, "자동 감지"),
    LanguageOption("ko", "한국어"),
    LanguageOption("en", "English"),
    LanguageOption("ja", "日本語"),
    LanguageOption("zh", "中文"),
    LanguageOption("es", "Español"),
    LanguageOption("fr", "Français"),
    LanguageOption("de", "Deutsch"),
    LanguageOption("ru", "Русский"),
)

class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences("settings", Application.MODE_PRIVATE)
    private val models = ModelRepository(application)
    private val store = TranscriptStore(application)
    private val watchRepo = WatchFolderRepository(application)
    private val pipeline = TranscriptionPipeline(application, models, store)

    private val _state = MutableStateFlow(
        UiState(
            language = prefs.getString(KEY_LANGUAGE, "ko") ?: LANG_AUTO,
            translate = prefs.getBoolean(KEY_TRANSLATE, false),
        ),
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var recorder: WavRecorder? = null
    private var recorderFile: File? = null
    private var timerJob: Job? = null
    private var workJob: Job? = null
    private var downloadJob: Job? = null
    private var transcriber: Transcriber? = null
    private var recordStartedAt = 0L
    private var watchScanJob: Job? = null
    private var updateCheckJob: Job? = null
    private var updateDownloadJob: Job? = null
    private var pendingRelease: AppRelease? = null

    init {
        models.ensureDefaultSelection()
        refreshModels()
        refreshTranscripts()
        refreshWatchFolder()
        FolderWatchScheduler.sync(application)
        _state.value = _state.value.copy(
            appUpdate = AppUpdateUi(currentVersion = BuildConfig.VERSION_NAME),
        )
        checkAppUpdate(manual = false)
        if (_state.value.selectedModel == null) {
            _state.value = _state.value.copy(screen = Screen.Models)
        }
    }

    // ------------------------------------------------------------- models ---

    fun refreshModels() {
        val installed = models.installed()
        val selected = models.selected()
        _state.value = _state.value.copy(
            models = installed,
            selectedModel = selected,
        )
    }

    fun selectModel(model: InstalledModel) {
        models.select(model)
        refreshModels()
    }

    fun deleteModel(model: InstalledModel) {
        models.delete(model)
        refreshModels()
    }

    fun downloadModel(spec: ModelSpec) {
        if (_state.value.download != null) return
        _state.value = _state.value.copy(download = DownloadState(spec), error = null)
        downloadJob = viewModelScope.launch {
            try {
                models.download(spec) { progress ->
                    _state.value = _state.value.copy(
                        download = DownloadState(spec, progress.coerceIn(0f, 1f)),
                    )
                }
                _state.value = _state.value.copy(
                    download = null,
                    notice = "${spec.displayName} 모델을 준비했습니다.",
                )
                refreshModels()
            } catch (t: Throwable) {
                if (t is CancellationException) {
                    _state.value = _state.value.copy(download = null)
                    return@launch
                }
                _state.value = _state.value.copy(
                    download = null,
                    error = t.message ?: "모델 다운로드에 실패했습니다.",
                )
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        _state.value = _state.value.copy(download = null)
    }

    fun importModel(uri: Uri) {
        if (_state.value.busy != null) return
        val displayName = queryDisplayName(uri)?.removeSuffix(".bin") ?: "가져온 모델"
        _state.value = _state.value.copy(busy = BusyState(Stage.IMPORT, 0f, title = displayName))
        viewModelScope.launch {
            try {
                val model = models.importFromUri(uri, displayName)
                _state.value = _state.value.copy(
                    busy = null,
                    notice = "${model.displayName} 모델을 추가했습니다.",
                )
                refreshModels()
            } catch (t: Throwable) {
                _state.value = _state.value.copy(
                    busy = null,
                    error = t.message ?: "모델을 가져오지 못했습니다.",
                )
            }
        }
    }

    // --------------------------------------------------------- navigation ---

    fun openModels() {
        _state.value = _state.value.copy(screen = Screen.Models)
    }

    fun openWatchFolder() {
        refreshWatchFolder()
        _state.value = _state.value.copy(screen = Screen.WatchFolder)
    }

    fun openTranscript(id: String) {
        val transcript = store.load(id)
        _state.value = _state.value.copy(screen = Screen.Detail(id), detail = transcript)
    }

    fun goHome() {
        _state.value = _state.value.copy(screen = Screen.Home, detail = null)
    }

    fun refreshTranscripts() {
        _state.value = _state.value.copy(transcripts = store.list())
    }

    fun deleteTranscript(id: String) {
        store.delete(id)
        val current = _state.value
        _state.value = current.copy(
            transcripts = store.list(),
            screen = if (current.screen is Screen.Detail) Screen.Home else current.screen,
            detail = null,
        )
    }

    // ------------------------------------------------------------ settings ---

    fun setLanguage(code: String) {
        prefs.edit().putString(KEY_LANGUAGE, code).apply()
        _state.value = _state.value.copy(language = code)
    }

    fun setTranslate(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_TRANSLATE, enabled).apply()
        _state.value = _state.value.copy(translate = enabled)
    }

    fun clearMessages() {
        _state.value = _state.value.copy(error = null, notice = null)
    }

    // --------------------------------------------------------- app update ---

    fun checkAppUpdate(manual: Boolean = true) {
        if (updateCheckJob?.isActive == true || updateDownloadJob?.isActive == true) return
        updateCheckJob = viewModelScope.launch {
            val current = _state.value.appUpdate
            _state.value = _state.value.copy(appUpdate = current.copy(checking = true, downloadProgress = 0f))
            try {
                val release = AppReleaseChecker.fetchLatest(BuildConfig.VERSION_NAME)
                pendingRelease = release
                val available = release != null &&
                    AppVersion.isNewer(release.version, BuildConfig.VERSION_NAME)
                _state.value = _state.value.copy(
                    appUpdate = AppUpdateUi(
                        currentVersion = BuildConfig.VERSION_NAME,
                        latestVersion = release?.version,
                        updateAvailable = available,
                        checking = false,
                    ),
                )
                if (manual) {
                    val message = when {
                        release == null -> "업데이트 정보를 가져오지 못했습니다."
                        available -> "새 버전 ${release.version}을(를) 받을 수 있습니다."
                        else -> "이미 최신 버전(${BuildConfig.VERSION_NAME})입니다."
                    }
                    _state.value = _state.value.copy(notice = message)
                }
            } catch (t: Throwable) {
                _state.value = _state.value.copy(
                    appUpdate = current.copy(checking = false),
                    error = if (manual) {
                        t.message ?: "업데이트를 확인하지 못했습니다."
                    } else {
                        null
                    },
                )
            }
        }
    }

    fun downloadAppUpdate() {
        val release = pendingRelease
        if (release == null || !_state.value.appUpdate.updateAvailable) {
            checkAppUpdate(manual = true)
            return
        }
        val app = getApplication<Application>()
        if (!AppUpdateInstaller.canRequestPackageInstalls(app)) {
            _state.value = _state.value.copy(
                error = "설치하려면 ‘알 수 없는 앱’ 설치를 허용해 주세요.",
            )
            AppUpdateInstaller.openUnknownSourcesSettings(app)
            return
        }
        updateDownloadJob?.cancel()
        updateDownloadJob = viewModelScope.launch {
            val base = _state.value.appUpdate
            _state.value = _state.value.copy(
                appUpdate = base.copy(downloading = true, downloadProgress = 0f),
            )
            try {
                val apk = AppUpdateInstaller.downloadApk(app, release.apkUrl) { progress ->
                    _state.value = _state.value.copy(
                        appUpdate = _state.value.appUpdate.copy(downloadProgress = progress),
                    )
                }
                AppUpdateInstaller.launchInstall(app, apk)
                _state.value = _state.value.copy(
                    appUpdate = _state.value.appUpdate.copy(downloading = false),
                    notice = "설치 화면에서 업데이트를 완료해 주세요.",
                )
            } catch (t: Throwable) {
                _state.value = _state.value.copy(
                    appUpdate = _state.value.appUpdate.copy(downloading = false),
                    error = t.message ?: "업데이트를 받지 못했습니다.",
                )
            }
        }
    }

    // ------------------------------------------------------ folder watch ---

    fun refreshWatchFolder() {
        val app = getApplication<Application>()
        _state.value = _state.value.copy(
            watch = WatchFolderUi(
                hasFolder = watchRepo.treeUri() != null,
                folderLabel = watchRepo.folderDisplayName(app),
                enabled = watchRepo.isEnabled(),
                autoTranscribe = watchRepo.autoTranscribe(),
                whileCharging = watchRepo.requiresCharging(),
                lastScanAt = watchRepo.lastScanAt(),
                processedCount = watchRepo.processedCount(),
                queuedCount = watchRepo.queuedCount(),
                scanInProgress = watchScanJob?.isActive == true,
            ),
            watchQueue = watchRepo.queue(),
        )
    }

    fun setWatchAutoTranscribe(enabled: Boolean) {
        watchRepo.setAutoTranscribe(enabled)
        refreshWatchFolder()
        _state.value = _state.value.copy(
            notice = if (enabled) {
                "찾은 파일을 바로 전사합니다."
            } else {
                "폴더 검사 후 홈에서 전사할 수 있습니다."
            },
        )
    }

    fun dismissWatchQueued(key: String) {
        watchRepo.removeFromQueue(key)
        refreshWatchFolder()
    }

    fun transcribeWatchQueued(key: String) {
        val queued = watchRepo.queue().firstOrNull { it.key == key } ?: return
        transcribeImportedAudio(
            uri = queued.uri,
            name = queued.displayName,
            watchKey = key,
        )
    }

    fun persistAndSetWatchFolder(uri: Uri) {
        val app = getApplication<Application>()
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        runCatching {
            app.contentResolver.takePersistableUriPermission(uri, flags)
        }.onFailure {
            _state.value = _state.value.copy(error = "폴더 권한을 저장하지 못했습니다.")
            return
        }
        val label = DocumentFile.fromTreeUri(app, uri)?.name ?: "선택된 폴더"
        watchRepo.saveTreeUri(uri)
        watchRepo.saveFolderLabel(label)
        refreshWatchFolder()
        FolderWatchScheduler.sync(app)
        _state.value = _state.value.copy(notice = "감시 폴더를 설정했습니다.")
    }

    fun clearWatchFolder() {
        val app = getApplication<Application>()
        watchRepo.clearTreeUri()
        FolderWatchScheduler.sync(app)
        refreshWatchFolder()
        _state.value = _state.value.copy(notice = "감시 폴더를 해제했습니다.")
    }

    fun setWatchEnabled(enabled: Boolean) {
        val app = getApplication<Application>()
        if (enabled && watchRepo.treeUri() == null) {
            _state.value = _state.value.copy(error = "먼저 폴더를 선택해 주세요.")
            return
        }
        watchRepo.setEnabled(enabled)
        FolderWatchScheduler.sync(app)
        refreshWatchFolder()
        if (enabled) {
            FolderWatchScheduler.runNow(app)
        }
    }

    fun setWatchWhileCharging(enabled: Boolean) {
        val app = getApplication<Application>()
        watchRepo.setRequiresCharging(enabled)
        FolderWatchScheduler.sync(app)
        refreshWatchFolder()
    }

    fun scanWatchFolderNow() {
        val current = _state.value
        if (current.watch.scanInProgress || current.busy != null || current.isRecording) return
        if (watchRepo.treeUri() == null) {
            _state.value = current.copy(error = "먼저 폴더를 선택해 주세요.")
            return
        }
        if (watchRepo.autoTranscribe() && !current.ready) {
            _state.value = current.copy(error = "먼저 모델을 준비해 주세요.", screen = Screen.Models)
            return
        }

        watchScanJob?.cancel()
        watchScanJob = viewModelScope.launch {
            refreshWatchFolder()
            try {
                val result = FolderWatchRunner.run(
                    context = getApplication(),
                    onFileStarting = { name ->
                        _state.value = _state.value.copy(
                            busy = BusyState(Stage.DECODING, 0f, title = name),
                        )
                    },
                    onFileProgress = { name, stage, fraction ->
                        _state.value = _state.value.copy(
                            busy = BusyState(stage, fraction.coerceIn(0f, 1f), title = name),
                        )
                    },
                )
                refreshTranscripts()
                refreshWatchFolder()
                val message = when {
                    result.skippedCharging -> "충전 중에만 처리하도록 설정되어 있습니다."
                    result.skippedNoModel -> "모델이 준비되지 않았습니다."
                    result.queued > 0 -> "새 파일 ${result.queued}개를 찾았습니다. 홈에서 전사할 수 있습니다."
                    result.transcribed > 0 -> "새 파일 ${result.transcribed}개를 전사했습니다."
                    result.scanned > 0 -> "새 파일을 처리하지 못했습니다."
                    else -> "새 오디오 파일이 없습니다."
                }
                _state.value = _state.value.copy(busy = null, notice = message, error = result.error)
            } catch (t: Throwable) {
                if (t is CancellationException) {
                    _state.value = _state.value.copy(busy = null)
                } else {
                    _state.value = _state.value.copy(
                        busy = null,
                        error = t.message ?: "폴더 검사에 실패했습니다.",
                    )
                }
            } finally {
                refreshWatchFolder()
            }
        }
    }

    // ----------------------------------------------------------- recording ---

    fun startRecording() {
        val current = _state.value
        if (current.isRecording || current.busy != null) return
        if (!current.ready) {
            _state.value = current.copy(error = "먼저 모델을 준비해 주세요.", screen = Screen.Models)
            return
        }
        if (current.download != null) return

        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(getApplication<Application>().cacheDir, "recordings/rec-$stamp.wav")
        val wavRecorder = WavRecorder(file)
        val failure = wavRecorder.start()
        if (failure != null) {
            _state.value = current.copy(error = failure)
            return
        }

        recorder = wavRecorder
        recorderFile = file
        recordStartedAt = System.currentTimeMillis()

        _state.value = current.copy(
            isRecording = true,
            recordDurationMs = 0L,
            level = 0f,
            error = null,
        )

        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            while (_state.value.isRecording) {
                if (!wavRecorder.isRecording) {
                    // The capture thread stopped on its own (device error,
                    // permission revoked, ...) - surface it instead of leaving
                    // the UI stuck in "recording".
                    val message = wavRecorder.error() ?: "녹음이 중단되었습니다."
                    _state.value = _state.value.copy(
                        isRecording = false,
                        level = 0f,
                        error = message,
                    )
                    wavRecorder.cancel()
                    recorder = null
                    recorderFile = null
                    return@launch
                }
                _state.value = _state.value.copy(
                    recordDurationMs = System.currentTimeMillis() - recordStartedAt,
                    level = wavRecorder.amplitude.value,
                )
                delay(100)
            }
        }
    }

    fun stopRecording() {
        val active = recorder ?: return
        if (active.error() != null) {
            _state.value = _state.value.copy(
                isRecording = false,
                error = active.error(),
            )
            active.cancel()
            recorder = null
            recorderFile = null
            return
        }

        timerJob?.cancel()
        timerJob = null
        val finished = active.stop()
        val duration = active.durationMs
        recorder = null
        recorderFile = null

        _state.value = _state.value.copy(
            isRecording = false,
            level = 0f,
            recordDurationMs = duration,
        )

        if (finished == null) {
            _state.value = _state.value.copy(error = active.error() ?: "녹음이 저장되지 않았습니다.")
            return
        }
        transcribeFile(finished, duration, "녹음")
    }

    fun cancelRecording() {
        timerJob?.cancel()
        timerJob = null
        recorder?.cancel()
        recorder = null
        recorderFile = null
        _state.value = _state.value.copy(isRecording = false, level = 0f, recordDurationMs = 0L)
    }

    // -------------------------------------------------------------- import ---

    fun stagePendingAudio(uri: Uri) {
        val current = _state.value
        if (current.busy != null || current.isRecording) return
        val name = queryDisplayName(uri) ?: "가져온 오디오"
        _state.value = current.copy(pendingAudio = PendingAudio(uri, name), error = null)
    }

    fun clearPendingAudio() {
        _state.value = _state.value.copy(pendingAudio = null)
    }

    fun transcribePendingAudio() {
        val pending = _state.value.pendingAudio ?: return
        transcribeImportedAudio(uri = pending.uri, name = pending.displayName, watchKey = null)
    }

    private fun transcribeImportedAudio(uri: Uri, name: String, watchKey: String?) {
        val current = _state.value
        if (current.busy != null || current.isRecording) return
        if (!current.ready) {
            _state.value = current.copy(error = "먼저 모델을 준비해 주세요.", screen = Screen.Models)
            return
        }

        workJob?.cancel()
        workJob = viewModelScope.launch {
            try {
                _state.value = _state.value.copy(
                    busy = BusyState(Stage.DECODING, 0f, title = name),
                    pendingAudio = null,
                    error = null,
                )

                val transcript = pipeline.transcribeUri(
                    uri = uri,
                    titleHint = name,
                    language = _state.value.language,
                    translate = _state.value.translate,
                    onProgress = { progress ->
                        _state.value = _state.value.copy(
                            busy = BusyState(
                                stage = progress.stage,
                                progress = progress.fraction.coerceIn(0f, 1f),
                                title = name,
                            ),
                        )
                    },
                )

                if (watchKey != null) {
                    watchRepo.markProcessed(watchKey)
                    watchRepo.removeFromQueue(watchKey)
                    refreshWatchFolder()
                }

                _state.value = _state.value.copy(
                    busy = null,
                    transcripts = store.list(),
                    screen = Screen.Detail(transcript.id),
                    detail = transcript,
                )
            } catch (t: Throwable) {
                if (watchKey == null) {
                    _state.value = _state.value.copy(
                        pendingAudio = PendingAudio(uri, name),
                    )
                }
                handleWorkError(t)
            }
        }
    }

    // -------------------------------------------------------- transcription ---

    private fun transcribeFile(wav: File, durationMs: Long, titleHint: String) {
        workJob?.cancel()
        workJob = viewModelScope.launch {
            try {
                handleTranscription(wav, durationMs, titleHint, deleteWhenDone = true)
            } catch (t: Throwable) {
                handleWorkError(t)
            }
        }
    }

    private suspend fun handleTranscription(
        wav: File,
        durationMs: Long,
        titleHint: String,
        deleteWhenDone: Boolean,
    ) {
        val model = _state.value.selectedModel
        val language = _state.value.language.takeIf { it != LANG_AUTO }
        val translate = _state.value.translate

        val worker = Transcriber(wavFile = wav, modelFile = model!!.file)
        transcriber = worker

        val segments = ArrayList<TranscriptSegment>()
        var lastEmit = 0L

        try {
            _state.value = _state.value.copy(
                busy = BusyState(Stage.TRANSCRIBING, 0f, title = titleHint),
            )

            val result = withContext(Dispatchers.Default) {
                worker.transcribe(
                    language = language,
                    translate = translate,
                    onProgress = { progress ->
                        _state.value = _state.value.copy(
                            busy = (_state.value.busy ?: BusyState(Stage.TRANSCRIBING))
                                .copy(progress = progress.coerceIn(0f, 1f)),
                        )
                    },
                    onSegment = { segment ->
                        synchronized(segments) { segments.add(segment) }
                        val now = System.currentTimeMillis()
                        if (now - lastEmit > 120 || segments.size < 3) {
                            lastEmit = now
                            val preview = synchronized(segments) { segments.takeLast(40) }
                                .joinToString(" ") { it.text }
                            _state.value = _state.value.copy(
                                busy = (_state.value.busy ?: BusyState(Stage.TRANSCRIBING))
                                    .copy(liveText = preview),
                            )
                        }
                    },
                )
            }

            if (result.isEmpty()) {
                _state.value = _state.value.copy(
                    busy = null,
                    error = "음성이 감지되지 않았습니다. 더 가까이에서 다시 시도해 보세요.",
                )
                return
            }

            val createdAt = System.currentTimeMillis()
            val transcript = Transcript(
                id = TranscriptStore.newId(),
                title = TranscriptStore.defaultTitle(result.first().text, createdAt),
                createdAt = createdAt,
                durationMs = durationMs,
                language = language ?: LANG_AUTO,
                modelName = model.displayName,
                segments = result,
            )
            store.save(transcript)

            _state.value = _state.value.copy(
                busy = null,
                transcripts = store.list(),
                screen = Screen.Detail(transcript.id),
                detail = transcript,
            )
        } finally {
            transcriber = null
            if (deleteWhenDone) wav.delete()
        }
    }

    fun cancelWork() {
        if (_state.value.isRecording) {
            cancelRecording()
            return
        }
        transcriber?.cancel()
        workJob?.cancel()
        watchScanJob?.cancel()
        transcriber = null
        _state.value = _state.value.copy(busy = null)
    }

    private fun handleWorkError(t: Throwable) {
        transcriber = null
        if (t is CancellationException) {
            _state.value = _state.value.copy(busy = null)
            return
        }
        _state.value = _state.value.copy(
            busy = null,
            error = t.message ?: "작업을 완료하지 못했습니다.",
        )
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        getApplication<Application>().contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }.getOrNull()

    override fun onCleared() {
        super.onCleared()
        timerJob?.cancel()
        workJob?.cancel()
        watchScanJob?.cancel()
        updateCheckJob?.cancel()
        updateDownloadJob?.cancel()
        recorder?.cancel()
        transcriber?.cancel()
    }

    companion object {
        private const val KEY_LANGUAGE = "language"
        private const val KEY_TRANSLATE = "translate"
    }
}
