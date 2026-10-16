@file:OptIn(ExperimentalMaterial3Api::class)

package com.laurio.rangelog.ui

import android.app.Application
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.laurio.rangelog.RangeLogApp
import com.laurio.rangelog.data.Cleaning
import com.laurio.rangelog.data.Gun
import com.laurio.rangelog.data.GunSummary
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

data class GunSessionRow(val sessionId: Long, val date: Long, val rounds: Int?, val notes: String)

class GunDetailViewModel(app: Application, handle: SavedStateHandle) : AndroidViewModel(app) {
    val repo = (app as RangeLogApp).repository
    val gunId: Long = handle.get<Long>("id") ?: 0L

    var name by mutableStateOf("")
    var description by mutableStateOf("")
    var notes by mutableStateOf("")
    // New guns start at 500 rounds between cleanings; it's editable on the gun's screen.
    var cleanInterval by mutableStateOf(if (gunId == 0L) "500" else "")
    var cleanMonths by mutableStateOf("")
    var photo by mutableStateOf("")
    var active by mutableStateOf(true)
    var notMine by mutableStateOf(false)
    var loaded by mutableStateOf(gunId == 0L)
        private set

    // Picture files copied in during this edit; removed again if the edit is thrown away.
    private val addedPhotos = mutableSetOf<String>()
    private var pendingCameraFile: String? = null

    private fun snapshot() = listOf(name, description, notes, cleanInterval, cleanMonths, photo, active, notMine)
    private var baseline: List<Any?> = snapshot()
    val isDirty: Boolean get() = snapshot() != baseline

    val summary: StateFlow<GunSummary?> = repo.gunSummaries
        .map { list -> list.firstOrNull { it.gun.id == gunId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val cleanings: StateFlow<List<Cleaning>> = repo.cleanings
        .map { list -> list.filter { it.gunId == gunId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val sessions: StateFlow<List<GunSessionRow>> = repo.sessions
        .map { list ->
            list.mapNotNull { s ->
                val mine = s.guns.filter { it.entry.gunId == gunId }
                if (mine.isEmpty()) null
                else GunSessionRow(
                    sessionId = s.session.id,
                    date = s.session.date,
                    rounds = if (mine.all { it.entry.rounds == null }) null else mine.sumOf { it.entry.rounds ?: 0 },
                    notes = mine.joinToString(" ") { it.entry.notes }.trim().ifBlank { s.session.notes },
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Loads the saved gun into the fields (also used to throw away unsaved edits). */
    fun load() = viewModelScope.launch {
        discardAdded()
        repo.getGun(gunId)?.let { g ->
                name = g.name
                description = g.description
                notes = g.notes
                cleanInterval = g.cleanInterval?.toString() ?: ""
                cleanMonths = g.cleanMonths?.toString() ?: ""
                photo = g.photo
                active = g.active
                notMine = g.notMine
        }
        baseline = snapshot()
        loaded = true
    }

    init {
        if (gunId != 0L) load()
    }

    fun save(onSaved: () -> Unit) = viewModelScope.launch {
        repo.saveGun(
            Gun(
                id = gunId,
                name = name.trim(),
                description = description.trim(),
                notes = notes.trim(),
                cleanInterval = cleanInterval.toIntOrNull()?.takeIf { it > 0 },
                cleanMonths = cleanMonths.toIntOrNull()?.takeIf { it > 0 },
                photo = photo,
                active = active,
                notMine = notMine,
            )
        )
        addedPhotos.clear()
        baseline = snapshot()
        onSaved()
    }

    fun newCameraUri(): Uri {
        val (file, uri) = repo.newCameraTarget()
        pendingCameraFile = file
        return uri
    }

    fun onCameraResult(success: Boolean) {
        val file = pendingCameraFile ?: return
        pendingCameraFile = null
        if (success) useNewPhoto(file) else repo.discardPhoto(file)
    }

    fun pickPhoto(uri: Uri?) {
        if (uri == null) return
        viewModelScope.launch { repo.importPhoto(uri)?.let(::useNewPhoto) }
    }

    private fun useNewPhoto(file: String) {
        removePhoto()
        addedPhotos += file
        photo = file
    }

    fun removePhoto() {
        // A file saved earlier is deleted when the gun is saved; one added in this edit goes now.
        if (addedPhotos.remove(photo)) repo.discardPhoto(photo)
        photo = ""
    }

    /** Throws away picture files added during an edit that wasn't saved. */
    fun discardAdded() {
        addedPhotos.forEach(repo::discardPhoto)
        addedPhotos.clear()
    }

    fun archive(onDone: () -> Unit) = viewModelScope.launch { repo.archiveGun(gunId); onDone() }

    fun delete(onDone: () -> Unit) = viewModelScope.launch { repo.deleteGun(gunId); onDone() }

    fun logCleaning(date: LocalDate, text: String) = viewModelScope.launch { repo.logCleaning(gunId, date, text) }

    fun deleteCleaning(c: Cleaning) = viewModelScope.launch { repo.deleteCleaning(c) }
}

@Composable
fun GunDetailScreen(
    onDone: () -> Unit,
    onOpenSession: (Long) -> Unit,
    vm: GunDetailViewModel = viewModel(),
) {
    val summary by vm.summary.collectAsStateWithLifecycle()
    val cleanings by vm.cleanings.collectAsStateWithLifecycle()
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var confirmDelete by remember { mutableStateOf(false) }
    var showCleaningDialog by remember { mutableStateOf(false) }
    var showAllSessions by remember { mutableStateOf(false) }
    // Existing guns open for reading; Edit switches to the form.
    var editing by rememberSaveable { mutableStateOf(vm.gunId == 0L) }
    var confirmDiscard by remember { mutableStateOf(false) }
    val stopEditing: () -> Unit = {
        if (vm.gunId == 0L) { vm.discardAdded(); onDone() } else { vm.load(); editing = false }
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { vm.onCameraResult(it) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { vm.pickPhoto(it) }
    val leave: () -> Unit = {
        if (!editing) onDone() else if (vm.isDirty) confirmDiscard = true else stopEditing()
    }
    BackHandler(onBack = leave)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (vm.gunId == 0L) "New gun" else vm.name.ifBlank { "Gun" }) },
                navigationIcon = {
                    IconButton(onClick = leave) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (!editing) {
                        TextButton(onClick = { editing = true }) { Text("Edit") }
                    } else {
                        if (vm.gunId != 0L) {
                            IconButton(onClick = { confirmDelete = true }) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete gun")
                            }
                        }
                        TextButton(
                            onClick = { vm.save { if (vm.gunId == 0L) onDone() else editing = false } },
                            enabled = vm.name.isNotBlank(),
                        ) { Text("Save") }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (!vm.loaded) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (editing) {
            if (vm.photo.isNotBlank()) {
                AsyncImage(
                    model = vm.repo.photoFile(vm.photo),
                    contentDescription = "Gun photo",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(12.dp)),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { camera.launch(vm.newCameraUri()) }) { Text("Take photo") }
                OutlinedButton(onClick = {
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }) { Text(if (vm.photo.isBlank()) "Add picture" else "Change") }
                if (vm.photo.isNotBlank()) TextButton(onClick = { vm.removePhoto() }) { Text("Remove") }
            }
            OutlinedTextField(
                value = vm.name, onValueChange = { vm.name = it },
                label = { Text("Short name (e.g. SP01)") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = vm.description, onValueChange = { vm.description = it },
                label = { Text("Make / model") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = vm.notes, onValueChange = { vm.notes = it },
                label = { Text("Upgrades & notes") }, minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Not my gun", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Rented or borrowed. No cleaning log or cleaning reminders.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = vm.notMine, onCheckedChange = { vm.notMine = it })
            }
            if (!vm.notMine) OutlinedTextField(
                value = vm.cleanInterval,
                onValueChange = { v -> vm.cleanInterval = v.filter(Char::isDigit) },
                label = { Text("Remind me to clean every … rounds (optional)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            if (!vm.notMine) OutlinedTextField(
                value = vm.cleanMonths,
                onValueChange = { v -> vm.cleanMonths = v.filter(Char::isDigit) },
                label = { Text("…or every … months since last cleaning (optional)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("In rotation", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Turn off to archive it. Archived guns keep their history but leave the gun picker.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = vm.active, onCheckedChange = { vm.active = it })
            }
            } else {
                GunReadOnlyDetails(vm)
            }

            if (vm.gunId != 0L) {
                summary?.let { s ->
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                "${s.totalRounds.withCommas()} rounds lifetime",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text("${s.sessionCount} sessions" + (s.lastShot?.let { " · last shot ${it.pretty()}" } ?: ""))
                            if (!vm.notMine) {
                                Text(
                                "${s.roundsSinceCleaning.withCommas()} rounds since last cleaning" +
                                    if (s.uncountedSinceCleaning > 0) " (+${s.uncountedSinceCleaning} uncounted sessions)" else ""
                            )
                            }
                        }
                    }
                }

                if (!vm.notMine) {
                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Cleaning log", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = { showCleaningDialog = true }) { Text("Log cleaning") }
                }
                if (cleanings.isEmpty()) {
                    Text("No cleanings logged yet.", style = MaterialTheme.typography.bodyMedium)
                }
                cleanings.forEach { c ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(c.date.epochDayPretty())
                            if (c.notes.isNotBlank()) {
                                Text(c.notes, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        if (editing) {
                            IconButton(onClick = { vm.deleteCleaning(c) }) {
                            Icon(Icons.Default.Close, contentDescription = "Delete cleaning entry")
                        }
                        }
                    }
                }
                }

                HorizontalDivider()
                Text("Sessions", style = MaterialTheme.typography.titleMedium)
                if (sessions.isEmpty()) {
                    Text("Not logged in any session yet.", style = MaterialTheme.typography.bodyMedium)
                }
                val shown = if (showAllSessions) sessions else sessions.take(10)
                shown.forEach { row ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onOpenSession(row.sessionId) }
                            .padding(vertical = 6.dp),
                    ) {
                        Row {
                            Text(row.date.epochDayPretty(), modifier = Modifier.weight(1f))
                            Text(row.rounds?.let { "$it rds" } ?: "— rds", fontWeight = FontWeight.SemiBold)
                        }
                        if (row.notes.isNotBlank()) {
                            Text(
                                row.notes, maxLines = 2,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (sessions.size > 10) {
                    TextButton(onClick = { showAllSessions = !showAllSessions }) {
                        Text(if (showAllSessions) "Show fewer" else "Show all ${sessions.size}")
                    }
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Remove this gun?") },
            text = {
                Text(
                    "Sessions are never deleted. Archive takes the gun out of the pick list; its sessions show " +
                        "\"${vm.name} (archived)\". Delete permanently removes the gun; any sessions that used it show " +
                        "\"${vm.name} (deleted)\"."
                )
            },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = { confirmDelete = false; vm.archive(onDone) }) { Text("Remove from pick list") }
                    TextButton(onClick = { confirmDelete = false; vm.delete(onDone) }) { Text("Delete permanently") }
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard changes?") },
            text = { Text("You have unsaved changes to this gun.") },
            confirmButton = { TextButton(onClick = { confirmDiscard = false; stopEditing() }) { Text("Discard") } },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Keep editing") } },
        )
    }

    if (showCleaningDialog) {
        CleaningDialog(
            onDismiss = { showCleaningDialog = false },
            onSave = { date, text ->
                vm.logCleaning(date, text)
                showCleaningDialog = false
            },
        )
    }
}

@Composable
private fun GunReadOnlyDetails(vm: GunDetailViewModel) {
    if (vm.photo.isNotBlank()) {
        AsyncImage(
            model = vm.repo.photoFile(vm.photo),
            contentDescription = "Gun photo",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(12.dp)),
        )
    }
    Text(vm.name, style = MaterialTheme.typography.headlineSmall)
    if (vm.description.isNotBlank()) Text(vm.description, style = MaterialTheme.typography.titleMedium)
    if (!vm.active) {
        Text("Archived: hidden from the gun picker", style = MaterialTheme.typography.bodyMedium)
    }
    if (vm.notMine) {
        Text("Not my gun (rented or borrowed)", style = MaterialTheme.typography.bodyMedium)
    } else {
        val parts = listOfNotNull(
            vm.cleanInterval.toIntOrNull()?.let { "$it rounds" },
            vm.cleanMonths.toIntOrNull()?.let { "$it months" },
        )
        Text(
            if (parts.isEmpty()) "No cleaning reminder" else "Clean every " + parts.joinToString(" or "),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    if (vm.notes.isNotBlank()) {
        Text("Upgrades & notes", style = MaterialTheme.typography.titleMedium)
        Text(vm.notes)
    }
}

@Composable
private fun CleaningDialog(onDismiss: () -> Unit, onSave: (LocalDate, String) -> Unit) {
    var date by remember { mutableStateOf(LocalDate.now()) }
    var text by remember { mutableStateOf("") }
    var pickDate by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Log cleaning") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { pickDate = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.DateRange, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(date.pretty())
                }
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    label = { Text("Notes (optional)") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(date, text) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )

    if (pickDate) {
        val state = rememberDatePickerState(initialSelectedDateMillis = date.toPickerMillis())
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { date = it.pickerMillisToDate() }
                    pickDate = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickDate = false }) { Text("Cancel") } },
        ) { DatePicker(state = state) }
    }
}
