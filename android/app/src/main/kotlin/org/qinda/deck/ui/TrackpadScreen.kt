package org.qinda.deck.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SwipeVertical
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt
import org.qinda.deck.data.DeckRepository
import org.qinda.deck.net.MouseButton

/**
 * A touchpad for the deck's relative mouse.
 *
 * Motion is sent as raw binary frames, unacknowledged, at whatever rate the touch
 * stream produces. Sub-pixel remainders are carried between samples rather than
 * truncated, because rounding every frame towards zero makes slow drags stall
 * completely.
 */
@Composable
fun TrackpadScreen(repo: DeckRepository) {
    val status by repo.status.collectAsState()
    val client = repo.client

    var sensitivity by remember { mutableFloatStateOf(1.6f) }
    var naturalScroll by remember { mutableStateOf(true) }
    var heldButtons by remember { mutableIntStateOf(0) }
    var lastMove by remember { mutableStateOf("idle") }

    val density = LocalDensity.current

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        if (!status.usb) {
            Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(10.dp)) {
                Text(
                    "The deck's native USB port is not connected to a computer, so " +
                        "nothing will move yet.",
                    Modifier.fillMaxWidth().padding(10.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.height(10.dp))
        }

        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            // --- the pad -------------------------------------------------
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.weight(1f).fillMaxHeight(),
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(14.dp))
                        .pointerInput(sensitivity) {
                            var remX = 0f
                            var remY = 0f
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                remX = 0f; remY = 0f
                                var moved = 0f
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull() ?: break
                                    if (!change.pressed) break
                                    val d = change.positionChange()
                                    // Work in dp so the same swipe travels the same
                                    // distance on a dense screen as on a coarse one.
                                    val dxDp = with(density) { d.x.toDp().value }
                                    val dyDp = with(density) { d.y.toDp().value }
                                    remX += dxDp * sensitivity
                                    remY += dyDp * sensitivity
                                    val sx = remX.roundToInt()
                                    val sy = remY.roundToInt()
                                    if (sx != 0 || sy != 0) {
                                        remX -= sx
                                        remY -= sy
                                        client.mouseRel(sx, sy, buttons = heldButtons)
                                        moved += abs(sx) + abs(sy)
                                        lastMove = "$sx, $sy"
                                    }
                                    change.consume()
                                }
                                if (moved < 3f) {
                                    // A stationary touch is a click, which is what a
                                    // laptop trackpad does and the thumb expects.
                                    client.mouseRel(0, 0, buttons = heldButtons or MouseButton.LEFT)
                                    client.mouseRel(0, 0, buttons = heldButtons)
                                }
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "Trackpad",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "drag to move · tap to click",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(lastMove, style = MonoNumbers, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }

            // --- scroll strip -------------------------------------------
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.width(56.dp).fillMaxHeight(),
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(naturalScroll) {
                            var acc = 0f
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                acc = 0f
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull() ?: break
                                    if (!change.pressed) break
                                    val dy = with(density) { change.positionChange().y.toDp().value }
                                    // One wheel click per ~12 dp of travel.
                                    acc += if (naturalScroll) dy / 12f else -dy / 12f
                                    val clicks = acc.roundToInt()
                                    if (clicks != 0) {
                                        acc -= clicks
                                        client.mouseRel(0, 0, wheel = clicks, buttons = heldButtons)
                                    }
                                    change.consume()
                                }
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.SwipeVertical,
                        "scroll",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // --- buttons ----------------------------------------------------
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MouseKey("Left", MouseButton.LEFT, heldButtons, client, Modifier.weight(2f)) {
                heldButtons = it
            }
            MouseKey("Middle", MouseButton.MIDDLE, heldButtons, client, Modifier.weight(1f)) {
                heldButtons = it
            }
            MouseKey("Right", MouseButton.RIGHT, heldButtons, client, Modifier.weight(2f)) {
                heldButtons = it
            }
        }

        Spacer(Modifier.height(10.dp))
        Panel("Pad") {
            RowLabel("Sensitivity", "%.1f×".format(sensitivity)) {}
            Slider(
                value = sensitivity,
                onValueChange = { sensitivity = it },
                valueRange = 0.4f..4f,
            )
            RowLabel("Natural scrolling", "Content follows your finger") {
                Switch(checked = naturalScroll, onCheckedChange = { naturalScroll = it })
            }
            if (heldButtons != 0) {
                Spacer(Modifier.height(6.dp))
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "A button is being held — drag the pad to drag on the host.",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = {
                            heldButtons = 0
                            client.mouseRel(0, 0, buttons = 0)
                        }) { Text("Release") }
                    }
                }
            }
        }
    }
}

/**
 * Tap to click, long-press to latch.
 *
 * Latching is what makes click-and-drag possible one-handed: hold the button
 * here, then drag on the pad.
 */
@Composable
private fun MouseKey(
    label: String,
    mask: Int,
    held: Int,
    client: org.qinda.deck.net.DeckClient,
    modifier: Modifier,
    onHeld: (Int) -> Unit,
) {
    val latched = held and mask != 0
    Surface(
        color = if (latched) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(10.dp),
        modifier = modifier
            .height(52.dp)
            .clip(RoundedCornerShape(10.dp))
            .pointerInput(latched, held) {
                detectTapGestures(
                    onLongPress = {
                        val next = if (latched) held and mask.inv() else held or mask
                        onHeld(next)
                        client.mouseRel(0, 0, buttons = next)
                    },
                    onTap = {
                        if (latched) {
                            val next = held and mask.inv()
                            onHeld(next)
                            client.mouseRel(0, 0, buttons = next)
                        } else {
                            client.mouseRel(0, 0, buttons = held or mask)
                            client.mouseRel(0, 0, buttons = held)
                        }
                    },
                )
            },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    label,
                    fontWeight = FontWeight.Medium,
                    color = if (latched) MaterialTheme.colorScheme.onPrimary else Color.Unspecified,
                )
                if (latched) {
                    Text(
                        "held",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
        }
    }
}
