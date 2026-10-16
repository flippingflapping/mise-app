package app.mise.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.mise.core.format.Fractions
import app.mise.core.model.Ingredient
import app.mise.core.model.MeasureUnit
import app.mise.core.scale.ScaleByIngredient
import app.mise.core.units.Units

/** Parses "400", "1.5", "1,5", "1½", "1 1/2", "3/4". */
fun parseAmount(text: String): Double? {
    val t = text.trim().replace(',', '.')
    if (t.isEmpty()) return null
    val glyphs = mapOf('½' to 0.5, '¼' to 0.25, '¾' to 0.75, '⅓' to 1.0 / 3, '⅔' to 2.0 / 3, '⅛' to 0.125)
    glyphs[t.last()]?.let { frac -> return (t.dropLast(1).trim().ifEmpty { "0" }.toDoubleOrNull() ?: return null) + frac }
    val parts = t.split(Regex("\\s+"))
    fun simple(s: String): Double? =
        if ('/' in s) s.split('/').let { (a, b) -> a.toDoubleOrNull()?.let { x -> b.toDoubleOrNull()?.takeIf { it != 0.0 }?.let { x / it } } }
        else s.toDoubleOrNull()
    return when (parts.size) {
        1 -> simple(parts[0])
        2 -> simple(parts[0])?.let { w -> simple(parts[1])?.let { w + it } }
        else -> null
    }?.takeIf { it > 0 }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ScaleByIngredientDialog(
    ingredient: Ingredient,
    baseServings: Double,
    onDismiss: () -> Unit,
    onApply: (Double, MeasureUnit?) -> Unit,
) {
    val units = remember(ingredient) { ScaleByIngredient.inputUnits(ingredient) }
    var unit by remember { mutableStateOf(ingredient.unit) }
    var text by remember { mutableStateOf("") }
    val amount = parseAmount(text)
    val factor = amount?.let { ScaleByIngredient.factor(ingredient, it, unit) }
    fun label(u: MeasureUnit?) = u?.let { Units.label(it, 2.0) } ?: ingredient.shortName

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Scale to what you have") },
        text = {
            Column {
                Text("How much ${ingredient.shortName} have you got?", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Amount") },
                    singleLine = true,
                    isError = text.isNotBlank() && amount == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (amount != null && factor != null) onApply(amount, unit) }),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (units.size > 1) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        units.forEach { u ->
                            FilterChip(selected = u == unit, onClick = { unit = u }, label = { Text(label(u)) })
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                val preview = factor?.let { "Makes ${Fractions.decimal(baseServings * it)} servings (×${Fractions.decimal(it)})" }
                    ?: "Everything else rescales to match."
                Text(preview, style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = {
            TextButton(onClick = { if (amount != null) onApply(amount, unit) }, enabled = factor != null) { Text("Scale recipe") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
