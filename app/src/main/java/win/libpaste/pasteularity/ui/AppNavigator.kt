package win.libpaste.pasteularity.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class Destination(val label: String, val icon: ImageVector, val needsShell: Boolean) {
    Home("Home", Icons.Outlined.Home, needsShell = false),
    Terminal("Terminal", Icons.Outlined.Terminal, needsShell = true),
    Files("Files", Icons.Outlined.Folder, needsShell = true),
    Display("Display", Icons.Outlined.Tune, needsShell = true),
    Apps("Apps", Icons.Outlined.Apps, needsShell = true),
    Utilities("Utilities", Icons.Outlined.Build, needsShell = true),
    Settings("Settings", Icons.Outlined.Settings, needsShell = false),
}

class AppNavigator {
    private val currentDestination = MutableStateFlow(Destination.Home)
    private val filesRequest = MutableStateFlow<String?>(null)
    private val setupOpen = MutableStateFlow(false)

    val destination = currentDestination.asStateFlow()
    val requestedFilesPath = filesRequest.asStateFlow()
    val showSetup = setupOpen.asStateFlow()

    fun go(destination: Destination) {
        currentDestination.value = destination
    }

    fun openInFiles(path: String) {
        filesRequest.value = path
        currentDestination.value = Destination.Files
    }

    fun consumeFilesRequest() {
        filesRequest.value = null
    }

    fun openSetup() {
        setupOpen.value = true
    }

    fun closeSetup() {
        setupOpen.value = false
    }
}
