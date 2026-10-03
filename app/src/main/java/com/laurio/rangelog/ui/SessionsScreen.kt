@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.laurio.rangelog.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.laurio.rangelog.data.SessionFull
import java.time.LocalDate

private sealed interface ListRow {
    data class Year(val year: Int, val rounds: Int, val count: Int) : ListRow
    data class Item(val session: SessionFull) : ListRow
}

@Composable
fun SessionsScreen(vm: MainViewModel, onOpen: (Long) -> Unit) {
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }

    val rows = remember(sessions, query) {
        val q = query.trim().lowercase()
        val filtered = if (q.isEmpty()) sessions else sessions.filter { s ->
            s.session.notes.lowercase().contains(q) ||
                s.session.location.lowercase().contains(q) ||
                s.guns.any { g ->
                    (g.gun?.name?.lowercase()?.contains(q) == true) ||
                        (g.gun?.description?.lowercase()?.contains(q) == true) ||
                        g.entry.notes.lowercase().contains(q)
                }
        }
        filtered.groupBy { LocalDate.ofEpochDay(it.session.date).year }
            .flatMap { (year, list) ->
                listOf<ListRow>(ListRow.Year(year, list.sumOf { it.totalRounds }, list.size)) + list.map { ListRow.Item(it) }
            }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = { TopAppBar(title = { Text("Range Log") }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { onOpen(0L) },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("New session") },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("Search notes, guns, places") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) IconButton(onClick = { query = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear search")
                        }
                    },
                )
            }
            if (rows.isEmpty()) {
                item {
                    Text(
                        if (sessions.isEmpty()) "No sessions yet. Tap New session after your next range trip."
                        else "No sessions match \"$query\".",
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            items(rows, key = {
                when (it) {
                    is ListRow.Year -> "y${it.year}"
                    is ListRow.Item -> "s${it.session.session.id}"
                }
            }) { row ->
                when (row) {
                    is ListRow.Year -> YearHeader(row)
                    is ListRow.Item -> SessionCard(row.session) { onOpen(row.session.session.id) }
                }
            }
        }
    }
}

@Composable
private fun YearHeader(row: ListRow.Year) {
    Row(
        Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 2.dp, start = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(row.year.toString(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.weight(1f))
        Text(
            "${row.count} sessions · ${row.rounds.withCommas()} rds",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SessionCard(s: SessionFull, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    s.session.date.epochDayPretty(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.weight(1f))
                if (s.totalRounds > 0) {
                    Text("${s.totalRounds.withCommas()} rds", style = MaterialTheme.typography.titleSmall)
                }
            }
            if (s.session.location.isNotBlank()) {
                Text(
                    s.session.location,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (s.guns.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    s.guns.forEach { g ->
                        val name = g.gun?.name ?: "?"
                        AssistChip(
                            onClick = onClick,
                            label = { Text(if (g.entry.rounds != null) "$name · ${g.entry.rounds}" else name) },
                        )
                    }
                }
            }
            val preview = s.session.notes.ifBlank { s.guns.firstOrNull { it.entry.notes.isNotBlank() }?.entry?.notes.orEmpty() }
            if (preview.isNotBlank()) {
                Text(preview, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            }
            if (s.photos.isNotEmpty()) {
                Row {
                    Text(
                        "${s.photos.size} photo${if (s.photos.size == 1) "" else "s"}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(4.dp))
                }
            }
        }
    }
}
