package com.hatchstudio.archive.jobs

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class JobActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val manager = JobManager.get() ?: return
        when (intent.action) {
            ExtractionService.ACTION_PAUSE -> manager.pauseActive()
            ExtractionService.ACTION_RESUME -> manager.resumeActive()
            ExtractionService.ACTION_STOP -> manager.stopActive()
        }
    }
}
