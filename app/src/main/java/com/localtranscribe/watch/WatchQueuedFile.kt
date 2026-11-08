package com.localtranscribe.watch

import android.net.Uri

data class WatchQueuedFile(
    val key: String,
    val uri: Uri,
    val displayName: String,
    val lastModified: Long,
) {
    fun encode(): String = listOf(key, uri.toString(), displayName, lastModified.toString()).joinToString(SEP)

    companion object {
        private const val SEP = "\u001F"

        fun decode(raw: String): WatchQueuedFile? {
            val parts = raw.split(SEP, limit = 4)
            if (parts.size != 4) return null
            val modified = parts[3].toLongOrNull() ?: return null
            return WatchQueuedFile(
                key = parts[0],
                uri = Uri.parse(parts[1]),
                displayName = parts[2],
                lastModified = modified,
            )
        }

        fun fromCandidate(candidate: WatchCandidate): WatchQueuedFile =
            WatchQueuedFile(
                key = candidate.key,
                uri = candidate.uri,
                displayName = candidate.displayName,
                lastModified = candidate.lastModified,
            )
    }
}
