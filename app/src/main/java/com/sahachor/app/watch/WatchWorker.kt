package com.sahachor.app.watch

import android.content.Context
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
import com.sahachor.app.SahachorApp
import com.sahachor.app.reminders.Notifier
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/** Runs in the background (Android decides the exact moment, at best every 15 minutes) and checks the sites that are due. */
class WatchWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? SahachorApp ?: return Result.success()
        val db = app.dbDeferred.await() ?: return Result.success()
        val dao = db.watch()
        val force = inputData.getBoolean(KEY_FORCE, false)
        val engine = WatchEngine(app, dao, app.router) { title, text, url, id -> Notifier.showWatch(app, id, title, text, url) }
        val now = System.currentTimeMillis()
        for (site in dao.activeSites()) {
            if (!force && !LinkRules.isDue(site.lastChecked, site.intervalMin, now)) continue
            try {
                engine.checkSite(site)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // this site is retried on the next run; other sites still get their turn
            }
        }
        return Result.success()
    }

    companion object { const val KEY_FORCE = "force" }
}

object WatchScheduler {
    private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun ensurePeriodic(ctx: Context) {
        val req = PeriodicWorkRequestBuilder<WatchWorker>(15, TimeUnit.MINUTES).setConstraints(online).build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("watch_periodic", ExistingPeriodicWorkPolicy.KEEP, req)
    }

    /** Check right away: new sites get their first (silent) scan, and force = true checks every site, not only the due ones. */
    fun runNow(ctx: Context, force: Boolean = false) {
        val req = OneTimeWorkRequestBuilder<WatchWorker>().setConstraints(online).setInputData(workDataOf(WatchWorker.KEY_FORCE to force)).build()
        WorkManager.getInstance(ctx).enqueueUniqueWork("watch_now", ExistingWorkPolicy.REPLACE, req)
    }
}
