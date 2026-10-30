package dev.opentunnel.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.opentunnel.app.data.Profile
import dev.opentunnel.app.data.VlessLink

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    profile: Profile,
    onSave: (Profile) -> Unit,
    onBack: () -> Unit,
) {
    var protocol by remember(profile) { mutableStateOf(profile.protocol) }
    var name by remember(profile) { mutableStateOf(profile.name) }
    var host by remember(profile) { mutableStateOf(profile.host) }
    var port by remember(profile) { mutableStateOf(profile.port.toString()) }
    var uuid by remember(profile) { mutableStateOf(profile.uuid) }
    var sni by remember(profile) { mutableStateOf(profile.sni) }
    var publicKey by remember(profile) { mutableStateOf(profile.publicKey) }
    var shortId by remember(profile) { mutableStateOf(profile.shortId) }
    var fingerprint by remember(profile) { mutableStateOf(profile.fingerprint) }
    var flow by remember(profile) { mutableStateOf(profile.flow) }
    var wgConfig by remember(profile) { mutableStateOf(profile.wgConfig) }
    var link by remember { mutableStateOf("") }
    var linkError by remember { mutableStateOf<String?>(null) }

    val isWireGuard = protocol == Profile.PROTOCOL_WIREGUARD
    val isMasque = protocol == Profile.PROTOCOL_WARP_MASQUE

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Server profile", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("Back") }
                },
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

            OutlinedTextField(
                value = link,
                onValueChange = {
                    link = it
                    linkError = null
                },
                label = { Text("Paste a vless:// link") },
                minLines = 2,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                horizontalArrangement = Arrangement.End,
                modifier = Modifier.fillMaxWidth(),
            ) {
                TextButton(
                    onClick = {
                        VlessLink.parse(link)
                            .onSuccess { imported ->
                                protocol = Profile.PROTOCOL_VLESS
                                name = imported.name
                                host = imported.host
                                port = imported.port.toString()
                                uuid = imported.uuid
                                sni = imported.sni
                                publicKey = imported.publicKey
                                shortId = imported.shortId
                                fingerprint = imported.fingerprint
                                flow = imported.flow
                                link = ""
                                linkError = null
                            }
                            .onFailure { linkError = it.message }
                    },
                ) {
                    Text("Import link")
                }
            }
            linkError?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = name, onValueChange = { name = it },
                label = { Text("Profile name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = !isWireGuard && !isMasque,
                    onClick = { protocol = Profile.PROTOCOL_VLESS },
                    label = { Text("VLESS + REALITY") },
                )
                FilterChip(
                    selected = isWireGuard,
                    onClick = { protocol = Profile.PROTOCOL_WIREGUARD },
                    label = { Text("WireGuard / WARP") },
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = isMasque,
                    onClick = { protocol = Profile.PROTOCOL_WARP_MASQUE },
                    label = { Text("WARP (free)") },
                )
            }
            Spacer(Modifier.height(12.dp))

            if (isMasque) {
                Text(
                    "Free Cloudflare WARP over MASQUE. No account or server needed. " +
                        "The first connection registers an anonymous device; using it " +
                        "accepts Cloudflare's Terms of Service.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = sni, onValueChange = { sni = it },
                    label = { Text("SNI / TLS name") },
                    supportingText = {
                        Text("TLS name presented to Cloudflare. m.facebook.com works on many social-only plans.")
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else if (isWireGuard) {
                OutlinedTextField(
                    value = wgConfig, onValueChange = { wgConfig = it },
                    label = { Text("WireGuard configuration") },
                    supportingText = {
                        Text("Paste the [Interface] / [Peer] text from wgcf or your provider.")
                    },
                    minLines = 10,
                    maxLines = 24,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                OutlinedTextField(
                    value = host, onValueChange = { host = it },
                    label = { Text("Server host or IP") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = port, onValueChange = { port = it.filter(Char::isDigit) },
                    label = { Text("Port") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = uuid, onValueChange = { uuid = it },
                    label = { Text("UUID") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = sni, onValueChange = { sni = it },
                    label = { Text("SNI / server name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = publicKey, onValueChange = { publicKey = it },
                    label = { Text("REALITY public key") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = shortId, onValueChange = { shortId = it },
                    label = { Text("REALITY short id") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = fingerprint, onValueChange = { fingerprint = it },
                    label = { Text("uTLS fingerprint") },
                    supportingText = { Text("chrome, firefox, safari, ios, edge, random…") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = flow, onValueChange = { flow = it },
                    label = { Text("VLESS flow (optional)") },
                    supportingText = { Text("Only if your server uses xtls-rprx-vision.") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.height(20.dp))

            Button(
                onClick = {
                    onSave(
                        Profile(
                            name = name.ifBlank {
                                if (protocol == Profile.PROTOCOL_WARP_MASQUE) "Cloudflare WARP (free)" else "My server"
                            },
                            protocol = protocol,
                            host = host.trim(),
                            port = (port.toIntOrNull() ?: 443).coerceIn(1, 65535),
                            uuid = uuid.trim(),
                            sni = sni.trim(),
                            publicKey = publicKey.trim(),
                            shortId = shortId.trim(),
                            fingerprint = fingerprint.trim().ifBlank { "chrome" },
                            flow = flow.trim(),
                            wgConfig = wgConfig.trim(),
                        )
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
            ) {
                Text("Save", fontWeight = FontWeight.SemiBold)
            }

            Spacer(Modifier.height(28.dp))
        }
    }
}
