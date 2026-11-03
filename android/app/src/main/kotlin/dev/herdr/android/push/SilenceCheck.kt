package dev.herdr.android.push

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import dev.herdr.android.HerdrApp

/**
 * The periodic local check behind "Alert health" (alerts spec, "Silence is
 * noticed without claiming a cause"): an hourly job, kept across reboots,
 * that runs with the app closed. JobScheduler rather than WorkManager, so no
 * new dependency; Android may run it later than asked, which a 13-hour
 * threshold does not mind.
 */
class SilenceCheckService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        (application as HerdrApp).alerts.checkSilence(System.currentTimeMillis())
        return false
    }

    override fun onStopJob(params: JobParameters): Boolean = false

    companion object {
        private const val JOB_ID = 3001
        private const val PERIOD_MS = 60 * 60 * 1000L

        /** Starts the hourly check unless it is already scheduled. */
        fun schedule(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            if (scheduler.getPendingJob(JOB_ID) != null) return
            scheduler.schedule(
                JobInfo.Builder(JOB_ID, ComponentName(context, SilenceCheckService::class.java))
                    .setPeriodic(PERIOD_MS)
                    .setPersisted(true)
                    .build(),
            )
        }

        fun cancel(context: Context) = context.getSystemService(JobScheduler::class.java).cancel(JOB_ID)
    }
}
