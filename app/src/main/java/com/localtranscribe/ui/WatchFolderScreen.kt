package com.localtranscribe.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class WatchFolderUi(
    val hasFolder: Boolean,
    val folderLabel: String,
    val enabled: Boolean,
    val autoTranscribe: Boolean,
    val whileCharging: Boolean,
    val lastScanAt: Long,
    val processedCount: Int,
    val queuedCount: Int,
    val scanInProgress: Boolean,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WatchFolderScreen(
    watch: WatchFolderUi,
    onBack: () -> Unit,
    onPickFolder: () -> Unit,
    onClearFolder: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onToggleAutoTranscribe: (Boolean) -> Unit,
    onToggleWhileCharging: (Boolean) -> Unit,
    onScanNow: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            TopAppBar(
                title = { Text("자동 폴더 전사") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        }

        item {
            Text(
                text = "지정한 폴더의 새 오디오를 주기적으로 찾습니다. 바로 전사하거나 홈에서 수동으로 전사할 수 있습니다.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.FolderOpen, contentDescription = null)
                        Spacer(Modifier.padding(4.dp))
                        Column(Modifier.weight(1f)) {
                            Text("감시 폴더", style = MaterialTheme.typography.titleSmall)
                            Text(
                                text = watch.folderLabel,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = onPickFolder,
                            modifier = Modifier.weight(1f),
                        ) { Text(if (watch.hasFolder) "폴더 변경" else "폴더 선택") }
                        if (watch.hasFolder) {
                            OutlinedButton(onClick = onClearFolder) {
                                Text("해제")
                            }
                        }
                    }
                }
            }
        }

        item {
            ToggleRow(
                title = "폴더 감시",
                subtitle = if (watch.hasFolder) "15분마다 새 파일을 검사합니다" else "먼저 폴더를 선택하세요",
                checked = watch.enabled && watch.hasFolder,
                enabled = watch.hasFolder,
                onCheckedChange = onToggleEnabled,
            )
        }

        item {
            ToggleRow(
                title = "찾은 파일 바로 전사",
                subtitle = when {
                    !watch.hasFolder -> "먼저 폴더를 선택하세요"
                    watch.autoTranscribe -> "새 파일을 찾으면 즉시 전사합니다"
                    else -> "검사만 합니다 · 홈에서 전사 시작"
                },
                checked = watch.autoTranscribe,
                enabled = watch.hasFolder,
                onCheckedChange = onToggleAutoTranscribe,
            )
        }

        item {
            ToggleRow(
                title = "충전 중에만",
                subtitle = "배터리 사용을 줄입니다",
                checked = watch.whileCharging,
                enabled = watch.hasFolder,
                onCheckedChange = onToggleWhileCharging,
            )
        }

        item {
            OutlinedButton(
                onClick = onScanNow,
                enabled = watch.hasFolder && !watch.scanInProgress,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Text(if (watch.scanInProgress) "검사 중…" else "지금 폴더 검사")
            }
        }

        item {
            val lastScan = if (watch.lastScanAt > 0L) {
                SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.KOREA).format(Date(watch.lastScanAt))
            } else {
                "아직 없음"
            }
            Text(
                text = "마지막 검사: $lastScan · 전사 완료 ${watch.processedCount}개 · 대기 ${watch.queuedCount}개",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
        }
    }
}
