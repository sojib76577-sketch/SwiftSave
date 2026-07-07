package com.example

import android.app.Application
import com.example.ads.AdManager

class VideoDownloaderApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Initialize Start.io Ads SDK
        AdManager.init(this)
    }
}
