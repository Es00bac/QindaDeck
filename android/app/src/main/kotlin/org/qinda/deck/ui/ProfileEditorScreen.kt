package org.qinda.deck.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.qinda.deck.data.*

/**
 * Structural edits write straight through to the deck.
 *
 * There is no local "unsaved profile" state on purpose: the device is the only
 * copy that matters, it validates every change, and a Save button that could fail
 * after ten edits would make the failure impossible to attribute. Each change is
 * one `put_profile`, which round-trips in about 20 ms.
 */
@Composable
fun ProfileEditorScreen(repo: DeckRepository, nav: Navigator, slug: String) {
    val loaded by repo.loaded.collectAsState()
    val summaries by repo.profiles.collectAsState()
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var pageIndex by remember { mutableIntStateOf(0) }

    LaunchedEffect(slug) {
        runCatching { repo.profile(slug) }.onFailure { error = it.message }
    }

    val profile = loaded[slug]
    var nameDraft by remember(profile?.slug) { mutableStateOf(profile?.name ?: "") }

    fun save(next: Profile) {
        busy = true
        error = null
        scope.launch {
            try {
                repo.saveProfile(next)
            } catch (e: Exception) {
                error = e.message
            } finally {
                busy = false
            }
        }
    }

    if (profile == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (error == null) CircularProgressIndicator() else Text(error!!)
        }
        return
    }

    val page = profile.pages.getOrNull(pageIndex.coerceIn(0, profile.pages.lastIndex))
    val accent = parseHex(profile.accent, MaterialTheme.colorScheme.primary)

    ScreenBody {
        error?.let {
            Spacer(Modifier.height(10.dp))
            Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(8.dp)) {
                Text(it, Modifier.padding(10.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodySmall)
            }
        }

        Panel("Profile") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = nameDraft,
                    onValueChange = { nameDraft = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                if (nameDraft != profile.name && nameDraft.isNotBlank()) {
                    IconButton(onClick = { save(profile.copy(name = nameDraft.trim())) }) {
                        Icon(Icons.Default.Check, "Apply name",
                            tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Text("Accent", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            ColorPicker(profile.accent) { save(profile.copy(accent = it.ifEmpty { "#4F8DF5" })) }
            Spacer(Modifier.height(6.dp))
            Text(
                "slug: ${profile.slug}${if (profile.builtin) "  ·  built-in" else ""}",
                style = MonoNumbers,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // --- pages -------------------------------------------------------
        SectionHeader("Pages")
        ScrollableTabRow(
            selectedTabIndex = pageIndex.coerceIn(0, profile.pages.lastIndex.coerceAtLeast(0)),
            edgePadding = 0.dp,
        ) {
            profile.pages.forEachIndexed { i, pg ->
                Tab(
                    selected = i == pageIndex,
                    onClick = { pageIndex = i },
                    text = { Text(pg.name.ifBlank { "Page ${i + 1}" }, maxLines = 1) },
                )
            }
            Tab(
                selected = false,
                onClick = {
                    val added = profile.pages + DeckPage(
                        name = "Page ${profile.pages.size + 1}", cols = 4, rows = 4,
                    )
                    pageIndex = added.lastIndex
                    save(profile.copy(pages = added))
                },
                text = { Icon(Icons.Default.Add, "Add page") },
            )
        }

        if (page == null) {
            EmptyNote("This profile has no pages.")
            return@ScreenBody
        }

        Spacer(Modifier.height(10.dp))
        Panel {
            var pageName by remember(page.name, pageIndex) { mutableStateOf(page.name) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = pageName,
                    onValueChange = { pageName = it },
                    label = { Text("Page name") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                if (pageName != page.name) {
                    IconButton(onClick = {
                        save(profile.replacePage(pageIndex, page.copy(name = pageName)))
                    }) { Icon(Icons.Default.Check, "Apply", tint = MaterialTheme.colorScheme.primary) }
                }
            }
            Spacer(Modifier.height(4.dp))
            RowLabel("Columns") {
                Stepper(page.cols, min = 1, max = 8, onValue = {
                    save(profile.replacePage(pageIndex, page.resize(it, page.rows)))
                })
            }
            RowLabel("Rows") {
                Stepper(page.rows, min = 1, max = 8, onValue = {
                    save(profile.replacePage(pageIndex, page.resize(page.cols, it)))
                })
            }
            if (profile.pages.size > 1) {
                Spacer(Modifier.height(6.dp))
                TextButton(
                    onClick = {
                        val next = profile.pages.toMutableList().also { it.removeAt(pageIndex) }
                        pageIndex = 0
                        save(profile.copy(pages = next))
                    },
                ) {
                    Icon(Icons.Default.Delete, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Delete this page")
                }
            }
        }

        // --- grid --------------------------------------------------------
        SectionHeader("Keys — tap to edit")
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (row in 0 until page.rows) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (col in 0 until page.cols) {
                        val index = row * page.cols + col
                        val b = page.buttonAt(index)
                        Surface(
                            color = when {
                                b == null -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                                b.color.isNotEmpty() -> parseHex(b.color, MaterialTheme.colorScheme.surface)
                                else -> accent.copy(alpha = 0.2f)
                            },
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier
                                .weight(1f)
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    nav.push(Screen.ButtonEditor(slug, pageIndex, index))
                                },
                        ) {
                            Box(Modifier.padding(3.dp), contentAlignment = Alignment.Center) {
                                Text(
                                    b?.label?.ifBlank { "·" } ?: "+",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Medium,
                                    textAlign = TextAlign.Center,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }

        // --- saved macros ------------------------------------------------
        if (profile.macros.isNotEmpty()) {
            Panel("Saved macros") {
                profile.macros.forEach { (name, m) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(name, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "${m.steps.size} step(s) · ${m.durationMs()} ms",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = {
                            scope.launch { runCatching { repo.runMacro(m) }
                                .onFailure { error = it.message } }
                        }) { Text("Test") }
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        OutlinedButton(
            onClick = { confirmDelete = true },
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.error,
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Default.Delete, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Delete profile")
        }

        if (busy) {
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${profile.name}?") },
            text = {
                Text(
                    if (profile.builtin)
                        "This is a built-in profile. Deleting it removes it from the deck; " +
                            "a factory reset in Settings brings it back."
                    else "This cannot be undone.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        try {
                            repo.deleteProfile(slug)
                            nav.pop()
                        } catch (e: Exception) {
                            error = e.message
                        }
                    }
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Keep") }
            },
        )
    }
}

private fun Profile.replacePage(index: Int, page: DeckPage): Profile =
    copy(pages = pages.toMutableList().also { it[index] = page })

/**
 * Resizing a grid re-flows nothing: indices are row-major, so changing the column
 * count would silently move every key. Buttons that fall outside the new grid are
 * dropped, which is visible, rather than scattered, which is not.
 */
private fun DeckPage.resize(newCols: Int, newRows: Int): DeckPage =
    copy(
        cols = newCols,
        rows = newRows,
        buttons = buttons.filter { it.index < newCols * newRows },
    )
