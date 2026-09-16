package org.qinda.deck.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.qinda.deck.data.BtDevice
import org.qinda.deck.data.DeckRepository

private fun iconFor(what: String): ImageVector = when (what) {
    "keyboard" -> Icons.Default.Keyboard
    "mouse" -> Icons.Default.Mouse
    "gamepad", "joystick" -> Icons.Default.SportsEsports
    else -> Icons.Default.Bluetooth
}

/**
 * Pairing happens on the deck, not the phone.
 *
 * The controller bonds to the ESP32 directly, which is why this app asks for no
 * Bluetooth permission at all: the phone is a remote control for the deck's
 * radio, and the paired device keeps working when the phone is nowhere nearby.
 */
@Composable
fun BluetoothScreen(repo: DeckRepository, nav: Navigator) {
    val paired by repo.btDevices.collectAsState()
    val scope = rememberCoroutineScope()
    var scanning by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf<List<BtDevice>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var scanned by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { runCatching { repo.refreshBt() } }

    fun scan() {
        scanning = true
        error = null
        scope.launch {
            try {
                found = repo.btScan(8)
                scanned = true
            } catch (e: Exception) {
                error = e.message
            } finally {
                scanning = false
            }
        }
    }

    ScreenBody {
        error?.let {
            Spacer(Modifier.height(10.dp))
            Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(8.dp)) {
                Text(it, Modifier.padding(10.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodySmall)
            }
        }

        if (paired.isNotEmpty()) {
            SectionHeader("Paired to the deck")
            Panel {
                paired.forEachIndexed { i, d ->
                    if (i > 0) HorizontalDivider()
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { nav.push(Screen.RemapEditor(d.addr)) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(
                                    if (d.connected) MaterialTheme.colorScheme.tertiary
                                    else MaterialTheme.colorScheme.outline,
                                ),
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(d.display, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                if (d.connected) "connected · tap to remap its buttons"
                                else "not connected · switch it on to reconnect",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(d.addr, style = MonoNumbers,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(Icons.Default.ChevronRight, null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Row {
                        TextButton(
                            onClick = {
                                scope.launch {
                                    runCatching { repo.btForget(d.addr) }
                                        .onFailure { error = it.message }
                                }
                            },
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.error,
                            ),
                        ) { Text("Forget") }
                    }
                }
            }
        }

        SectionHeader("Add a device")
        Panel {
            Text(
                "Put the controller or keyboard into pairing mode first, then scan. " +
                    "The deck's radio is Bluetooth Low Energy only — the chip has no " +
                    "Bluetooth Classic hardware — so BLE devices appear here and " +
                    "Classic-only ones (DualShock 4, DualSense, older Xbox pads) cannot " +
                    "be paired at all.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { scan() },
                enabled = !scanning,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (scanning) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Scanning…")
                } else {
                    Text("Scan for devices")
                }
            }
            if (scanning) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "The deck stops answering for a few seconds while it scans.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (found.isNotEmpty()) {
            SectionHeader("Found")
            Panel {
                found.forEachIndexed { i, d ->
                    if (i > 0) HorizontalDivider()
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(iconFor(d.what), null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                d.name.ifBlank { "(unnamed ${d.what})" },
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                "${d.what} · ${d.rssi} dBm · ${d.addr}",
                                style = MonoNumbers,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        val already = paired.any { it.addr.equals(d.addr, true) }
                        TextButton(
                            enabled = !already,
                            onClick = {
                                scope.launch {
                                    try {
                                        repo.btPair(d)
                                        repo.say("Connecting to ${d.display}…")
                                    } catch (e: Exception) {
                                        error = e.message
                                    }
                                }
                            },
                        ) { Text(if (already) "Paired" else "Pair") }
                    }
                }
            }
        } else if (scanned && !scanning) {
            Panel {
                Text(
                    "Nothing advertising. Most controllers only advertise for a minute " +
                        "or two after you hold their pairing combination, and they stop " +
                        "as soon as they reconnect to something else — switch off " +
                        "Bluetooth on whatever they were last paired with.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
