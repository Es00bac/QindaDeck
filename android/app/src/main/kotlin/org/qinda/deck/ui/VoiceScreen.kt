package org.qinda.deck.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.qinda.deck.data.DeckRepository
import org.qinda.deck.net.HidUsage
import org.qinda.deck.net.KeyMod
import org.qinda.deck.voice.VoiceTyper

/*
 * Voice typing.
 *
 * One tap starts the mic, another stops it. Segments finalise whenever you
 * pause and are typed on the host immediately, so text appears on the computer
 * as you speak. The quick keys below cover what speech cannot say well —
 * Enter, Backspace, punctuation — grouped and coloured by what they do:
 *
 *   green   commits (Enter, Confirm)
 *   red     deletes or cancels (Backspace, Esc)
 *   slate   neutral keys (Space, Tab, arrows, punctuation)
 *
 * The colours deliberately echo the deck profiles, so the two screens read as
 * one language.
 */

private val CommitGreen = Color(0xFF2F6B4F)
private val DangerRed = Color(0xFF5A2F34)
private val NeutralSlate = Color(0xFF2A3550)
private val ListeningGreen = Color(0xFF16794F)

@Composable
fun VoiceScreen(repo: DeckRepository) {
    val voice = repo.voice
    val state by voice.state.collectAsState()
    val partial by voice.partial.collectAsState()
    val log by voice.log.collectAsState()
    val voiceError by voice.error.collectAsState()
    val enterAfter by repo.voiceEnterAfter.collectAsState()
    val spaceBetween by repo.voiceSpaceBetween.collectAsState()
    val scope = rememberCoroutineScope()

    var hasPermission by remember { mutableStateOf(voice.hasPermission) }
    var wantStart by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasPermission = granted
        if (granted && wantStart) voice.start()
        wantStart = false
    }

    // Backgrounding the app always closes the mic: a hot mic in a pocket is a
    // privacy bug, not a feature.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) voice.stop()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val listening = state == VoiceTyper.State.Listening

    Column(
        Modifier
            .fillMaxSize()
            .padding(12.dp),
    ) {
        if (!hasPermission) {
            Panel("Microphone") {
                Text(
                    "Voice typing turns your speech into keystrokes on the " +
                        "computer. Audio goes to the phone's speech recognizer; " +
                        "only the recognized text is sent, as key input.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        wantStart = true
                        permissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Allow microphone") }
            }
            return@Column
        }

        // --- mic ----------------------------------------------------------
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MicButton(
                listening = listening,
                enabled = voice.available,
                onToggle = { if (listening) voice.stop() else voice.start() },
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    when {
                        !voice.available -> "No recognizer on this phone"
                        listening -> "Listening…"
                        else -> "Tap to talk"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    if (listening) "Pause to end a phrase — it types as you go."
                    else "Speak, and it types on the computer.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (listening) {
                    TextButton(
                        onClick = { voice.cancel() },
                        contentPadding = PaddingValues(0.dp),
                    ) { Text("Discard this phrase", color = MaterialTheme.colorScheme.error) }
                }
            }
        }

        voiceError?.let {
            Spacer(Modifier.height(8.dp))
            Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(8.dp)) {
                Text(
                    it, Modifier.fillMaxWidth().padding(10.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        // --- transcript ---------------------------------------------------
        Spacer(Modifier.height(10.dp))
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "TRANSCRIPT",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    if (log.isNotEmpty()) {
                        TextButton(onClick = { voice.clearLog() }) { Text("Clear") }
                    }
                }
                val scroll = rememberScrollState()
                LaunchedEffect(log.size, partial) { scroll.animateScrollTo(scroll.maxValue) }
                Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
                    if (log.isEmpty() && partial.isEmpty()) {
                        Text(
                            "What you say shows up here before it lands on the " +
                                "computer. The last line, while dim, is still being " +
                                "recognized and has not been typed yet.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    log.forEach { line ->
                        Text(line, style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(vertical = 2.dp))
                    }
                    if (partial.isNotEmpty()) {
                        Text(
                            partial,
                            style = MaterialTheme.typography.bodyMedium,
                            fontStyle = FontStyle.Italic,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.alpha(0.8f).padding(vertical = 2.dp),
                        )
                    }
                }
            }
        }

        // --- options ------------------------------------------------------
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Enter after phrase", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Send each phrase with Enter — chats, terminals, search boxes",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = enterAfter, onCheckedChange = { repo.setVoiceEnterAfter(it) })
        }
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Space between phrases", style = MaterialTheme.typography.bodyMedium)
            }
            Switch(checked = spaceBetween, onCheckedChange = { repo.setVoiceSpaceBetween(it) })
        }

        // --- quick keys ---------------------------------------------------
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            VoiceKey("Enter", CommitGreen, Modifier.weight(1.4f)) { repo.tapKey(HidUsage.ENTER) }
            VoiceKey("Space", NeutralSlate, Modifier.weight(1.6f)) { repo.tapKey(HidUsage.SPACE) }
            VoiceKey("Tab", NeutralSlate, Modifier.weight(1f)) { repo.tapKey(HidUsage.TAB) }
            VoiceKey("Esc", DangerRed, Modifier.weight(1f)) { repo.tapKey(HidUsage.ESCAPE) }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RepeatKey("⌫", DangerRed, Modifier.weight(1.4f)) { repo.tapKey(HidUsage.BACKSPACE) }
            VoiceKey("Word ⌫", DangerRed, Modifier.weight(1.4f)) {
                repo.tapKey(HidUsage.BACKSPACE, KeyMod.LCTRL)
            }
            VoiceKey("◀", NeutralSlate, Modifier.weight(1f)) { repo.tapKey(HidUsage.LEFT) }
            VoiceKey("▶", NeutralSlate, Modifier.weight(1f)) { repo.tapKey(HidUsage.RIGHT) }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(",", ".", "?", "!", ":", ";").forEach { p ->
                VoiceKey(p, NeutralSlate, Modifier.weight(1f), send = {
                    scope.launch { runCatching { repo.typeText(p) } }
                })
            }
        }
    }
}

@Composable
private fun MicButton(listening: Boolean, enabled: Boolean, onToggle: () -> Unit) {
    val pulse = rememberInfiniteTransition(label = "mic")
    val ring by pulse.animateFloat(
        initialValue = 1f, targetValue = 1.25f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "ring",
    )
    Box(contentAlignment = Alignment.Center) {
        if (listening) {
            Box(
                Modifier
                    .size(84.dp)
                    .scale(ring)
                    .alpha(0.35f)
                    .clip(CircleShape)
                    .background(ListeningGreen),
            )
        }
        Surface(
            color = when {
                !enabled -> MaterialTheme.colorScheme.surfaceVariant
                listening -> ListeningGreen
                else -> MaterialTheme.colorScheme.primary
            },
            shape = CircleShape,
            modifier = Modifier
                .size(84.dp)
                .clip(CircleShape)
                .pointerInput(listening, enabled) {
                    detectTapGestures { if (enabled) onToggle() }
                },
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    if (listening) Icons.Default.Mic else Icons.Default.MicOff,
                    contentDescription = if (listening) "Stop" else "Talk",
                    tint = Color.White,
                    modifier = Modifier.size(36.dp),
                )
            }
        }
    }
}

@Composable
private fun VoiceKey(
    label: String,
    color: Color,
    modifier: Modifier,
    send: () -> Unit,
) {
    var pressed by remember { mutableStateOf(false) }
    Surface(
        color = if (pressed) color.lighten() else color,
        shape = RoundedCornerShape(10.dp),
        modifier = modifier
            .height(52.dp)
            .clip(RoundedCornerShape(10.dp))
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        send()
                        tryAwaitRelease()
                        pressed = false
                    },
                )
            },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                label,
                color = Color.White,
                fontWeight = FontWeight.Medium,
                fontSize = 15.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Backspace and friends: tap fires once, holding repeats like a real key. */
@Composable
private fun RepeatKey(
    label: String,
    color: Color,
    modifier: Modifier,
    send: () -> Unit,
) {
    var pressed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Surface(
        color = if (pressed) color.lighten() else color,
        shape = RoundedCornerShape(10.dp),
        modifier = modifier
            .height(52.dp)
            .clip(RoundedCornerShape(10.dp))
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        send()
                        val job = scope.launch {
                            delay(400)
                            while (true) {
                                send()
                                delay(70)
                            }
                        }
                        tryAwaitRelease()
                        job.cancel()
                        pressed = false
                    },
                )
            },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                label,
                color = Color.White,
                fontWeight = FontWeight.Medium,
                fontSize = 15.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}

private fun Color.lighten(): Color =
    Color(
        red = (red + 0.14f).coerceAtMost(1f),
        green = (green + 0.14f).coerceAtMost(1f),
        blue = (blue + 0.14f).coerceAtMost(1f),
        alpha = alpha,
    )
