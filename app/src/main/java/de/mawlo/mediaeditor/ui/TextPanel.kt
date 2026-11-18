package de.mawlo.mediaeditor.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.mawlo.mediaeditor.common.TextItem
import de.mawlo.mediaeditor.common.TextStyle

@Composable
fun TextInputDialog(initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Text") },
        text = {
            OutlinedTextField(value = value, onValueChange = { value = it }, minLines = 2, modifier = Modifier.fillMaxWidth())
        },
        confirmButton = { TextButton(onClick = { if (value.isNotBlank()) onConfirm(value.trim()) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

/** Werkzeugleiste für Texte – genutzt von Foto- und Video-Editor. */
@Composable
fun TextPanel(
    texts: List<TextItem>,
    selectedId: Long?,
    onAdd: (String) -> Unit,
    onUpdate: (Long, (TextItem) -> TextItem) -> Unit,
    onDelete: (Long) -> Unit,
) {
    var dialog by remember { mutableStateOf<String?>(null) } // "new" oder "edit"
    val selected = texts.firstOrNull { it.id == selectedId }

    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { dialog = "new" }) {
                Icon(Icons.Default.Add, null); HSpace(6); Text("Text hinzufügen")
            }
            HSpace(8)
            if (selected != null) {
                IconButton(onClick = { dialog = "edit" }) { Icon(Icons.Default.Edit, "Bearbeiten", tint = Color.White) }
                IconButton(onClick = { onDelete(selected.id) }) { Icon(Icons.Default.Delete, "Löschen", tint = Accent2) }
            }
        }
        if (selected == null) {
            Text(
                if (texts.isEmpty()) "Füge einen Text hinzu. Danach kannst du ihn mit dem Finger verschieben."
                else "Tippe einen Text im Bild an, um ihn zu bearbeiten oder zu verschieben.",
                color = TextDim, fontSize = 13.sp, modifier = Modifier.padding(16.dp),
            )
        } else {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SwatchColors.forEach { c ->
                    ColorDot(Color(c), selected.color == c) { onUpdate(selected.id) { it.copy(color = c) } }
                }
            }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextStyle.entries.forEach { s ->
                    Chip(s.label, selected.style == s) { onUpdate(selected.id) { it.copy(style = s) } }
                }
                Chip("Fett", selected.bold) { onUpdate(selected.id) { it.copy(bold = !it.bold) } }
            }
            LabeledSlider("Größe", selected.size, { v -> onUpdate(selected.id) { it.copy(size = v) } }, 0.03f..0.2f) {
                "${(it * 100).toInt()}"
            }
        }
    }

    when (dialog) {
        "new" -> TextInputDialog("", { dialog = null }) { onAdd(it); dialog = null }
        "edit" -> if (selected != null) TextInputDialog(selected.text, { dialog = null }) { t ->
            onUpdate(selected.id) { it.copy(text = t) }; dialog = null
        }
    }
}
