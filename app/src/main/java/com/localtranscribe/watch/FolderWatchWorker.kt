package com.localtranscribe.watch

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
class FolderWatchWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        FolderWatchNotifications.ensureChannel(applicationContext)
        setForeground(progressInfo("폴더 검사 중…", -1))
        return try {
            FolderWatchRunner.run(
                context = applicationContext,
                onFileStarting = { name -> setForeground(progressInfo(name, 0)) },
            )
            Result.success()
        } catch (_: Throwable) {
            Result.failure()
        }
    }

    private fun progressInfo(fileName: String, percent: Int): ForegroundInfo {
        val notification = FolderWatchNotifications.buildProgress(
            applicationContext,
            fileName,
            percent,
        )
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                FolderWatchNotifications.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(FolderWatchNotifications.NOTIFICATION_ID, notification)
        }
    }
}
