package com.localtranscribe.model

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ModelSpec(
    val id: String,
    val displayName: String,
    val fileName: String,
    val sizeBytes: Long,
    val url: String,
    val note: String,
)

data class InstalledModel(
    val id: String,
    val displayName: String,
    val file: File,
) {
    val sizeBytes: Long get() = file.length()
}

object ModelCatalog {
    private const val HF_BASE = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main"

    /** Default for new installs and auto folder transcription when nothing is selected. */
    const val RECOMMENDED_ID = "base-q5_1"

    /** Multilingual checkpoints - required for Korean. */
    val downloadable = listOf(
        ModelSpec(
            id = "base-q5_1",
            displayName = "Base",
            fileName = "ggml-base-q5_1.bin",
            sizeBytes = 59_707_625,
            url = "$HF_BASE/ggml-base-q5_1.bin",
            note = "속도와 정확도의 균형 · 통화·일상 녹음 권장",
        ),
        ModelSpec(
            id = "tiny-q5_1",
            displayName = "Tiny",
            fileName = "ggml-tiny-q5_1.bin",
            sizeBytes = 32_152_673,
            url = "$HF_BASE/ggml-tiny-q5_1.bin",
            note = "가장 빠름 · 정확도 낮음 · 저사양 기기용",
        ),
        ModelSpec(
            id = "small-q5_1",
            displayName = "Small",
            fileName = "ggml-small-q5_1.bin",
            sizeBytes = 190_085_487,
            url = "$HF_BASE/ggml-small-q5_1.bin",
            note = "정확도 높음 · 느림 · 4GB 이상 RAM 권장",
        ),
        ModelSpec(
            id = "large-v3-turbo-q5_0",
            displayName = "Turbo",
            fileName = "ggml-large-v3-turbo-q5_0.bin",
            sizeBytes = 574_041_195,
            url = "$HF_BASE/ggml-large-v3-turbo-q5_0.bin",
            note = "Large v3 Turbo · 정확도 최고 · 574 MB · 8GB RAM / 넉넉한 저장 공간 권장",
        ),
    )
}

class ModelRepository(context: Context) {

    private val appContext = context.applicationContext
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val modelsDir: File = File(appContext.filesDir, "models").apply { mkdirs() }

    fun installed(): List<InstalledModel> {
        val known = ModelCatalog.downloadable.associateBy { it.fileName }
        return modelsDir.listFiles()
            .orEmpty()
            .filter { it.isFile && it.name.endsWith(".bin", ignoreCase = true) && it.length() > 1_000_000 }
            .map { file ->
                val spec = known[file.name]
                InstalledModel(
                    id = spec?.id ?: file.name.removeSuffix(".bin"),
                    displayName = spec?.displayName ?: file.name.removeSuffix(".bin"),
                    file = file,
                )
            }
            .sortedByDescending { it.sizeBytes }
    }

    fun selectedId(): String? = prefs.getString(KEY_SELECTED_MODEL, null)

    /** Selected model, falling back to [ModelCatalog.RECOMMENDED_ID] when installed. */
    fun selected(): InstalledModel? {
        val all = installed()
        val id = selectedId()
        all.firstOrNull { it.id == id }?.let { return it }
        return pickPreferredInstalled(all)
    }

    /**
     * If the user has models but none chosen (e.g. after upgrade), prefer Base.
     */
    fun ensureDefaultSelection() {
        if (selectedId() != null) return
        val preferred = pickPreferredInstalled(installed()) ?: return
        select(preferred)
    }

    private fun pickPreferredInstalled(all: List<InstalledModel>): InstalledModel? {
        if (all.isEmpty()) return null
        val order = listOf(
            ModelCatalog.RECOMMENDED_ID,
            "tiny-q5_1",
            "small-q5_1",
            "large-v3-turbo-q5_0",
        )
        for (modelId in order) {
            all.firstOrNull { it.id == modelId }?.let { return it }
        }
        return all.minByOrNull { it.sizeBytes }
    }

    fun select(model: InstalledModel) {
        prefs.edit().putString(KEY_SELECTED_MODEL, model.id).apply()
        prefs.edit().putString(KEY_SELECTED_PATH, model.file.absolutePath).apply()
    }

    fun delete(model: InstalledModel) {
        model.file.delete()
        if (selectedId() == model.id) {
            prefs.edit().remove(KEY_SELECTED_MODEL).remove(KEY_SELECTED_PATH).apply()
        }
    }

    fun pathOf(model: InstalledModel): String = model.file.absolutePath

    suspend fun download(spec: ModelSpec, onProgress: (Float) -> Unit): InstalledModel =
        withContext(Dispatchers.IO) {
            val target = File(modelsDir, spec.fileName)
            val partial = File(modelsDir, "${spec.fileName}.part")
            partial.delete()

            // Leave a little headroom for the model copy during rename.
            val required = spec.sizeBytes + spec.sizeBytes / 20
            val free = modelsDir.usableSpace
            if (free in 1 until required) {
                throw IllegalStateException(
                    "저장 공간이 부족합니다. " +
                        "필요 약 ${spec.sizeBytes / 1_000_000} MB, " +
                        "남은 공간 ${free / 1_000_000} MB",
                )
            }

            var connection: HttpURLConnection? = null
            try {
                connection = openFollowingRedirects(spec.url)
                val total = connection.contentLengthLong.takeIf { it > 0 } ?: spec.sizeBytes
                connection.inputStream.use { input ->
                    FileOutputStream(partial).use { output ->
                        val buffer = ByteArray(256 * 1024)
                        var written = 0L
                        var lastReported = -1f
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            written += read
                            val progress = (written.toFloat() / total).coerceIn(0f, 1f)
                            if (progress - lastReported >= 0.005f || progress >= 1f) {
                                lastReported = progress
                                onProgress(progress)
                            }
                        }
                    }
                }
                if (partial.length() < 1_000_000L) {
                    throw IllegalStateException("다운로드한 파일이 너무 작습니다.")
                }
                target.delete()
                if (!partial.renameTo(target)) {
                    throw IllegalStateException("파일을 저장하지 못했습니다.")
                }
                val model = InstalledModel(spec.id, spec.displayName, target)
                select(model)
                onProgress(1f)
                model
            } catch (t: Throwable) {
                partial.delete()
                if (t is CancellationException) throw t
                throw t
            } finally {
                connection?.disconnect()
            }
        }

    suspend fun importFromUri(uri: Uri, displayName: String): InstalledModel =
        withContext(Dispatchers.IO) {
            val safeName = displayName
                .substringAfterLast('/')
                .replace(Regex("[^\\p{L}\\p{N}._-]"), "_")
                .ifBlank { "imported-${System.currentTimeMillis()}.bin" }
            val target = File(modelsDir, if (safeName.endsWith(".bin")) safeName else "$safeName.bin")
            val partial = File(modelsDir, "${target.name}.part")
            partial.delete()

            try {
                appContext.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(partial).use { output ->
                        input.copyTo(output, 256 * 1024)
                    }
                } ?: throw IllegalStateException("파일을 열 수 없습니다.")
                if (partial.length() < 1_000_000L) {
                    throw IllegalStateException("모델 파일이 너무 작습니다. ggml .bin 모델인지 확인하세요.")
                }
                target.delete()
                if (!partial.renameTo(target)) throw IllegalStateException("파일을 저장하지 못했습니다.")
                val model = InstalledModel(target.name.removeSuffix(".bin"), displayName, target)
                select(model)
                model
            } catch (t: Throwable) {
                partial.delete()
                throw t
            }
        }

    private fun openFollowingRedirects(url: String): HttpURLConnection {
        var current = URL(url)
        repeat(5) {
            val connection = current.openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 20_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("User-Agent", "LocalTranscribe/1.0")
            val code = connection.responseCode
            if (code in 300..399) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                if (location.isNullOrBlank()) throw IllegalStateException("리다이렉트 주소가 없습니다.")
                current = URL(current, location)
            } else if (code in 200..299) {
                return connection
            } else {
                connection.disconnect()
                throw IllegalStateException("모델을 내려받지 못했습니다 (HTTP $code).")
            }
        }
        throw IllegalStateException("리다이렉트가 너무 많습니다.")
    }

    companion object {
        private const val KEY_SELECTED_MODEL = "selected_model_id"
        private const val KEY_SELECTED_PATH = "selected_model_path"
    }
}
