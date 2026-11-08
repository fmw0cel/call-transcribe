package com.localtranscribe.watch

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.localtranscribe.data.TranscriptStore
import com.localtranscribe.model.ModelRepository
import com.localtranscribe.ui.LANG_AUTO
import com.localtranscribe.ui.Stage
import kotlinx.coroutines.CancellationException

data class FolderWatchRunResult(
    val scanned: Int,
    val transcribed: Int,
    val queued: Int,
    val skippedCharging: Boolean,
    val skippedNoModel: Boolean,
    val error: String? = null,
)

/**
 * Scans the persisted SAF folder. Transcribes immediately or enqueues for manual mode.
 * Safe to call from UI coroutines or [FolderWatchWorker].
 */
object FolderWatchRunner {

    suspend fun run(
        context: Context,
        onFileStarting: suspend (String) -> Unit = {},
        onFileProgress: (String, Stage, Float) -> Unit = { _, _, _ -> },
    ): FolderWatchRunResult {
        val app = context.applicationContext
        val repository = WatchFolderRepository(app)
        if (!repository.isEnabled()) {
            return FolderWatchRunResult(0, 0, 0, skippedCharging = false, skippedNoModel = false)
        }
        val treeUri = repository.treeUri()
            ?: return FolderWatchRunResult(0, 0, 0, skippedCharging = false, skippedNoModel = false)

        if (repository.requiresCharging() && !isCharging(app)) {
            return FolderWatchRunResult(0, 0, 0, skippedCharging = true, skippedNoModel = false)
        }

        val candidates = WatchFolderScanner.findNewAudio(app, treeUri, repository)
        repository.markScanned()

        if (candidates.isEmpty()) {
            return FolderWatchRunResult(0, 0, 0, skippedCharging = false, skippedNoModel = false)
        }

        if (!repository.autoTranscribe()) {
            candidates.forEach { repository.enqueue(it) }
            return FolderWatchRunResult(
                scanned = candidates.size,
                transcribed = 0,
                queued = candidates.size,
                skippedCharging = false,
                skippedNoModel = false,
            )
        }

        val models = ModelRepository(app)
        if (models.selected() == null) {
            return FolderWatchRunResult(
                scanned = candidates.size,
                transcribed = 0,
                queued = 0,
                skippedCharging = false,
                skippedNoModel = true,
            )
        }

        val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val language = prefs.getString("language", LANG_AUTO) ?: LANG_AUTO
        val translate = prefs.getBoolean("translate", false)

        val pipeline = TranscriptionPipeline(app, models, TranscriptStore(app))
        var done = 0
        var lastError: String? = null

        for (candidate in candidates) {
            try {
                onFileStarting(candidate.displayName)
                pipeline.transcribeUri(
                    uri = candidate.uri,
                    titleHint = candidate.displayName,
                    language = language,
                    translate = translate,
                    onProgress = { progress ->
                        onFileProgress(candidate.displayName, progress.stage, progress.fraction)
                    },
                )
                repository.markProcessed(candidate.key)
                done++
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                lastError = t.message ?: "전사에 실패했습니다."
            }
        }

        return FolderWatchRunResult(
            scanned = candidates.size,
            transcribed = done,
            queued = 0,
            skippedCharging = false,
            skippedNoModel = false,
            error = lastError,
        )
    }

    private fun isCharging(context: Context): Boolean {
        val intent = context.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
        ) ?: return false
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        return status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
    }
}
