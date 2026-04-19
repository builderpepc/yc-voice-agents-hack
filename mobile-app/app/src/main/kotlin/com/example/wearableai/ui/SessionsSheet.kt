package com.example.wearableai.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.wearableai.shared.SessionSummary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionsSheet(
    visible: Boolean,
    currentId: String,
    currentName: String,
    sessions: List<SessionSummary>,
    isInspectionRunning: Boolean,
    onDismiss: () -> Unit,
    onNewInspection: () -> Unit,
    onLoad: (String) -> Unit,
    onDelete: (String) -> Unit,
    onRename: (String, String) -> Unit,
) {
    if (!visible) return
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var renaming by remember { mutableStateOf(false) }
    var renameText by remember(currentName) { mutableStateOf(currentName) }
    var confirmLoadId by remember { mutableStateOf<String?>(null) }
    var confirmDeleteId by remember { mutableStateOf<String?>(null) }
    var confirmNew by remember { mutableStateOf(false) }

    val others = sessions.filter { it.id != currentId }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text("Inspections", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(Modifier.size(8.dp))

            // Current session header — tap the name to rename.
            Text("Current", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            if (renaming) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = renameText,
                        onValueChange = { renameText = it },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.size(8.dp))
                    TextButton(onClick = {
                        val trimmed = renameText.trim()
                        if (trimmed.isNotEmpty() && trimmed != currentName) {
                            onRename(currentId, trimmed)
                        }
                        renaming = false
                    }) { Text("Save") }
                    TextButton(onClick = {
                        renameText = currentName
                        renaming = false
                    }) { Text("Cancel") }
                }
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        currentName.ifEmpty { "(unnamed)" },
                        fontSize = 16.sp,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { renaming = true }) { Text("Rename") }
                }
            }

            Spacer(Modifier.size(12.dp))
            Button(
                onClick = {
                    if (isInspectionRunning) confirmNew = true else {
                        onNewInspection(); onDismiss()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("New Inspection") }

            Spacer(Modifier.size(16.dp))
            HorizontalDivider()
            Spacer(Modifier.size(8.dp))
            Text("Past inspections", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.size(4.dp))

            if (others.isEmpty()) {
                Text("No other inspections yet.", fontSize = 13.sp)
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 360.dp),
                    contentPadding = PaddingValues(vertical = 4.dp),
                ) {
                    items(others, key = { it.id }) { s ->
                        SessionRow(
                            summary = s,
                            onClick = {
                                if (isInspectionRunning) confirmLoadId = s.id
                                else { onLoad(s.id); onDismiss() }
                            },
                            onDelete = { confirmDeleteId = s.id },
                        )
                    }
                }
            }
        }
    }

    // Confirmation dialogs when the user tries to start fresh / load while running.
    confirmLoadId?.let { id ->
        AlertDialog(
            onDismissRequest = { confirmLoadId = null },
            title = { Text("Stop current inspection?") },
            text = { Text("Loading another session will end the active inspection first.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmLoadId = null
                    onLoad(id)
                    onDismiss()
                }) { Text("Load") }
            },
            dismissButton = {
                TextButton(onClick = { confirmLoadId = null }) { Text("Cancel") }
            },
        )
    }

    if (confirmNew) {
        AlertDialog(
            onDismissRequest = { confirmNew = false },
            title = { Text("Stop current inspection?") },
            text = { Text("Starting a new inspection will end the active one first.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmNew = false
                    onNewInspection()
                    onDismiss()
                }) { Text("New") }
            },
            dismissButton = {
                TextButton(onClick = { confirmNew = false }) { Text("Cancel") }
            },
        )
    }

    confirmDeleteId?.let { id ->
        val target = sessions.firstOrNull { it.id == id }
        AlertDialog(
            onDismissRequest = { confirmDeleteId = null },
            title = { Text("Delete inspection?") },
            text = { Text("This permanently removes ${target?.name ?: "this inspection"} and its photos.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDeleteId = null
                    onDelete(id)
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteId = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun SessionRow(
    summary: SessionSummary,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    val dateFmt = remember { SimpleDateFormat("MMM d, h:mm a", Locale.US) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(summary.name, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            val noteWord = if (summary.noteCount == 1) "note" else "notes"
            Text(
                "${dateFmt.format(Date(summary.updatedAtMs))} · ${summary.noteCount} $noteWord",
                fontSize = 12.sp,
            )
        }
        TextButton(onClick = onClick) { Text("Load") }
        TextButton(onClick = onDelete) { Text("Delete") }
    }
}
