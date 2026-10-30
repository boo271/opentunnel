package dev.opentunnel.app

import android.app.Application
import dev.opentunnel.app.update.UpdateManager

class OpenTunnelApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Diag.init(this)
        UpdateManager.init(this)
        Diag.log("App", "started")
    }
}
