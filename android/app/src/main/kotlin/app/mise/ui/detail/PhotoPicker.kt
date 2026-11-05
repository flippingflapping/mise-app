package app.mise.ui.detail

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import app.mise.photos.PhotoProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Take a photo with the camera, or choose one from the gallery (no storage permission needed). */
class PhotoPicker(val takePhoto: () -> Unit, val choosePhoto: () -> Unit)

/** [onPhoto] gets a ready-to-store JPEG (upright, at most 1600 px), or an error message. */
@Composable
fun rememberPhotoPicker(onPhoto: (jpeg: ByteArray) -> Unit, onError: (String) -> Unit): PhotoPicker {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    fun process(uri: Uri) = scope.launch {
        val jpeg = runCatching { withContext(Dispatchers.IO) { PhotoProcessor.prepare(context, uri) } }
        jpeg.onSuccess(onPhoto).onFailure { onError("That photo couldn't be read") }
    }
    // Where the camera app writes the photo; made only when it's needed.
    var cameraUri by remember { mutableStateOf<Uri?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        cameraUri?.takeIf { ok }?.let(::process)
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(::process) }
    return remember(camera, gallery) {
        PhotoPicker(
            takePhoto = {
                runCatching { FileProvider.getUriForFile(context, "${context.packageName}.photos", cameraFile(context)) }
                    .onSuccess { uri -> cameraUri = uri; camera.launch(uri) }
                    .onFailure { onError("The camera isn't available") }
            },
            choosePhoto = { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        )
    }
}

private fun cameraFile(context: Context): File = File(File(context.cacheDir, "camera").apply { mkdirs() }, "photo.jpg")
