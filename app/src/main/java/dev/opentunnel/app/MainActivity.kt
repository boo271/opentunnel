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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import dev.opentunnel.app.data.Profile
import dev.opentunnel.app.data.ProfileStore
import dev.opentunnel.app.service.TunnelService
import dev.opentunnel.app.ui.HomeScreen
import dev.opentunnel.app.ui.OpenTunnelTheme
import dev.opentunnel.app.ui.ProfileScreen
import dev.opentunnel.app.ui.SettingsScreen
import dev.opentunnel.app.ui.UpdateBanner
import dev.opentunnel.app.ui.UpdateDialog
import dev.opentunnel.app.ui.toast
import dev.opentunnel.app.update.UpdateManager

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
                val context = LocalContext.current
                var screen by rememberSaveable { mutableStateOf("home") }
                val running by TunnelService.running.collectAsState()
                val status by TunnelService.status.collectAsState()
                val update by UpdateManager.state.collectAsState()
                var skipped by rememberSaveable {
                    mutableStateOf(UpdateManager.skippedVersion() ?: "")
                }
                var updateOpen by remember { mutableStateOf(false) }
                var installPermission by rememberSaveable { mutableStateOf(false) }

                LaunchedEffect(Unit) { UpdateManager.maybeAutoCheck() }

                val later: () -> Unit = {
                    val version = when (val current = update) {
                        is UpdateManager.State.Available -> current.release.version
                        is UpdateManager.State.Downloading -> current.release.version
                        is UpdateManager.State.Ready -> current.release.version
                        else -> null
                    }
                    if (version != null) {
                        UpdateManager.skip(version)
                        skipped = version
                    }
                    updateOpen = false
                }

                val offer = when (val current = update) {
                    is UpdateManager.State.Available -> current.release.version
                        .takeIf { it != skipped }
                        ?.let { UpdateBanner(it) }

                    is UpdateManager.State.Downloading -> current.release.version
                        .takeIf { it != skipped }
                        ?.let { UpdateBanner(it, downloading = true, percent = current.percent) }

                    is UpdateManager.State.Ready -> current.release.version
                        .takeIf { it != skipped }
                        ?.let { UpdateBanner(it, ready = true) }

                    else -> null
                }

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

                        "settings" -> SettingsScreen(
                            onBack = { screen = "home" },
                            update = update,
                            onCheckUpdates = { UpdateManager.checkNow() },
                            onOpenUpdate = { updateOpen = true },
                        )

                        else -> HomeScreen(
                            running = running,
                            status = status,
                            profile = profile,
                            update = offer,
                            onToggle = { connect ->
                                if (connect) connect() else TunnelService.stop(this)
                            },
                            onEdit = { screen = "profile" },
                            onSettings = { screen = "settings" },
                            onUpdate = { updateOpen = true },
                            onUpdateLater = later,
                        )
                    }
                }

                if (updateOpen) {
                    UpdateDialog(
                        state = update,
                        onDismiss = { updateOpen = false },
                        onLater = later,
                        onDownload = { UpdateManager.download() },
                        onCancelDownload = { UpdateManager.cancelDownload() },
                        onInstall = {
                            when (UpdateManager.install(context)) {
                                UpdateManager.InstallResult.Started -> updateOpen = false
                                UpdateManager.InstallResult.NeedsPermission -> installPermission = true
                                UpdateManager.InstallResult.Unavailable ->
                                    toast(context, "Nothing to install yet")
                            }
                        },
                    )
                }

                if (installPermission) {
                    AlertDialog(
                        onDismissRequest = { installPermission = false },
                        title = { Text("Allow installs") },
                        text = {
                            Text(
                                "Android blocks app installs until you allow them for " +
                                    "OpenTunnel. Open Settings, turn on \"Allow from this " +
                                    "source\", then tap Install again."
                            )
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    installPermission = false
                                    UpdateManager.openInstallSettings(context)
                                },
                            ) { Text("Open settings") }
                        },
                        dismissButton = {
                            TextButton(onClick = { installPermission = false }) { Text("Not now") }
                        },
                    )
                }
            }
        }
    }
}
