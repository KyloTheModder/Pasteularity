package win.libpaste.pasteularity

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import win.libpaste.pasteularity.ui.AppRoot
import win.libpaste.pasteularity.ui.components.DotWebBackdrop
import win.libpaste.pasteularity.ui.theme.PasteularityTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as PasteularityApp).container
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { container.shell.keepConnected() }
        }
        setContent {
            PasteularityTheme { DotWebBackdrop { AppRoot(container) } }
        }
    }
}
