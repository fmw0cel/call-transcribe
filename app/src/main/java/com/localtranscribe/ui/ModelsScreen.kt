package com.localtranscribe.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.localtranscribe.model.InstalledModel
import com.localtranscribe.model.ModelCatalog
import com.localtranscribe.model.ModelSpec

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelsScreen(
    state: UiState,
    onBack: () -> Unit,
    onDownload: (ModelSpec) -> Unit,
    onCancelDownload: () -> Unit,
    onSelect: (InstalledModel) -> Unit,
    onDelete: (InstalledModel) -> Unit,
    onImport: () -> Unit,
) {
    var confirmSpec by remember { mutableStateOf<ModelSpec?>(null) }
    val installedById = state.models.associateBy { it.id }
    val extraModels = state.models.filter { installed ->
        ModelCatalog.downloadable.none { it.id == installed.id }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("전사 모델") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
            ),
            windowInsets = WindowInsets(0, 0, 0, 0),
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text(
                    "Whisper 모델은 한 번만 내려받으면 인터넷 연결 없이 기기에서 전사합니다. " +
                        "모델이 클수록 정확하지만 느리고 메모리를 더 씁니다.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            items(ModelCatalog.downloadable, key = { it.id }) { spec ->
                val installed = installedById[spec.id]
                val selected = state.selectedModel?.id == spec.id
                val downloading = state.download?.spec?.id == spec.id

                ModelRow(
                    title = spec.displayName,
                    recommended = spec.id == ModelCatalog.RECOMMENDED_ID,
                    subtitle = spec.note,
                    sizeText = formatBytes(installed?.sizeBytes ?: spec.sizeBytes),
                    installed = installed != null,
                    selected = selected,
                    downloading = downloading,
                    progress = if (downloading) state.download?.progress ?: 0f else 0f,
                    onDownload = {
                        // Big downloads deserve an explicit confirmation.
                        if (spec.sizeBytes > 300L * 1024 * 1024) confirmSpec = spec else onDownload(spec)
                    },
                    onCancelDownload = onCancelDownload,
                    onUse = { installed?.let(onSelect) },
                    onDelete = { installed?.let(onDelete) },
                )
            }

            if (extraModels.isNotEmpty()) {
                item {
                    Text(
                        "가져온 모델",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
                items(extraModels, key = { it.id }) { model ->
                    ModelRow(
                        title = model.displayName,
                        subtitle = model.file.name,
                        sizeText = formatBytes(model.sizeBytes),
                        installed = true,
                        selected = state.selectedModel?.id == model.id,
                        downloading = false,
                        progress = 0f,
                        onDownload = {},
                        onCancelDownload = {},
                        onUse = { onSelect(model) },
                        onDelete = { onDelete(model) },
                    )
                }
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    ),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("직접 가져오기", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "whisper.cpp 용 ggml .bin 모델 파일을 기기에서 선택합니다. " +
                                "이미 받아 둔 모델이 있다면 여기로 추가하세요.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(onClick = onImport) {
                            Icon(Icons.Filled.UploadFile, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(".bin 모델 선택")
                        }
                    }
                }
            }

            item {
                Text(
                    "모델 출처: Hugging Face ggerganov/whisper.cpp · MIT 라이선스",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }

    confirmSpec?.let { spec ->
        AlertDialog(
            onDismissRequest = { confirmSpec = null },
            title = { Text("${spec.displayName} 모델 (${formatBytes(spec.sizeBytes)})") },
            text = {
                Text(
                    "큰 모델은 정확도가 높지만 다운로드에 시간이 걸리고, " +
                        "전사할 때 RAM을 많이 사용합니다. " +
                        "Turbo 모델은 8GB 이상 RAM 기기를 권장하며, " +
                        "부족하면 전사 중 앱이 종료될 수 있습니다.\n\n" +
                        "지금 내려받을까요?",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmSpec = null
                        onDownload(spec)
                    },
                ) { Text("다운로드") }
            },
            dismissButton = {
                TextButton(onClick = { confirmSpec = null }) { Text("취소") }
            },
        )
    }
}

@Composable
private fun ModelRow(
    title: String,
    recommended: Boolean = false,
    subtitle: String,
    sizeText: String,
    installed: Boolean,
    selected: Boolean,
    downloading: Boolean,
    progress: Float,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onUse: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        },
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(title, style = MaterialTheme.typography.titleSmall)
                        if (recommended) {
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "권장",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            sizeText,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (selected) {
                            Spacer(Modifier.width(8.dp))
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = null,
                                modifier = Modifier.width(14.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                when {
                    downloading -> {
                        IconButton(onClick = onCancelDownload) {
                            CircularProgressIndicator(
                                progress = { progress },
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp,
                            )
                        }
                    }
                    !installed -> {
                        Button(onClick = onDownload) {
                            Icon(
                                Icons.Filled.Download,
                                contentDescription = null,
                                modifier = Modifier.width(16.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("받기")
                        }
                    }
                    selected -> {
                        TextButton(onClick = onUse, enabled = false) { Text("사용 중") }
                    }
                    else -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = onUse) { Text("사용") }
                            IconButton(onClick = onDelete) {
                                Icon(
                                    Icons.Filled.DeleteOutline,
                                    contentDescription = "모델 삭제",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            if (downloading) {
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "${(progress * 100).toInt()}% · 탭하면 취소",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
