package org.qinda.deck.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Monitor
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.qinda.deck.data.DeckButton
import org.qinda.deck.data.DeckPage
import org.qinda.deck.data.DeckRepository
import org.qinda.deck.data.Profile

@Composable
fun ProfilesScreen(repo: DeckRepository, nav: Navigator) {
    val summaries by repo.profiles.collectAsState()
    val scope = rememberCoroutineScope()
    var creating by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    ScreenBody {
        SectionHeader("Profiles on the deck")
        Panel {
            if (summaries.isEmpty()) {
                EmptyNote("No profiles. That is unusual — the firmware ships seven.")
            }
            summaries.forEachIndexed { i, s ->
                if (i > 0) HorizontalDivider()
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { nav.push(Screen.ProfileEditor(s.slug)) }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(10.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(parseHex(s.accent, MaterialTheme.colorScheme.primary)),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(s.name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "${s.pages} page(s)${if (s.builtin) " · built-in" else ""}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(
                        Icons.Default.ChevronRight, null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        OutlinedButton(
            onClick = { creating = true; newName = "" },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Default.Add, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("New profile")
        }

        Spacer(Modifier.height(10.dp))
        OutlinedButton(
            onClick = { nav.push(Screen.Displays) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Default.Monitor, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Display layout")
        }

        Panel("Editing") {
            Text(
                "Long-press any key on the Deck screen to edit it directly. Built-in " +
                    "profiles can be edited too — a factory reset restores them.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        error?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall)
        }
    }

    if (creating) {
        AlertDialog(
            onDismissRequest = { creating = false },
            title = { Text("New profile") },
            text = {
                Column {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text("Name") },
                        singleLine = true,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Starts as one empty 4×4 page.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = newName.isNotBlank(),
                    onClick = {
                        val slug = slugify(newName)
                        val p = Profile(
                            slug = slug,
                            name = newName.trim(),
                            accent = "#4F8DF5",
                            pages = listOf(DeckPage(name = "Page 1", cols = 4, rows = 4)),
                        )
                        creating = false
                        scope.launch {
                            try {
                                repo.saveProfile(p)
                                nav.push(Screen.ProfileEditor(slug))
                            } catch (e: Exception) {
                                error = e.message
                            }
                        }
                    },
                ) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { creating = false }) { Text("Cancel") } },
        )
    }
}

/** Slugs must match `^[a-z0-9][a-z0-9_-]{0,31}$`, which the firmware enforces. */
fun slugify(name: String): String {
    val base = name.lowercase()
        .map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '-' }
        .joinToString("")
        .trim('-')
        .ifEmpty { "profile" }
        .take(31)
    return if (base.first().isLetterOrDigit()) base else "p$base".take(32)
}
