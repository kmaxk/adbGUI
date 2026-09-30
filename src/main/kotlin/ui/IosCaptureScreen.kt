package ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Construction
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import ios.IosDevice

// Placeholder until the iOS Capture screen is built.
@Composable
fun IosCaptureScreen(device: IosDevice) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        EmptyState(Icons.Filled.Construction, "Capture for iOS isn't available yet")
    }
}
