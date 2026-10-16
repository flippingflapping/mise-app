package app.mise.data.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.mise.data.repo.RecipeRepository
import app.mise.data.repo.SyncResult
import app.mise.data.source.SourceException
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/** Optional background sync (Settings → "Sync in the background"). */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val repository: RecipeRepository,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = when (val r = repository.sync()) {
        is SyncResult.Success -> Result.success()
        // The PC being off is normal; try again at the next period rather than hammering it.
        is SyncResult.Failed -> if (r.reason == SourceException.Reason.UNREACHABLE) Result.success() else Result.failure()
    }

    companion object {
        private const val NAME = "mise-periodic-sync"

        fun schedule(context: Context, enabled: Boolean) {
            val wm = WorkManager.getInstance(context)
            if (!enabled) {
                wm.cancelUniqueWork(NAME)
                return
            }
            val request = PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build())
                .build()
            wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}
