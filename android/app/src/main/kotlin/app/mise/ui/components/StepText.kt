package app.mise.ui.components

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import app.mise.core.model.Recipe
import app.mise.core.render.StepRenderer
import app.mise.core.render.StepSegment
import app.mise.core.units.UnitMode

/**
 * Step instructions with ingredient amounts rendered inline as tappable, highlighted "chips".
 * Unknown placeholders show as a warning chip. TalkBack reads a spoken version (units spelled out).
 */
@Composable
fun StepText(
    content: String,
    recipe: Recipe,
    factor: Double,
    mode: UnitMode,
    onIngredientClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
) {
    val colors = MaterialTheme.colorScheme
    val segments = remember(content, recipe, factor, mode) { StepRenderer.render(content, recipe, factor, mode) }
    val spoken = remember(content, recipe, factor, mode) { StepRenderer.spokenText(content, recipe, factor, mode) }
    val chip = SpanStyle(background = colors.secondaryContainer, color = colors.onSecondaryContainer, fontWeight = FontWeight.SemiBold)
    val nameRef = SpanStyle(fontWeight = FontWeight.SemiBold)
    val warn = SpanStyle(background = colors.errorContainer, color = colors.onErrorContainer, fontWeight = FontWeight.Bold)
    val text: AnnotatedString = buildAnnotatedString {
        for (seg in segments) {
            when (seg) {
                is StepSegment.Text -> append(seg.text)
                is StepSegment.IngredientRef -> withLink(
                    LinkAnnotation.Clickable(
                        tag = seg.ingredientId,
                        styles = TextLinkStyles(style = if (seg.nameOnly) nameRef else chip),
                        linkInteractionListener = { onIngredientClick(seg.ingredientId) },
                    ),
                ) { append(if (seg.nameOnly) seg.text else " ${seg.text} ") }
                is StepSegment.Missing -> withStyle(warn) { append(seg.text) }
            }
        }
    }
    Text(
        text = text,
        style = style,
        color = LocalContentColor.current,
        modifier = modifier.semantics { contentDescription = spoken },
    )
}
