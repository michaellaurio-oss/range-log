@file:OptIn(ExperimentalMaterial3Api::class)

package com.laurio.rangelog.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import com.laurio.rangelog.RangeLogApp
import com.laurio.rangelog.data.GunSummary

@Composable
fun GunsScreen(vm: MainViewModel, onOpen: (Long) -> Unit) {
    val summaries by vm.gunSummaries.collectAsStateWithLifecycle()
    var showRetired by rememberSaveable { mutableStateOf(false) }
    val active = summaries.filter { it.gun.active && !it.gun.deleted }
    val retired = summaries.filter { !it.gun.active && !it.gun.deleted }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = { TopAppBar(title = { Text("Guns") }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { onOpen(0L) },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Add gun") },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(active, key = { it.gun.id }) { s ->
                GunCard(s, onClick = { onOpen(s.gun.id) }, onCleaned = { vm.markCleanedToday(s.gun.id) })
            }
            if (retired.isNotEmpty()) {
                item {
                    TextButton(onClick = { showRetired = !showRetired }) {
                        Text(if (showRetired) "Hide archived guns" else "Show archived guns (${retired.size})")
                    }
                }
                if (showRetired) {
                    items(retired, key = { it.gun.id }) { s ->
                        GunCard(s, onClick = { onOpen(s.gun.id) }, onCleaned = null)
                    }
                }
            }
        }
    }
}

@Composable
private fun GunCard(s: GunSummary, onClick: () -> Unit, onCleaned: (() -> Unit)?) {
    val due = s.cleaningDue
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = if (due) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
        else CardDefaults.cardColors(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (s.gun.photo.isNotBlank()) {
                    val repo = (LocalContext.current.applicationContext as RangeLogApp).repository
                    AsyncImage(
                        model = repo.photoFile(s.gun.photo),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)),
                    )
                    Spacer(Modifier.width(12.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(s.gun.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    if (s.gun.description.isNotBlank()) {
                        Text(
                            s.gun.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text("${s.totalRounds.withCommas()} rds", style = MaterialTheme.typography.titleSmall)
            }
            val lastShot = s.lastShot?.let { " · last shot ${it.short()}" } ?: ""
            Text(
                "${s.sessionCount} session${if (s.sessionCount == 1) "" else "s"}$lastShot",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.padding(2.dp))
            if (s.gun.notMine) {
                Text("Not my gun", style = MaterialTheme.typography.bodyMedium)
            } else {
            val cleaned = s.lastCleaned?.let { "cleaned ${it.short()}" } ?: "no cleaning logged"
            val uncounted = if (s.uncountedSinceCleaning > 0) {
                " (+${s.uncountedSinceCleaning} session${if (s.uncountedSinceCleaning == 1) "" else "s"} not counted)"
            } else ""
            Text(
                "Since cleaning: ${s.roundsSinceCleaning.withCommas()} rds$uncounted · $cleaned",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (due) FontWeight.Bold else FontWeight.Normal,
            )
            val interval = s.gun.cleanInterval?.takeIf { it > 0 }
            val months = s.gun.cleanMonths?.takeIf { it > 0 }
            if (interval != null) {
                LinearProgressIndicator(
                    progress = { (s.roundsSinceCleaning.toFloat() / interval).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
            }
            if (interval != null || months != null) {
                val every = listOfNotNull(interval?.let { "$it rds" }, months?.let { "$it months" }).joinToString(" or ")
                Text(
                    if (due) "Cleaning due (every $every)" else "Clean every $every",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            if (onCleaned != null && (due || s.roundsSinceCleaning > 0 || s.uncountedSinceCleaning > 0)) {
                TextButton(onClick = onCleaned, contentPadding = PaddingValues(0.dp)) { Text("Cleaned it today") }
            }
            }
        }
    }
}
