package org.qinda.deck.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.qinda.deck.data.*

/**
 * Bindings for one paired device.
 *
 * Capturing is the heart of it: the deck streams a diff of each input report while
 * learn mode is on, this screen turns that into a candidate predicate, and the
 * user confirms by simply pressing the button they want to remap. That avoids ever
 * asking a person to know which byte and bit their controller uses.
 */
@Composable
fun RemapEditorScreen(repo: DeckRepository, nav: Navigator, addr: String) {
    val paired by repo.btDevices.collectAsState()
    val scope = rememberCoroutineScope()
    var doc by remember { mutableStateOf<RemapDoc?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var capturing by remember { mutableStateOf(false) }

    val device = paired.firstOrNull { it.addr.equals(addr, true) }

    LaunchedEffect(addr) {
        try {
            doc = repo.remap(addr).let {
                if (it.name.isBlank() && device != null) it.copy(name = device.name) else it
            }
        } catch (e: Exception) {
            error = e.message
        }
    }

    // Learn mode is a device-wide setting; leaving it on would keep muting the
    // user's controller, so it is always turned off on the way out.
    DisposableEffect(Unit) {
        onDispose {
            repo.client.let {
                scope.launch { runCatching { repo.setLearn(false) } }
            }
        }
    }

    fun save(next: RemapDoc, thenPop: Boolean = false) {
        busy = true
        error = null
        doc = next
        scope.launch {
            try {
                repo.saveRemap(next)
                if (thenPop) nav.pop()
            } catch (e: Exception) {
                error = e.message
            } finally {
                busy = false
            }
        }
    }

    val d = doc
    if (d == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (error == null) CircularProgressIndicator() else Text(error!!)
        }
        return
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

        Panel(d.name.ifBlank { addr }) {
            Text(
                if (device?.connected == true) "Connected."
                else "Not connected — switch the device on to capture or test bindings.",
                style = MaterialTheme.typography.bodyMedium,
                color = if (device?.connected == true) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(6.dp))
            RowLabel(
                "Pass unmapped keys through",
                "Keyboards only: every key you have not rebound types normally",
            ) {
                Switch(
                    checked = d.passthrough,
                    onCheckedChange = { save(d.copy(passthrough = it)) },
                )
            }
            Text(addr, style = MonoNumbers, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        SectionHeader("Bindings")
        if (d.bindings.isEmpty()) {
            EmptyNote("Nothing bound yet. Capture a button below to start.")
        } else {
            Panel {
                d.bindings.forEachIndexed { i, b ->
                    if (i > 0) HorizontalDivider()
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { nav.push(Screen.BindingEditor(addr, i)) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                b.label.ifBlank { b.src.label() },
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                b.src.label(),
                                style = MonoNumbers,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                "→ ${b.action.summary()}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Icon(Icons.Default.ChevronRight, null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(
                        onClick = {
                            save(d.copy(bindings = d.bindings.toMutableList().also { it.removeAt(i) }))
                        },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                    ) {
                        Icon(Icons.Default.Delete, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Remove")
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        Button(
            onClick = { capturing = true },
            enabled = device?.connected == true,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Default.Add, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Capture a button")
        }
        if (device?.connected != true) {
            Text(
                "The device has to be connected to capture — the deck learns the " +
                    "binding by watching its actual input reports.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        if (busy) {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }

    if (capturing) {
        CaptureDialog(
            repo = repo,
            addr = addr,
            onDismiss = { capturing = false },
            onCaptured = { src, label ->
                capturing = false
                val next = d.copy(bindings = d.bindings + Binding(src = src, label = label))
                save(next)
                // Straight into the action editor: capturing a button and not
                // saying what it should do is never the whole intent.
                nav.push(Screen.BindingEditor(addr, next.bindings.lastIndex))
            },
        )
    }
}

/** Waits for the user to press something, then offers what it saw. */
@Composable
private fun CaptureDialog(
    repo: DeckRepository,
    addr: String,
    onDismiss: () -> Unit,
    onCaptured: (SrcSpec, String) -> Unit,
) {
    var candidates by remember { mutableStateOf<List<Pair<SrcSpec, String>>>(emptyList()) }
    var raw by remember { mutableStateOf("") }
    var ready by remember { mutableStateOf(false) }

    LaunchedEffect(addr) {
        runCatching { repo.setLearn(true, mute = true) }
        ready = true
        repo.events.collect { ev ->
            if (ev.optString("ev") != "src_input") return@collect
            if (!ev.optString("addr").equals(addr, true)) return@collect
            raw = ev.optString("raw")
            val fresh = candidatesFrom(ev)
            if (fresh.isNotEmpty()) candidates = fresh
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Press the button") },
        text = {
            Column {
                Text(
                    "Press and release the physical button, stick or key you want to " +
                        "remap. Its existing binding is muted while capturing.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                if (!ready) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Starting learn mode…")
                    }
                } else if (candidates.isEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Listening…")
                    }
                } else {
                    Text("Saw:", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(6.dp))
                    candidates.forEach { (src, label) ->
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp)
                                .clickable { onCaptured(src, label) },
                        ) {
                            Column(Modifier.padding(10.dp)) {
                                Text(label, style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium)
                                Text(src.label(), style = MonoNumbers,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("Tap the one you meant.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary)
                }
                if (raw.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text("report: $raw", style = MonoNumbers,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/**
 * Turn a report diff into predicates a human can choose between.
 *
 * Buttons and analog axes are both just bytes changing, so both readings are
 * offered rather than guessed at: a single bit going high is almost always a
 * button, a byte that jumped by a lot is almost always a stick, and the person
 * holding the controller knows which they pressed.
 */
private fun candidatesFrom(ev: JSONObject): List<Pair<SrcSpec, String>> {
    val out = mutableListOf<Pair<SrcSpec, String>>()

    ev.optJSONArray("keys")?.let { keys ->
        for (i in 0 until keys.length()) {
            val usage = keys.optInt(i)
            if (usage != 0) {
                out.add(SrcSpec.Key(usage) to "Keyboard key 0x%02X".format(usage))
            }
        }
    }

    ev.optJSONArray("bits")?.let { bits ->
        for (i in 0 until bits.length()) {
            val b = bits.optJSONObject(i) ?: continue
            if (!b.optBoolean("down")) continue
            out.add(
                SrcSpec.Btn(b.optInt("byte"), b.optInt("bit"))
                    to "Button (byte ${b.optInt("byte")}, bit ${b.optInt("bit")})",
            )
        }
    }

    ev.optJSONArray("bytes")?.let { bytes ->
        for (i in 0 until bytes.length()) {
            val by = bytes.optJSONObject(i) ?: continue
            if (!by.optBoolean("analog")) continue
            val byteIdx = by.optInt("byte")
            val v = by.optInt("val")
            val prev = by.optInt("prev")
            // Threshold halfway between rest and the extreme just seen, so the
            // binding triggers well before the stick bottoms out.
            val dir = if (v < prev) "lo" else "hi"
            val thr = ((v + prev) / 2).coerceIn(1, 254)
            out.add(
                SrcSpec.Axis(byteIdx, dir, thr)
                    to "Stick or trigger (byte $byteIdx ${if (dir == "lo") "below" else "above"} $thr)",
            )
        }
    }

    return out.distinctBy { it.first }
}
