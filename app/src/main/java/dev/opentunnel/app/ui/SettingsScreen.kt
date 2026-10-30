package dev.opentunnel.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.opentunnel.app.BuildConfig
import dev.opentunnel.app.Diag
import dev.opentunnel.app.data.ShareSettings
import dev.opentunnel.app.data.ShareStore
import dev.opentunnel.app.service.WarpMasque
import dev.opentunnel.app.update.UpdateManager

private const val SOURCE_URL = "https://github.com/boo271/opentunnel"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    update: UpdateManager.State,
    onCheckUpdates: () -> Unit,
    onOpenUpdate: () -> Unit,
) {
    val context = LocalContext.current
    var autoCheck by remember { mutableStateOf(UpdateManager.autoCheckEnabled()) }
    var registered by remember { mutableStateOf(WarpMasque.hasRegistration(context)) }
    var confirmForget by remember { mutableStateOf(false) }
    var importOpen by remember { mutableStateOf(false) }
    var importText by remember { mutableStateOf("") }
    var importError by remember { mutableStateOf<String?>(null) }

    val shareStore = remember { ShareStore(context) }
    var share by remember { mutableStateOf(shareStore.load()) }
    var portText by remember { mutableStateOf(share.port.toString()) }
    val updateShare: (ShareSettings) -> Unit = { next ->
        share = next
        shareStore.save(next)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", fontWeight = FontWeight.SemiBold) },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    navigationIconContentColor = MaterialTheme.colorScheme.primary,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Spacer(Modifier.height(8.dp))
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(20.dp)) {
                    Text("Free WARP registration", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (registered) {
                            "An anonymous Cloudflare device is registered on this phone. " +
                                "No e-mail or payment is attached."
                        } else {
                            "No device is registered. The next connection creates an " +
                                "anonymous one automatically."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row {
                        TextButton(
                            onClick = {
                                importError = null
                                importOpen = true
                            },
                        ) { Text("Import config…") }
                        if (registered) {
                            TextButton(onClick = { confirmForget = true }) {
                                Text("Forget registration…")
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Share to PC (LAN)", fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "HTTP + SOCKS proxy inside the tunnel, for a PC connected " +
                                    "to this phone's hotspot.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Switch(
                            checked = share.enabled,
                            onCheckedChange = { updateShare(share.copy(enabled = it)) },
                        )
                    }

                    if (share.enabled) {
                        Spacer(Modifier.height(14.dp))
                        OutlinedTextField(
                            value = portText,
                            onValueChange = { text ->
                                val digits = text.filter(Char::isDigit).take(5)
                                portText = digits
                                digits.toIntOrNull()?.takeIf { it in 1024..65535 }?.let {
                                    updateShare(share.copy(port = it))
                                }
                            },
                            label = { Text("Port") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if ((portText.toIntOrNull() ?: 0) !in 1024..65535) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "Port must be between 1024 and 65535.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = share.username,
                            onValueChange = { updateShare(share.copy(username = it.trim())) },
                            label = { Text("Username") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = share.password,
                            onValueChange = { updateShare(share.copy(password = it.trim())) },
                            label = { Text("Password") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "On the PC, set an HTTP proxy to this phone's hotspot IP, " +
                                "port ${share.port}, with the credentials above. " +
                                "Reconnect the tunnel to apply changes.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (!share.hasCredentials) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "No credentials set: anyone on the network can use the tunnel.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(20.dp)) {
                    Text("Updates", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Checks GitHub for a newer release. Nothing is downloaded unless you ask.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Check automatically",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(
                            checked = autoCheck,
                            onCheckedChange = {
                                autoCheck = it
                                UpdateManager.setAutoCheck(it)
                                if (it) UpdateManager.maybeAutoCheck()
                            },
                        )
                    }
                    Text(
                        "Once a day, when the app opens. Your profiles and tunnel are not involved.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        updateStatus(update),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (update is UpdateManager.State.Error) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    Spacer(Modifier.height(8.dp))
                    Row {
                        OutlinedButton(
                            onClick = onCheckUpdates,
                            enabled = update !is UpdateManager.State.Checking &&
                                update !is UpdateManager.State.Downloading &&
                                update !is UpdateManager.State.Ready,
                        ) {
                            Text(
                                if (update is UpdateManager.State.Checking) {
                                    "Checking…"
                                } else {
                                    "Check for updates"
                                }
                            )
                        }
                        if (update is UpdateManager.State.Available ||
                            update is UpdateManager.State.Downloading ||
                            update is UpdateManager.State.Ready ||
                            (update is UpdateManager.State.Error && update.release != null)
                        ) {
                            Spacer(Modifier.width(8.dp))
                            TextButton(onClick = onOpenUpdate) {
                                Text(if (update is UpdateManager.State.Ready) "Install" else "Update")
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(20.dp)) {
                    Text("About", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "OpenTunnel ${BuildConfig.VERSION_NAME} · GPL-3.0",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Made by boo271. Built on sing-box (GPL-3.0) and usque (MIT). " +
                            "Not affiliated with Cloudflare.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = { openLink(context, SOURCE_URL) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("GitHub") }
                }
            }

            Spacer(Modifier.height(16.dp))

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(20.dp)) {
                    Text("Diagnostics", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Logs stay on this phone. They can include your server address and " +
                            "recent errors, and are only sent when you share them.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = {
                            if (Diag.copy(context)) toast(context, "Debug info copied")
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Copy debug info") }
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(
                        onClick = {
                            if (!Diag.share(context)) {
                                Diag.copy(context)
                                toast(context, "No app to share with, copied instead", always = true)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Share logs") }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmForget) {
        AlertDialog(
            onDismissRequest = { confirmForget = false },
            title = { Text("Forget WARP registration?") },
            text = {
                Text(
                    "This deletes the anonymous device stored on this phone. " +
                        "The next connect creates a new one."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        WarpMasque.forgetRegistration(context)
                        registered = WarpMasque.hasRegistration(context)
                        confirmForget = false
                    },
                ) { Text("Forget") }
            },
            dismissButton = {
                TextButton(onClick = { confirmForget = false }) { Text("Cancel") }
            },
        )
    }

    if (importOpen) {
        AlertDialog(
            onDismissRequest = {
                importOpen = false
                importError = null
            },
            title = { Text("Import WARP config") },
            text = {
                Column {
                    Text(
                        "Paste the JSON from usque register (private_key, " +
                            "endpoint_pub_key). This replaces the stored registration.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = importText,
                        onValueChange = {
                            importText = it
                            importError = null
                        },
                        label = { Text("warp.json contents") },
                        minLines = 6,
                        maxLines = 12,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    importError?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            it,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        WarpMasque.importRegistration(context, importText)
                            .onSuccess {
                                registered = WarpMasque.hasRegistration(context)
                                importOpen = false
                                importText = ""
                                importError = null
                            }
                            .onFailure {
                                importError = it.message ?: "Invalid JSON"
                            }
                    },
                ) { Text("Import") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        importOpen = false
                        importError = null
                    },
                ) { Text("Cancel") }
            },
        )
    }
}

private fun updateStatus(state: UpdateManager.State): String = when (state) {
    is UpdateManager.State.Idle -> "Not checked yet."
    is UpdateManager.State.Checking -> "Checking GitHub…"
    is UpdateManager.State.UpToDate -> "OpenTunnel ${state.version} is the latest release."
    is UpdateManager.State.Available -> "Version ${state.release.version} is available."
    is UpdateManager.State.Downloading -> if (state.percent >= 0) {
        "Downloading ${state.release.version}… ${state.percent}%"
    } else {
        "Downloading ${state.release.version}…"
    }
    is UpdateManager.State.Ready -> "Version ${state.release.version} is downloaded and verified."
    is UpdateManager.State.Error -> state.message
}
