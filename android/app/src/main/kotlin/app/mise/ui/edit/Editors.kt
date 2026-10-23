package app.mise.ui.edit

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.mise.core.edit.RecipeEditor
import app.mise.core.edit.StepText
import app.mise.core.model.Ingredient
import app.mise.core.model.IngredientEdit
import app.mise.core.model.MeasureUnit
import app.mise.core.model.Recipe
import app.mise.core.model.RecipeYield
import app.mise.core.model.Step
import app.mise.core.model.StepEdit
import app.mise.core.scale.AmountRenderer
import app.mise.core.timer.StepTimers
import app.mise.core.units.UnitMode
import app.mise.core.units.Units

/** "2 tsp minced ginger, heaped" as written (unscaled). */
fun ingredientLine(ing: Ingredient): String {
    val amount = AmountRenderer.render(ing, 1.0, UnitMode.ORIGINAL)
    val qty = if (amount.toTaste) "" else amount.text + " "
    return qty + ing.name + (ing.prep?.let { ", $it" } ?: "") + (if (amount.toTaste) " (to taste)" else "") +
        (if (ing.optional) " (optional)" else "")
}

fun stepTimerText(step: Step): String? = step.timerSeconds?.let { min ->
    val max = step.timerMaxSeconds
    if (max != null && max > min) "${StepTimers.formatDuration(min)}–${StepTimers.formatDuration(max)}" else StepTimers.formatDuration(min)
}

/** What the person has typed for one ingredient; [result] turns it into an edit or explains what's wrong. */
@Stable
class IngredientDraft(private val start: IngredientEdit) {
    var amount by mutableStateOf(RecipeEditor.formatAmount(start.amount))
    var amountMax by mutableStateOf(RecipeEditor.formatAmount(start.amountMax))
    var unit by mutableStateOf(start.unit)
    var name by mutableStateOf(start.name)
    var prep by mutableStateOf(start.prep.orEmpty())
    var optional by mutableStateOf(start.optional)

    fun result(): Pair<IngredientEdit?, List<String>> {
        val problems = mutableListOf<String>()
        val a = RecipeEditor.parseAmount(amount)
        if (amount.isNotBlank() && a == null) problems += "“$amount” isn't a number. Try 2, 1.5, ½ or 1 1/2."
        val m = RecipeEditor.parseAmount(amountMax)
        if (amountMax.isNotBlank() && m == null) problems += "“$amountMax” isn't a number."
        // A short name that matched the name keeps matching it; one that differed ("garlic") stays.
        val short = if (start.shortName == null || start.shortName == start.name) name.trim() else start.shortName
        val edit = IngredientEdit(
            name = name.trim(), shortName = short, prep = prep.trim().ifEmpty { null },
            amount = a, amountMax = m, unit = unit, optional = optional,
        )
        problems += RecipeEditor.problems(edit)
        return (if (problems.isEmpty()) edit else null) to problems
    }
}

@Composable
fun IngredientEditor(
    draft: IngredientDraft,
    problems: List<String>,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = draft.amount, onValueChange = { draft.amount = it }, label = { Text("Amount") },
                placeholder = { Text("to taste") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text), modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = draft.amountMax, onValueChange = { draft.amountMax = it }, label = { Text("up to") },
                singleLine = true, modifier = Modifier.weight(1f),
            )
            UnitPicker(draft.unit) { draft.unit = it }
        }
        OutlinedTextField(
            value = draft.name, onValueChange = { draft.name = it }, label = { Text("Ingredient") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = draft.prep, onValueChange = { draft.prep = it }, label = { Text("Preparation (optional)") },
            placeholder = { Text("finely chopped") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Optional ingredient", Modifier.weight(1f))
            Switch(checked = draft.optional, onCheckedChange = { draft.optional = it })
        }
        Problems(problems)
    }
}

@Composable
private fun UnitPicker(unit: MeasureUnit?, onPick: (MeasureUnit?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        OutlinedButton(onClick = { open = true }) {
            Text(unit?.let { Units.label(it, 2.0) } ?: "items")
            Icon(Icons.Filled.ArrowDropDown, "Choose unit")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("items (no unit: 2 onions)") }, onClick = { onPick(null); open = false })
            MeasureUnit.entries.forEach { u ->
                DropdownMenuItem(text = { Text(Units.label(u, 2.0)) }, onClick = { onPick(u); open = false })
            }
        }
    }
}

/**
 * What the person has typed for one step. [original] is the step as converted: new wording is only
 * stored when it differs from that.
 */
@Stable
class StepDraft(step: Step, private val recipe: Recipe, private val original: Step?) {
    private val startTitle = step.title
    private val startTimer = step.timerSeconds?.let { minutesText(it) }.orEmpty()
    private val startTimerMax = step.timerMaxSeconds?.let { minutesText(it) }.orEmpty()
    private val startWording = StepText.toEditable(step.content, recipe)

    var title by mutableStateOf(startTitle)
    var timer by mutableStateOf(startTimer)
    var timerMax by mutableStateOf(startTimerMax)
    var wording by mutableStateOf(startWording)

    val isChanged: Boolean
        get() = title != startTitle || timer != startTimer || timerMax != startTimerMax || wording != startWording

    /** The ingredient names that can go in {braces}. */
    val names: List<String> get() = StepText.labels(recipe).values.toList()

    fun insert(name: String) {
        wording = wording.trimEnd() + (if (wording.isBlank()) "" else " ") + "{$name}"
    }

    fun result(): Pair<StepEdit?, List<String>> {
        val problems = mutableListOf<String>()
        val t = RecipeEditor.parseAmount(timer)
        if (timer.isNotBlank() && t == null) problems += "“$timer” isn't a number of minutes."
        val m = RecipeEditor.parseAmount(timerMax)
        if (timerMax.isNotBlank() && m == null) problems += "“$timerMax” isn't a number of minutes."
        val content = when (val parsed = StepText.fromEditable(wording, recipe)) {
            is StepText.Parsed.Ok -> parsed.content
            is StepText.Parsed.Problem -> { problems += parsed.message; null }
        }
        val edit = StepEdit(
            title.trim(), t?.let { (it * 60).toInt() }, m?.let { (it * 60).toInt() },
            content = content?.takeIf { it != original?.content },
        )
        problems += RecipeEditor.problems(edit)
        return (if (problems.isEmpty()) edit else null) to problems
    }

    private companion object {
        fun minutesText(seconds: Int) = RecipeEditor.formatAmount(seconds / 60.0)
    }
}

@Composable
fun StepEditor(draft: StepDraft, problems: List<String>, label: String?, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        label?.let { Text(it, style = MaterialTheme.typography.labelLarge) }
        OutlinedTextField(
            value = draft.title, onValueChange = { draft.title = it }, label = { Text("Step title") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = draft.wording, onValueChange = { draft.wording = it }, label = { Text("What to do") },
            minLines = 3, maxLines = 10, modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "{lamb} shows the amount and name, and scales with the servings; [lamb] shows just the name. " +
                "Tap an ingredient to add it:",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            draft.names.forEach { name ->
                AssistChip(onClick = { draft.insert(name) }, label = { Text(name) })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = draft.timer, onValueChange = { draft.timer = it }, label = { Text("Timer (min)") },
                placeholder = { Text("none") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = draft.timerMax, onValueChange = { draft.timerMax = it }, label = { Text("up to (min)") },
                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f),
            )
        }
        Problems(problems)
    }
}

@Stable
class ServingsDraft(start: RecipeYield) {
    var servings by mutableStateOf(RecipeEditor.formatAmount(start.baseServings))
    var label by mutableStateOf(start.label.orEmpty())

    fun result(): Pair<RecipeYield?, List<String>> {
        val n = RecipeEditor.parseAmount(servings)
        val problem = if (servings.isNotBlank() && n == null) "“$servings” isn't a number." else RecipeEditor.servingsProblem(n)
        return if (problem == null) RecipeYield(n!!, label.trim().ifEmpty { null }) to emptyList() else null to listOf(problem)
    }
}

@Composable
fun ServingsEditor(draft: ServingsDraft, problems: List<String>, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = draft.servings, onValueChange = { draft.servings = it }, label = { Text("Makes") },
                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = draft.label, onValueChange = { draft.label = it }, label = { Text("of what (optional)") },
                placeholder = { Text("servings") }, singleLine = true, modifier = Modifier.weight(2f),
            )
        }
        Text(
            "How many the recipe makes as written. To cook for more or fewer, use − / + on the recipe instead.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Problems(problems)
    }
}

@Composable
private fun Problems(problems: List<String>) {
    problems.forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
}

/** Save / Cancel / Back to original / Remove (wraps onto two lines when they don't fit). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EditorButtons(
    onSave: () -> Unit,
    onCancel: (() -> Unit)?,
    onUndo: (() -> Unit)?,
    saveLabel: String = "Save",
    onRemove: (() -> Unit)? = null,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.Center) {
        Button(onClick = onSave) { Text(saveLabel) }
        onCancel?.let { TextButton(onClick = it) { Text("Cancel") } }
        onUndo?.let { TextButton(onClick = it) { Text("Back to original") } }
        onRemove?.let { TextButton(onClick = it) { Text("Remove", color = MaterialTheme.colorScheme.error) } }
    }
}
