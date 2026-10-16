package app.mise.data.source

import app.mise.core.model.Annotations
import app.mise.core.model.Recipe
import app.mise.core.model.RecipeSummary
import app.mise.data.settings.SourceKind
import java.time.Instant

/**
 * Placeholder for a future hosted backend.
 *
 * TODO: implement against a cloud API with the same shape as the LAN server
 *  (`GET /recipes?since=`, `GET /recipes/{id}` with ETags, `PUT /recipes/{id}/annotations`),
 *  authenticated with a per-user token. [LanServerSource] is the reference implementation.
 */
class CloudApiSource : RecipeSource {
    override val kind = SourceKind.CLOUD
    override val capabilities = SourceCapabilities(
        readOnly = false, supportsAnnotations = true, supportsSourceText = true, supportsImages = true, supportsSince = true,
    )

    private fun notYet(): Nothing =
        throw SourceException(SourceException.Reason.NOT_CONFIGURED, "Cloud sync isn't available yet")

    override suspend fun listRecipes(since: Instant?): List<RecipeSummary> = notYet()
    override suspend fun getRecipe(id: String): Recipe = notYet()
    override suspend fun putAnnotations(id: String, a: Annotations) = notYet()
}
