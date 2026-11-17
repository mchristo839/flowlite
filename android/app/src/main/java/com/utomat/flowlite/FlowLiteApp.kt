package com.utomat.flowlite

import android.app.Application

class FlowLiteApp : Application() {
    override fun onCreate() {
        super.onCreate()
        SyncManager.request(this) // sync on app start
    }
}
