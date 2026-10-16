package app.mise

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mise.data.settings.Settings
import app.mise.data.settings.SettingsRepository
import app.mise.ui.LaunchRequest
import app.mise.ui.MiseNavHost
import app.mise.ui.theme.MiseTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var settingsRepository: SettingsRepository

    private var launch by mutableStateOf<LaunchRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) launch = requestFrom(intent)
        setContent {
            val settings by settingsRepository.settings.collectAsStateWithLifecycle(Settings())
            MiseTheme(settings.theme, settings.textScale) {
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
        val data = intent.data
        if (intent.action == Intent.ACTION_VIEW && data?.scheme == "mise") return LaunchRequest.Pair(data.toString())
        return null
    }

    companion object {
        const val EXTRA_COOK_RECIPE = "cook_recipe_id"
    }
}
