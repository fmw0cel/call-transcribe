package com.localtranscribe.watch

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object FolderWatchScheduler {

    private const val PERIODIC_NAME = "folder_watch_periodic"
    private const val RUN_NOW_NAME = "folder_watch_now"

    fun sync(context: Context) {
        val app = context.applicationContext
        val repository = WatchFolderRepository(app)
        val manager = WorkManager.getInstance(app)

        if (!repository.isEnabled() || repository.treeUri() == null) {
            manager.cancelUniqueWork(PERIODIC_NAME)
            return
        }

        val constraints = Constraints.Builder()
            .apply {
                if (repository.requiresCharging()) {
                    setRequiresCharging(true)
                }
            }
            .build()

        val periodic = PeriodicWorkRequestBuilder<FolderWatchWorker>(
            15,
            TimeUnit.MINUTES,
        )
            .setConstraints(constraints)
            .build()

        manager.enqueueUniquePeriodicWork(
            PERIODIC_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            periodic,
        )
    }

    fun runNow(context: Context) {
        val app = context.applicationContext
        val repository = WatchFolderRepository(app)
        if (repository.treeUri() == null) return

        val constraints = Constraints.Builder()
            .apply {
                if (repository.requiresCharging()) {
                    setRequiresCharging(true)
                }
            }
            .build()

        val request = OneTimeWorkRequestBuilder<FolderWatchWorker>()
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(app).enqueueUniqueWork(
            RUN_NOW_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }
}
