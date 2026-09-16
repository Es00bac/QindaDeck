package org.qinda.deck.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.qinda.deck.data.DeckRepository
import org.qinda.deck.data.DisplayCfg
import org.qinda.deck.data.DisplayConfig

private val PRESETS = listOf(
    "1920×1080" to (1920 to 1080),
    "2560×1440" to (2560 to 1440),
    "3840×2160" to (3840 to 2160),
    "1366×768" to (1366 to 768),
    "1920×1200" to (1920 to 1200),
    "3440×1440" to (3440 to 1440),
    "2560×1080" to (2560 to 1080),
)

/**
 * Describe the monitors, so macro coordinates mean something.
 *
 * The device reports the absolute pointer over a fixed 0..32767 range regardless
 * of resolution, so this layout is what converts a recorded pixel into that
 * range. Get it wrong and every macro lands in the wrong place proportionally —
 * which is why the canvas here is a literal scale drawing rather than a form.
 */
@Composable
fun DisplaysScreen(repo: DeckRepository) {
    val stored by repo.displays.collectAsState()
    val scope = rememberCoroutineScope()
    var draft by remember(stored) { mutableStateOf(stored) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun save() {
        busy = true
        error = null
        scope.launch {
            try {
                repo.saveDisplays(draft)
            } catch (e: Exception) {
                error = e.message
            } finally {
                busy = false
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

        Panel("Layout") {
            DisplayCanvas(config = draft)
            Text(
                "Virtual desktop ${draft.virtualWidth} × ${draft.virtualHeight} px",
                style = MonoNumbers,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
            Text(
                "Arrange these to match how your monitors are actually placed. " +
                    "Coordinates in macros are per-display, so moving a monitor here " +
                    "moves every macro on it with no re-recording.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        draft.displays.forEachIndexed { i, d ->
            Panel(if (d.name.isBlank()) "Display ${i + 1}" else d.name) {
                OutlinedTextField(
                    value = d.name,
                    onValueChange = { draft = draft.replace(i, d.copy(name = it.take(40))) },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                Text("Resolution", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                ChipPicker(
                    options = PRESETS.map { it.first },
                    selected = PRESETS.firstOrNull { it.second == (d.w to d.h) }?.first ?: "",
                    label = { it },
                    onPick = { label ->
                        PRESETS.firstOrNull { it.first == label }?.second?.let { (w, h) ->
                            draft = draft.replace(i, d.copy(w = w, h = h))
                        }
                    },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField(d.w, { draft = draft.replace(i, d.copy(w = it)) }, "Width",
                        Modifier.weight(1f), min = 64, max = 32767, suffix = "px")
                    NumberField(d.h, { draft = draft.replace(i, d.copy(h = it)) }, "Height",
                        Modifier.weight(1f), min = 64, max = 32767, suffix = "px")
                }
                Spacer(Modifier.height(8.dp))
                Text("Position within the virtual desktop",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField(d.x, { draft = draft.replace(i, d.copy(x = it)) }, "Left",
                        Modifier.weight(1f), min = -32767, max = 32767, suffix = "px")
                    NumberField(d.y, { draft = draft.replace(i, d.copy(y = it)) }, "Top",
                        Modifier.weight(1f), min = -32767, max = 32767, suffix = "px")
                }
                Spacer(Modifier.height(4.dp))
                RowLabel("Primary", "Used when a macro names no display") {
                    Switch(
                        checked = d.primary,
                        onCheckedChange = { on ->
                            // Exactly one primary, or "primary" means nothing.
                            draft = draft.copy(
                                displays = draft.displays.mapIndexed { j, other ->
                                    other.copy(primary = if (on) j == i else other.primary && j != i)
                                },
                            )
                        },
                    )
                }
                RowLabel("Default for new steps", if (draft.active == d.id) "yes" else "no") {
                    Switch(
                        checked = draft.active == d.id,
                        onCheckedChange = { on -> draft = draft.copy(active = if (on) d.id else "") },
                    )
                }
                Text("id: ${d.id}", style = MonoNumbers,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)

                if (draft.displays.size > 1) {
                    Spacer(Modifier.height(6.dp))
                    TextButton(
                        onClick = {
                            draft = draft.copy(
                                displays = draft.displays.toMutableList().also { it.removeAt(i) },
                            )
                        },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                    ) {
                        Icon(Icons.Default.Delete, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Remove display")
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        OutlinedButton(
            onClick = {
                val used = draft.displays.map { it.id }.toSet()
                val id = generateSequence(1) { it + 1 }
                    .map { "screen$it" }
                    .first { it !in used }
                val rightEdge = draft.displays.maxOfOrNull { it.x + it.w } ?: 0
                draft = draft.copy(
                    displays = draft.displays + DisplayCfg(
                        id = id,
                        name = "Display ${draft.displays.size + 1}",
                        w = 1920, h = 1080, x = rightEdge, y = 0,
                    ),
                )
            },
            enabled = draft.displays.size < 8,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Default.Add, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Add a display")
        }

        Spacer(Modifier.height(14.dp))
        Button(
            onClick = { save() },
            enabled = !busy && draft != stored,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (draft == stored) "Saved" else "Save layout") }

        if (busy) {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}

private fun DisplayConfig.replace(i: Int, d: DisplayCfg): DisplayConfig =
    copy(displays = displays.toMutableList().also { it[i] = d })
