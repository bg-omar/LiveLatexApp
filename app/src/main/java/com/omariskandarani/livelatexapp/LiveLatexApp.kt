package com.omariskandarani.livelatexapp

import android.app.Application
import com.google.android.gms.ads.MobileAds

class LiveLatexApp : Application() {
    override fun onCreate() {
        super.onCreate()
        MobileAds.initialize(this) {}
    }
}
