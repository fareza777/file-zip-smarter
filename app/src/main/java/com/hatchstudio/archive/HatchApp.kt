package com.hatchstudio.archive

import android.app.Application
import com.hatchstudio.archive.jobs.JobManager

class HatchApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        JobManager.init(this)
    }

    companion object {
        lateinit var instance: HatchApp
            private set
    }
}
