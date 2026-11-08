package com.localtranscribe.watch

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.localtranscribe.audio.AudioDecoder
import com.localtranscribe.data.Transcript
import com.localtranscribe.data.TranscriptStore
import com.localtranscribe.model.ModelRepository
import com.localtranscribe.ui.LANG_AUTO
import com.localtranscribe.ui.Stage
import com.localtranscribe.whisper.Transcriber
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

class TranscriptionPipeline(
    private val context: Context,
    private val models: ModelRepository,
    private val store: TranscriptStore,
) {

    data class Progress(val stage: Stage, val fraction: Float)

    suspend fun transcribeUri(
        uri: Uri,
        titleHint: String?,
        language: String,
        translate: Boolean,
        onProgress: (Progress) -> Unit = {},
    ): Transcript {
        val model = models.selected()
            ?: throw IllegalStateException("전사 모델이 준비되지 않았습니다.")
        val name = titleHint ?: queryDisplayName(uri) ?: "가져온 오디오"
        val temp = File(context.cacheDir, "imports/import-${System.currentTimeMillis()}.wav")

        var wavFile: File? = null
        try {
            onProgress(Progress(Stage.DECODING, 0f))
            val decoded = withContext(Dispatchers.IO) {
                AudioDecoder.decodeTo16kWav(
                    context = context,
                    uri = uri,
                    target = temp,
                    onProgress = { onProgress(Progress(Stage.DECODING, it.coerceIn(0f, 1f))) },
                    isCancelled = { !coroutineContext.isActive },
                )
            }

            wavFile = decoded.file
            onProgress(Progress(Stage.TRANSCRIBING, 0f))
            val languageCode = language.takeIf { it != LANG_AUTO }
            val worker = Transcriber(wavFile = decoded.file, modelFile = model.file)
            val result = withContext(Dispatchers.Default) {
                worker.transcribe(
                    language = languageCode,
                    translate = translate,
                    onProgress = { onProgress(Progress(Stage.TRANSCRIBING, it.coerceIn(0f, 1f))) },
                    onSegment = {},
                )
            }

            if (result.isEmpty()) {
                throw IllegalStateException("음성이 감지되지 않았습니다.")
            }

            val createdAt = System.currentTimeMillis()
            val transcript = Transcript(
                id = TranscriptStore.newId(),
                title = TranscriptStore.defaultTitle(result.first().text, createdAt),
                createdAt = createdAt,
                durationMs = decoded.durationMs,
                language = languageCode ?: LANG_AUTO,
                modelName = model.displayName,
                segments = result,
            )
            store.save(transcript)
            return transcript
        } finally {
            wavFile?.delete()
            temp.delete()
        }
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }.getOrNull()
}
