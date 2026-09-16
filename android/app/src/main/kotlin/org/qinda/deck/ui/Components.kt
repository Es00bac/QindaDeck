package org.qinda.deck.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.qinda.deck.data.Action
import org.qinda.deck.data.ActionKind
import org.qinda.deck.data.Macro

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier.padding(start = 4.dp, top = 18.dp, bottom = 6.dp),
    )
}

/** A titled card that groups related controls. */
@Composable
fun Panel(
    title: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier) {
        title?.let { SectionHeader(it) }
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(Modifier.padding(14.dp), content = content)
        }
    }
}

@Composable
fun RowLabel(label: String, hint: String? = null, trailing: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            hint?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        trailing()
    }
}

/**
 * A number field that tolerates an empty box mid-edit.
 *
 * Binding an Int straight to a TextField fights the user: clearing the last digit
 * would snap it back to 0 and move the cursor. So the text is the state and the
 * Int is derived.
 */
@Composable
fun NumberField(
    value: Int,
    onValue: (Int) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    min: Int = Int.MIN_VALUE,
    max: Int = Int.MAX_VALUE,
    suffix: String? = null,
) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { raw ->
            val cleaned = raw.filter { it.isDigit() || (it == '-' && min < 0) }
            text = cleaned
            cleaned.toIntOrNull()?.let { onValue(it.coerceIn(min, max)) }
        },
        label = { Text(label) },
        suffix = suffix?.let { { Text(it, style = MaterialTheme.typography.bodySmall) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        textStyle = MonoNumbers.copy(color = MaterialTheme.colorScheme.onSurface),
        modifier = modifier,
    )
}

/** A stepper for small bounded values, where a keyboard would be overkill. */
@Composable
fun Stepper(
    value: Int,
    onValue: (Int) -> Unit,
    min: Int,
    max: Int,
    step: Int = 1,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        FilledTonalIconButton(
            onClick = { onValue((value - step).coerceAtLeast(min)) },
            enabled = value > min,
            modifier = Modifier.size(34.dp),
        ) { Icon(Icons.Default.Remove, "less", Modifier.size(16.dp)) }
        Text(
            value.toString(),
            style = MonoNumbers,
            modifier = Modifier.widthIn(min = 40.dp).padding(horizontal = 6.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        FilledTonalIconButton(
            onClick = { onValue((value + step).coerceAtMost(max)) },
            enabled = value < max,
            modifier = Modifier.size(34.dp),
        ) { Text("+", fontSize = 16.sp) }
    }
}

/** Horizontal chip row for picking one of a small set. */
@Composable
fun <T> ChipPicker(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onPick: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = PaddingValues(vertical = 4.dp),
    ) {
        items(options.size) { i ->
            val opt = options[i]
            FilterChip(
                selected = opt == selected,
                onClick = { onPick(opt) },
                label = { Text(label(opt)) },
            )
        }
    }
}

private val PALETTE = listOf(
    "", "#2A3550", "#23395C", "#1F4E79", "#3C6EA5", "#1E6FA8",
    "#2F6B4F", "#16794F", "#8A5A1E", "#8A2F28", "#5A2F34", "#3A2D5C",
)

/** Swatch row; the empty entry means "inherit the profile accent". */
@Composable
fun ColorPicker(value: String, onValue: (String) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(PALETTE.size) { i ->
            val hex = PALETTE[i]
            val c = if (hex.isEmpty()) MaterialTheme.colorScheme.surfaceVariant
                    else parseHex(hex, MaterialTheme.colorScheme.surfaceVariant)
            Box(
                Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(c)
                    .clickable { onValue(hex) },
                contentAlignment = Alignment.Center,
            ) {
                if (hex == value) {
                    Icon(Icons.Default.Check, "selected", tint = Color.White, modifier = Modifier.size(18.dp))
                } else if (hex.isEmpty()) {
                    Text("—", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

// ------------------------------------------------------------------ chords

private val MODIFIERS = listOf("Ctrl", "Shift", "Alt", "Meta")

private val COMMON_KEYS = listOf(
    ('A'..'Z').map(Char::toString),
    (0..9).map(Int::toString),
    (1..24).map { "F$it" },
    listOf("Return", "Escape", "Tab", "Space", "Backspace", "Delete", "Insert",
           "Home", "End", "PageUp", "PageDown", "Up", "Down", "Left", "Right",
           "Plus", "Minus", "Equal", "BracketLeft", "BracketRight", "Backslash",
           "Semicolon", "Apostrophe", "Grave", "Comma", "Period", "Slash",
           "CapsLock", "PrintScreen", "Pause", "Menu"),
    (0..9).map { "Numpad$it" } + listOf("NumpadAdd", "NumpadSubtract",
        "NumpadMultiply", "NumpadDivide", "NumpadEnter", "NumpadDecimal", "NumLock"),
).flatten()

/**
 * Builds a chord string the firmware's parser accepts.
 *
 * The modifiers are toggles and the key is a searchable list, rather than a free
 * text box: "Ctrl+Shift+P" is easy to typo, and an unparseable chord is only
 * discovered when the profile is rejected on save.
 */
@Composable
fun ChordPicker(value: String, onValue: (String) -> Unit) {
    val parts = value.split('+').map { it.trim() }.filter { it.isNotEmpty() }
    val mods = parts.filter { p -> MODIFIERS.any { it.equals(p, true) } }
        .map { p -> MODIFIERS.first { it.equals(p, true) } }
    val key = parts.lastOrNull { p -> MODIFIERS.none { it.equals(p, true) } } ?: ""

    fun rebuild(newMods: List<String>, newKey: String) {
        val ordered = MODIFIERS.filter { it in newMods }
        onValue((ordered + listOf(newKey).filter { it.isNotEmpty() }).joinToString("+"))
    }

    var query by remember { mutableStateOf("") }

    Column {
        Text("Modifiers", style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
            MODIFIERS.forEach { m ->
                FilterChip(
                    selected = m in mods,
                    onClick = {
                        rebuild(if (m in mods) mods - m else mods + m, key)
                    },
                    label = { Text(m) },
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Find key") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Result: ${value.ifEmpty { "(nothing)" }}",
            style = MonoNumbers,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(6.dp))

        val shown = remember(query) {
            if (query.isBlank()) COMMON_KEYS.take(48)
            else COMMON_KEYS.filter { it.contains(query, ignoreCase = true) }.take(48)
        }
        FlowChips(shown, key) { rebuild(mods, it) }
    }
}

/** A wrapping chip grid. Compose's FlowRow is experimental, so this is explicit. */
@Composable
fun FlowChips(options: List<String>, selected: String, onPick: (String) -> Unit) {
    val perRow = 4
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        options.chunked(perRow).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { opt ->
                    FilterChip(
                        selected = opt.equals(selected, true),
                        onClick = { onPick(opt) },
                        label = { Text(opt, maxLines = 1) },
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(perRow - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

val MEDIA_KEYS = listOf(
    "play_pause", "stop", "next", "prev", "vol_up", "vol_down", "mute",
    "record", "rewind", "fast_forward", "eject", "bright_up", "bright_down",
    "launch_browser", "launch_mail", "launch_calc", "launch_files",
    "search", "home", "back", "forward", "refresh",
)

val MOUSE_BUTTONS = listOf("left", "right", "middle", "back", "forward")

// ------------------------------------------------------------------ actions

/**
 * Editor for an [Action]. Shared by deck buttons and gamepad bindings, which is
 * the whole point of the vocabulary being shared on the device too.
 *
 * `onEditMacro` is null where a macro cannot be edited in place (for example a
 * binding whose macro lives on another screen).
 */
@Composable
fun ActionEditor(
    action: Action,
    onAction: (Action) -> Unit,
    profileSlugs: List<String>,
    macroNames: List<String>,
    pageCount: Int,
    allowPageSwitch: Boolean,
    onEditMacro: (() -> Unit)?,
) {
    val kinds = remember(allowPageSwitch) {
        ActionKind.entries.filter { allowPageSwitch || it != ActionKind.PAGE }
    }

    Column {
        SectionHeader("Does what")
        ChipPicker(
            options = kinds,
            selected = action.kind,
            label = { it.label },
            onPick = { onAction(action.copy(kind = it)) },
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(8.dp))
        when (action.kind) {
            ActionKind.NOOP -> Text(
                "The button stays on the grid but does nothing when pressed.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            ActionKind.TAP, ActionKind.HOLD -> Column {
                if (action.kind == ActionKind.HOLD) {
                    Text(
                        "Held down for as long as you hold the button — for chords like " +
                            "Meta+Shift that arm a drag.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                ChordPicker(action.keys) { onAction(action.copy(keys = it)) }
                if (action.kind == ActionKind.TAP) {
                    Spacer(Modifier.height(4.dp))
                    RowLabel("Repeat while held", "Like a keyboard's arrows or volume") {
                        Switch(
                            checked = action.repeatOnHold,
                            onCheckedChange = { onAction(action.copy(repeatOnHold = it)) },
                        )
                    }
                }
            }

            ActionKind.TEXT -> OutlinedTextField(
                value = action.text,
                onValueChange = { onAction(action.copy(text = it)) },
                label = { Text("Text to type") },
                supportingText = { Text("US layout, ASCII only") },
                modifier = Modifier.fillMaxWidth(),
            )

            ActionKind.CONSUMER -> Column {
                RowLabel("Repeat while held", "Useful for volume and brightness") {
                    Switch(
                        checked = action.repeatOnHold,
                        onCheckedChange = { onAction(action.copy(repeatOnHold = it)) },
                    )
                }
                FlowChips(MEDIA_KEYS, action.media) { onAction(action.copy(media = it)) }
            }

            ActionKind.MACRO -> Column {
                val m = action.macro ?: Macro()
                Text(
                    "${m.steps.size} step(s), about ${m.durationMs()} ms",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                if (onEditMacro != null) {
                    Button(onClick = onEditMacro) { Text("Edit macro") }
                } else {
                    Text(
                        "Save first, then reopen to edit the steps.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            ActionKind.MACRO_REF -> if (macroNames.isEmpty()) {
                Text(
                    "This profile has no saved macros yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                FlowChips(macroNames, action.macroRef) { onAction(action.copy(macroRef = it)) }
            }

            ActionKind.PAGE -> Column {
                Text("Jump to page", style = MaterialTheme.typography.bodyMedium)
                FlowChips((0 until pageCount.coerceAtLeast(1)).map { "${it + 1}" },
                    "${action.page + 1}") { onAction(action.copy(page = it.toInt() - 1)) }
            }

            ActionKind.PROFILE -> if (profileSlugs.isEmpty()) {
                Text("No other profiles.", style = MaterialTheme.typography.bodySmall)
            } else {
                FlowChips(profileSlugs, action.profile) { onAction(action.copy(profile = it)) }
            }
        }
    }
}

/** Scrollable page body with consistent padding. */
@Composable
fun ScreenBody(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp)
            .padding(bottom = 32.dp),
        content = content,
    )
}

@Composable
fun EmptyNote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 24.dp),
    )
}
