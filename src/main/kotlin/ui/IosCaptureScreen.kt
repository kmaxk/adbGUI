package ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhoneIphone
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.dp
import ios.IosDevice
import ios.IosService
import ios.isFinishingRecording
import ios.isRecording
import ios.recordingExtension
import ios.screenshot
import ios.startRecording
import ios.stopRecording
import ios.stopRecordingUnattended
import ios.takeRecordingNotice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun IosCaptureScreen(device: IosDevice) {
    val scope = rememberCoroutineScope()
    var screenshotBytes by remember(device.udid) { mutableStateOf<ByteArray?>(null) }
    var screenshotImage by remember(device.udid) { mutableStateOf<ImageBitmap?>(null) }
    var isCapturing by remember(device.udid) { mutableStateOf(false) }
    var isRecording by remember(device.udid) { mutableStateOf(IosService.isRecording(device)) }
    var isRecordingBusy by remember(device.udid) { mutableStateOf(false) }
    var feedback by remember(device.udid) { mutableStateOf<Pair<Boolean, String>?>(null) }
    val fileTag = device.name.replace(Regex("[^A-Za-z0-9._-]+"), "-")

    // Nobody can press "Stop & Save" once the screen is gone (tab left, device switched),
    // so finalize the video into ~/Movies/adbGUI; the next visit shows where it went.
    DisposableEffect(device.udid) {
        onDispose {
            if (IosService.isRecording(device)) {
                @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
                GlobalScope.launch(Dispatchers.IO) { IosService.stopRecordingUnattended(device) }
            }
        }
    }

    // Coming back while an unattended stop is still finalizing: wait for its notice
    LaunchedEffect(device.udid) {
        while (IosService.isFinishingRecording(device)) delay(300)
        IosService.takeRecordingNotice(device)?.let { feedback = true to it }
    }

    fun capture() {
        scope.launch {
            isCapturing = true
            IosService.screenshot(device).fold(
                onSuccess = { bytes ->
                    screenshotBytes = bytes
                    screenshotImage = decodeImage(bytes)
                    feedback = null
                },
                onFailure = {
                    feedback = false to "Screenshot failed: ${it.message}" +
                        if (device.isSimulator) "" else ". Unlock the device and check that Developer Mode is on."
                }
            )
            isCapturing = false
        }
    }

    fun toggleRecording() {
        scope.launch {
            if (!isRecording) {
                isRecordingBusy = true
                IosService.startRecording(device).fold(
                    onSuccess = { isRecording = true; feedback = true to "Recording started" },
                    onFailure = { feedback = false to "Recording failed to start: ${it.message}" }
                )
                isRecordingBusy = false
            } else {
                val target = chooseSaveFile(
                    "Save recording…",
                    "recording-$fileTag.${IosService.recordingExtension(device)}"
                ) ?: return@launch
                isRecordingBusy = true
                val result = IosService.stopRecording(device, target)
                isRecordingBusy = false
                isRecording = false
                feedback = result.fold(
                    onSuccess = { true to "Saved: $it" },
                    onFailure = { false to "Recording failed: ${it.message}" }
                )
            }
        }
    }

    CaptureLayout(
        controls = {
            CaptureFeedback(feedback)

            ScreenshotSection(
                isCapturing = isCapturing,
                captureEnabled = true,
                canSave = screenshotBytes != null,
                onCapture = { capture() },
                onSave = {
                    screenshotBytes?.let { bytes ->
                        scope.launch { savePng(bytes, "screenshot-$fileTag.png")?.let { feedback = it } }
                    }
                },
            )

            RecordingSection(
                isRecording = isRecording,
                isBusy = isRecordingBusy,
                onToggle = { toggleRecording() },
                hint = if (device.isSimulator) "Records until you stop it. Saved as a QuickTime movie (.mov)."
                else "Records until you stop it. The video is copied off the device when you stop, which can take a moment.",
            )

            // Simulator.app is the live view for simulators; phones have no mirror counterpart here
            if (device.isSimulator) {
                SimulatorWindowSection(device, scope) { feedback = it }
            }
        },
        preview = {
            CapturePreview(
                image = screenshotImage,
                emptySubtitle = "Capture the device screen to see it here",
            )
        },
    )
}

@Composable
private fun SimulatorWindowSection(
    device: IosDevice,
    scope: CoroutineScope,
    onFeedback: (Pair<Boolean, String>) -> Unit,
) {
    CaptureSection("Simulator window", Icons.Filled.PhoneIphone) {
        Row {
            Button(onClick = {
                scope.launch {
                    IosService.openSimulatorApp(device).onFailure {
                        onFeedback(false to "Couldn't open Simulator: ${it.message}")
                    }
                }
            }) {
                Icon(Icons.Filled.PhoneIphone, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Open in Simulator")
            }
        }
        Text(
            "Shows the live screen and takes mouse and keyboard input",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
