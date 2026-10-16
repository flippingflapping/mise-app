package app.mise.ui

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.mise.TestFixtures
import app.mise.core.model.MeasureUnit
import app.mise.ui.detail.ScaleByIngredientDialog
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScaleDialogTest {
    @get:Rule val compose = createComposeRule()

    @Test fun typeAnAmountPickAUnitAndApply() {
        var applied: Pair<Double, MeasureUnit?>? = null
        compose.setContent {
            ScaleByIngredientDialog(TestFixtures.lamb.ingredients[0], 2.0, onDismiss = {}, onApply = { a, u -> applied = a to u })
        }
        compose.onNodeWithText("How much lamb have you got?").assertExists()
        compose.onNodeWithText("Scale recipe").assertIsNotEnabled()
        compose.onNodeWithText("Amount").performTextInput("400")
        compose.onNodeWithText("Makes 1.6 servings (×0.8)").assertExists()
        compose.onNodeWithText("Scale recipe").performClick()
        assertThat(applied).isEqualTo(400.0 to MeasureUnit.G)
    }

    @Test fun otherUnitsOfTheSameKind() {
        var applied: Pair<Double, MeasureUnit?>? = null
        compose.setContent {
            ScaleByIngredientDialog(TestFixtures.lamb.ingredients[0], 2.0, onDismiss = {}, onApply = { a, u -> applied = a to u })
        }
        compose.onNodeWithText("Amount").performTextInput("1")
        compose.onNodeWithText("lb").performClick()
        compose.onNodeWithText("Makes 1.81 servings (×0.91)").assertExists() // 1 lb = 453.6 g of 500 g
        compose.onNodeWithText("ml").assertDoesNotExist() // only units of weight are offered
        compose.onNodeWithText("Scale recipe").performClick()
        assertThat(applied).isEqualTo(1.0 to MeasureUnit.LB)
    }
}
