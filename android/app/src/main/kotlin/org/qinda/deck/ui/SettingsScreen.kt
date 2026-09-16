package org.qinda.deck.ui

import android.Manifest
import android.net.wifi.WifiManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.qinda.deck.ble.WifiSync
import org.qinda.deck.data.DeckRepository

@Composable
fun SettingsScreen(repo: DeckRepository, nav: Navigator) {
    val status by repo.status.collectAsState()
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var nameDraft by remember(status.name) { mutableStateOf(status.name) }
    var confirmReset by remember { mutableStateOf(false) }
    var wifiOpen by remember { mutableStateOf(false) }
    var nets by remember { mutableStateOf<List<Pair<String, Int>>>(emptyList()) }
    var scanning by remember { mutableStateOf(false) }
    var ssid by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }

    // --- BLE Wi-Fi sync state
    val context = LocalContext.current
    var syncSsid by remember { mutableStateOf(readCurrentSsid(context)) }
    var syncPass by remember { mutableStateOf("") }
    var pendingSync by remember { mutableStateOf(false) }
    val syncStage by repo.wifiSync.stage.collectAsState()
    val syncDetail by repo.wifiSync.detail.collectAsState()

    val blePermissions = remember {
        if (Build.VERSION.SDK_INT >= 31) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }
    val blePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        if (grants.values.all { it }) {
            pendingSync = true
        }
    }

    fun startBleSync() {
        if (syncSsid.isBlank()) return
        scope.launch {
            runCatching { repo.btProv(120) }   // arm the window when the deck is reachable
            repo.wifiSync.sync(syncSsid.trim(), syncPass, scope)
        }
    }

    // Permissions granted after the first tap: go straight into the sync.
    LaunchedEffect(pendingSync) {
        if (pendingSync) {
            pendingSync = false
            startBleSync()
        }
    }

    fun run(block: suspend () -> Unit) {
        error = null
        scope.launch { runCatching { block() }.onFailure { error = it.message } }
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

        Panel("Device") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = nameDraft,
                    onValueChange = { nameDraft = it.take(39) },
                    label = { Text("Name") },
                    supportingText = { Text("Shown when the app discovers it") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                if (nameDraft != status.name && nameDraft.isNotBlank()) {
                    IconButton(onClick = { run { repo.setName(nameDraft.trim()) } }) {
                        Icon(Icons.Default.Check, "Apply", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            InfoRow("Firmware", status.fw)
            InfoRow("Address", status.ip)
            InfoRow("MAC", status.mac)
            InfoRow("USB to host", if (status.usb) "connected" else "not connected")
            InfoRow("SD card", if (status.sd) "${status.sdMb} MiB, exposed as USB drive"
                                else "no card detected")
            if (status.sd) {
                Spacer(Modifier.height(6.dp))
                OutlinedButton(onClick = { nav.push(Screen.Sd) }, Modifier.fillMaxWidth()) {
                    Text("Browse card over Wi-Fi (ejects it from the PC)")
                }
            }
            InfoRow("Free memory", "${status.heap / 1024} KiB")
            InfoRow("Uptime", formatUptime(status.uptime))
            InfoRow("Profiles", "${status.profiles}")
            InfoRow("Report rate", "${1000 / status.tick.coerceAtLeast(1)} Hz")
            InfoRow("Bluetooth", "BLE only (no Classic on this chip)")
        }

        Panel("Paired devices") {
            Text(
                "Pair a BLE mouse, gamepad or keyboard straight to the deck — it " +
                    "keeps working with the phone switched off. Remap its buttons " +
                    "to any deck action from the same screen.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = { nav.push(Screen.Bluetooth) }, Modifier.fillMaxWidth()) {
                Text("Pairing and remapping")
            }
        }

        Panel("Displays") {
            Text(
                "Macro coordinates are stored per display, and converted using this " +
                    "layout. Change it whenever you move or replace a monitor.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = { nav.push(Screen.Displays) }, Modifier.fillMaxWidth()) {
                Text("Edit display layout")
            }
        }

        Panel("Wi-Fi") {
            Text(
                "The deck joins one 2.4 GHz network. Changing it reboots the deck, " +
                    "and it will disappear from this app until it reconnects.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = {
                    wifiOpen = true
                    scanning = true
                    scope.launch {
                        runCatching { nets = repo.wifiScan() }.onFailure { error = it.message }
                        scanning = false
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Change network") }

            Spacer(Modifier.height(14.dp))
            HorizontalDivider()
            Spacer(Modifier.height(6.dp))
            SectionHeader("Sync from this phone")
            Text(
                "Hands this phone's network to the deck over a short Bluetooth " +
                    "link — the way to provision a fresh deck or one on the wrong " +
                    "network, without joining its setup hotspot. The link drops " +
                    "the moment it is done, and the radio goes back to hosting " +
                    "your controller.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = syncSsid,
                onValueChange = { syncSsid = it.take(32) },
                label = { Text("Network name (SSID)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = syncPass,
                onValueChange = { syncPass = it.take(63) },
                label = { Text("Password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = {
                    repo.wifiSync.reset()
                    blePermissionLauncher.launch(blePermissions)
                },
                enabled = syncSsid.isNotBlank() &&
                    syncStage != WifiSync.Stage.Scanning &&
                    syncStage != WifiSync.Stage.Connecting &&
                    syncStage != WifiSync.Stage.Sending,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    when (syncStage) {
                        WifiSync.Stage.Scanning -> "Scanning…"
                        WifiSync.Stage.Connecting -> "Connecting…"
                        WifiSync.Stage.Sending -> "Sending…"
                        else -> "Sync to deck via Bluetooth"
                    },
                )
            }
            if (syncDetail.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    syncDetail,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (syncStage == WifiSync.Stage.Failed)
                        MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Panel("Maintenance") {
            OutlinedButton(
                onClick = { run { repo.reboot() } },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Reboot the deck") }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { run { repo.disconnect() } },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Disconnect") }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { confirmReset = true },
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Restore built-in profiles") }
        }

        Panel("About") {
            Text(
                "QindaDeck turns an ESP32-S3 into a USB keyboard, mouse and media " +
                    "controller. The board must be cabled to the computer you want to " +
                    "control through its native USB port — the other port is only for " +
                    "flashing and logs.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Restore built-in profiles?") },
            text = {
                Text(
                    "The six profiles that ship with the firmware are rewritten, " +
                        "discarding your edits to them. Profiles you created yourself " +
                        "are deleted. Wi-Fi details and paired devices are kept.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    run { repo.factoryReset() }
                }) { Text("Restore", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) { Text("Cancel") }
            },
        )
    }

    if (wifiOpen) {
        AlertDialog(
            onDismissRequest = { wifiOpen = false },
            title = { Text("Join a network") },
            text = {
                Column {
                    if (scanning) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("Scanning…")
                        }
                    } else if (nets.isNotEmpty()) {
                        Text("Seen by the deck:", style = MaterialTheme.typography.labelMedium)
                        Spacer(Modifier.height(4.dp))
                        nets.take(10).forEach { (s, rssi) ->
                            TextButton(onClick = { ssid = s }) {
                                Text("$s  ($rssi dBm)", style = MonoNumbers)
                            }
                        }
                    }
                    OutlinedTextField(
                        value = ssid,
                        onValueChange = { ssid = it },
                        label = { Text("Network name") },
                        singleLine = true,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = pass,
                        onValueChange = { pass = it },
                        label = { Text("Password") },
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = ssid.isNotBlank(),
                    onClick = {
                        wifiOpen = false
                        run {
                            repo.wifiSet(ssid.trim(), pass)
                            repo.say("Saved. The deck is rebooting onto $ssid.")
                        }
                    },
                ) { Text("Save and reboot") }
            },
            dismissButton = { TextButton(onClick = { wifiOpen = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(value, style = MonoNumbers)
    }
}

private fun formatUptime(seconds: Int): String {
    val d = seconds / 86400
    val h = (seconds % 86400) / 3600
    val m = (seconds % 3600) / 60
    return when {
        d > 0 -> "${d}d ${h}h"
        h > 0 -> "${h}h ${m}m"
        else -> "${m}m ${seconds % 60}s"
    }
}


/** Best-effort current-network SSID for the sync form. Needs location on old
 * APIs and is simply blank where the platform refuses — the user types it. */
private fun readCurrentSsid(context: android.content.Context): String = try {
    val wm = context.applicationContext.getSystemService(android.content.Context.WIFI_SERVICE)
        as WifiManager
    wm.connectionInfo?.ssid
        ?.removePrefix("\"")?.removeSuffix("\"")
        ?.takeIf { it != "<unknown ssid>" }
        ?: ""
} catch (_: Exception) {
    ""
}
