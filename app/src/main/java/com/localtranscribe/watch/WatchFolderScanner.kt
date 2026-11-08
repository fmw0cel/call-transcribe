package com.localtranscribe.watch

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

data class WatchCandidate(
    val uri: Uri,
    val displayName: String,
    val lastModified: Long,
) {
    val key: String get() = WatchFolderRepository.fileKey(uri, lastModified)
}

object WatchFolderScanner {

    private val AUDIO_EXTENSIONS = listOf(
        ".m4a", ".mp3", ".ogg", ".opus", ".flac", ".webm", ".wav",
        ".amr", ".3gp", ".aac", ".wma", ".mkv",
    )

    /** Skip files still being written (common while a recorder is flushing). */
    private const val MIN_AGE_MS = 60_000L

    fun findNewAudio(
        context: Context,
        treeUri: Uri,
        repository: WatchFolderRepository,
        nowMs: Long = System.currentTimeMillis(),
    ): List<WatchCandidate> {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return emptyList()
        val found = mutableListOf<WatchCandidate>()
        walk(root, found)
        return found
            .filter { nowMs - it.lastModified >= MIN_AGE_MS }
            .filterNot { repository.isProcessed(it.key) }
            .filterNot { repository.isQueued(it.key) }
            .sortedBy { it.lastModified }
    }

    private fun walk(doc: DocumentFile, out: MutableList<WatchCandidate>) {
        if (doc.isDirectory) {
            doc.listFiles()?.forEach { walk(it, out) }
            return
        }
        if (!doc.isFile) return
        if (!isAudio(doc)) return
        val uri = doc.uri
        val name = doc.name ?: return
        out.add(WatchCandidate(uri, name, doc.lastModified()))
    }

    private fun isAudio(doc: DocumentFile): Boolean {
        val mime = doc.type
        if (mime != null && mime.startsWith("audio/")) return true
        val name = doc.name?.lowercase() ?: return false
        return AUDIO_EXTENSIONS.any { name.endsWith(it) }
    }
}
