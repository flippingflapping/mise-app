package app.mise.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.mise.TestFixtures
import app.mise.data.source.BundledSampleSource
import app.mise.data.source.FolderImportSource
import app.mise.data.source.JavaFileTree
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SourcesTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun bundledSampleIsTheSharedFixture() = runTest {
        val s = BundledSampleSource(ApplicationProvider.getApplicationContext<android.app.Application>().assets)
        val list = s.listRecipes(null)
        assertThat(list.map { it.id }).containsExactly("sri-lankan-lamb-curry-for-kothu")
        assertThat(s.getRecipe(list.single().id)).isEqualTo(TestFixtures.lamb)
        assertThat(s.getSourceText(list.single().id)).isEqualTo(TestFixtures.lambRaw)
        assertThat(s.capabilities.supportsAnnotations).isFalse()
    }

    @Test fun folderSourceReadsRecipesAndSkipsJunk() = runTest {
        val dir = tmp.newFolder("recipes")
        dir.resolve("sri-lankan-lamb-curry-for-kothu.json").writeText(TestFixtures.lambJson)
        dir.resolve("sri-lankan-lamb-curry-for-kothu.source.txt").writeText("raw")
        dir.resolve("sri-lankan-lamb-curry-for-kothu.user.json").writeText("""{"favorite":true}""")
        dir.resolve("broken.json").writeText("{nope")
        dir.resolve("notes.txt").writeText("hello")
        val s = FolderImportSource(listOf(JavaFileTree(dir)))
        val list = s.listRecipes(null)
        assertThat(list.map { it.id }).containsExactly("sri-lankan-lamb-curry-for-kothu")
        assertThat(list.single().etag).isNotNull()
        assertThat(s.getRecipe("sri-lankan-lamb-curry-for-kothu").title).isEqualTo("Sri Lankan Lamb Curry for Kothu")
        assertThat(s.getSourceText("sri-lankan-lamb-curry-for-kothu")).isEqualTo("raw")
        assertThat(s.testConnection()).isEqualTo("Found 1 recipe (1 file couldn't be read)")
    }
}
