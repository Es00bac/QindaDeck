package org.qinda.deck.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.qinda.deck.data.*

/**
 * One key.
 *
 * Unlike the profile editor this keeps a local draft, because a half-configured
 * action (a macro kind with no macro yet) would be rejected by the firmware, and
 * writing through on every keystroke would surface that as a stream of errors.
 * The draft is written once, on Save.
 */
@Composable
fun ButtonEditorScreen(
    repo: DeckRepository,
    nav: Navigator,
    slug: String,
    pageIndex: Int,
    index: Int,
) {
    val loaded by repo.loaded.collectAsState()
    val summaries by repo.profiles.collectAsState()
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(slug) { runCatching { repo.profile(slug) }.onFailure { error = it.message } }

    val profile = loaded[slug]
    val page = profile?.pages?.getOrNull(pageIndex)
    val existing = page?.buttonAt(index)

    var draft by remember(profile?.slug, pageIndex, index, existing) {
        mutableStateOf(existing ?: DeckButton(index = index))
    }

    if (profile == null || page == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (error == null) CircularProgressIndicator() else Text(error!!)
        }
        return
    }

    fun persist(next: DeckButton?, thenPop: Boolean) {
        busy = true
        error = null
        val buttons = page.buttons.filter { it.index != index } + listOfNotNull(next)
        val nextProfile = profile.copy(
            pages = profile.pages.toMutableList().also {
                it[pageIndex] = page.copy(buttons = buttons.sortedBy { b -> b.index })
            },
        )
        scope.launch {
            try {
                repo.saveProfile(nextProfile)
                if (thenPop) nav.pop()
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

        Panel("Label") {
            OutlinedTextField(
                value = draft.label,
                onValueChange = { draft = draft.copy(label = it.take(24)) },
                label = { Text("Label") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = draft.sub,
                onValueChange = { draft = draft.copy(sub = it.take(24)) },
                label = { Text("Caption") },
                supportingText = { Text("Small text under the label, e.g. the shortcut") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            Text("Colour", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            ColorPicker(draft.color) { draft = draft.copy(color = it) }
            Spacer(Modifier.height(6.dp))
            Text(
                "grid position ${index + 1} of ${page.cols * page.rows}" +
                    "  ·  row ${index / page.cols + 1}, column ${index % page.cols + 1}",
                style = MonoNumbers,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Panel {
            ActionEditor(
                action = draft.action,
                onAction = { draft = draft.copy(action = it) },
                profileSlugs = summaries.map { it.slug }.filter { it != slug },
                macroNames = profile.macros.keys.sorted(),
                pageCount = profile.pages.size,
                allowPageSwitch = true,
                onEditMacro = if (draft.action.kind == ActionKind.MACRO) {
                    {
                        // The macro editor reads the stored profile, so the draft
                        // has to land first or it would edit the previous macro.
                        val seeded = if (draft.action.macro == null) {
                            draft.copy(action = draft.action.copy(macro = Macro(name = draft.label.ifBlank { "Macro" })))
                        } else {
                            draft
                        }
                        val buttons = page.buttons.filter { it.index != index } + seeded
                        val nextProfile = profile.copy(
                            pages = profile.pages.toMutableList().also {
                                it[pageIndex] = page.copy(buttons = buttons.sortedBy { b -> b.index })
                            },
                        )
                        scope.launch {
                            try {
                                repo.saveProfile(nextProfile)
                                nav.push(Screen.MacroEditor(slug, pageIndex, index))
                            } catch (e: Exception) {
                                error = e.message
                            }
                        }
                    }
                } else null,
            )
        }

        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { persist(draft, thenPop = true) },
                enabled = !busy,
                modifier = Modifier.weight(1f),
            ) { Text("Save") }

            OutlinedButton(
                onClick = {
                    scope.launch {
                        runCatching { repo.press(slug, pageIndex, index, "tap") }
                            .onFailure { error = it.message }
                    }
                },
                enabled = existing != null,
            ) {
                Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Test")
            }
        }

        if (existing != null) {
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = { persist(null, thenPop = true) },
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Delete, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Clear this key")
            }
        }

        if (busy) {
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        Text(
            "Test only works once the key has been saved — the deck fires the stored " +
                "button, not the draft.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}
