package com.sahachor.app.reminders

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.sahachor.app.SahachorApp
import kotlinx.coroutines.launch

object Notifier {
    const val CHANNEL = "reminders"

    fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "রিমাইন্ডার", NotificationManager.IMPORTANCE_HIGH))
    }

    fun show(ctx: Context, id: Long, title: String) {
        val nm = NotificationManagerCompat.from(ctx)
        if (!nm.areNotificationsEnabled()) return
        val launch = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
        val open = launch?.let { PendingIntent.getActivity(ctx, id.toInt(), it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT) }
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("রিমাইন্ডার")
            .setContentText(title)
            .setStyle(NotificationCompat.BigTextStyle().bigText(title))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        try { nm.notify(id.toInt(), n) } catch (e: SecurityException) { /* notification permission revoked */ }
    }
}

class ReminderScheduler(private val ctx: Context) {
    private fun pending(id: Long, title: String): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, id.toInt(),
            Intent(ctx, ReminderReceiver::class.java).putExtra(EXTRA_ID, id).putExtra(EXTRA_TITLE, title),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /** Exact when the user allowed exact alarms, otherwise the best the OS permits (may fire a few minutes late in Doze). */
    fun schedule(id: Long, title: String, atMillis: Long): Boolean {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return false
        val pi = pending(id, title)
        return try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
            else am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
            true
        } catch (e: SecurityException) {
            try { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi); true } catch (e2: Exception) { false }
        } catch (e: Exception) { false }
    }

    fun cancel(id: Long) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        try { am.cancel(pending(id, "")) } catch (e: Exception) { /* nothing to cancel */ }
    }

    companion object { const val EXTRA_ID = "task_id"; const val EXTRA_TITLE = "title" }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val title = intent.getStringExtra(ReminderScheduler.EXTRA_TITLE).orEmpty().ifBlank { "আপনার একটি কাজ আছে" }
        Notifier.show(context, intent.getLongExtra(ReminderScheduler.EXTRA_ID, 0), title)
    }
}

/** Alarms are wiped on reboot, so every upcoming reminder is rescheduled from the encrypted database. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val app = context.applicationContext as? SahachorApp ?: return
        val result = goAsync()
        app.appScope.launch {
            try {
                val db = app.dbDeferred.await() ?: return@launch
                db.tasks().upcoming(System.currentTimeMillis()).forEach { t -> t.dueAt?.let { app.scheduler.schedule(t.id, t.title, it) } }
            } catch (e: Exception) { /* will retry at next boot; the app also reschedules on open in a later phase */ }
            finally { result.finish() }
        }
    }
}
