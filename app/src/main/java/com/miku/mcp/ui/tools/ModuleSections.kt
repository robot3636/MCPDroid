package com.miku.mcp.ui.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

// Slider that sets the maximum size of a single download
@Composable
internal fun DownloadLimitSection(maxMb: Int, onChange: (Int) -> Unit) {
    // Fixed steps are easier to pick than a free value
    val options = remember { listOf(10, 25, 50, 100, 250, 500) }
    // Local position keeps the slider smooth; the value is saved when the finger lifts
    var position by remember(maxMb) {
        mutableFloatStateOf(options.indexOf(maxMb).coerceAtLeast(0).toFloat())
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionLabel("Limits")
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLarge,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
        ) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Maximum download size", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${options[position.roundToInt()]} MB",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Slider(
                    value = position,
                    onValueChange = { position = it },
                    onValueChangeFinished = { onChange(options[position.roundToInt()]) },
                    valueRange = 0f..(options.size - 1).toFloat(),
                    steps = options.size - 2
                )
                Text(
                    "Larger downloads are cancelled. Applies from the next download.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}