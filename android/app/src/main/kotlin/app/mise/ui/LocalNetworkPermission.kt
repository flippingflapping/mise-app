package app.mise.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
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
 * Android 17 blocks apps that target API 37 from the local network (connections just time out)
 * unless the user grants ACCESS_LOCAL_NETWORK ("Nearby devices"). The PC server lives on the local
 * network, so Mise asks before talking to it.
 */
object LocalNetworkPermission {
    const val PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"
    private const val ANDROID_17 = 37

    fun needed(context: Context): Boolean =
        Build.VERSION.SDK_INT >= ANDROID_17 &&
            ContextCompat.checkSelfPermission(context, PERMISSION) != PackageManager.PERMISSION_GRANTED

    fun appSettingsIntent(context: Context) =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    const val EXPLANATION =
        "Mise needs the “Nearby devices” permission to reach your PC on your home network (Android 17 and newer). " +
            "Allow it in App info → Permissions → Nearby devices."
}

/**
 * Runs `block` once local network access is allowed — asking first if needed. `onDenied` is called
 * if the user says no (the block still runs, so the user sees the real result).
 */
class LocalNetworkGate(private val run: (() -> Unit) -> Unit) {
    operator fun invoke(block: () -> Unit) = run(block)
}

@Composable
fun rememberLocalNetworkGate(onDenied: () -> Unit = {}): LocalNetworkGate {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) onDenied()
        pending?.invoke()
        pending = null
    }
    return remember(launcher) {
        LocalNetworkGate { block ->
            if (LocalNetworkPermission.needed(context)) {
                pending = block
                launcher.launch(LocalNetworkPermission.PERMISSION)
            } else {
                block()
            }
        }
    }
}
