package app.mise.core.model

import app.mise.core.Fixtures
import com.google.common.truth.Truth.assertThat
import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import org.junit.Test

/** Kotlin side of the contract: fixtures ⇄ schema ⇄ Kotlin models. */
class ContractTest {
    private val schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
        .getSchema(Fixtures.schemaFile.readText(), InputFormat.JSON)

    private fun schemaErrors(json: String): List<String> =
        schema.validate(json, InputFormat.JSON).map { it.toString() }

    @Test fun fixturesValidateAgainstSchema() {
        assertThat(Fixtures.expected).isNotEmpty()
        for (f in Fixtures.expected) {
            assertThat(schemaErrors(f.readText())).isEmpty()
        }
    }

    @Test fun fixturesDecodeAndReEncodeToSchemaValidEquivalentJson() {
        for (f in Fixtures.expected) {
            val original = Json.parseToJsonElement(f.readText())
            val recipe = RecipeJson.decodeRecipe(f.readText())
            val encoded = RecipeJson.encodeRecipe(recipe)
            assertThat(schemaErrors(encoded)).isEmpty()
            assertThat(normalize(Json.parseToJsonElement(encoded))).isEqualTo(normalize(original))
        }
    }

    @Test fun fixtureContentIsReadCorrectly() {
        val r = Fixtures.lambCurry
        assertThat(r.recipeYield.baseServings).isEqualTo(2.0)
        assertThat(r.ingredients).hasSize(26)
        assertThat(r.ingredient("0021")!!.amountMax).isEqualTo(12.0)
        assertThat(r.ingredient("0009")!!.unit).isNull()
        assertThat(r.ingredient("0025")!!.optional).isTrue()
        assertThat(r.steps[1].heat).isEqualTo(Heat.MEDIUM_HIGH)
        assertThat(r.status).isEqualTo(RecipeStatus.NEEDS_REVIEW)
        assertThat(r.ingredientGroups().map { it.first }).containsExactly("Marinade", "Curry").inOrder()
    }

    @Test fun unknownFieldsAndEnumValuesAreTolerated() {
        val json = """
            {"schema_version":1,"id":"x","title":"X","yield":{"base_servings":1},
             "future_field":{"a":1},"status":"archived",
             "ingredients":[{"id":"0001","name":"egg","short_name":"egg","amount":1,"unit":"handful","colour":"red"}],
             "steps":[{"id":"s1","title":"Go","content":"Use {0001}.","heat":"volcanic"}]}
        """
        val r = RecipeJson.decodeRecipe(json)
        assertThat(r.ingredients.single().unit).isNull()
        assertThat(r.steps.single().heat).isNull()
        assertThat(r.status).isEqualTo(RecipeStatus.NEEDS_REVIEW)
    }

    @Test fun schemaVersionZeroIsMigrated() {
        val v0 = """
            {"id":"old","title":"Old","servings":4,"cook_notes":"less salt\nmore lime",
             "ingredients":[],"steps":[{"id":"s1","title":"Go","content":"Cook."}],"status":"ready"}
        """
        val obj = Json.parseToJsonElement(v0).jsonObject
        assertThat(RecipeMigrations.versionOf(obj)).isEqualTo(0)
        val r = RecipeJson.decodeRecipe(v0)
        assertThat(r.schemaVersion).isEqualTo(1)
        assertThat(r.recipeYield.baseServings).isEqualTo(4.0)
        assertThat(r.cookNotes).containsExactly("less salt", "more lime").inOrder()
        assertThat(schemaErrors(RecipeJson.encodeRecipe(r))).isEmpty()
    }

    @Test fun newerSchemaVersionIsDetectedAndStillParsed() {
        val v9 = """{"schema_version":9,"id":"n","title":"N","yield":{"base_servings":2},"steps":[{"id":"s1","title":"t","content":"c"}],"status":"ready"}"""
        val obj = Json.parseToJsonElement(v9).jsonObject
        assertThat(RecipeMigrations.isNewerThanSupported(obj)).isTrue()
        assertThat(RecipeJson.decodeRecipe(v9).title).isEqualTo("N")
    }

    /** JSON equality that treats 500 and 500.0 as the same number. */
    private fun normalize(e: JsonElement): Any? = when (e) {
        is JsonNull -> null
        is JsonObject -> e.mapValues { normalize(it.value) }
        is JsonArray -> e.map { normalize(it) }
        is JsonPrimitive -> if (e.isString) e.content else e.booleanOrNull ?: e.doubleOrNull
    }
}
