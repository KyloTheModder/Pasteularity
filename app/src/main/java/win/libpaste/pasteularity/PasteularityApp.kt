package win.libpaste.pasteularity

import android.app.Application
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import win.libpaste.pasteularity.data.SettingsStore
import win.libpaste.pasteularity.features.apps.AppRepository
import win.libpaste.pasteularity.features.files.FileRepository
import win.libpaste.pasteularity.features.home.AndroidSettings
import win.libpaste.pasteularity.shell.AdbBackend
import win.libpaste.pasteularity.shell.BytezukuBackend
import win.libpaste.pasteularity.shell.ShellManager
import win.libpaste.pasteularity.ui.AppNavigator
import win.libpaste.pasteularity.ui.UiMessenger

class AppContainer(context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settings = SettingsStore(context)
    val shell = ShellManager(scope, AdbBackend(context), BytezukuBackend(context), settings)
    val messenger = UiMessenger()
    val navigator = AppNavigator()
    val files = FileRepository(shell)
    val apps = AppRepository(context, shell)
    val androidSettings = AndroidSettings(context)
}

class PasteularityApp : Application() {
    val container by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        container.shell.bytezuku.start()
    }
}
