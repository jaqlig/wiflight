package io.github.jaqlig.wiflight

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Log changes recompose this card, leaving the controls and automation state alone. */
@Composable
internal fun ActivityLogHeader(store: FlightStore, expanded: Boolean, onExpandedChange: (Boolean) -> Unit) {
    val logs by store.logs.collectAsStateWithLifecycle()
    Section {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Activity", style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = { store.clearLog(); onExpandedChange(false) }) { Text("Clear") }
        }
        Text(if (logs.isEmpty()) "Changes and errors appear here. Logs stay on this device."
            else "Recent changes · stored only on this device", style = MaterialTheme.typography.bodySmall)
        if (logs.isNotEmpty()) TextButton(onClick = { onExpandedChange(!expanded) }) {
            Text(if (expanded) "Hide logs (${logs.size})" else "Show logs (${logs.size})")
        }
    }
}
