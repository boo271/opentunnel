package dev.opentunnel.app

import android.Manifest
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import dev.opentunnel.app.data.Profile
import dev.opentunnel.app.data.ProfileStore
import dev.opentunnel.app.service.TunnelService
import dev.opentunnel.app.ui.HomeScreen
import dev.opentunnel.app.ui.OpenTunnelTheme
import dev.opentunnel.app.ui.ProfileScreen
import dev.opentunnel.app.ui.SettingsScreen

class MainActivity : ComponentActivity() {

    private lateinit var store: ProfileStore

    private val vpnPermission = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) TunnelService.start(this)
    }

    // Denial is fine: the tunnel still runs, only its notification stays hidden.
    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { startVpn() }

    private fun connect() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startVpn()
        }
    }

    private fun startVpn() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            vpnPermission.launch(intent)
        } else {
            TunnelService.start(this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        store = ProfileStore(this)
        var profile by mutableStateOf(store.load())

        setContent {
            OpenTunnelTheme {
                var screen by rememberSaveable { mutableStateOf("home") }
                val running by TunnelService.running.collectAsState()
                val status by TunnelService.status.collectAsState()

                Crossfade(
                    targetState = screen,
                    animationSpec = tween(durationMillis = 160),
                    label = "screen",
                ) { current ->
                    when (current) {
                        "profile" -> ProfileScreen(
                            profile = profile,
                            onSave = { updated ->
                                profile = updated
                                store.save(updated)
                                screen = "home"
                            },
                            onBack = { screen = "home" },
                        )

                        "settings" -> SettingsScreen(onBack = { screen = "home" })

                        else -> HomeScreen(
                            running = running,
                            status = status,
                            profile = profile,
                            onToggle = { connect ->
                                if (connect) connect() else TunnelService.stop(this)
                            },
                            onEdit = { screen = "profile" },
                            onSettings = { screen = "settings" },
                        )
                    }
                }
            }
        }
    }
}
