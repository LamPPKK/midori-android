package io.github.lamppkk.xanhbrowser

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.github.lamppkk.xanhbrowser.sync.AccountState
import io.github.lamppkk.xanhbrowser.sync.SyncReason
import io.github.lamppkk.xanhbrowser.sync.SyncStatus

class SyncWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val coordinator = SyncCoordinator.get(applicationContext)
        val reason = inputData.getString(KEY_REASON)
            ?.let { runCatching { SyncReason.valueOf(it) }.getOrNull() }
            ?: SyncReason.SCHEDULED
        val snapshot = coordinator.snapshot() ?: return Result.success()
        if (snapshot.accountState != AccountState.CONNECTED) return Result.success()
        if (snapshot.nextSyncAllowedEpochSeconds?.let { System.currentTimeMillis() / 1_000 < it } == true) {
            return Result.success()
        }
        if (!coordinator.isDue(reason)) return Result.success()
        return runCatching { coordinator.sync(reason) }.fold(
            onSuccess = { if (it.status == SyncStatus.AUTH_ERROR) Result.failure() else Result.success() },
            onFailure = { Result.retry() },
        )
    }

    companion object {
        const val KEY_REASON = "sync_reason"
    }
}
