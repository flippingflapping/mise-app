package app.mise.ui.timers

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * Wraps "start a timer" so that, on Android 13+, the notification permission is requested the
 * first time. The timer starts either way; without the permission it only alerts in the app.
 */
class TimerStarter(private val launch: (() -> Unit) -> Unit) {
    operator fun invoke(start: () -> Unit) = launch(start)
}

@Composable
fun rememberTimerStarter(onDenied: () -> Unit = {}): TimerStarter {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        pending?.invoke()
        pending = null
        if (!granted) onDenied()
    }
    return remember(launcher) {
        TimerStarter { start ->
            val needs = Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            if (needs) {
                pending = start
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                start()
            }
        }
    }
}
