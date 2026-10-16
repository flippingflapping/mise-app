package app.mise.core.search

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RecipeSearchTest {
    private val curry = SearchDoc("Sri Lankan Lamb Curry", listOf("sri lankan", "curry"), listOf("lamb", "pandan leaf"))
    private val brulee = SearchDoc("Crème brûlée", listOf("dessert"), listOf("cream", "egg yolks"))

    @Test fun matchesTitleTagsAndIngredients() {
        assertThat(RecipeSearch.matches(curry, "lamb")).isTrue()
        assertThat(RecipeSearch.matches(curry, "pandan")).isTrue()
        assertThat(RecipeSearch.matches(curry, "LANKAN curry")).isTrue()
        assertThat(RecipeSearch.matches(curry, "lamb dessert")).isFalse()
        assertThat(RecipeSearch.matches(brulee, "creme brulee")).isTrue()
        assertThat(RecipeSearch.matches(brulee, "")).isTrue()
    }

    @Test fun titleRanksAboveIngredient() {
        val a = SearchDoc("Pandan cake", emptyList(), emptyList())
        assertThat(RecipeSearch.score(a, "pandan")).isGreaterThan(RecipeSearch.score(curry, "pandan"))
    }
}
