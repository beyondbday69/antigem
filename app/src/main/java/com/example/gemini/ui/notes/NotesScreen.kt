package com.example.gemini.ui.notes

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.gemini.data.notes.Note
import com.example.gemini.data.notes.NoteKind
import com.example.gemini.data.notes.NotesRepository
import com.example.gemini.data.notes.TodoItem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesScreen(
    isVisible: Boolean,
    onClose: () -> Unit
) {
    if (!isVisible) return

    val context = LocalContext.current
    LaunchedEffect(Unit) {
        NotesRepository.init(context)
    }

    val notes by NotesRepository.notes.collectAsState()
    var selectedFilter by remember { mutableStateOf<NoteKind?>(null) } // null = all
    var editingNote by remember { mutableStateOf<Note?>(null) }
    var isCreatingNote by remember { mutableStateOf(false) }
    var createAsKind by remember { mutableStateOf(NoteKind.NOTE) }

    BackHandler(enabled = isVisible) {
        if (editingNote != null || isCreatingNote) {
            editingNote = null
            isCreatingNote = false
        } else {
            onClose()
        }
    }

    val filteredNotes = remember(notes, selectedFilter) {
        val list = if (selectedFilter == null) notes else notes.filter { it.kind == selectedFilter }
        list.sortedWith(compareByDescending<Note> { it.pinned }.thenByDescending { it.updatedAt })
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Notes & Tasks",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close Notes")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    createAsKind = selectedFilter ?: NoteKind.NOTE
                    isCreatingNote = true
                },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = CircleShape
            ) {
                Icon(Icons.Default.Add, contentDescription = "New Note or Task")
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            // Filter Chips (All, Notes, To-Dos)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = selectedFilter == null,
                    onClick = { selectedFilter = null },
                    label = { Text("All (${notes.size})") }
                )
                FilterChip(
                    selected = selectedFilter == NoteKind.NOTE,
                    onClick = { selectedFilter = NoteKind.NOTE },
                    label = { Text("Notes (${notes.count { it.kind == NoteKind.NOTE }})") }
                )
                FilterChip(
                    selected = selectedFilter == NoteKind.TODO,
                    onClick = { selectedFilter = NoteKind.TODO },
                    label = { Text("To-Dos (${notes.count { it.kind == NoteKind.TODO }})") }
                )
            }

            if (filteredNotes.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Outlined.EditNote,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = if (selectedFilter == NoteKind.TODO) "No to-do lists yet" else "No notes yet",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Tap + to create one or ask AI agents to manage them.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 80.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(filteredNotes, key = { it.id }) { note ->
                        NoteCard(
                            note = note,
                            onClick = { editingNote = note },
                            onTogglePin = {
                                NotesRepository.update(note.id) { it.copy(pinned = !it.pinned) }
                            },
                            onDelete = {
                                NotesRepository.delete(note.id)
                            },
                            onToggleTodo = { todoId ->
                                NotesRepository.update(note.id) { current ->
                                    val updatedItems = current.items.map {
                                        if (it.id == todoId) it.copy(done = !it.done) else it
                                    }
                                    current.copy(items = updatedItems)
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    // Note Editor / Creator Dialog
    if (isCreatingNote) {
        NoteEditorDialog(
            initialNote = Note(kind = createAsKind),
            isNew = true,
            onDismiss = { isCreatingNote = false },
            onSave = { kind, title, text, items ->
                NotesRepository.create(
                    kind = kind,
                    title = title,
                    text = text,
                    items = items
                )
                isCreatingNote = false
            }
        )
    }

    editingNote?.let { note ->
        NoteEditorDialog(
            initialNote = note,
            isNew = false,
            onDismiss = { editingNote = null },
            onSave = { kind, title, text, items ->
                NotesRepository.update(note.id) {
                    it.copy(
                        kind = kind,
                        title = title,
                        text = text,
                        items = items.map { txt ->
                            val existing = note.items.firstOrNull { old -> old.text == txt }
                            existing ?: TodoItem(text = txt)
                        }
                    )
                }
                editingNote = null
            },
            onDelete = {
                NotesRepository.delete(note.id)
                editingNote = null
            }
        )
    }
}

@Composable
private fun NoteCard(
    note: Note,
    onClick: () -> Unit,
    onTogglePin: () -> Unit,
    onDelete: () -> Unit,
    onToggleTodo: (String) -> Unit
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = if (note.kind == NoteKind.TODO) Icons.Outlined.Checklist else Icons.Outlined.Description,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = note.title.ifBlank { if (note.kind == NoteKind.TODO) "Untitled To-Do" else "Untitled Note" },
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onTogglePin,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = if (note.pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                            contentDescription = "Pin Note",
                            tint = if (note.pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Delete,
                            contentDescription = "Delete",
                            tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            if (note.kind == NoteKind.NOTE && note.text.isNotBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = note.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis
                )
            } else if (note.kind == NoteKind.TODO && note.items.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                note.items.take(4).forEach { item ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                    ) {
                        Checkbox(
                            checked = item.done,
                            onCheckedChange = { onToggleTodo(item.id) },
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = item.text,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                textDecoration = if (item.done) TextDecoration.LineThrough else null
                            ),
                            color = if (item.done) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f) else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                if (note.items.size > 4) {
                    Text(
                        text = "+ ${note.items.size - 4} more items",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 4.dp, start = 28.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            val dateStr = remember(note.updatedAt) {
                SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(note.updatedAt))
            }
            Text(
                text = dateStr,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
        }
    }
}

@Composable
private fun NoteEditorDialog(
    initialNote: Note,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onSave: (kind: NoteKind, title: String, text: String, items: List<String>) -> Unit,
    onDelete: (() -> Unit)? = null
) {
    var kind by remember { mutableStateOf(initialNote.kind) }
    var title by remember { mutableStateOf(initialNote.title) }
    var text by remember { mutableStateOf(initialNote.text) }
    var todoItems by remember { mutableStateOf(initialNote.items.map { it.text }) }
    var newTodoInput by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (isNew) "Create ${if (kind == NoteKind.TODO) "To-Do" else "Note"}" else "Edit ${if (kind == NoteKind.TODO) "To-Do" else "Note"}",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Kind selector if new
                if (isNew) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = kind == NoteKind.NOTE,
                            onClick = { kind = NoteKind.NOTE },
                            label = { Text("Note") }
                        )
                        FilterChip(
                            selected = kind == NoteKind.TODO,
                            onClick = { kind = NoteKind.TODO },
                            label = { Text("To-Do List") }
                        )
                    }
                }

                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                if (kind == NoteKind.NOTE) {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        label = { Text("Content") },
                        minLines = 4,
                        maxLines = 8,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            OutlinedTextField(
                                value = newTodoInput,
                                onValueChange = { newTodoInput = it },
                                label = { Text("Add task...") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            IconButton(
                                onClick = {
                                    if (newTodoInput.isNotBlank()) {
                                        todoItems = todoItems + newTodoInput.trim()
                                        newTodoInput = ""
                                    }
                                }
                            ) {
                                Icon(Icons.Default.Add, contentDescription = "Add Item")
                            }
                        }

                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 180.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(todoItems.size) { idx ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("•", modifier = Modifier.padding(horizontal = 6.dp))
                                    Text(
                                        text = todoItems[idx],
                                        modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    IconButton(
                                        onClick = {
                                            todoItems = todoItems.filterIndexed { i, _ -> i != idx }
                                        },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Close,
                                            contentDescription = "Remove",
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(kind, title.trim(), text.trim(), todoItems)
                }
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onDelete != null) {
                    TextButton(
                        onClick = onDelete,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Delete")
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        }
    )
}
