package org.qinda.deck.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.qinda.deck.data.ActionKind
import org.qinda.deck.data.DeckButton
import org.qinda.deck.data.DeckRepository
import org.qinda.deck.data.Profile

/**
 * The deck itself.
 *
 * A press sends down and up as separate events rather than a single tap, because
 * `hold` bindings and hold-to-repeat need a real edge pair — a chord like
 * Meta+Shift is useless if it is released a millisecond after being pressed.
 */
@Composable
fun DeckScreen(repo: DeckRepository, nav: Navigator) {
    val status by repo.status.collectAsState()
    val summaries by repo.profiles.collectAsState()
    val loaded by repo.loaded.collectAsState()
    val scope = rememberCoroutineScope()

    var error by remember { mutableStateOf<String?>(null) }
    val slug = status.active
    val profile: Profile? = loaded[slug]

    LaunchedEffect(slug) {
        if (slug.isNotEmpty()) {
            runCatching { repo.profile(slug) }.onFailure { error = it.message }
        }
    }

    Column(Modifier.fillMaxSize()) {
        // Profile switcher
        if (summaries.isNotEmpty()) {
            ScrollableTabRow(
                selectedTabIndex = summaries.indexOfFirst { it.slug == slug }.coerceAtLeast(0),
                edgePadding = 12.dp,
            ) {
                summaries.forEach { s ->
                    Tab(
                        selected = s.slug == slug,
                        onClick = {
                            scope.launch { runCatching { repo.setActive(s.slug, 0) } }
                        },
                        text = { Text(s.name, maxLines = 1) },
                    )
                }
            }
        }

        val pages = profile?.pages ?: emptyList()
        if (pages.size > 1) {
            val page = status.page.coerceIn(0, pages.lastIndex)
            SecondaryScrollableTabRow(selectedTabIndex = page, edgePadding = 12.dp) {
                pages.forEachIndexed { i, pg ->
                    Tab(
                        selected = i == page,
                        onClick = { scope.launch { runCatching { repo.setActive(slug, i) } } },
                        text = { Text(pg.name.ifBlank { "Page ${i + 1}" }, maxLines = 1) },
                    )
                }
            }
        }

        if (!status.usb) {
            Surface(color = MaterialTheme.colorScheme.errorContainer) {
                Text(
                    "The deck is not plugged into a computer. Connect its native USB " +
                        "port (not the UART one) to send any input.",
                    Modifier.fillMaxWidth().padding(12.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        error?.let {
            Surface(color = MaterialTheme.colorScheme.errorContainer) {
                Text(
                    it, Modifier.fillMaxWidth().padding(12.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        if (profile == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Column
        }

        val page = pages.getOrNull(status.page.coerceIn(0, pages.lastIndex.coerceAtLeast(0)))
        if (page == null) {
            EmptyNote("This profile has no pages.")
            return@Column
        }

        val accent = parseHex(profile.accent, MaterialTheme.colorScheme.primary)

        // A fixed grid, sized to fill: a deck you have to scroll is a deck you
        // mis-hit, so the cells shrink instead.
        Column(
            Modifier
                .fillMaxSize()
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (row in 0 until page.rows) {
                Row(
                    Modifier.fillMaxWidth().weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (col in 0 until page.cols) {
                        val index = row * page.cols + col
                        DeckKey(
                            button = page.buttonAt(index),
                            accent = accent,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            onDown = {
                                scope.launch {
                                    runCatching { repo.press(slug, status.page, index, "down") }
                                        .onFailure { error = it.message }
                                }
                            },
                            onUp = {
                                scope.launch {
                                    runCatching { repo.press(slug, status.page, index, "up") }
                                }
                            },
                            onLongPress = {
                                nav.push(Screen.ButtonEditor(slug, status.page, index))
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DeckKey(
    button: DeckButton?,
    accent: Color,
    modifier: Modifier,
    onDown: () -> Unit,
    onUp: () -> Unit,
    onLongPress: () -> Unit,
) {
    var pressed by remember { mutableStateOf(false) }
    val empty = button == null || button.action.kind == ActionKind.NOOP

    val base = when {
        button == null -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        button.color.isNotEmpty() -> parseHex(button.color, MaterialTheme.colorScheme.surface)
        else -> accent.copy(alpha = 0.22f)
    }

    Surface(
        color = if (pressed) base.copy(alpha = 1f).compositeLighten() else base,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(
            1.dp,
            if (pressed) accent else MaterialTheme.colorScheme.outlineVariant,
        ),
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .pointerInput(button, empty) {
                detectTapGestures(
                    onLongPress = { onLongPress() },
                    onPress = {
                        if (empty) {
                            // Nothing to fire, but long-press still edits it.
                            tryAwaitRelease()
                            return@detectTapGestures
                        }
                        pressed = true
                        onDown()
                        tryAwaitRelease()
                        pressed = false
                        onUp()
                    },
                )
            },
    ) {
        Box(Modifier.padding(6.dp), contentAlignment = Alignment.Center) {
            if (button == null) {
                Text(
                    "+",
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    fontSize = 20.sp,
                )
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        button.label.ifBlank { "—" },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (button.sub.isNotEmpty()) {
                        Text(
                            button.sub,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.alpha(0.9f),
                        )
                    }
                }
            }
        }
    }
}

/** Lift a colour slightly, to show a press without needing a second palette. */
private fun Color.compositeLighten(): Color =
    Color(
        red = (red + 0.14f).coerceAtMost(1f),
        green = (green + 0.14f).coerceAtMost(1f),
        blue = (blue + 0.14f).coerceAtMost(1f),
        alpha = alpha,
    )
