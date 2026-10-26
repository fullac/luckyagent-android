package com.luckyagent.android.data.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.luckyagent.android.LuckyAgentApp
import com.luckyagent.android.data.notification.cronFingerprint
import com.luckyagent.android.data.notification.cronPopupForChange

/**
 * Reads the computer's cron list once, after the app has left the foreground.
 * A new run posts the high-importance notice. It does not keep a foreground service.
 */
class CronWatchWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? LuckyAgentApp ?: return Result.failure()
        if (!app.container.settingsRepository.snapshot().notifyOnCron) return Result.success()
        val jobs = app.container.api.listCronJobs().getOrElse { return Result.retry() }.jobs
        val prefs = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val seen = prefs.getBoolean(KEY_SEEN, false)
        val editor = prefs.edit()
        jobs.forEach { job ->
            val key = KEY_PREFIX + job.id
            val previous = prefs.getString(key, null)
            val popup = if (seen) cronPopupForChange(job, previous) else null
            if (popup != null) {
                app.container.notifications.notifyCron(popup.sessionId, popup.eventId, popup.body)
            }
            editor.putString(key, cronFingerprint(job))
        }
        val live = jobs.map { KEY_PREFIX + it.id }.toSet()
        prefs.all.keys.filter { it.startsWith(KEY_PREFIX) && it !in live }.forEach(editor::remove)
        editor.putBoolean(KEY_SEEN, true).apply()
        CronWatchWork.enqueue(applicationContext, jobs)
        return Result.success()
    }

    companion object {
        const val PREFS = "cron_watch"
        const val KEY_SEEN = "seen"
        const val KEY_PREFIX = "job:"
    }
}
