package com.localtranscribe

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.localtranscribe.ui.AppRoot
import com.localtranscribe.ui.theme.LocalTranscribeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LocalTranscribeTheme {
                AppRoot()
            }
        }
    }
}
