package com.postsaimanager.core.data.backup

import androidx.work.NetworkType
import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.backup.BackupOutcome
import com.postsaimanager.core.domain.backup.RemoteBackup
import org.junit.jupiter.api.Test

/** The daily backup job's constraints (unmetered while charging by default) and how each outcome maps to a WorkManager result. */
class BackupWorkTest {

    private val done = BackupOutcome.Done(RemoteBackup("id", "n", 1, 1, null, null, null))

    @Test
    fun `the daily job needs an unmetered network and charging by default`() {
        val constraints = BackupWork.constraints(wifiOnly = true, manual = false)
        assertThat(constraints.requiredNetworkType).isEqualTo(NetworkType.UNMETERED)
        assertThat(constraints.requiresCharging()).isTrue()
    }

    @Test
    fun `with Wi-Fi only off the daily job accepts any network but still waits for charging`() {
        val constraints = BackupWork.constraints(wifiOnly = false, manual = false)
        assertThat(constraints.requiredNetworkType).isEqualTo(NetworkType.CONNECTED)
        assertThat(constraints.requiresCharging()).isTrue()
    }

    @Test
    fun `Back up now needs only a network`() {
        val constraints = BackupWork.constraints(wifiOnly = true, manual = true)
        assertThat(constraints.requiredNetworkType).isEqualTo(NetworkType.CONNECTED)
        assertThat(constraints.requiresCharging()).isFalse()
    }

    @Test
    fun `the daily request is a one-day periodic job with those constraints`() {
        val request = BackupWork.dailyRequest(wifiOnly = true)
        assertThat(request.workSpec.isPeriodic).isTrue()
        assertThat(request.workSpec.intervalDuration).isEqualTo(24L * 60 * 60 * 1000)
        assertThat(request.workSpec.constraints.requiredNetworkType).isEqualTo(NetworkType.UNMETERED)
    }

    @Test
    fun `a finished backup succeeds, a lost account or consent stops without retry`() {
        assertThat(BackupWork.dispositionFor(done, 0, manual = false)).isEqualTo(BackupWork.Disposition.SUCCESS)
        assertThat(BackupWork.dispositionFor(BackupOutcome.NotConnected, 0, manual = false)).isEqualTo(BackupWork.Disposition.FAILURE)
        assertThat(BackupWork.dispositionFor(BackupOutcome.AuthRequired, 0, manual = false)).isEqualTo(BackupWork.Disposition.FAILURE)
    }

    @Test
    fun `a busy phone or a failure is retried a few times by the daily job, never by a manual run`() {
        assertThat(BackupWork.dispositionFor(BackupOutcome.Busy, 0, manual = false)).isEqualTo(BackupWork.Disposition.RETRY)
        assertThat(BackupWork.dispositionFor(BackupOutcome.Failed("x"), BackupWork.MAX_ATTEMPTS - 2, manual = false)).isEqualTo(BackupWork.Disposition.RETRY)
        assertThat(BackupWork.dispositionFor(BackupOutcome.Failed("x"), BackupWork.MAX_ATTEMPTS - 1, manual = false)).isEqualTo(BackupWork.Disposition.FAILURE)
        assertThat(BackupWork.dispositionFor(BackupOutcome.Failed("x"), 0, manual = true)).isEqualTo(BackupWork.Disposition.FAILURE)
    }
}
