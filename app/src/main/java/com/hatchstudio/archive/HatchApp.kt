package com.hatchstudio.archive

import android.app.Application
import com.hatchstudio.archive.ads.AdsManager
import com.hatchstudio.archive.jobs.JobManager

class HatchApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        JobManager.init(this)
        AdsManager.initialize(this)
        AdsManager.preloadInterstitial(this)
        AdsManager.preloadRewarded(this)
    }

    companion object {
        lateinit var instance: HatchApp
            private set
    }
}
