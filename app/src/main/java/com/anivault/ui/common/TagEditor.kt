@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.anivault.ui.common

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions

/** Pure tag rules (unit-tested): trimmed, no empty tags, case-insensitive uniqueness, short. */
object Tags {
    fun clean(raw: String): String = raw.trim().replace(Regex("\\s+"), " ").take(24)
    fun add(tags: List<String>, raw: String): List<String> {
        val t = clean(raw)
        return if (t.isEmpty() || tags.any { it.equals(t, true) }) tags else tags + t
    }
}

/** Chips for the user's own tags with an input to add more, and suggestions from tags used elsewhere in the library. */
@Composable
fun TagEditor(tags: List<String>, suggestions: List<String>, onChange: (List<String>) -> Unit) {
    var text by remember { mutableStateOf("") }
    val commit = { onChange(Tags.add(tags, text)); text = "" }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("My tags", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        if (tags.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            tags.forEach { t ->
                InputChip(
                    selected = true, onClick = { onChange(tags - t) }, label = { Text(t) },
                    trailingIcon = { Icon(Icons.Default.Close, "Remove tag $t", Modifier.size(16.dp)) }
                )
            }
        }
        OutlinedTextField(
            value = text, onValueChange = { text = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Add a tag, e.g. favourite, rewatch") },
            trailingIcon = { IconButton(onClick = commit, enabled = Tags.clean(text).isNotEmpty()) { Icon(Icons.Default.Add, "Add tag") } },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { commit() })
        )
        val more = suggestions.filter { s -> tags.none { it.equals(s, true) } }.take(8)
        if (more.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            more.forEach { s -> SuggestionChip(onClick = { onChange(Tags.add(tags, s)) }, label = { Text(s) }) }
        }
    }
}
