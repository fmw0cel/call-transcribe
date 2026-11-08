package com.localtranscribe.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.localtranscribe.R
import com.localtranscribe.data.Transcript
import com.localtranscribe.watch.WatchQueuedFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HomeScreen(
    state: UiState,
    onRecord: () -> Unit,
    onStop: () -> Unit,
    onCancelRecording: () -> Unit,
    onImport: () -> Unit,
    onOpenModels: () -> Unit,
    onOpenTranscript: (String) -> Unit,
    onDeleteTranscript: (String) -> Unit,
    onLanguageChange: (String) -> Unit,
    onTranslateChange: (Boolean) -> Unit,
    onCancelWork: () -> Unit,
    onOpenWatchFolder: () -> Unit,
    watch: WatchFolderUi,
    pendingAudio: PendingAudio?,
    onTranscribePending: () -> Unit,
    onClearPending: () -> Unit,
    watchQueue: List<WatchQueuedFile>,
    onTranscribeWatchQueued: (String) -> Unit,
    onDismissWatchQueued: (String) -> Unit,
) {
    var pendingDelete by remember { mutableStateOf<Transcript?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Column {
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "오디오가 기기 밖으로 나가지 않습니다",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            ModelStrip(model = state.selectedModel?.displayName, onOpenModels = onOpenModels)
        }

        item {
            RecordCard(
                isRecording = state.isRecording,
                elapsedMs = state.recordDurationMs,
                level = state.level,
                enabled = state.ready && state.busy == null,
                onRecord = onRecord,
                onStop = onStop,
                onCancel = onCancelRecording,
            )
        }

        item {
            OutlinedButton(
                onClick = onImport,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(16.dp),
                enabled = state.busy == null && !state.isRecording,
            ) {
                Icon(Icons.Filled.AudioFile, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("오디오 파일 가져오기")
            }
        }

        pendingAudio?.let { pending ->
            item {
                PendingAudioCard(
                    sourceLabel = "가져온 파일",
                    displayName = pending.displayName,
                    canTranscribe = state.ready && state.busy == null && !state.isRecording,
                    canClear = state.busy == null && !state.isRecording,
                    onTranscribe = onTranscribePending,
                    onClear = onClearPending,
                )
            }
        }

        if (watchQueue.isNotEmpty()) {
            item {
                Text(
                    text = "폴더에서 찾은 파일",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            items(watchQueue, key = { it.key }) { queued ->
                PendingAudioCard(
                    sourceLabel = "폴더",
                    displayName = queued.displayName,
                    canTranscribe = state.ready && state.busy == null && !state.isRecording,
                    canClear = state.busy == null && !state.isRecording,
                    onTranscribe = { onTranscribeWatchQueued(queued.key) },
                    onClear = { onDismissWatchQueued(queued.key) },
                )
            }
        }

        item {
            WatchFolderStrip(watch = watch, onOpen = onOpenWatchFolder)
        }

        state.busy?.let { busy ->
            item { BusyCard(busy = busy, onCancel = onCancelWork) }
        }

        item {
            SettingsCard(
                language = state.language,
                translate = state.translate,
                enabled = !state.isRecording,
                onLanguageChange = onLanguageChange,
                onTranslateChange = onTranslateChange,
            )
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "전사 기록",
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "${state.transcripts.size}개",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (state.transcripts.isEmpty()) {
            item {
                EmptyState(
                    title = "아직 전사한 기록이 없습니다",
                    body = "녹음하거나 오디오 파일을 가져와 시작하세요.",
                )
            }
        } else {
            items(state.transcripts, key = { it.id }) { transcript ->
                TranscriptRow(
                    transcript = transcript,
                    onClick = { onOpenTranscript(transcript.id) },
                    onDelete = { pendingDelete = transcript },
                )
            }
        }
    }

    pendingDelete?.let { transcript ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("기록 삭제") },
            text = { Text("'${transcript.title}' 전사 기록을 삭제할까요? 되돌릴 수 없습니다.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteTranscript(transcript.id)
                        pendingDelete = null
                    },
                ) { Text("삭제") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("취소") }
            },
        )
    }
}

@Composable
private fun PendingAudioCard(
    sourceLabel: String,
    displayName: String,
    canTranscribe: Boolean,
    canClear: Boolean,
    onTranscribe: () -> Unit,
    onClear: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.AudioFile,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    sourceLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Button(
                onClick = onTranscribe,
                enabled = canTranscribe,
                shape = RoundedCornerShape(12.dp),
            ) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text("전사 시작")
            }
            IconButton(onClick = onClear, enabled = canClear) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "파일 선택 취소",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun WatchFolderStrip(watch: WatchFolderUi, onOpen: () -> Unit) {
    val subtitle = when {
        !watch.hasFolder -> "폴더를 고르면 새 파일을 찾습니다"
        watch.queuedCount > 0 && !watch.autoTranscribe ->
            "대기 ${watch.queuedCount}개 · ${watch.folderLabel}"
        watch.enabled && watch.autoTranscribe -> "자동 전사 켜짐 · ${watch.folderLabel}"
        watch.enabled -> "수동 전사 · ${watch.folderLabel}"
        else -> "꺼짐 · ${watch.folderLabel}"
    }
    Surface(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(onClick = onOpen),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.FolderOpen,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("자동 폴더 전사", style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                imageVector = Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun ModelStrip(model: String?, onOpenModels: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(onClick = onOpenModels),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.GraphicEq,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = if (model == null) "모델 준비 필요" else "Whisper $model",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = if (model == null) {
                        "Base 모델(약 60MB) 받기 권장 · 오프라인 전사"
                    } else {
                        "기기 내 추론 · 오프라인"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = if (model == null) "설정" else "변경",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Icon(
                imageVector = Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun RecordCard(
    isRecording: Boolean,
    elapsedMs: Long,
    level: Float,
    enabled: Boolean,
    onRecord: () -> Unit,
    onStop: () -> Unit,
    onCancel: () -> Unit,
) {
    val animatedLevel by animateFloatAsState(
        targetValue = if (isRecording) level.coerceIn(0f, 1f) else 0f,
        label = "level",
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (isRecording) {
                    Box(
                        modifier = Modifier
                            .size((110 + 50 * animatedLevel).dp)
                            .background(
                                brush = Brush.radialGradient(
                                    colors = listOf(
                                        MaterialTheme.colorScheme.error.copy(alpha = 0.28f),
                                        MaterialTheme.colorScheme.error.copy(alpha = 0.02f),
                                    ),
                                ),
                                shape = CircleShape,
                            ),
                    )
                }

                Box(
                    modifier = Modifier
                        .size(104.dp)
                        .clip(CircleShape)
                        .background(
                            if (isRecording) {
                                MaterialTheme.colorScheme.error
                            } else if (enabled) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                        )
                        .clickable(enabled = enabled || isRecording) {
                            if (isRecording) onStop() else onRecord()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (isRecording) Icons.Filled.Stop else Icons.Filled.Mic,
                        contentDescription = if (isRecording) "녹음 중지" else "녹음 시작",
                        tint = if (isRecording || enabled) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(42.dp),
                    )
                }
            }

            Text(
                text = if (isRecording) formatDuration(elapsedMs) else "탭하여 녹음 시작",
                style = if (isRecording) {
                    MaterialTheme.typography.headlineSmall.copy(fontFamily = FontFamily.Monospace)
                } else {
                    MaterialTheme.typography.titleMedium
                },
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = when {
                    !enabled && !isRecording -> "모델을 먼저 준비해 주세요"
                    isRecording -> "다시 탭하면 녹음을 마치고 바로 전사합니다"
                    else -> "16 kHz 모노 WAV로 저장 · Whisper로 전사"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (isRecording) {
                Spacer(Modifier.height(14.dp))
                Box(
                    modifier = Modifier
                        .width(180.dp)
                        .height(6.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(animatedLevel)
                            .height(6.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.error),
                    )
                }
                Spacer(Modifier.height(6.dp))
                TextButton(onClick = onCancel) {
                    Text("녹음 취소", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun BusyCard(busy: BusyState, onCancel: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = when (busy.stage) {
                            Stage.DECODING -> "오디오 준비 중"
                            Stage.TRANSCRIBING -> "전사 중"
                            Stage.IMPORT -> "모델 가져오는 중"
                        },
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = busy.title,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = "${(busy.progress * 100).toInt()}%",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { busy.progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
            )
            if (busy.liveText.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = busy.liveText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton(onClick = onCancel, modifier = Modifier.align(Alignment.End)) {
                Text("취소")
            }
        }
    }
}

@Composable
private fun SettingsCard(
    language: String,
    translate: Boolean,
    enabled: Boolean,
    onLanguageChange: (String) -> Unit,
    onTranslateChange: (Boolean) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val languageLabel = LANGUAGES.firstOrNull { it.code == language }?.label ?: language

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("언어", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "자동 감지가 느리면 직접 선택하세요",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Box {
                    TextButton(onClick = { expanded = true }, enabled = enabled) {
                        Text(languageLabel)
                    }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        LANGUAGES.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option.label) },
                                onClick = {
                                    expanded = false
                                    onLanguageChange(option.code)
                                },
                            )
                        }
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("영어로 번역", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "전사 결과를 영어로 출력합니다",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = translate,
                    onCheckedChange = onTranslateChange,
                    enabled = enabled,
                )
            }
        }
    }
}

@Composable
private fun TranscriptRow(
    transcript: Transcript,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    val date = remember(transcript.createdAt) {
        SimpleDateFormat("M월 d일 HH:mm", Locale.KOREAN).format(Date(transcript.createdAt))
    }

    Card(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 14.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = transcript.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = "$date · ${formatDuration(transcript.durationMs)} · ${transcript.modelName ?: "Whisper"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = transcript.plainText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Filled.DeleteOutline,
                    contentDescription = "삭제",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
