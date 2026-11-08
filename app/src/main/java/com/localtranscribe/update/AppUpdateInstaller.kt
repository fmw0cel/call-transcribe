package com.localtranscribe.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object AppUpdateInstaller {

    suspend fun downloadApk(
        context: Context,
        apkUrl: String,
        onProgress: (Float) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val target = File(dir, "app-update.apk")
        val partial = File(dir, "app-update.apk.part")
        partial.delete()

        val connection = URL(apkUrl).openConnection() as HttpURLConnection
        connection.connectTimeout = 20_000
        connection.readTimeout = 120_000
        connection.setRequestProperty("User-Agent", "LocalTranscribe")
        try {
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: -1L
            connection.inputStream.use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(256 * 1024)
                    var read: Int
                    var written = 0L
                    while (input.read(buffer).also { read = it } >= 0) {
                        output.write(buffer, 0, read)
                        written += read
                        if (total > 0) {
                            onProgress((written.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
            }
            target.delete()
            if (!partial.renameTo(target)) {
                throw IllegalStateException("업데이트 파일을 저장하지 못했습니다.")
            }
            onProgress(1f)
            target
        } finally {
            connection.disconnect()
            partial.delete()
        }
    }

    fun launchInstall(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apk,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (context !is android.app.Activity) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        context.startActivity(intent)
    }

    fun canRequestPackageInstalls(context: Context): Boolean =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            true
        } else {
            context.packageManager.canRequestPackageInstalls()
        }

    fun openUnknownSourcesSettings(context: Context) {
        val intent = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
            data = Uri.parse("package:${context.packageName}")
            if (context !is android.app.Activity) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        context.startActivity(intent)
    }
}
