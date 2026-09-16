package com.example.discogsandroidapp

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

class SellerSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(
    appContext,
    workerParams
) {
    override suspend fun doWork(): Result {
        val token = BuildConfig.DISCOGS_TOKEN

        if (token.isBlank()) {
            return Result.failure()
        }

        return try {
            SellerLocalRepository(applicationContext)
                .syncAll(token)
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}

object SellerSyncScheduler {
    private const val PERIODIC_WORK_NAME =
        "discogs_seller_periodic_sync"
    private const val IMMEDIATE_WORK_NAME =
        "discogs_seller_immediate_sync"

    private fun networkConstraints(): Constraints {
        return Constraints.Builder()
            .setRequiredNetworkType(
                NetworkType.CONNECTED
            )
            .build()
    }

    fun schedulePeriodic(context: Context) {
        val request =
            PeriodicWorkRequestBuilder<SellerSyncWorker>(
                6,
                TimeUnit.HOURS
            )
                .setConstraints(networkConstraints())
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    15,
                    TimeUnit.MINUTES
                )
                .build()

        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(
                PERIODIC_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
    }

    fun enqueueNow(context: Context) {
        val request =
            OneTimeWorkRequestBuilder<SellerSyncWorker>()
                .setConstraints(networkConstraints())
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    30,
                    TimeUnit.SECONDS
                )
                .build()

        WorkManager.getInstance(context)
            .enqueueUniqueWork(
                IMMEDIATE_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request
            )
    }
}
