package com.localtranscribe.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch

@Composable
fun AppRoot(vm: AppViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            vm.startRecording()
        } else {
            scope.launch {
                snackbarHostState.showSnackbar("마이크 권한이 없으면 녹음할 수 없습니다.")
            }
        }
    }

    val audioPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(vm::importAudio) }

    val modelPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(vm::importModel) }

    LaunchedEffect(state.error) {
        state.error?.let {
            snackbarHostState.showSnackbar(it)
            vm.clearMessages()
        }
    }

    LaunchedEffect(state.notice) {
        state.notice?.let {
            snackbarHostState.showSnackbar(it)
            vm.clearMessages()
        }
    }

    BackHandler(enabled = state.screen != Screen.Home) { vm.goHome() }

    val keepScreenOn = state.isRecording || state.busy != null
    val view = LocalView.current
    DisposableEffect(keepScreenOn) {
        view.keepScreenOn = keepScreenOn
        onDispose { view.keepScreenOn = false }
    }

    val requestRecord: () -> Unit = {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) vm.startRecording() else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (state.screen) {
                Screen.Home -> HomeScreen(
                    state = state,
                    onRecord = requestRecord,
                    onStop = vm::stopRecording,
                    onCancelRecording = vm::cancelRecording,
                    onImport = { audioPicker.launch(arrayOf("audio/*")) },
                    onOpenModels = vm::openModels,
                    onOpenTranscript = vm::openTranscript,
                    onDeleteTranscript = vm::deleteTranscript,
                    onLanguageChange = vm::setLanguage,
                    onTranslateChange = vm::setTranslate,
                    onCancelWork = vm::cancelWork,
                )

                is Screen.Detail -> DetailScreen(
                    transcript = state.detail,
                    onBack = vm::goHome,
                    onDelete = vm::deleteTranscript,
                    notify = { message ->
                        scope.launch { snackbarHostState.showSnackbar(message) }
                    },
                )

                Screen.Models -> ModelsScreen(
                    state = state,
                    onBack = vm::goHome,
                    onDownload = vm::downloadModel,
                    onCancelDownload = vm::cancelDownload,
                    onSelect = vm::selectModel,
                    onDelete = vm::deleteModel,
                    onImport = { modelPicker.launch(arrayOf("*/*")) },
                )
            }
        }
    }
}
