package org.qinda.deck.ui

import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.qinda.deck.data.DeckRepository
import java.io.File

/**
 * The SD card, over Wi-Fi.
 *
 * The card can belong to exactly one side at a time, so entering this screen
 * takes it: the computer sees its QindaDeck drive eject, and it comes back the
 * moment you leave. One idle minute on the deck does the same thing, so a
 * forgotten screen cannot strand the host's drive.
 */
@Composable
fun SdScreen(repo: DeckRepository, nav: Navigator) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var phase by remember { mutableStateOf("taking") }   // taking | ready | failed
    var failReason by remember { mutableStateOf("") }
    var path by remember { mutableStateOf("/") }
    var entries by remember { mutableStateOf<List<DeckRepository.SdEntry>>(emptyList()) }
    var truncated by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf<DeckRepository.SdEntry?>(null) }
    var newFolder by remember { mutableStateOf(false) }

    fun refresh() {
        scope.launch {
            runCatching {
                val (list, tr) = repo.sdList(path)
                entries = list.sortedWith(compareBy({ !it.dir }, { it.name.lowercase() }))
                truncated = tr
            }.onFailure {
                failReason = it.message ?: "list failed"
            }
        }
    }

    // Take the card on entry, release it on exit — no matter how we leave.
    LaunchedEffect(Unit) {
        runCatching {
            val hasFs = repo.sdTake()
            if (hasFs) {
                phase = "ready"
                refresh()
            } else {
                failReason = "The card has no filesystem the deck can read " +
                    "(FAT/exFAT only). It is back on the PC now."
                phase = "failed"
                runCatching { repo.sdRelease() }
            }
        }.onFailure {
            failReason = it.message ?: "no SD card"
            phase = "failed"
        }
    }
    DisposableEffect(Unit) {
        onDispose { repo.sdReleaseAsync() }
    }

    fun child(entry: DeckRepository.SdEntry): String =
        if (path == "/") "/${entry.name}" else "$path/${entry.name}"

    suspend fun download(entry: DeckRepository.SdEntry) {
        busy = "Downloading ${entry.name}…"
        try {
            val resolver = context.contentResolver
            val out = if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, entry.name)
                    put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: error("could not create the download")
                val stream = resolver.openOutputStream(uri) ?: error("could not open the download")
                Pair(stream, Triple(resolver, uri, values))
            } else {
                val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                    ?: error("no download directory")
                Pair(File(dir, entry.name).outputStream(), null)
            }
            out.first.use { stream ->
                var offset = 0L
                while (true) {
                    val chunk = repo.sdRead(child(entry), offset)
                    stream.write(chunk.data)
                    offset += chunk.data.size
                    if (chunk.eof) break
                }
            }
            out.second?.let { (resolver2, uri, values) ->
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver2.update(uri, values, null, null)
                repo.say("Saved to Downloads/${entry.name}")
            } ?: repo.say("Saved to app files/${entry.name}")
        } catch (e: Exception) {
            repo.say("Download failed: ${e.message}")
        } finally {
            busy = null
        }
    }

    val uploadLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val name = uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':')
                ?: "upload.bin"
            busy = "Uploading $name…"
            try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        val buf = ByteArray(24 * 1024)
                        var offset = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            repo.sdWrite(
                                if (path == "/") "/$name" else "$path/$name",
                                offset, buf.copyOf(n),
                            )
                            offset += n
                        }
                    }
                }
                repo.say("Uploaded $name")
                refresh()
            } catch (e: Exception) {
                repo.say("Upload failed: ${e.message}")
            } finally {
                busy = null
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        // The ejection contract, stated where it cannot be missed.
        Surface(
            color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.15f),
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.4f)),
        ) {
            Text(
                "The PC's QindaDeck drive is ejected while you browse. " +
                    "Leaving this screen gives it back.",
                Modifier.fillMaxWidth().padding(10.dp),
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Spacer(Modifier.height(10.dp))

        when (phase) {
            "taking" -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            "failed" -> Panel("SD card") {
                Text(
                    failReason,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> {
                // --- toolbar -------------------------------------------------
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = {
                            path = path.trimEnd('/').substringBeforeLast('/').ifEmpty { "/" }
                            refresh()
                        },
                        enabled = path != "/",
                    ) { Icon(Icons.Default.ArrowUpward, "Up") }
                    Text(
                        path,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { newFolder = true }) {
                        Icon(Icons.Default.CreateNewFolder, "New folder")
                    }
                    IconButton(onClick = { uploadLauncher.launch(arrayOf("*/*")) }) {
                        Icon(Icons.Default.Upload, "Upload here")
                    }
                    IconButton(onClick = { refresh() }) {
                        Icon(Icons.Default.Refresh, "Refresh")
                    }
                }

                busy?.let {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                }

                if (truncated) {
                    Text(
                        "This folder has many entries — showing the first 256.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(entries, key = { it.name }) { entry ->
                        SdRow(
                            entry = entry,
                            onClick = {
                                if (entry.dir) {
                                    path = child(entry)
                                    refresh()
                                } else {
                                    scope.launch { download(entry) }
                                }
                            },
                            onLongClick = {
                                if (!entry.dir) confirmDelete = entry
                            },
                        )
                    }
                    if (entries.isEmpty()) {
                        item {
                            Text(
                                "Empty folder. The upload button puts files here.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(12.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    confirmDelete?.let { entry ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete ${entry.name}?") },
            text = { Text("This deletes the file on the SD card itself.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = null
                    scope.launch {
                        runCatching { repo.sdDelete(child(entry)) }
                            .onSuccess { refresh() }
                            .onFailure { repo.say("Delete failed: ${it.message}") }
                    }
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text("Cancel") }
            },
        )
    }

    if (newFolder) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { newFolder = false },
            title = { Text("New folder in $path") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.filter { c -> c != '/' && c != '\\' } },
                    singleLine = true,
                    label = { Text("Name") },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    newFolder = false
                    if (name.isNotBlank()) {
                        scope.launch {
                            runCatching {
                                repo.sdMkdir(if (path == "/") "/$name" else "$path/$name")
                            }.onSuccess { refresh() }
                                .onFailure { repo.say("mkdir failed: ${it.message}") }
                        }
                    }
                }) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { newFolder = false }) { Text("Cancel") }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SdRow(
    entry: DeckRepository.SdEntry,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (entry.dir) Icons.Default.Folder
                else Icons.AutoMirrored.Filled.InsertDriveFile,
                contentDescription = null,
                tint = if (entry.dir) MaterialTheme.colorScheme.primary
                       else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                entry.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (!entry.dir) {
                Text(
                    formatSize(entry.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes.toDouble() / (1L shl 30))
    bytes >= 1L shl 20 -> "%.1f MB".format(bytes.toDouble() / (1L shl 20))
    bytes >= 1L shl 10 -> "%.1f KB".format(bytes.toDouble() / (1L shl 10))
    else -> "$bytes B"
}
