package dev.opentunnel.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.opentunnel.app.data.Profile

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    running: Boolean,
    status: String,
    profile: Profile,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onSettings: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("OpenTunnel", fontWeight = FontWeight.SemiBold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(24.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                val failed = status.startsWith("Failed")
                val connected = running && status.startsWith("Connected")
                val connecting = status.startsWith("Connecting")

                val dotColor by animateColorAsState(
                    targetValue = when {
                        failed -> MaterialTheme.colorScheme.error
                        connected -> Color(0xFF39D98A)
                        connecting -> MaterialTheme.colorScheme.primary
                        else -> Color(0xFF6B7A90)
                    },
                    animationSpec = tween(durationMillis = 220),
                    label = "dotColor",
                )
                val dotAlpha = if (connecting) {
                    val pulse = rememberInfiniteTransition(label = "pulse")
                    pulse.animateFloat(
                        initialValue = 0.35f,
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(durationMillis = 650),
                            repeatMode = RepeatMode.Reverse,
                        ),
                        label = "pulseAlpha",
                    ).value
                } else {
                    1f
                }

                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .alpha(dotAlpha)
                        .background(dotColor, CircleShape),
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    text = status,
                    color = if (failed) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }

            Spacer(Modifier.height(28.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(Modifier.padding(20.dp)) {
                    Text(
                        profile.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        profile.summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(32.dp))

            val buttonColor by animateColorAsState(
                targetValue = if (running) Color(0xFFB43A3A) else MaterialTheme.colorScheme.primary,
                animationSpec = tween(durationMillis = 220),
                label = "buttonColor",
            )
            Button(
                onClick = { onToggle(!running) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = buttonColor),
            ) {
                Text(
                    text = if (running) "Disconnect" else "Connect",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            Spacer(Modifier.height(16.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onEdit) { Text("Edit profile") }
                TextButton(onClick = onSettings) { Text("Settings") }
            }

            Spacer(Modifier.weight(1f))
            Text(
                "VLESS + REALITY · WARP · WireGuard · GPL-3.0",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 20.dp),
            )
        }
    }
}
