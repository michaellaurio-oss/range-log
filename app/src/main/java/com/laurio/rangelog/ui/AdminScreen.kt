@file:OptIn(ExperimentalMaterial3Api::class)

package com.laurio.rangelog.ui

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.LocalDate

/** Undo history, cloud sync, photo import and backup: the housekeeping that used to sit under Stats. */
@Composable
fun AdminScreen(vm: MainViewModel) {
    val sync by vm.syncStatus.collectAsStateWithLifecycle()
    val undoItems by vm.undoItems.collectAsStateWithLifecycle()
    val activity = LocalContext.current as? Activity
    var confirmImport by remember { mutableStateOf(false) }

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) vm.exportTo(uri)
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importFrom(uri)
    }
    val photoImporter = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.importPhotos(uris)
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = { TopAppBar(title = { Text("Admin") }) },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Undo and sync need a Google sign-in, which only the personal build has.
            if (com.laurio.rangelog.data.CloudSync.ENABLED) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Undo", style = MaterialTheme.typography.titleMedium)
                        if (undoItems.isEmpty()) {
                            Text(
                                if (sync.email == null) "Sign in with Google below to use undo. It's shared with the web page."
                                else "Nothing to undo yet. The last ${com.laurio.rangelog.data.CloudSync.UNDO_MAX} changes " +
                                    "from this phone and the web page are kept here, pictures included.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        } else {
                            val fmt = remember { java.time.format.DateTimeFormatter.ofPattern("MMM d, h:mm a") }
                            val undoable by vm.undoableIds.collectAsStateWithLifecycle()
                            undoItems.forEach { item ->
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        "${item.label} (${item.device}) · " + java.time.Instant.ofEpochMilli(item.time)
                                            .atZone(java.time.ZoneId.systemDefault()).format(fmt),
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.weight(1f),
                                    )
                                    // A step whose items were edited again since can't be undone safely.
                                    if (item.id in undoable) {
                                        OutlinedButton(onClick = { vm.undo(entryId = item.id) }) { Text("Undo") }
                                    } else {
                                        Text("edited since", style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                    }
                }

                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Sync", style = MaterialTheme.typography.titleMedium)
                        if (sync.email == null) {
                            Text(
                                "Sign in with Google to keep this log in step with your desktop. It works offline " +
                                    "and catches up when you're connected. Photos stay on this phone.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            OutlinedButton(onClick = { activity?.let(vm::signIn) }) { Text("Sign in with Google") }
                        } else {
                            Text("${sync.email}", style = MaterialTheme.typography.bodyMedium)
                            Text(sync.text, style = MaterialTheme.typography.bodySmall)
                            if (sync.needsChoice) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(onClick = vm::useThisPhone) { Text("Keep this phone's data") }
                                    OutlinedButton(onClick = vm::useCloud) { Text("Use cloud data") }
                                }
                            }
                            OutlinedButton(onClick = vm::signOut) { Text("Sign out") }
                        }
                    }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Import photos", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Pick photos named YYYYMMDD## (for example 2024121001.JPG). Each one is added to the " +
                            "session on that date.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(onClick = { photoImporter.launch(arrayOf("image/jpeg")) }) { Text("Choose photos") }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Backup", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Save everything, pictures included, to a zip file (for example to Google Drive) so you " +
                            "don't lose your log if you change phones. Restore also accepts older .json backups.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            exporter.launch("RangeLog-backup-${LocalDate.now()}.zip")
                        }) { Text("Export") }
                        OutlinedButton(onClick = { confirmImport = true }) { Text("Restore") }
                    }
                }
            }

            Text(
                "${androidx.compose.ui.res.stringResource(com.laurio.rangelog.R.string.app_name)} " +
                    "version ${com.laurio.rangelog.BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
    }

    if (confirmImport) {
        AlertDialog(
            onDismissRequest = { confirmImport = false },
            title = { Text("Restore from backup?") },
            text = { Text("This replaces everything currently in the app with the contents of the backup file.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmImport = false
                    importer.launch(
                        arrayOf("application/zip", "application/x-zip-compressed", "application/json", "text/plain", "application/octet-stream")
                    )
                }) { Text("Choose file") }
            },
            dismissButton = { TextButton(onClick = { confirmImport = false }) { Text("Cancel") } },
        )
    }
}
