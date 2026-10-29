package com.hatchstudio.archive.jobs

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.hatchstudio.archive.MainActivity

import com.hatchstudio.archive.R
import com.hatchstudio.archive.data.ExtractionJob
import com.hatchstudio.archive.data.JobState
import com.hatchstudio.archive.ui.format.formatBytes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class ExtractionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observeJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        observeJob = scope.launch {
            JobManager.get()?.jobs?.collectLatest { jobs ->
                val active = jobs.firstOrNull {
                    it.state == JobState.RUNNING || it.state == JobState.PAUSED
                }
                if (active == null) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return@collectLatest
                }
                val notification = buildNotification(active)
                runCatching {
                    NotificationManagerCompat.from(this@ExtractionService)
                        .notify(NOTIFICATION_ID, notification)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val notification = buildNotification(null)
        if (Build.VERSION.SDK_INT >= 29) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(job: ExtractionJob?): Notification {
        val open = PendingIntent.getActivity(
            this,
            100,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(MainActivity.EXTRA_OPEN_QUEUE, true)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        if (job == null) {
            return builder
                .setContentTitle("ZipHatch is preparing")
                .setContentText("Getting the archive ready…")
                .setProgress(0, 0, true)
                .build()
        }

        val progress = (job.progress * 100).toInt().coerceIn(0, 100)
        val text = when (job.state) {
            JobState.PAUSED -> "Paused at $progress% · ${formatBytes(job.completedBytes)} written"
            JobState.RUNNING -> "$progress% · ${formatBytes(job.completedBytes)} of ${formatBytes(job.totalBytes)}"
            else -> job.message ?: "Extracting"
        }
        builder.setContentTitle(job.archiveName)
            .setContentText(text)
            .setProgress(100, progress, job.totalBytes <= 0)
            .setSubText(if (job.reclaim) "Reclaiming space" else "Smart extraction")

        if (job.state == JobState.PAUSED) {
            builder.addAction(
                0,
                "Resume",
                actionIntent(ACTION_RESUME, 201),
            )
        } else {
            builder.addAction(
                0,
                "Pause",
                actionIntent(ACTION_PAUSE, 202),
            )
        }
        builder.addAction(0, "Stop", actionIntent(ACTION_STOP, 203))
        return builder.build()
    }

    private fun actionIntent(action: String, code: Int): PendingIntent {
        val intent = Intent(this, JobActionReceiver::class.java).setAction(action)
        return PendingIntent.getBroadcast(
            this,
            code,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Extraction",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Progress for ZipHatch archive extraction"
            setShowBadge(false)
            enableVibration(false)
        }
        manager.createNotificationChannel(channel)
    }

    override fun onDestroy() {
        observeJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val CHANNEL_ID = "hatch.extraction"
        const val NOTIFICATION_ID = 41
        const val ACTION_PAUSE = "com.hatchstudio.archive.action.PAUSE"
        const val ACTION_RESUME = "com.hatchstudio.archive.action.RESUME"
        const val ACTION_STOP = "com.hatchstudio.archive.action.STOP"

        fun start(context: Context) {
            val intent = Intent(context, ExtractionService::class.java)
            runCatching {
                ContextCompat.startForegroundService(context, intent)
            }
        }

        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, ExtractionService::class.java))
            }
        }
    }
}
