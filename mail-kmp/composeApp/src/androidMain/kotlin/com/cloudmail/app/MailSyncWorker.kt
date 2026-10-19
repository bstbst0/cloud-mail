package com.cloudmail.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import java.util.concurrent.TimeUnit

class MailSyncWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        if (AndroidRuntime.foreground) return Result.success()
        val platform = AndroidPlatform(applicationContext)
        val encodedSession = platform.secret("session") ?: return Result.success()
        val session = encodedSession.let { runCatching { MailJson.decodeFromString<Session>(it) }.getOrNull() } ?: return Result.success()
        val server = platform.secret("server") ?: return Result.success()
        val store = MailStore(platform).apply { owner = "$server#${session.userId}" }
        if (session.expiresAt <= System.currentTimeMillis() / 1000) { revokeSession(platform, encodedSession, store); return Result.success() }
        val initial = store.cursor() == null
        val client = platform.httpClient()
        return try {
            val api = ApiClient(client, server) { session.token }
            val arrived = MailRepository(store, sessionIsValid = { platform.secret("session") == encodedSession }, api = { api }).sync()
            if (!initial && platform.secret("session") == encodedSession) platform.notifyMail(arrived)
            Result.success()
        } catch (error: MailException) { if (error.code == 401) { revokeSession(platform, encodedSession, store); Result.success() } else if (runAttemptCount < 3) Result.retry() else Result.failure() }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { if (runAttemptCount < 3) Result.retry() else Result.failure() }
        finally { client.close() }
    }
    private suspend fun revokeSession(platform: PlatformServices, encodedSession: String, store: MailStore) {
        val removed = synchronized(AndroidRuntime.sessionLock) {
            if (platform.secret("session") != encodedSession) false
            else { platform.putSecret("session", null); true }
        }
        if (removed) kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { MailRepository(store) { error("Session ended") }.clear() }
    }
    companion object {
        fun schedule(context: Context, intervalMinutes: Long = 15) {
            val task = PeriodicWorkRequestBuilder<MailSyncWorker>(intervalMinutes.coerceAtLeast(15), TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("mail-sync", ExistingPeriodicWorkPolicy.UPDATE, task)
        }
        fun cancel(context: Context) = WorkManager.getInstance(context).cancelUniqueWork("mail-sync")
    }
}
internal object MailNotifications {
    @Synchronized fun show(context: Context, platform: PlatformServices, messages: List<Message>) {
        synchronized(AndroidRuntime.sessionLock) {
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
            val session = platform.secret("session")?.let { MailJson.decodeFromString<Session>(it) } ?: return
            if (session.expiresAt <= System.currentTimeMillis() / 1000) return
            val owner = "${platform.secret("server")}#${session.userId}"
            val key = "notified-$owner"
            val seen = platform.secret(key)?.split(',')?.toMutableSet() ?: mutableSetOf()
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel("mail", "New mail", NotificationManager.IMPORTANCE_DEFAULT).apply { description = "New mail in your Cloud Mail addresses" })
            for (message in messages.filter { !it.read && !it.deleted && it.id.toString() !in seen }) {
                val notificationId = "$owner#${message.id}".hashCode()
                val intent = Intent(context, MainActivity::class.java).putExtra("messageId", message.id).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                val pending = PendingIntent.getActivity(context, notificationId, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                // Hide message content on the lock screen; never include body text in notifications.
                manager.notify(notificationId, NotificationCompat.Builder(context, "mail").setSmallIcon(com.cloudmail.app.R.drawable.ic_notification)
                    .setContentTitle(message.fromName.ifBlank { message.fromAddress }).setContentText(message.subject.ifBlank { "New message" })
                    .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setContentIntent(pending).setAutoCancel(true).build())
                seen += message.id.toString()
            }
            platform.putSecret(key, seen.toList().takeLast(1000).joinToString(","))
        }
    }
}
