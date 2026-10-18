package app.mise

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import app.mise.data.repo.RecipeRepository
import app.mise.data.settings.Settings
import app.mise.data.settings.SourceKind
import app.mise.ui.LocalNetworkPermission
import app.mise.ui.rememberLocalNetworkGate
import kotlinx.coroutines.launch
import app.mise.data.settings.SettingsRepository
import app.mise.ui.LaunchRequest
import app.mise.ui.MiseNavHost
import app.mise.ui.theme.MiseTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var repository: RecipeRepository

    private var launch by mutableStateOf<LaunchRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) launch = requestFrom(intent)
        setContent {
            val settings by settingsRepository.settings.collectAsStateWithLifecycle(Settings())
            MiseTheme(settings.theme, settings.textScale) {
                // Android 17+: ask for local network access up front when the PC server is the source,
                // then sync (the first sync attempt would have timed out without it).
                val gate = rememberLocalNetworkGate()
                val usesPc = settings.effectiveSource == SourceKind.LAN
                LaunchedEffect(usesPc) {
                    if (usesPc && LocalNetworkPermission.needed(this@MainActivity)) {
                        gate { lifecycleScope.launch { repository.sync() } }
                    }
                }
                MiseNavHost(launch, onLaunchHandled = { launch = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        requestFrom(intent)?.let { launch = it }
    }

    private fun requestFrom(intent: Intent?): LaunchRequest? {
        intent ?: return null
        intent.getStringExtra(EXTRA_COOK_RECIPE)?.let { return LaunchRequest.Cook(it) }
        intent.getLongExtra(EXTRA_FEAST, -1).takeIf { it >= 0 }?.let { return LaunchRequest.Meal(it) }
        if (intent.action == Intent.ACTION_SEND && intent.type?.startsWith("text/") == true) {
            return LaunchRequest.AddRecipe(intent.getStringExtra(Intent.EXTRA_TEXT), intent.getStringExtra(Intent.EXTRA_SUBJECT))
        }
        val data = intent.data
        if (intent.action == Intent.ACTION_VIEW && data?.scheme == "mise") return LaunchRequest.Pair(data.toString())
        return null
    }

    companion object {
        const val EXTRA_COOK_RECIPE = "cook_recipe_id"
        const val EXTRA_FEAST = "feast_id"
    }
}
