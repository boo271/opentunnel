package dev.opentunnel.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.opentunnel.app.update.UpdateManager

@Composable
fun UpdateDialog(
    state: UpdateManager.State,
    onDismiss: () -> Unit,
    onLater: () -> Unit,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onInstall: () -> Unit,
) {
    val release = when (state) {
        is UpdateManager.State.Available -> state.release
        is UpdateManager.State.Downloading -> state.release
        is UpdateManager.State.Ready -> state.release
        is UpdateManager.State.Error -> state.release
        else -> null
    }

    val title = when (state) {
        is UpdateManager.State.Available -> "Update available"
        is UpdateManager.State.Downloading -> "Downloading update"
        is UpdateManager.State.Ready -> "Update ready"
        is UpdateManager.State.Error -> "Update failed"
        else -> "Updates"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                when (state) {
                    is UpdateManager.State.Available -> {
                        Text("OpenTunnel ${state.release.version} is available. Updating is optional.")
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "The download comes from this project's GitHub release and is checked " +
                                "against the release checksum and the app's signing key.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                        ReleasePageButton(state.release)
                    }

                    is UpdateManager.State.Downloading -> {
                        Text("OpenTunnel ${state.release.version}")
                        Spacer(Modifier.height(10.dp))
                        if (state.percent >= 0) {
                            LinearProgressIndicator(
                                progress = { state.percent / 100f },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "${state.percent}%",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Downloading…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "You can close this and keep using the app while it downloads.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    is UpdateManager.State.Ready -> {
                        Text(
                            "OpenTunnel ${state.release.version} is downloaded and verified. " +
                                "Android will ask you to confirm the install."
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Your profile and WARP registration are kept.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                        ReleasePageButton(state.release)
                    }

                    is UpdateManager.State.Error -> {
                        Text(state.message)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "The installed app was not changed.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (release != null) {
                            Spacer(Modifier.height(4.dp))
                            ReleasePageButton(release)
                        }
                    }

                    else -> Text("There is no update to install right now.")
                }
            }
        },
        confirmButton = {
            when (state) {
                is UpdateManager.State.Available -> TextButton(onClick = onDownload) {
                    Text("Download")
                }

                is UpdateManager.State.Downloading -> TextButton(onClick = onCancelDownload) {
                    Text("Cancel")
                }

                is UpdateManager.State.Ready -> TextButton(onClick = onInstall) {
                    Text("Install")
                }

                is UpdateManager.State.Error -> if (release != null) {
                    TextButton(onClick = onDownload) { Text("Try again") }
                } else {
                    TextButton(onClick = onDismiss) { Text("Close") }
                }

                else -> TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
        dismissButton = when (state) {
            is UpdateManager.State.Available -> {
                { TextButton(onClick = onLater) { Text("Later") } }
            }

            is UpdateManager.State.Downloading -> {
                { TextButton(onClick = onDismiss) { Text("Hide") } }
            }

            is UpdateManager.State.Ready -> {
                { TextButton(onClick = onLater) { Text("Later") } }
            }

            is UpdateManager.State.Error -> if (release != null) {
                { TextButton(onClick = onDismiss) { Text("Close") } }
            } else {
                null
            }

            else -> null
        },
    )
}

@Composable
private fun ReleasePageButton(release: UpdateManager.Release) {
    val context = LocalContext.current
    TextButton(onClick = { openLink(context, release.pageUrl) }) {
        Text("What's new on GitHub")
    }
}
