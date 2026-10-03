@file:OptIn(ExperimentalMaterial3Api::class)

package com.laurio.rangelog.ui

import android.app.Application
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.laurio.rangelog.RangeLogApp
import com.laurio.rangelog.data.Gun
import com.laurio.rangelog.data.GunEntryDraft
import com.laurio.rangelog.data.PhotoDraft
import com.laurio.rangelog.data.SessionDraft
import com.laurio.rangelog.data.StringDraft
import com.laurio.rangelog.data.toDraft
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SessionEditViewModel(app: Application, private val handle: SavedStateHandle) : AndroidViewModel(app) {
    val repo = (app as RangeLogApp).repository
    val sessionId: Long = handle.get<Long>("id") ?: 0L

    var draft by mutableStateOf(SessionDraft())
        private set
    var loaded by mutableStateOf(sessionId == 0L)
        private set
    private var original: SessionDraft? = null

    val guns: StateFlow<List<Gun>> =
        repo.guns.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Photo files created during this edit; removed again if the edit is discarded. */
    private val addedThisEdit = mutableListOf<String>()

    private var pendingCameraFile: String?
        get() = handle["pendingCamera"]
        set(value) { handle["pendingCamera"] = value }

    init {
        if (sessionId == 0L) {
            original = draft
        } else {
            viewModelScope.launch {
                repo.getSession(sessionId)?.let { draft = it.toDraft() }
                original = draft
                loaded = true
            }
        }
    }

    val isDirty: Boolean get() = original != null && draft != original

    fun edit(change: (SessionDraft) -> SessionDraft) {
        draft = change(draft)
    }

    fun editGun(key: Long, change: (GunEntryDraft) -> GunEntryDraft) = edit { d ->
        d.copy(guns = d.guns.map { if (it.key == key) change(it) else it })
    }

    fun addGunEntry() = edit { it.copy(guns = it.guns + GunEntryDraft()) }

    fun removeGunEntry(key: Long) = edit { d -> d.copy(guns = d.guns.filterNot { it.key == key }) }

    fun addString(gunKey: Long) = editGun(gunKey) { it.copy(strings = it.strings + StringDraft()) }

    fun editString(gunKey: Long, key: Long, change: (StringDraft) -> StringDraft) = editGun(gunKey) { g ->
        g.copy(strings = g.strings.map { if (it.key == key) change(it) else it })
    }

    fun removeString(gunKey: Long, key: Long) = editGun(gunKey) { g ->
        g.copy(strings = g.strings.filterNot { it.key == key })
    }

    fun newCameraUri(): Uri {
        val (name, uri) = repo.newCameraTarget()
        pendingCameraFile = name
        return uri
    }

    fun onCameraResult(success: Boolean) {
        val name = pendingCameraFile ?: return
        pendingCameraFile = null
        if (success) {
            addedThisEdit += name
            edit { it.copy(photos = it.photos + PhotoDraft(name)) }
        } else {
            repo.discardPhoto(name)
        }
    }

    fun addPickedPhotos(uris: List<Uri>) = viewModelScope.launch {
        for (uri in uris) {
            val name = repo.importPhoto(uri) ?: continue
            addedThisEdit += name
            edit { it.copy(photos = it.photos + PhotoDraft(name)) }
        }
    }

    fun removePhoto(name: String) {
        edit { d -> d.copy(photos = d.photos.filterNot { it.fileName == name }) }
        if (addedThisEdit.remove(name)) repo.discardPhoto(name)
    }

    fun validationError(): String? =
        if (draft.guns.any { it.gunId == null && it.hasContent }) "Pick a gun for each entry, or remove the empty one."
        else null

    fun save(onSaved: () -> Unit) = viewModelScope.launch {
        repo.saveSession(draft)
        addedThisEdit.clear()
        original = draft
        onSaved()
    }

    fun discard() {
        addedThisEdit.forEach(repo::discardPhoto)
        addedThisEdit.clear()
    }

    fun delete(onDone: () -> Unit) = viewModelScope.launch {
        discard()
        repo.deleteSession(sessionId)
        onDone()
    }

    suspend fun createGun(name: String, description: String): Long =
        repo.saveGun(Gun(name = name.trim(), description = description.trim()))
}

@Composable
fun SessionEditScreen(
    onDone: () -> Unit,
    vm: SessionEditViewModel = viewModel(),
) {
    val draft = vm.draft
    val guns by vm.guns.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var showDate by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var newGunForEntry by remember { mutableStateOf<Long?>(null) }
    var viewingPhoto by remember { mutableStateOf<String?>(null) }

    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { vm.onCameraResult(it) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) {
        vm.addPickedPhotos(it)
    }

    val leave: () -> Unit = {
        if (vm.isDirty) confirmDiscard = true else {
            vm.discard(); onDone()
        }
    }
    BackHandler(onBack = leave)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (vm.sessionId == 0L) "New session" else "Edit session") },
                navigationIcon = {
                    IconButton(onClick = leave) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (vm.sessionId != 0L) {
                        IconButton(onClick = { confirmDelete = true }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete session")
                        }
                    }
                    TextButton(onClick = {
                        val error = vm.validationError()
                        if (error != null) scope.launch { snackbar.showSnackbar(error) } else vm.save(onDone)
                    }) { Text("Save") }
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
            OutlinedButton(onClick = { showDate = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.DateRange, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(draft.date.pretty())
            }
            OutlinedTextField(
                value = draft.location,
                onValueChange = { v -> vm.edit { it.copy(location = v) } },
                label = { Text("Location") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Text("Guns", style = MaterialTheme.typography.titleMedium)
            draft.guns.forEach { entry ->
                key(entry.key) {
                    GunEntryCard(
                        entry = entry,
                        guns = guns,
                        onChange = { change -> vm.editGun(entry.key, change) },
                        onRemove = { vm.removeGunEntry(entry.key) },
                        onAddString = { vm.addString(entry.key) },
                        onEditString = { k, change -> vm.editString(entry.key, k, change) },
                        onRemoveString = { k -> vm.removeString(entry.key, k) },
                        onNewGun = { newGunForEntry = entry.key },
                    )
                }
            }
            OutlinedButton(onClick = { vm.addGunEntry() }) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Add another gun")
            }

            OutlinedTextField(
                value = draft.notes,
                onValueChange = { v -> vm.edit { it.copy(notes = v) } },
                label = { Text("Session notes") },
                minLines = 4,
                modifier = Modifier.fillMaxWidth(),
            )

            Text("Target photos", style = MaterialTheme.typography.titleMedium)
            if (draft.photos.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(draft.photos, key = { it.fileName }) { p ->
                        Box {
                            AsyncImage(
                                model = vm.repo.photoFile(p.fileName),
                                contentDescription = "Target photo",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(120.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { viewingPhoto = p.fileName },
                            )
                            FilledTonalIconButton(
                                onClick = { vm.removePhoto(p.fileName) },
                                modifier = Modifier.align(Alignment.TopEnd).size(32.dp),
                            ) { Icon(Icons.Default.Close, contentDescription = "Remove photo") }
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { camera.launch(vm.newCameraUri()) }) { Text("Take photo") }
                OutlinedButton(onClick = {
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }) { Text("Add from gallery") }
            }
            Spacer(Modifier.height(32.dp))
        }
    }

    if (showDate) {
        val state = rememberDatePickerState(initialSelectedDateMillis = draft.date.toPickerMillis())
        DatePickerDialog(
            onDismissRequest = { showDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { ms -> vm.edit { it.copy(date = ms.pickerMillisToDate()) } }
                    showDate = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showDate = false }) { Text("Cancel") } },
        ) { DatePicker(state = state) }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this session?") },
            text = { Text("This removes the session, its round counts and its photos. It can't be undone.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; vm.delete(onDone) }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard changes?") },
            text = { Text("You have unsaved changes to this session.") },
            confirmButton = {
                TextButton(onClick = { confirmDiscard = false; vm.discard(); onDone() }) { Text("Discard") }
            },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Keep editing") } },
        )
    }

    newGunForEntry?.let { entryKey ->
        NewGunDialog(
            onDismiss = { newGunForEntry = null },
            onCreate = { name, description ->
                scope.launch {
                    val id = vm.createGun(name, description)
                    vm.editGun(entryKey) { it.copy(gunId = id) }
                    newGunForEntry = null
                }
            },
        )
    }

    viewingPhoto?.let { name ->
        Dialog(onDismissRequest = { viewingPhoto = null }) {
            AsyncImage(
                model = vm.repo.photoFile(name),
                contentDescription = "Target photo",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().clickable { viewingPhoto = null },
            )
        }
    }
}

@Composable
private fun GunEntryCard(
    entry: GunEntryDraft,
    guns: List<Gun>,
    onChange: ((GunEntryDraft) -> GunEntryDraft) -> Unit,
    onRemove: () -> Unit,
    onAddString: () -> Unit,
    onEditString: (Long, (StringDraft) -> StringDraft) -> Unit,
    onRemoveString: (Long) -> Unit,
    onNewGun: () -> Unit,
) {
    val numbers = KeyboardOptions(keyboardType = KeyboardType.Number)
    val decimals = KeyboardOptions(keyboardType = KeyboardType.Decimal)

    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GunPicker(
                    selectedId = entry.gunId,
                    guns = guns,
                    onSelect = { id -> onChange { it.copy(gunId = id) } },
                    onNewGun = onNewGun,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(
                    value = entry.rounds,
                    onValueChange = { v -> onChange { it.copy(rounds = v.filter(Char::isDigit)) } },
                    label = { Text("Rounds") },
                    placeholder = { if (entry.stringTotal > 0) Text(entry.stringTotal.toString()) },
                    singleLine = true,
                    keyboardOptions = numbers,
                    modifier = Modifier.width(100.dp),
                )
                IconButton(onClick = onRemove) { Icon(Icons.Default.Close, contentDescription = "Remove gun") }
            }

            if (entry.strings.isNotEmpty()) {
                Text("Distances", style = MaterialTheme.typography.labelLarge)
            }
            entry.strings.forEach { s ->
                key(s.key) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(
                            value = s.rounds,
                            onValueChange = { v -> onEditString(s.key) { it.copy(rounds = v.filter(Char::isDigit)) } },
                            label = { Text("Rds") },
                            singleLine = true,
                            keyboardOptions = numbers,
                            modifier = Modifier.width(72.dp),
                        )
                        Text("@")
                        OutlinedTextField(
                            value = s.yards,
                            onValueChange = { v ->
                                onEditString(s.key) { it.copy(yards = v.filter { c -> c.isDigit() || c == '.' }) }
                            },
                            label = { Text("Yds") },
                            singleLine = true,
                            keyboardOptions = decimals,
                            modifier = Modifier.width(72.dp),
                        )
                        OutlinedTextField(
                            value = s.notes,
                            onValueChange = { v -> onEditString(s.key) { it.copy(notes = v) } },
                            label = { Text("Note") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { onRemoveString(s.key) }) {
                            Icon(Icons.Default.Close, contentDescription = "Remove distance")
                        }
                    }
                }
            }
            TextButton(onClick = onAddString) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("Add distance")
            }
            OutlinedTextField(
                value = entry.notes,
                onValueChange = { v -> onChange { it.copy(notes = v) } },
                label = { Text("Notes for this gun") },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun GunPicker(
    selectedId: Long?,
    guns: List<Gun>,
    onSelect: (Long) -> Unit,
    onNewGun: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    val selected = guns.firstOrNull { it.id == selectedId }
    Box(modifier) {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Text(
                selected?.name ?: "Choose gun",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            guns.filter { it.active || it.id == selectedId }.forEach { g ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(g.name)
                            if (g.description.isNotBlank()) {
                                Text(
                                    g.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    onClick = { onSelect(g.id); open = false },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(text = { Text("+ New gun…") }, onClick = { open = false; onNewGun() })
        }
    }
}

@Composable
fun NewGunDialog(onDismiss: () -> Unit, onCreate: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New gun") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Short name (e.g. SP01)") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Make / model") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onCreate(name, description) }, enabled = name.isNotBlank()) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
