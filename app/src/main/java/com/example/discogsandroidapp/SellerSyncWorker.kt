package com.example.discogsandroidapp

import com.example.discogsandroidapp.data.SellerLocalRepository

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
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
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
                .syncAll(token, includeInventory = inputData.getBoolean(SellerSyncScheduler.INCLUDE_INVENTORY, false))
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            Result.retry()
        }
    }
}

object SellerSyncScheduler {
    internal const val INCLUDE_INVENTORY = "include_inventory"
    private const val PERIODIC_WORK_NAME =
        "discogs_seller_periodic_sync"
    private const val IMMEDIATE_WORK_NAME =
        "discogs_seller_immediate_sync"
    private const val MANUAL_WORK_NAME =
        "discogs_seller_manual_sync"

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

    fun enqueueNow(context: Context, includeInventory: Boolean = false) {
        val request =
            OneTimeWorkRequestBuilder<SellerSyncWorker>()
                .setInputData(workDataOf(INCLUDE_INVENTORY to includeInventory))
                .setConstraints(networkConstraints())
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    30,
                    TimeUnit.SECONDS
                )
                .build()

        WorkManager.getInstance(context)
            .enqueueUniqueWork(
                if (includeInventory) MANUAL_WORK_NAME else IMMEDIATE_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request
            )
    }
}
