package com.autodoomscroller

import android.app.Application

class AutoDoomscrollerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppGraph.init(this)
    }
}
