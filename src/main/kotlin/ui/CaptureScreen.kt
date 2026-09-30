package ui

import adb.AdbDevice
import adb.AdbService
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image as SkiaImage
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import kotlin.math.min
import kotlin.math.roundToInt

private data class KeyAction(val label: String, val icon: ImageVector, val keyCode: Int)

@Composable
fun CaptureScreen(device: AdbDevice) {
    val scope = rememberCoroutineScope()
    var screenshotBytes by remember { mutableStateOf<ByteArray?>(null) }
    var screenshotImage by remember { mutableStateOf<ImageBitmap?>(null) }
    var isCapturing by remember { mutableStateOf(false) }
    var isRecording by remember { mutableStateOf(AdbService.isRecording(device.serial)) }
    var isStoppingRecording by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var inputText by remember { mutableStateOf("") }
    var liveView by remember { mutableStateOf(false) }
    var previewSize by remember { mutableStateOf(IntSize.Zero) }
    val scrcpyAvailable = remember { AdbService.findScrcpy() != null }

    LaunchedEffect(device.serial) {
        isRecording = AdbService.isRecording(device.serial)
        liveView = false
        feedback = null
    }

    LaunchedEffect(liveView, device.serial) {
        if (!liveView) return@LaunchedEffect
        while (isActive && liveView) {
            AdbService.screenshot(device.serial).fold(
                onSuccess = { bytes ->
                    screenshotBytes = bytes
                    decodeImage(bytes)?.let { screenshotImage = it }
                },
                onFailure = {
                    feedback = false to "Live view stopped: ${it.message}"
                    liveView = false
                }
            )
            delay(120)
        }
    }

    fun capture() {
        scope.launch {
            isCapturing = true
            val result = AdbService.screenshot(device.serial)
            result.fold(
                onSuccess = { bytes ->
                    screenshotBytes = bytes
                    screenshotImage = decodeImage(bytes)
                    feedback = null
                },
                onFailure = { feedback = false to "Screenshot failed: ${it.message}" }
            )
            isCapturing = false
        }
    }

    fun saveScreenshot() {
        val bytes = screenshotBytes ?: return
        scope.launch { savePng(bytes, "screenshot-${device.serial}.png")?.let { feedback = it } }
    }

    fun toggleRecording() {
        scope.launch {
            if (!isRecording) {
                val result = AdbService.startRecording(device.serial)
                result.fold(
                    onSuccess = { isRecording = true; feedback = true to "Recording started (max 3 min)" },
                    onFailure = { feedback = false to "Recording failed: ${it.message}" }
                )
            } else {
                val target = chooseSaveFile("Save recording…", "recording-${device.serial}.mp4")
                    ?.absolutePath ?: return@launch
                isStoppingRecording = true
                val result = AdbService.stopRecording(device.serial, target)
                isStoppingRecording = false
                isRecording = false
                feedback = result.fold(
                    onSuccess = { true to "Saved: $it" },
                    onFailure = { false to "Recording failed: ${it.message}" }
                )
            }
        }
    }

    val keyActions = listOf(
        KeyAction("Back", Icons.AutoMirrored.Filled.ArrowBack, 4),
        KeyAction("Home", Icons.Filled.Home, 3),
        KeyAction("Recents", Icons.Filled.ViewCarousel, 187),
        KeyAction("Power", Icons.Filled.PowerSettingsNew, 26),
        KeyAction("Vol +", Icons.Filled.VolumeUp, 24),
        KeyAction("Vol −", Icons.Filled.VolumeDown, 25),
        KeyAction("Enter", Icons.Filled.KeyboardReturn, 66),
        KeyAction("Delete", Icons.Filled.Backspace, 67),
    )

    CaptureLayout(
        controls = {
            CaptureFeedback(feedback)

            CaptureSection("Live View", Icons.Filled.PlayCircle) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = { liveView = !liveView },
                        colors = if (liveView) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        else ButtonDefaults.buttonColors()
                    ) {
                        Icon(
                            if (liveView) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                            null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(if (liveView) "Stop Live View" else "Start Live View")
                    }
                    if (liveView) {
                        Text("● LIVE", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
                    }
                }
                Text(
                    "~1–2 fps via screencap. Click on the preview to tap, drag to swipe.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            ScreenshotSection(
                isCapturing = isCapturing,
                captureEnabled = !liveView,
                canSave = screenshotBytes != null,
                onCapture = { capture() },
                onSave = { saveScreenshot() },
            )

            RecordingSection(
                isRecording = isRecording,
                isBusy = isStoppingRecording,
                onToggle = { toggleRecording() },
                hint = "screenrecord stops automatically after 3 minutes",
            )

            CaptureSection("Mirror (scrcpy)", Icons.Filled.Cast) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        enabled = scrcpyAvailable,
                        onClick = {
                            scope.launch {
                                AdbService.launchScrcpy(device.serial).onFailure {
                                    feedback = false to (it.message ?: "scrcpy failed")
                                }
                            }
                        }
                    ) {
                        Icon(Icons.Filled.Cast, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Launch scrcpy")
                    }
                }
                if (!scrcpyAvailable) {
                    Text(
                        "scrcpy not found — install with: brew install scrcpy",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            CaptureSection("Remote Input", Icons.Filled.Keyboard) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        label = { Text("Send text") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                    )
                    IconButton(
                        enabled = inputText.isNotEmpty(),
                        onClick = {
                            scope.launch {
                                AdbService.inputText(device.serial, inputText)
                                inputText = ""
                            }
                        }
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, "Send", modifier = Modifier.size(18.dp))
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    keyActions.take(4).forEach { action ->
                        OutlinedButton(
                            onClick = { scope.launch { AdbService.keyEvent(device.serial, action.keyCode) } },
                            contentPadding = PaddingValues(horizontal = 8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(action.icon, action.label, modifier = Modifier.size(16.dp))
                        }
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    keyActions.drop(4).forEach { action ->
                        OutlinedButton(
                            onClick = { scope.launch { AdbService.keyEvent(device.serial, action.keyCode) } },
                            contentPadding = PaddingValues(horizontal = 8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(action.icon, action.label, modifier = Modifier.size(16.dp))
                        }
                    }
                }
                Text(
                    "Back · Home · Recents · Power / Vol+ · Vol− · Enter · Delete",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        preview = {
            val image = screenshotImage
            CapturePreview(
                image = image,
                emptySubtitle = "Capture the device screen or start Live View",
                imageModifier = if (image == null) Modifier else Modifier
                    .onSizeChanged { previewSize = it }
                    .pointerInput(liveView, image.width, image.height) {
                        if (!liveView) return@pointerInput
                        detectTapGestures { offset ->
                            mapToDevice(offset, image, previewSize)?.let { (x, y) ->
                                scope.launch { AdbService.tap(device.serial, x, y) }
                            }
                        }
                    }
                    .pointerInput(liveView, image.width, image.height) {
                        if (!liveView) return@pointerInput
                        var start: Offset? = null
                        var last = Offset.Zero
                        var startTime = 0L
                        detectDragGestures(
                            onDragStart = {
                                start = it
                                last = it
                                startTime = System.currentTimeMillis()
                            },
                            onDrag = { change, _ -> last = change.position },
                            onDragCancel = { start = null },
                            onDragEnd = {
                                val s = start ?: return@detectDragGestures
                                start = null
                                val from = mapToDevice(s, image, previewSize) ?: return@detectDragGestures
                                val to = mapToDevice(last, image, previewSize) ?: return@detectDragGestures
                                val duration = (System.currentTimeMillis() - startTime)
                                    .coerceIn(50, 2000).toInt()
                                scope.launch {
                                    AdbService.swipe(device.serial, from.first, from.second, to.first, to.second, duration)
                                }
                            }
                        )
                    },
            ) {
                if (liveView) {
                    Text(
                        "LIVE",
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .background(MaterialTheme.colorScheme.error, RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
        },
    )
}

/** Maps a pointer position on the letterboxed preview to device pixel coordinates. */
private fun mapToDevice(offset: Offset, image: ImageBitmap, box: IntSize): Pair<Int, Int>? {
    if (box.width == 0 || box.height == 0) return null
    val scale = min(box.width / image.width.toFloat(), box.height / image.height.toFloat())
    val left = (box.width - image.width * scale) / 2f
    val top = (box.height - image.height * scale) / 2f
    val x = (offset.x - left) / scale
    val y = (offset.y - top) / scale
    if (x < 0 || y < 0 || x >= image.width || y >= image.height) return null
    return x.roundToInt() to y.roundToInt()
}

// --- Shared with IosCaptureScreen ---

/** Controls column on the left, screenshot preview well on the right. */
@Composable
fun CaptureLayout(controls: @Composable ColumnScope.() -> Unit, preview: @Composable () -> Unit) {
    Row(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .width(360.dp)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Screen", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            controls()
        }

        Box(
            Modifier
                .width(1.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.outline)
        )

        Box(
            modifier = Modifier.weight(1f).fillMaxHeight().background(Bench.Well).padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            preview()
        }
    }
}

/** Success/error card at the top of the controls; `true` means success. */
@Composable
fun CaptureFeedback(feedback: Pair<Boolean, String>?) {
    AnimatedFade(feedback) { (success, msg) ->
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (success) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.errorContainer
            ),
            border = appCardBorder(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                msg,
                modifier = Modifier.padding(12.dp),
                color = if (success) MaterialTheme.colorScheme.onSecondaryContainer
                else MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
fun ScreenshotSection(
    isCapturing: Boolean,
    captureEnabled: Boolean,
    canSave: Boolean,
    onCapture: () -> Unit,
    onSave: () -> Unit,
) {
    CaptureSection("Screenshot", Icons.Filled.Screenshot) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(enabled = !isCapturing && captureEnabled, onClick = onCapture) {
                Icon(Icons.Filled.CameraAlt, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Capture")
            }
            OutlinedButton(enabled = canSave, onClick = onSave) {
                Icon(Icons.Filled.Save, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Save PNG")
            }
            if (isCapturing) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        }
    }
}

/** Start/stop button with REC badge; [isBusy] disables it while a start or stop is in flight. */
@Composable
fun RecordingSection(isRecording: Boolean, isBusy: Boolean, onToggle: () -> Unit, hint: String) {
    CaptureSection("Screen Recording", Icons.Filled.Videocam) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(
                enabled = !isBusy,
                onClick = onToggle,
                colors = if (isRecording) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                else ButtonDefaults.buttonColors()
            ) {
                Icon(
                    if (isRecording) Icons.Filled.Stop else Icons.Filled.FiberManualRecord,
                    null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(if (isRecording) "Stop & Save" else "Start Recording")
            }
            if (isBusy) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            if (isRecording && !isBusy) {
                Text("● REC", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
            }
        }
        Text(
            hint,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** The last screenshot scaled to fit, or an empty state; [overlay] draws on top (e.g. a LIVE badge). */
@Composable
fun CapturePreview(
    image: ImageBitmap?,
    emptySubtitle: String,
    imageModifier: Modifier = Modifier,
    overlay: @Composable BoxScope.() -> Unit = {},
) {
    if (image != null) {
        Box(Modifier.fillMaxSize()) {
            Image(
                bitmap = image,
                contentDescription = "Screenshot",
                modifier = Modifier.fillMaxSize().then(imageModifier)
            )
            overlay()
        }
    } else {
        EmptyState(
            icon = Icons.Filled.Screenshot,
            title = "No screenshot yet",
            subtitle = emptySubtitle,
        )
    }
}

fun decodeImage(bytes: ByteArray): ImageBitmap? =
    runCatching { SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()

/** Native save dialog; null when the user cancels. */
suspend fun chooseSaveFile(title: String, fileName: String): File? = withContext(Dispatchers.Swing) {
    val dialog = FileDialog(null as Frame?, title, FileDialog.SAVE)
    dialog.file = fileName
    dialog.isVisible = true
    val dir = dialog.directory
    val name = dialog.file
    if (dir != null && name != null) File(dir, name) else null
}

/** Asks where to save [bytes] and writes them; returns the feedback to show, or null when cancelled. */
suspend fun savePng(bytes: ByteArray, fileName: String): Pair<Boolean, String>? {
    val target = chooseSaveFile("Save screenshot…", fileName) ?: return null
    return withContext(Dispatchers.IO) {
        runCatching { target.writeBytes(bytes) }.fold(
            onSuccess = { true to "Saved: ${target.absolutePath}" },
            onFailure = { false to "Save failed: ${it.message}" }
        )
    }
}

@Composable
fun CaptureSection(title: String, icon: ImageVector, content: @Composable ColumnScope.() -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = appCardBorder(),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }
            content()
        }
    }
}
