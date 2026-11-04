package com.localtranscribe.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.localtranscribe.data.Transcript
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    transcript: Transcript?,
    onBack: () -> Unit,
    onDelete: (String) -> Unit,
    notify: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirmDelete by remember { mutableStateOf(false) }

    val saveSrt = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/x-subrip"),
    ) { uri ->
        val current = transcript ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(current.srt().toByteArray())
                    } ?: error("open failed")
                }.isSuccess
            }
            if (ok) notify("SRT 자막 파일을 저장했습니다.") else notify("저장하지 못했습니다.")
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Text(
                    text = transcript?.title ?: "전사",
                    maxLines = 1,
                    style = MaterialTheme.typography.titleMedium,
                )
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
                }
            },
            actions = {
                if (transcript != null) {
                    IconButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                                as ClipboardManager
                            clipboard.setPrimaryClip(
                                ClipData.newPlainText("전사", transcript.text),
                            )
                            notify("전사문을 복사했습니다.")
                        },
                    ) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = "복사")
                    }
                    IconButton(onClick = { shareText(context, transcript) }) {
                        Icon(Icons.Filled.Share, contentDescription = "공유")
                    }
                    IconButton(onClick = { confirmDelete = true }) {
                        Icon(Icons.Filled.DeleteOutline, contentDescription = "삭제")
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
            ),
            windowInsets = WindowInsets(0, 0, 0, 0),
        )

        if (transcript == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("기록을 찾을 수 없습니다.")
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 40.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LabeledValue("길이", formatDuration(transcript.durationMs))
                        Spacer(Modifier.width(24.dp))
                        LabeledValue("모델", transcript.modelName ?: "Whisper")
                        Spacer(Modifier.width(24.dp))
                        LabeledValue(
                            "언어",
                            LANGUAGES.firstOrNull { it.code == transcript.language }?.label ?: "자동",
                        )
                    }
                }
                item { Spacer(Modifier.height(8.dp)) }
                item { HorizontalDivider() }
                item {
                    SelectionContainer {
                        Column {
                            transcript.segments.forEach { segment ->
                                Column(Modifier.padding(top = 14.dp)) {
                                    Text(
                                        text = formatDuration(segment.startMs),
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontFamily = FontFamily.Monospace,
                                        ),
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.Medium,
                                    )
                                    Spacer(Modifier.height(3.dp))
                                    Text(
                                        text = segment.text,
                                        style = MaterialTheme.typography.bodyLarge,
                                    )
                                }
                            }
                        }
                    }
                }
                item {
                    Spacer(Modifier.height(28.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { shareText(context, transcript) }) {
                            Text("전체 텍스트 공유")
                        }
                        TextButton(onClick = { saveSrt.launch("${transcript.title}.srt") }) {
                            Text("SRT로 저장")
                        }
                    }
                }
            }
        }
    }

    if (confirmDelete && transcript != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("기록 삭제") },
            text = { Text("이 전사 기록을 삭제할까요?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        onDelete(transcript.id)
                    },
                ) { Text("삭제") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("취소") }
            },
        )
    }
}

internal fun shareText(context: Context, transcript: Transcript) {
    val body = buildString {
        append(transcript.title).append("\n\n")
        append(transcript.text)
    }
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, transcript.title)
        putExtra(Intent.EXTRA_TEXT, body)
    }
    context.startActivity(Intent.createChooser(intent, "전사문 공유"))
}
