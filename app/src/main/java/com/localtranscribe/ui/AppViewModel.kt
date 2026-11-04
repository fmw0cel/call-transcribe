package com.localtranscribe.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.localtranscribe.audio.AudioDecoder
import com.localtranscribe.audio.WavRecorder
import com.localtranscribe.data.Transcript
import com.localtranscribe.data.TranscriptStore
import com.localtranscribe.model.InstalledModel
import com.localtranscribe.model.ModelRepository
import com.localtranscribe.model.ModelSpec
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
) {
    val ready: Boolean get() = selectedModel != null
}

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

    init {
        refreshModels()
        refreshTranscripts()
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

    fun importAudio(uri: Uri) {
        val current = _state.value
        if (current.busy != null || current.isRecording) return
        if (!current.ready) {
            _state.value = current.copy(error = "먼저 모델을 준비해 주세요.", screen = Screen.Models)
            return
        }

        val app = getApplication<Application>()
        workJob?.cancel()
        workJob = viewModelScope.launch {
            val name = queryDisplayName(uri) ?: "가져온 오디오"
            val temp = File(app.cacheDir, "imports/import-${System.currentTimeMillis()}.wav")
            try {
                _state.value = _state.value.copy(
                    busy = BusyState(Stage.DECODING, 0f, title = name),
                    error = null,
                )

                val decoded = withContext(Dispatchers.IO) {
                    AudioDecoder.decodeTo16kWav(
                        context = app,
                        uri = uri,
                        target = temp,
                        onProgress = { progress ->
                            _state.value = _state.value.copy(
                                busy = (_state.value.busy ?: BusyState(Stage.DECODING))
                                    .copy(progress = progress.coerceIn(0f, 1f)),
                            )
                        },
                        isCancelled = { !isActive },
                    )
                }

                handleTranscription(
                    wav = decoded.file,
                    durationMs = decoded.durationMs,
                    titleHint = name,
                    deleteWhenDone = true,
                )
            } catch (t: Throwable) {
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
        recorder?.cancel()
        transcriber?.cancel()
    }

    companion object {
        private const val KEY_LANGUAGE = "language"
        private const val KEY_TRANSLATE = "translate"
    }
}
