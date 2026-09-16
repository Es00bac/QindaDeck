package org.qinda.deck.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.qinda.deck.data.*

/** Where a canvas tap should write its coordinates. */
private data class PickTarget(val path: List<Int>, val field: String)

/**
 * The macro editor body, shared by the button and gamepad-binding entry points.
 *
 * Two things make this usable rather than a JSON form with nicer fonts. First,
 * coordinates are picked on a scale drawing of the user's actual monitors, so
 * "1400, 700" is a place rather than a number. Second, the whole macro can be run
 * on the real device from here, which is the only way to tell whether a 600 ms
 * drag actually feels right.
 */
@Composable
fun MacroEditorBody(
    macro: Macro,
    onMacro: (Macro) -> Unit,
    displays: DisplayConfig,
    running: String?,
    onTest: () -> Unit,
    onStop: () -> Unit,
    onSave: () -> Unit,
    busy: Boolean,
    error: String?,
) {
    var expanded by remember { mutableStateOf<List<Int>?>(null) }
    var pick by remember { mutableStateOf<PickTarget?>(null) }
    var adding by remember { mutableStateOf<List<Int>?>(null) }

    // Every absolute point in order, so the canvas can draw the route.
    val route = remember(macro) { macro.routeMarks() }

    ScreenBody {
        error?.let {
            Spacer(Modifier.height(10.dp))
            Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(8.dp)) {
                Text(it, Modifier.padding(10.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodySmall)
            }
        }

        Panel("Macro") {
            OutlinedTextField(
                value = macro.name,
                onValueChange = { onMacro(macro.copy(name = it.take(40))) },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(4.dp))
            RowLabel("Repeat whole macro", if (macro.loop > 1) "${macro.loop} times" else "once") {
                Stepper(macro.loop, min = 1, max = 100, onValue = { onMacro(macro.copy(loop = it)) })
            }
            Text(
                "${macro.steps.size} step(s) · about ${macro.durationMs()} ms per run",
                style = MonoNumbers,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // --- the desktop ------------------------------------------------
        SectionHeader("Where it goes")
        Panel {
            DisplayCanvas(
                config = displays,
                marks = route,
                path = route,
                onTap = pick?.let { target ->
                    { displayId, x, y ->
                        onMacro(macro.updateAt(target.path) { st ->
                            when (target.field) {
                                "abs" -> st.copy(x = x, y = y, display = displayId)
                                "from" -> st.copy(from = Point(x, y), display = displayId)
                                "to" -> st.copy(to = Point(x, y), display = displayId)
                                else -> st
                            }
                        })
                        pick = null
                    }
                },
            )
            Text(
                pick?.let { "Tap a display to set the ${it.field} point." }
                    ?: if (route.isEmpty())
                        "No pointer targets yet. Add a move or drag step and tap “Pick on screen”."
                    else "Expand a move or drag step and tap “Pick on screen” to reposition it.",
                style = MaterialTheme.typography.bodySmall,
                color = if (pick != null) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        // --- steps -------------------------------------------------------
        SectionHeader("Steps")
        if (macro.steps.isEmpty()) {
            EmptyNote("Empty. Add the first step below.")
        }
        StepList(
            steps = macro.steps,
            path = emptyList(),
            displays = displays,
            expanded = expanded,
            onExpand = { expanded = if (expanded == it) null else it },
            pick = pick,
            onPick = { pick = it },
            onSteps = { next -> onMacro(macro.copy(steps = next)) },
            onAddAt = { adding = it },
        )

        Spacer(Modifier.height(10.dp))
        OutlinedButton(
            onClick = { adding = emptyList() },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Default.Add, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Add step")
        }

        // --- run / save --------------------------------------------------
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onSave, enabled = !busy, modifier = Modifier.weight(1f)) {
                Text("Save macro")
            }
            if (running != null) {
                FilledTonalButton(onClick = onStop) {
                    Icon(Icons.Default.Stop, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Stop")
                }
            } else {
                OutlinedButton(onClick = onTest, enabled = macro.steps.isNotEmpty()) {
                    Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Run now")
                }
            }
        }
        if (busy) {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        Text(
            "“Run now” sends the macro as it is on screen, without saving. It moves the " +
                "real pointer on the connected computer.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 10.dp),
        )
    }

    adding?.let { at ->
        AddStepSheet(
            onDismiss = { adding = null },
            onPick = { kind ->
                val newStep = defaultStep(kind, displays)
                if (at.isEmpty()) {
                    onMacro(macro.copy(steps = macro.steps + newStep))
                    expanded = listOf(macro.steps.size)
                } else {
                    onMacro(macro.updateAt(at) { st -> st.copy(steps = st.steps + newStep) })
                    expanded = at + (macro.stepAt(at)?.steps?.size ?: 0)
                }
                adding = null
            },
        )
    }
}

/** Recursive step list; `path` is the index chain to this level. */
@Composable
private fun StepList(
    steps: List<Step>,
    path: List<Int>,
    displays: DisplayConfig,
    expanded: List<Int>?,
    onExpand: (List<Int>) -> Unit,
    pick: PickTarget?,
    onPick: (PickTarget?) -> Unit,
    onSteps: (List<Step>) -> Unit,
    onAddAt: (List<Int>) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        steps.forEachIndexed { i, step ->
            val myPath = path + i
            val isOpen = expanded == myPath

            Surface(
                color = if (isOpen) MaterialTheme.colorScheme.surfaceContainerHigh
                        else MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(
                    1.dp,
                    if (isOpen) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                    else MaterialTheme.colorScheme.outlineVariant,
                ),
            ) {
                Column {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onExpand(myPath) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .size(24.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("${i + 1}", style = MaterialTheme.typography.labelSmall)
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                step.kind.label,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                step.summary(),
                                style = MonoNumbers,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(
                            onClick = { onSteps(steps.moved(i, -1)) },
                            enabled = i > 0,
                            modifier = Modifier.size(32.dp),
                        ) { Icon(Icons.Default.ArrowUpward, "Move up", Modifier.size(16.dp)) }
                        IconButton(
                            onClick = { onSteps(steps.moved(i, 1)) },
                            enabled = i < steps.lastIndex,
                            modifier = Modifier.size(32.dp),
                        ) { Icon(Icons.Default.ArrowDownward, "Move down", Modifier.size(16.dp)) }
                    }

                    AnimatedVisibility(isOpen) {
                        Column(Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                            HorizontalDivider(Modifier.padding(bottom = 10.dp))
                            StepEditor(
                                step = step,
                                onStep = { next ->
                                    onSteps(steps.toMutableList().also { it[i] = next })
                                },
                                displays = displays,
                                myPath = myPath,
                                pick = pick,
                                onPick = onPick,
                            )

                            if (step.kind == StepKind.REPEAT) {
                                Spacer(Modifier.height(10.dp))
                                Text(
                                    "Steps inside the block",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.height(6.dp))
                                if (myPath.size >= 4) {
                                    Text(
                                        "Nesting is capped at four levels on the device.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                } else {
                                    StepList(
                                        steps = step.steps,
                                        path = myPath,
                                        displays = displays,
                                        expanded = expanded,
                                        onExpand = onExpand,
                                        pick = pick,
                                        onPick = onPick,
                                        onSteps = { inner ->
                                            onSteps(steps.toMutableList().also {
                                                it[i] = step.copy(steps = inner)
                                            })
                                        },
                                        onAddAt = onAddAt,
                                    )
                                    Spacer(Modifier.height(6.dp))
                                    OutlinedButton(onClick = { onAddAt(myPath) }) {
                                        Icon(Icons.Default.Add, null, Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Add inside block")
                                    }
                                }
                            }

                            Spacer(Modifier.height(10.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = {
                                    onSteps(steps.toMutableList().also { it.add(i + 1, step) })
                                }) {
                                    Icon(Icons.Default.ContentCopy, null, Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Duplicate")
                                }
                                TextButton(
                                    onClick = {
                                        onSteps(steps.toMutableList().also { it.removeAt(i) })
                                    },
                                    colors = ButtonDefaults.textButtonColors(
                                        contentColor = MaterialTheme.colorScheme.error,
                                    ),
                                ) {
                                    Icon(Icons.Default.Delete, null, Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Delete")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StepEditor(
    step: Step,
    onStep: (Step) -> Unit,
    displays: DisplayConfig,
    myPath: List<Int>,
    pick: PickTarget?,
    onPick: (PickTarget?) -> Unit,
) {
    @Composable
    fun PickButton(field: String, label: String) {
        val active = pick?.path == myPath && pick.field == field
        FilledTonalButton(
            onClick = { onPick(if (active) null else PickTarget(myPath, field)) },
        ) { Text(if (active) "Tap the map…" else label) }
    }

    @Composable
    fun DisplayChips() {
        if (displays.displays.size <= 1) return
        Spacer(Modifier.height(8.dp))
        Text("On display", style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        ChipPicker(
            options = displays.displays.map { it.id },
            selected = step.display.ifEmpty { displays.active },
            label = { id -> displays.byId(id)?.name?.ifBlank { id } ?: id },
            onPick = { onStep(step.copy(display = it)) },
        )
    }

    @Composable
    fun MotionControls() {
        Spacer(Modifier.height(8.dp))
        RowLabel("Travel time", "${step.dur} ms — 0 teleports") {
            Stepper(step.dur, min = 0, max = 10000, step = 50,
                onValue = { onStep(step.copy(dur = it)) })
        }
        Text("Easing", style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        ChipPicker(
            options = Easing.entries.toList(),
            selected = step.ease,
            label = { it.label },
            onPick = { onStep(step.copy(ease = it)) },
        )
        RowLabel("Hand wobble", if (step.jitter == 0) "off" else "±${step.jitter} px") {
            Stepper(step.jitter, min = 0, max = 16, onValue = { onStep(step.copy(jitter = it)) })
        }
    }

    Column {
        when (step.kind) {
            StepKind.TAP, StepKind.DOWN, StepKind.UP ->
                ChordPicker(step.keys) { onStep(step.copy(keys = it)) }

            StepKind.TEXT -> OutlinedTextField(
                value = step.text,
                onValueChange = { onStep(step.copy(text = it)) },
                label = { Text("Text") },
                supportingText = { Text("US layout, ASCII only") },
                modifier = Modifier.fillMaxWidth(),
            )

            StepKind.CONSUMER -> FlowChips(MEDIA_KEYS, step.media) {
                onStep(step.copy(media = it))
            }

            StepKind.MOVE_ABS -> Column {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField(step.x, { onStep(step.copy(x = it)) }, "X",
                        Modifier.weight(1f), min = -32767, max = 32767, suffix = "px")
                    NumberField(step.y, { onStep(step.copy(y = it)) }, "Y",
                        Modifier.weight(1f), min = -32767, max = 32767, suffix = "px")
                }
                Spacer(Modifier.height(8.dp))
                PickButton("abs", "Pick on screen")
                DisplayChips()
                MotionControls()
            }

            StepKind.MOVE_REL -> Column {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField(step.dx, { onStep(step.copy(dx = it)) }, "ΔX",
                        Modifier.weight(1f), min = -32767, max = 32767, suffix = "px")
                    NumberField(step.dy, { onStep(step.copy(dy = it)) }, "ΔY",
                        Modifier.weight(1f), min = -32767, max = 32767, suffix = "px")
                }
                MotionControls()
            }

            StepKind.MDOWN, StepKind.MUP -> ChipPicker(
                options = MOUSE_BUTTONS,
                selected = step.btn,
                label = { it },
                onPick = { onStep(step.copy(btn = it)) },
            )

            StepKind.CLICK -> Column {
                ChipPicker(
                    options = MOUSE_BUTTONS,
                    selected = step.btn,
                    label = { it },
                    onPick = { onStep(step.copy(btn = it)) },
                )
                RowLabel("Clicks", if (step.count > 1) "${step.count}× " else "single") {
                    Stepper(step.count, min = 1, max = 8, onValue = { onStep(step.copy(count = it)) })
                }
                RowLabel("Gap", "${step.gap} ms between clicks") {
                    Stepper(step.gap, min = 10, max = 800, step = 10,
                        onValue = { onStep(step.copy(gap = it)) })
                }
            }

            StepKind.DRAG -> Column {
                Text("Start", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                RowLabel("Start from a fixed point", "Off means start wherever the pointer is") {
                    Switch(
                        checked = step.from != null,
                        onCheckedChange = {
                            onStep(step.copy(from = if (it) Point(step.x, step.y) else null))
                        },
                    )
                }
                if (step.from != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NumberField(step.from.x, { onStep(step.copy(from = step.from.copy(x = it))) },
                            "From X", Modifier.weight(1f), min = -32767, max = 32767)
                        NumberField(step.from.y, { onStep(step.copy(from = step.from.copy(y = it))) },
                            "From Y", Modifier.weight(1f), min = -32767, max = 32767)
                    }
                    Spacer(Modifier.height(6.dp))
                    PickButton("from", "Pick start on screen")
                }
                Spacer(Modifier.height(12.dp))
                Text("End", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                val to = step.to ?: Point()
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField(to.x, { onStep(step.copy(to = to.copy(x = it))) },
                        "To X", Modifier.weight(1f), min = -32767, max = 32767)
                    NumberField(to.y, { onStep(step.copy(to = to.copy(y = it))) },
                        "To Y", Modifier.weight(1f), min = -32767, max = 32767)
                }
                Spacer(Modifier.height(6.dp))
                PickButton("to", "Pick end on screen")
                Spacer(Modifier.height(8.dp))
                ChipPicker(
                    options = MOUSE_BUTTONS,
                    selected = step.btn,
                    label = { it },
                    onPick = { onStep(step.copy(btn = it)) },
                )
                DisplayChips()
                MotionControls()
            }

            StepKind.SCROLL -> Column {
                RowLabel("Amount", if (step.amount >= 0) "up ${step.amount}" else "down ${-step.amount}") {
                    Stepper(step.amount, min = -100, max = 100,
                        onValue = { onStep(step.copy(amount = it)) })
                }
                RowLabel("Sideways", "${step.hamount}") {
                    Stepper(step.hamount, min = -100, max = 100,
                        onValue = { onStep(step.copy(hamount = it)) })
                }
                RowLabel("Spread over", "${step.dur} ms") {
                    Stepper(step.dur, min = 0, max = 5000, step = 50,
                        onValue = { onStep(step.copy(dur = it)) })
                }
            }

            StepKind.DELAY -> NumberField(
                step.ms, { onStep(step.copy(ms = it)) }, "Wait",
                Modifier.fillMaxWidth(), min = 0, max = 60000, suffix = "ms",
            )

            StepKind.REPEAT -> RowLabel("Iterations", "${step.count}×") {
                Stepper(step.count, min = 1, max = 100, onValue = { onStep(step.copy(count = it)) })
            }
        }

        if (step.kind != StepKind.DELAY) {
            Spacer(Modifier.height(8.dp))
            RowLabel("Then wait", if (step.after == 0) "no pause" else "${step.after} ms") {
                Stepper(step.after, min = 0, max = 10000, step = 20,
                    onValue = { onStep(step.copy(after = it)) })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddStepSheet(onDismiss: () -> Unit, onPick: (StepKind) -> Unit) {
    val groups = listOf(
        "Keyboard" to listOf(StepKind.TAP, StepKind.DOWN, StepKind.UP, StepKind.TEXT, StepKind.CONSUMER),
        "Pointer" to listOf(StepKind.MOVE_ABS, StepKind.MOVE_REL, StepKind.DRAG,
                            StepKind.CLICK, StepKind.MDOWN, StepKind.MUP, StepKind.SCROLL),
        "Timing" to listOf(StepKind.DELAY, StepKind.REPEAT),
    )
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 32.dp)) {
            Text("Add a step", style = MaterialTheme.typography.titleMedium)
            groups.forEach { (title, kinds) ->
                SectionHeader(title)
                kinds.forEach { k ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPick(k) }
                            .padding(vertical = 12.dp),
                    ) {
                        Column {
                            Text(k.label, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                stepHint(k),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun stepHint(k: StepKind): String = when (k) {
    StepKind.TAP -> "Press and release a chord"
    StepKind.DOWN -> "Hold a chord down across later steps"
    StepKind.UP -> "Release a chord held earlier"
    StepKind.TEXT -> "Type a string, one keystroke at a time"
    StepKind.CONSUMER -> "Play/pause, volume, brightness…"
    StepKind.MOVE_ABS -> "Travel to an exact pixel, over time"
    StepKind.MOVE_REL -> "Travel by an offset from wherever it is"
    StepKind.MDOWN -> "Press and keep holding a mouse button"
    StepKind.MUP -> "Release a mouse button"
    StepKind.CLICK -> "Click, double-click, triple-click"
    StepKind.DRAG -> "Press, travel, release — in one step"
    StepKind.SCROLL -> "Wheel, spread smoothly over time"
    StepKind.DELAY -> "Do nothing for a while"
    StepKind.REPEAT -> "Run a block of steps several times"
}

private fun defaultStep(kind: StepKind, displays: DisplayConfig): Step {
    val d = displays.resolve("")
    val cx = (d?.w ?: 1920) / 2
    val cy = (d?.h ?: 1080) / 2
    return when (kind) {
        StepKind.MOVE_ABS -> Step(kind = kind, x = cx, y = cy, dur = 500, display = d?.id ?: "")
        StepKind.MOVE_REL -> Step(kind = kind, dx = 120, dy = 0, dur = 300)
        StepKind.DRAG -> Step(
            kind = kind,
            from = Point(cx / 2, cy / 2),
            to = Point(cx, cy),
            dur = 700,
            display = d?.id ?: "",
        )
        StepKind.DELAY -> Step(kind = kind, ms = 250)
        StepKind.SCROLL -> Step(kind = kind, amount = 3, dur = 150)
        StepKind.REPEAT -> Step(kind = kind, count = 2)
        StepKind.CONSUMER -> Step(kind = kind, media = "play_pause")
        else -> Step(kind = kind)
    }
}

// ------------------------------------------------------------------ helpers

private fun <T> List<T>.moved(from: Int, delta: Int): List<T> {
    val to = (from + delta).coerceIn(0, lastIndex)
    if (to == from) return this
    return toMutableList().also { it.add(to, it.removeAt(from)) }
}

/** Walk to a nested step by index chain. */
private fun Macro.stepAt(path: List<Int>): Step? {
    var cur: Step? = null
    var list = steps
    path.forEach { i ->
        cur = list.getOrNull(i) ?: return null
        list = cur!!.steps
    }
    return cur
}

/** Rewrite a nested step in place, rebuilding the chain above it. */
private fun Macro.updateAt(path: List<Int>, f: (Step) -> Step): Macro =
    copy(steps = steps.updateAt(path, f))

private fun List<Step>.updateAt(path: List<Int>, f: (Step) -> Step): List<Step> {
    if (path.isEmpty()) return this
    val i = path.first()
    if (i !in indices) return this
    val step = this[i]
    val next = if (path.size == 1) f(step) else step.copy(steps = step.steps.updateAt(path.drop(1), f))
    return toMutableList().also { it[i] = next }
}

/** Absolute waypoints in execution order, for drawing the route. */
private fun Macro.routeMarks(): List<CanvasMark> {
    val out = mutableListOf<CanvasMark>()
    var n = 1
    fun walk(list: List<Step>) {
        list.forEach { s ->
            when (s.kind) {
                StepKind.MOVE_ABS -> out.add(CanvasMark(s.display, s.x, s.y, "${n++}"))
                StepKind.DRAG -> {
                    s.from?.let { out.add(CanvasMark(s.display, it.x, it.y, "${n++}")) }
                    s.to?.let { out.add(CanvasMark(s.display, it.x, it.y, "${n++}", highlight = true)) }
                }
                StepKind.REPEAT -> walk(s.steps)
                else -> {}
            }
        }
    }
    walk(steps)
    return out
}
