package app.mise.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.mise.core.model.Heat

@Composable
fun HeatBadge(heat: Heat, modifier: Modifier = Modifier, large: Boolean = false) {
    val (bg, fg) = when (heat) {
        Heat.LOW -> Color(0xFFFFE9A8) to Color(0xFF4A3B00)
        Heat.MEDIUM_LOW -> Color(0xFFFFD79A) to Color(0xFF4B2E00)
        Heat.MEDIUM -> Color(0xFFFFC089) to Color(0xFF4F2400)
        Heat.MEDIUM_HIGH -> Color(0xFFFFA27A) to Color(0xFF561A00)
        Heat.HIGH -> Color(0xFFFF8566) to Color(0xFF5C0D00)
    }
    Surface(
        color = bg,
        contentColor = fg,
        shape = MaterialTheme.shapes.small,
        modifier = modifier.semantics(mergeDescendants = true) { contentDescription = "${heat.label} heat" },
    ) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.LocalFireDepartment, null, Modifier.size(if (large) 22.dp else 16.dp))
            Spacer(Modifier.size(4.dp))
            Text(heat.label, style = if (large) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
fun StatusBanner(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Outlined.CloudOff,
    container: Color = MaterialTheme.colorScheme.surfaceVariant,
) {
    Card(colors = CardDefaults.cardColors(containerColor = container), modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null)
            Spacer(Modifier.size(12.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    action: String? = null,
    onAction: () -> Unit = {},
) {
    Column(
        modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Text(body, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        if (action != null) Button(onClick = onAction) { Text(action) }
    }
}
