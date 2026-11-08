package com.localtranscribe.watch

import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import androidx.documentfile.provider.DocumentFile

class WatchFolderRepository(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, false)

    fun setEnabled(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_ENABLED, enabled) }
    }

    fun requiresCharging(): Boolean = prefs.getBoolean(KEY_WHILE_CHARGING, false)

    fun setRequiresCharging(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_WHILE_CHARGING, enabled) }
    }

    /** When false, scans only add files to [queue] for manual transcription on Home. */
    fun autoTranscribe(): Boolean = prefs.getBoolean(KEY_AUTO_TRANSCRIBE, true)

    fun setAutoTranscribe(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_AUTO_TRANSCRIBE, enabled) }
    }

    fun queue(): List<WatchQueuedFile> = queuedRaw()
        .mapNotNull(WatchQueuedFile::decode)
        .sortedBy { it.lastModified }

    fun enqueue(candidate: WatchCandidate) {
        if (isQueued(candidate.key) || isProcessed(candidate.key)) return
        val next = queuedRaw().toMutableSet()
        next.add(WatchQueuedFile.fromCandidate(candidate).encode())
        prefs.edit { putStringSet(KEY_QUEUE, next) }
    }

    fun isQueued(key: String): Boolean = queue().any { it.key == key }

    fun removeFromQueue(key: String) {
        val next = queuedRaw().filterNot { raw ->
            WatchQueuedFile.decode(raw)?.key == key
        }.toMutableSet()
        prefs.edit { putStringSet(KEY_QUEUE, next) }
    }

    fun queuedCount(): Int = queue().size

    private fun queuedRaw(): Set<String> = prefs.getStringSet(KEY_QUEUE, emptySet()) ?: emptySet()

    fun treeUri(): Uri? = prefs.getString(KEY_TREE_URI, null)?.let(Uri::parse)

    fun saveTreeUri(uri: Uri) {
        prefs.edit { putString(KEY_TREE_URI, uri.toString()) }
    }

    fun clearTreeUri() {
        prefs.edit {
            remove(KEY_TREE_URI)
            remove(KEY_FOLDER_LABEL)
            remove(KEY_QUEUE)
            putBoolean(KEY_ENABLED, false)
        }
    }

    fun folderLabel(): String? = prefs.getString(KEY_FOLDER_LABEL, null)

    fun saveFolderLabel(label: String) {
        prefs.edit { putString(KEY_FOLDER_LABEL, label) }
    }

    fun lastScanAt(): Long = prefs.getLong(KEY_LAST_SCAN, 0L)

    fun markScanned() {
        prefs.edit { putLong(KEY_LAST_SCAN, System.currentTimeMillis()) }
    }

    fun isProcessed(key: String): Boolean = processed().contains(key)

    fun markProcessed(key: String) {
        val next = processed().toMutableSet().apply { add(key) }
        prefs.edit { putStringSet(KEY_PROCESSED, next) }
    }

    fun processedCount(): Int = processed().size

    private fun processed(): Set<String> = prefs.getStringSet(KEY_PROCESSED, emptySet()) ?: emptySet()

    fun folderDisplayName(context: Context): String {
        folderLabel()?.let { return it }
        val uri = treeUri() ?: return "선택 안 됨"
        return DocumentFile.fromTreeUri(context, uri)?.name ?: "선택된 폴더"
    }

    companion object {
        private const val PREFS = "watch_folder"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_AUTO_TRANSCRIBE = "auto_transcribe"
        private const val KEY_WHILE_CHARGING = "while_charging"
        private const val KEY_QUEUE = "manual_queue"
        private const val KEY_TREE_URI = "tree_uri"
        private const val KEY_FOLDER_LABEL = "folder_label"
        private const val KEY_LAST_SCAN = "last_scan"
        private const val KEY_PROCESSED = "processed_keys"

        fun fileKey(uri: Uri, lastModified: Long): String = "${uri}:${lastModified}"
    }
}
