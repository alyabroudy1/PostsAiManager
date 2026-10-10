package com.postsaimanager.core.data.backup

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.postsaimanager.core.data.R
import com.postsaimanager.core.domain.backup.BackUpNowUseCase
import com.postsaimanager.core.domain.backup.BackupOutcome
import com.postsaimanager.core.domain.backup.BackupScheduler
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One backup run in the background: the daily job ([KEY_MANUAL] false: waits for the phone to be quiet) or "Back up now" (true).
 * With no screen, a Google consent that is needed again cannot be asked for here, so the worker posts a notification that opens the app
 * (Settings > Backup > Connect) and stops instead of retrying.
 */
@HiltWorker
class BackupWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val backUp: BackUpNowUseCase,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val manual = inputData.getBoolean(BackupWork.KEY_MANUAL, false)
        val outcome = backUp(waitForQuiet = !manual)
        if (outcome == BackupOutcome.AuthRequired) BackupNotifications.postConsentNeeded(applicationContext)
        return when (BackupWork.dispositionFor(outcome, runAttemptCount, manual)) {
            BackupWork.Disposition.SUCCESS -> Result.success()
            BackupWork.Disposition.RETRY -> Result.retry()
            BackupWork.Disposition.FAILURE -> Result.failure()
        }
    }
}

/** The names, constraints and retry rules of the backup jobs; pure, so they are unit-tested. */
internal object BackupWork {

    const val DAILY_NAME = "drive-backup-daily"
    const val NOW_NAME = "drive-backup-now"
    const val KEY_MANUAL = "manual"

    /** The most runs of one daily job that find the phone busy or fail, before it waits for the next day. */
    const val MAX_ATTEMPTS = 4
    const val BACKOFF_MINUTES = 15L

    enum class Disposition { SUCCESS, RETRY, FAILURE }

    /**
     * The daily job runs unmetered (or any network when "Wi-Fi only" is off) and only while charging: a backup reads every page image
     * and sends hundreds of MB. "Back up now" is the person's own request, so it needs a network and nothing else.
     */
    fun constraints(wifiOnly: Boolean, manual: Boolean): Constraints = Constraints.Builder()
        .setRequiredNetworkType(if (wifiOnly && !manual) NetworkType.UNMETERED else NetworkType.CONNECTED)
        .setRequiresCharging(!manual)
        .build()

    fun dailyRequest(wifiOnly: Boolean): PeriodicWorkRequest =
        PeriodicWorkRequestBuilder<BackupWorker>(1, TimeUnit.DAYS)
            .setConstraints(constraints(wifiOnly, manual = false))
            .setInputData(Data.Builder().putBoolean(KEY_MANUAL, false).build())
            .setBackoffCriteria(BackoffPolicy.LINEAR, BACKOFF_MINUTES, TimeUnit.MINUTES)
            .build()

    fun nowRequest(): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<BackupWorker>()
            .setConstraints(constraints(wifiOnly = false, manual = true))
            .setInputData(Data.Builder().putBoolean(KEY_MANUAL, true).build())
            .build()

    fun dispositionFor(outcome: BackupOutcome, runAttemptCount: Int, manual: Boolean): Disposition = when (outcome) {
        is BackupOutcome.Done -> Disposition.SUCCESS
        // Not connected any more: nothing to do, and nothing to retry. Consent needed: the notification asks the person.
        BackupOutcome.NotConnected, BackupOutcome.AuthRequired -> Disposition.FAILURE
        // The failure is already in the Settings line; a manual run is not repeated behind the person's back.
        BackupOutcome.Busy, is BackupOutcome.Failed ->
            if (!manual && runAttemptCount + 1 < MAX_ATTEMPTS) Disposition.RETRY else Disposition.FAILURE
    }
}

/** [BackupScheduler] over WorkManager: one unique daily job (updated in place when "Wi-Fi only" changes) and one unique "now" job. */
@Singleton
class WorkManagerBackupScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) : BackupScheduler {

    override fun schedule(wifiOnly: Boolean) {
        runCatching {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(BackupWork.DAILY_NAME, ExistingPeriodicWorkPolicy.UPDATE, BackupWork.dailyRequest(wifiOnly))
        }
    }

    override fun cancel() {
        runCatching { WorkManager.getInstance(context).cancelUniqueWork(BackupWork.DAILY_NAME) }
    }

    override fun runNow() {
        runCatching {
            WorkManager.getInstance(context).enqueueUniqueWork(BackupWork.NOW_NAME, ExistingWorkPolicy.KEEP, BackupWork.nowRequest())
        }
    }
}

/** The one notification the backup posts: Google needs the person's consent again. */
internal object BackupNotifications {

    private const val CHANNEL_ID = "drive_backup"
    private const val NOTIFICATION_ID = 0x0DB0

    fun postConsentNeeded(context: Context) {
        runCatching {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, context.getString(R.string.backup_channel_name), NotificationManager.IMPORTANCE_LOW),
                )
            }
            val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            val open = launch?.let { PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT) }
            manager.notify(
                NOTIFICATION_ID,
                NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle(context.getString(R.string.backup_consent_needed_title))
                    .setContentText(context.getString(R.string.backup_consent_needed_text))
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .setVisibility(NotificationCompat.VISIBILITY_SECRET)
                    .build(),
            )
        }
    }
}
