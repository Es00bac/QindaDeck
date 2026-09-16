package org.qinda.deck.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch
import org.qinda.deck.data.*

/** Editing the macro attached to one deck button. */
@Composable
fun MacroEditorScreen(
    repo: DeckRepository,
    nav: Navigator,
    slug: String,
    pageIndex: Int,
    index: Int,
) {
    val loaded by repo.loaded.collectAsState()
    val displays by repo.displays.collectAsState()
    val status by repo.status.collectAsState()
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(slug) { runCatching { repo.profile(slug) }.onFailure { error = it.message } }

    val profile = loaded[slug]
    val page = profile?.pages?.getOrNull(pageIndex)
    val button = page?.buttonAt(index)

    var draft by remember(button?.action?.macro) {
        mutableStateOf(button?.action?.macro ?: Macro(name = button?.label ?: "Macro"))
    }

    if (profile == null || page == null || button == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (error == null) CircularProgressIndicator() else Text(error!!)
        }
        return
    }

    MacroEditorBody(
        macro = draft,
        onMacro = { draft = it },
        displays = displays,
        running = status.running,
        onTest = {
            scope.launch {
                runCatching { repo.runMacro(draft) }.onFailure { error = it.message }
            }
        },
        onStop = { scope.launch { runCatching { repo.stopMacro() } } },
        onSave = {
            busy = true
            error = null
            val nextButton = button.copy(
                action = button.action.copy(kind = ActionKind.MACRO, macro = draft),
            )
            val nextProfile = profile.copy(
                pages = profile.pages.toMutableList().also {
                    it[pageIndex] = page.copy(
                        buttons = (page.buttons.filter { b -> b.index != index } + nextButton)
                            .sortedBy { b -> b.index },
                    )
                },
            )
            scope.launch {
                try {
                    repo.saveProfile(nextProfile)
                    nav.pop()
                } catch (e: Exception) {
                    error = e.message
                } finally {
                    busy = false
                }
            }
        },
        busy = busy,
        error = error,
    )
}

/** Editing the macro attached to one gamepad or keyboard binding. */
@Composable
fun RemapMacroEditorScreen(
    repo: DeckRepository,
    nav: Navigator,
    addr: String,
    bindingIndex: Int,
) {
    val displays by repo.displays.collectAsState()
    val status by repo.status.collectAsState()
    val scope = rememberCoroutineScope()
    var doc by remember { mutableStateOf<RemapDoc?>(null) }
    var draft by remember { mutableStateOf<Macro?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(addr) {
        try {
            val d = repo.remap(addr)
            doc = d
            draft = d.bindings.getOrNull(bindingIndex)?.action?.macro ?: Macro(name = "Macro")
        } catch (e: Exception) {
            error = e.message
        }
    }

    val d = doc
    val m = draft
    if (d == null || m == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (error == null) CircularProgressIndicator() else Text(error!!)
        }
        return
    }

    MacroEditorBody(
        macro = m,
        onMacro = { draft = it },
        displays = displays,
        running = status.running,
        onTest = { scope.launch { runCatching { repo.runMacro(m) }.onFailure { error = it.message } } },
        onStop = { scope.launch { runCatching { repo.stopMacro() } } },
        onSave = {
            busy = true
            error = null
            val binding = d.bindings.getOrNull(bindingIndex)
            if (binding == null) {
                error = "That binding no longer exists"
                busy = false
                return@MacroEditorBody
            }
            val next = d.copy(
                bindings = d.bindings.toMutableList().also {
                    it[bindingIndex] = binding.copy(
                        action = binding.action.copy(kind = ActionKind.MACRO, macro = m),
                    )
                },
            )
            scope.launch {
                try {
                    repo.saveRemap(next)
                    nav.pop()
                } catch (e: Exception) {
                    error = e.message
                } finally {
                    busy = false
                }
            }
        },
        busy = busy,
        error = error,
    )
}
