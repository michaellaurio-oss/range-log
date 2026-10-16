@file:OptIn(ExperimentalMaterial3Api::class)

package com.laurio.rangelog.ui

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.LocalDate

@Composable
fun StatsScreen(vm: MainViewModel) {
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val summaries by vm.gunSummaries.collectAsStateWithLifecycle()
    val sync by vm.syncStatus.collectAsStateWithLifecycle()
    val undoItems by vm.undoItems.collectAsStateWithLifecycle()
    val activity = LocalContext.current as? Activity
    var confirmImport by remember { mutableStateOf(false) }

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) vm.exportTo(uri)
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importFrom(uri)
    }

    val photoImporter = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.importPhotos(uris)
    }

    val thisYear = LocalDate.now().year
    val total = sessions.sumOf { it.totalRounds }
    val yearSessions = sessions.filter { LocalDate.ofEpochDay(it.session.date).year == thisYear }
    val byYear = sessions.groupBy { LocalDate.ofEpochDay(it.session.date).year }
        .map { (y, list) -> y.toString() to list.sumOf { it.totalRounds } }
        .sortedBy { it.first }
    val byGun = summaries.filter { it.totalRounds > 0 }
        .sortedByDescending { it.totalRounds }
        .map { it.gun.name to it.totalRounds }
    val counted = sessions.filter { it.totalRounds > 0 }
    val avg = if (counted.isEmpty()) 0 else counted.sumOf { it.totalRounds } / counted.size

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = { TopAppBar(title = { Text("Stats") }) },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile("Rounds, all time", total.withCommas(), Modifier.weight(1f))
                StatTile("Sessions", sessions.size.toString(), Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile("Rounds in $thisYear", yearSessions.sumOf { it.totalRounds }.withCommas(), Modifier.weight(1f))
                StatTile("Avg rounds / session", avg.toString(), Modifier.weight(1f))
            }

            BarSection("Rounds by year", byYear)
            BarSection("Rounds by gun", byGun)

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
                        undoItems.forEach { item ->
                            Text(
                                "${item.label} (${item.device}) · " + java.time.Instant.ofEpochMilli(item.time)
                                    .atZone(java.time.ZoneId.systemDefault()).format(fmt),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        OutlinedButton(onClick = vm::undo) { Text("Undo: ${undoItems.first().label}") }
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
                        "Save everything to a file (for example to Google Drive) so you don't lose your log " +
                            "if you change phones. Photos aren't included in the backup file.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            exporter.launch("RangeLog-backup-${LocalDate.now()}.json")
                        }) { Text("Export") }
                        OutlinedButton(onClick = { confirmImport = true }) { Text("Restore") }
                    }
                }
            }
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
                    importer.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
                }) { Text("Choose file") }
            },
            dismissButton = { TextButton(onClick = { confirmImport = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(Modifier.padding(14.dp)) {
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun BarSection(title: String, rows: List<Pair<String, Int>>) {
    if (rows.isEmpty()) return
    val max = rows.maxOf { it.second }.coerceAtLeast(1)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            rows.forEach { (label, value) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        label,
                        modifier = Modifier.width(72.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Box(Modifier.weight(1f).height(18.dp)) {
                        Box(
                            Modifier
                                .fillMaxWidth(value.toFloat() / max)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(4.dp))
                                .background(MaterialTheme.colorScheme.primary),
                        )
                    }
                    Text(
                        value.withCommas(),
                        modifier = Modifier.width(64.dp).padding(start = 8.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}
