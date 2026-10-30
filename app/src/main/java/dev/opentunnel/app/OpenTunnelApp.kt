package dev.opentunnel.app

import android.app.Application

class OpenTunnelApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Diag.init(this)
        Diag.log("App", "started")
    }
}
