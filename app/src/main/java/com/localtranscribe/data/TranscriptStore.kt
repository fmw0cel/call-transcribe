package com.localtranscribe.data

import android.content.Context
import android.util.Log
import com.localtranscribe.whisper.TranscriptSegment
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

data class Transcript(
    val id: String,
    val title: String,
    val createdAt: Long,
    val durationMs: Long,
    val language: String?,
    val modelName: String?,
    val segments: List<TranscriptSegment>,
) {
    val text: String
        get() = segments.joinToString("\n") { it.text }

    val plainText: String
        get() = segments.joinToString(" ") { it.text }

    fun srt(): String {
        val builder = StringBuilder()
        segments.forEachIndexed { index, segment ->
            builder.append(index + 1).append('\n')
            builder.append(formatTimestamp(segment.startMs)).append(" --> ")
                .append(formatTimestamp(segment.endMs)).append('\n')
            builder.append(segment.text).append("\n\n")
        }
        return builder.toString()
    }

    companion object {
        fun formatTimestamp(ms: Long): String {
            val totalSeconds = ms / 1000
            val hours = totalSeconds / 3600
            val minutes = (totalSeconds % 3600) / 60
            val seconds = totalSeconds % 60
            val millis = ms % 1000
            return String.format(
                Locale.US,
                "%02d:%02d:%02d,%03d",
                hours,
                minutes,
                seconds,
                millis,
            )
        }
    }
}

class TranscriptStore(context: Context) {

    private val dir = File(context.filesDir, "transcripts").apply { mkdirs() }

    fun list(): List<Transcript> = dir.listFiles()
        .orEmpty()
        .filter { it.isFile && it.name.endsWith(".json") }
        .mapNotNull { read(it) }
        .sortedByDescending { it.createdAt }

    fun load(id: String): Transcript? = read(File(dir, "$id.json"))

    fun save(transcript: Transcript): Transcript {
        val json = JSONObject().apply {
            put("id", transcript.id)
            put("title", transcript.title)
            put("createdAt", transcript.createdAt)
            put("durationMs", transcript.durationMs)
            put("language", transcript.language ?: "")
            put("model", transcript.modelName ?: "")
            put("segments", JSONArray().apply {
                transcript.segments.forEach { segment ->
                    put(JSONObject().apply {
                        put("s", segment.startMs)
                        put("e", segment.endMs)
                        put("t", segment.text)
                    })
                }
            })
        }
        File(dir, "${transcript.id}.json").writeText(json.toString())
        return transcript
    }

    fun delete(id: String) {
        File(dir, "$id.json").delete()
    }

    private fun read(file: File): Transcript? = try {
        val json = JSONObject(file.readText())
        val segmentsJson = json.optJSONArray("segments") ?: JSONArray()
        val segments = ArrayList<TranscriptSegment>(segmentsJson.length())
        for (i in 0 until segmentsJson.length()) {
            val item = segmentsJson.getJSONObject(i)
            segments.add(
                TranscriptSegment(
                    startMs = item.optLong("s"),
                    endMs = item.optLong("e"),
                    text = item.optString("t"),
                ),
            )
        }
        Transcript(
            id = json.getString("id"),
            title = json.optString("title", "전사"),
            createdAt = json.optLong("createdAt"),
            durationMs = json.optLong("durationMs"),
            language = json.optString("language").ifBlank { null },
            modelName = json.optString("model").ifBlank { null },
            segments = segments,
        )
    } catch (t: Throwable) {
        Log.w("TranscriptStore", "failed to read ${file.name}", t)
        null
    }

    companion object {
        fun newId(): String = UUID.randomUUID().toString()

        fun defaultTitle(firstText: String?, createdAt: Long): String {
            val snippet = firstText?.trim()?.take(28)?.takeIf { it.isNotBlank() }
            if (snippet != null) return snippet
            val time = SimpleDateFormat("M월 d일 HH:mm", Locale.KOREAN).format(Date(createdAt))
            return "전사 $time"
        }
    }
}
