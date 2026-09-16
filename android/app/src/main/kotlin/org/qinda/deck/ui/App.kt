package org.qinda.deck.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.qinda.deck.data.DeckRepository
import org.qinda.deck.net.Link
import org.qinda.deck.voice.VoiceTyper

private fun tabIcon(s: Screen): ImageVector = when (s) {
    Screen.Deck -> Icons.Default.Dashboard
    Screen.Trackpad -> Icons.Default.Mouse
    Screen.Voice -> Icons.Default.Mic
    Screen.Profiles -> Icons.Default.ViewList
    else -> Icons.Default.Settings
}

private fun tabLabel(s: Screen): String = when (s) {
    Screen.Deck -> "Deck"
    Screen.Trackpad -> "Mouse"
    Screen.Voice -> "Voice"
    Screen.Profiles -> "Profiles"
    else -> "Settings"
}

private fun title(s: Screen): String = when (s) {
    Screen.Connect -> "Connect"
    Screen.Deck -> "Deck"
    Screen.Trackpad -> "Mouse"
    Screen.Voice -> "Voice"
    Screen.Profiles -> "Profiles"
    Screen.Displays -> "Displays"
    Screen.Bluetooth -> "Paired devices"
    Screen.Settings -> "Settings"
    is Screen.ProfileEditor -> "Edit profile"
    is Screen.ButtonEditor -> "Edit button"
    is Screen.MacroEditor -> "Edit macro"
    is Screen.RemapEditor -> "Remap"
    is Screen.BindingEditor -> "Edit binding"
    is Screen.RemapMacroEditor -> "Edit macro"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(repo: DeckRepository) {
    val link by repo.link.collectAsState()
    val status by repo.status.collectAsState()
    val toast by repo.toast.collectAsState()
    val voiceState by repo.voice.state.collectAsState()
    val scope = rememberCoroutineScope()

    val nav = remember { Navigator(if (repo.lastHost.isEmpty()) Screen.Connect else Screen.Connect) }
    val snackbar = remember { SnackbarHostState() }

    // Losing the link mid-session drops back to Connect rather than leaving
    // screens showing stale data they can no longer act on.
    LaunchedEffect(link) {
        if (link is Link.Down && nav.current != Screen.Connect) {
            nav.selectTab(Screen.Connect)
        }
    }

    LaunchedEffect(toast) {
        toast?.let {
            snackbar.showSnackbar(it)
            repo.clearToast()
        }
    }

    BackHandler(enabled = nav.canGoBack) { nav.pop() }

    val connected = link is Link.Up
    val onTab = nav.current in bottomTabs

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(title(nav.current))
                        if (connected) {
                            val sub = buildString {
                                append(status.name.ifBlank { "QindaDeck" })
                                append(if (status.usb) " · USB ready" else " · USB not connected")
                            }
                            Text(
                                sub,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (status.usb) MaterialTheme.colorScheme.onSurfaceVariant
                                        else MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                },
                navigationIcon = {
                    if (nav.canGoBack) {
                        IconButton(onClick = { nav.pop() }) {
                            Icon(Icons.Default.ArrowBack, "Back")
                        }
                    }
                },
                actions = {
                    // Dictation keeps running when you wander to another tab;
                    // the dot is the reminder that the mic is hot, and a way back.
                    if (voiceState == VoiceTyper.State.Listening && nav.current != Screen.Voice) {
                        TextButton(onClick = { nav.selectTab(Screen.Voice) }) {
                            Icon(
                                Icons.Default.Mic, "Listening",
                                Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.tertiary,
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("Listening", color = MaterialTheme.colorScheme.tertiary)
                        }
                    }
                    // A running macro is the one thing you always want to be able
                    // to stop, from wherever you are.
                    if (status.running != null) {
                        TextButton(onClick = { scope.launch { runCatching { repo.stopMacro() } } }) {
                            Icon(Icons.Default.Stop, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Stop")
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (connected && onTab) {
                NavigationBar {
                    bottomTabs.forEach { tab ->
                        NavigationBarItem(
                            selected = nav.current == tab,
                            onClick = { nav.selectTab(tab) },
                            icon = { Icon(tabIcon(tab), tabLabel(tab)) },
                            label = { Text(tabLabel(tab)) },
                        )
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        Box(Modifier.padding(pad)) {
            when (val s = nav.current) {
                Screen.Connect -> ConnectScreen(repo) { nav.selectTab(Screen.Deck) }
                Screen.Deck -> DeckScreen(repo, nav)
                Screen.Trackpad -> TrackpadScreen(repo)
                Screen.Voice -> VoiceScreen(repo)
                Screen.Profiles -> ProfilesScreen(repo, nav)
                Screen.Displays -> DisplaysScreen(repo)
                Screen.Bluetooth -> BluetoothScreen(repo, nav)
                Screen.Settings -> SettingsScreen(repo, nav)
                is Screen.ProfileEditor -> ProfileEditorScreen(repo, nav, s.slug)
                is Screen.ButtonEditor -> ButtonEditorScreen(repo, nav, s.slug, s.page, s.index)
                is Screen.MacroEditor -> MacroEditorScreen(repo, nav, s.slug, s.page, s.index)
                is Screen.RemapEditor -> RemapEditorScreen(repo, nav, s.addr)
                is Screen.BindingEditor -> BindingEditorScreen(repo, nav, s.addr, s.bindingIndex)
                is Screen.RemapMacroEditor ->
                    RemapMacroEditorScreen(repo, nav, s.addr, s.bindingIndex)
            }
        }
    }
}
