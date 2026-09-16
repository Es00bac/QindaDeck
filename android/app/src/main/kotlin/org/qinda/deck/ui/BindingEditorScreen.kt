package org.qinda.deck.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.qinda.deck.data.*

/** What one captured physical input should do. */
@Composable
fun BindingEditorScreen(
    repo: DeckRepository,
    nav: Navigator,
    addr: String,
    bindingIndex: Int,
) {
    val summaries by repo.profiles.collectAsState()
    val scope = rememberCoroutineScope()
    var doc by remember { mutableStateOf<RemapDoc?>(null) }
    var draft by remember { mutableStateOf<Binding?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(addr, bindingIndex) {
        try {
            val d = repo.remap(addr)
            doc = d
            draft = d.bindings.getOrNull(bindingIndex)
        } catch (e: Exception) {
            error = e.message
        }
    }

    val d = doc
    val b = draft
    if (d == null || b == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (error == null) CircularProgressIndicator() else Text(error ?: "Binding not found")
        }
        return
    }

    fun persist(next: Binding, thenPop: Boolean, thenMacro: Boolean = false) {
        busy = true
        error = null
        val nextDoc = d.copy(
            bindings = d.bindings.toMutableList().also { it[bindingIndex] = next },
        )
        scope.launch {
            try {
                repo.saveRemap(nextDoc)
                doc = nextDoc
                when {
                    thenMacro -> nav.push(Screen.RemapMacroEditor(addr, bindingIndex))
                    thenPop -> nav.pop()
                }
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

        Panel("Physical input") {
            Text(b.src.label(), style = MonoNumbers, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = b.label,
                onValueChange = { draft = b.copy(label = it.take(32)) },
                label = { Text("Call it") },
                placeholder = { Text("A button, Left trigger, …") },
                supportingText = { Text("Only for your benefit in this list") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (b.src is SrcSpec.Axis) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "Threshold",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                RowLabel(
                    "Triggers when the axis goes ${if (b.src.dir == "lo") "below" else "above"}",
                    "${b.src.thr} (0–255)",
                ) {
                    Stepper(
                        b.src.thr, min = 1, max = 254, step = 4,
                        onValue = { draft = b.copy(src = b.src.copy(thr = it)) },
                    )
                }
                ChipPicker(
                    options = listOf("lo", "hi"),
                    selected = b.src.dir,
                    label = { if (it == "lo") "Below threshold" else "Above threshold" },
                    onPick = { draft = b.copy(src = b.src.copy(dir = it)) },
                )
            }
        }

        Panel {
            ActionEditor(
                action = b.action,
                onAction = { draft = b.copy(action = it) },
                profileSlugs = summaries.map { it.slug },
                macroNames = emptyList(),
                pageCount = 1,
                // A controller button has no deck page context, and the firmware
                // rejects page actions on bindings for the same reason.
                allowPageSwitch = false,
                onEditMacro = if (b.action.kind == ActionKind.MACRO) {
                    {
                        val seeded = if (b.action.macro == null) {
                            b.copy(action = b.action.copy(
                                macro = Macro(name = b.label.ifBlank { "Macro" }),
                            ))
                        } else {
                            b
                        }
                        persist(seeded, thenPop = false, thenMacro = true)
                    }
                } else null,
            )
        }

        Spacer(Modifier.height(18.dp))
        Button(
            onClick = { persist(b, thenPop = true) },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Save binding") }

        if (busy) {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }

        Text(
            "Bindings fire on the real press and release, so a “Hold key” action holds " +
                "for exactly as long as you hold the button.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}
