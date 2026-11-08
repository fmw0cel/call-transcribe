package com.localtranscribe.update

object AppVersion {

    fun normalize(tagOrVersion: String): String =
        tagOrVersion.trim().removePrefix("v").removePrefix("V")

    /** @return positive if [latest] is newer than [current] */
    fun compare(latest: String, current: String): Int {
        val latestParts = normalize(latest).split('.').map { it.toIntOrNull() ?: 0 }
        val currentParts = normalize(current).split('.').map { it.toIntOrNull() ?: 0 }
        val max = maxOf(latestParts.size, currentParts.size, 3)
        for (i in 0 until max) {
            val l = latestParts.getOrElse(i) { 0 }
            val c = currentParts.getOrElse(i) { 0 }
            if (l != c) return l - c
        }
        return 0
    }

    fun isNewer(latest: String, current: String): Boolean = compare(latest, current) > 0
}
