package org.qinda.deck.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Router
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.qinda.deck.data.DeckRepository
import org.qinda.deck.net.Discovery
import org.qinda.deck.net.Found
import org.qinda.deck.net.Link

@Composable
fun ConnectScreen(repo: DeckRepository, onConnected: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val link by repo.link.collectAsState()
    val scope = rememberCoroutineScope()

    var manual by remember { mutableStateOf(repo.lastHost) }
    var error by remember { mutableStateOf<String?>(null) }
    val found = remember { mutableStateListOf<Found>() }
    var searching by remember { mutableStateOf(true) }
    var attempt by remember { mutableIntStateOf(0) }

    // Discovery restarts on `attempt`, which the refresh button bumps.
    LaunchedEffect(attempt) {
        found.clear()
        searching = true
        val discovery = Discovery(ctx)
        runCatching {
            discovery.decks().collect { d ->
                if (found.none { it.host == d.host }) found.add(d)
            }
        }
        searching = false
    }

    fun connect(host: String) {
        error = null
        scope.launch {
            try {
                repo.connect(host)
                onConnected()
            } catch (e: Exception) {
                error = e.message ?: "Could not connect"
            }
        }
    }

    // A remembered deck is almost always the right one, so try it unprompted.
    LaunchedEffect(Unit) {
        if (repo.lastHost.isNotEmpty() && repo.link.value is Link.Idle) {
            runCatching {
                repo.connect(repo.lastHost)
                onConnected()
            }
        }
    }

    ScreenBody {
        Spacer(Modifier.height(12.dp))
        Text(
            "Find your deck",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            "The deck advertises itself on the local network. Both devices need to be " +
                "on the same Wi-Fi — the deck is 2.4 GHz only.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )

        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionHeader("Discovered", Modifier.weight(1f))
            if (searching) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
            }
            IconButton(onClick = { attempt++ }) { Icon(Icons.Default.Refresh, "Search again") }
        }

        if (found.isEmpty()) {
            Panel {
                Text(
                    if (searching) "Looking for decks…"
                    else "Nothing found. Check the deck is powered and on this network, " +
                        "or type its address below.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Panel {
                found.forEachIndexed { i, d ->
                    if (i > 0) HorizontalDivider()
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { connect(d.host) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.Router, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(d.name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "${d.host}:${d.port}",
                                style = MonoNumbers,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text("Connect", color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }

        Panel("Or enter an address") {
            OutlinedTextField(
                value = manual,
                onValueChange = { manual = it },
                label = { Text("Address") },
                placeholder = { Text("10.0.0.239") },
                singleLine = true,
                textStyle = MonoNumbers.copy(color = MaterialTheme.colorScheme.onSurface),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = { connect(manual.trim()) },
                enabled = manual.isNotBlank() && link !is Link.Connecting,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (link is Link.Connecting) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text("Connect")
            }
        }

        error?.let {
            Spacer(Modifier.height(12.dp))
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = MaterialTheme.shapes.medium,
            ) {
                Text(
                    it,
                    Modifier.padding(12.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        Panel("First run") {
            Text(
                "A deck with no Wi-Fi saved raises its own network instead: join " +
                    "QindaDeck-XXXXXX with the passphrase qindadeck, open " +
                    "http://192.168.4.1/ and give it your 2.4 GHz details. It will " +
                    "reboot and appear here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
