package win.libpaste.pasteularity.shell

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku
import win.libpaste.pasteularity.BuildConfig

enum class BytezukuState { NotRunning, NeedsPermission, Ready }

/**
 * ByteZuku is a Shizuku fork that keeps the Shizuku binder protocol, so it is reached through the
 * upstream client library. That library's `rikka.shizuku` names are the only ones left in the app.
 */
class BytezukuBackend(context: Context) : StreamingShellBackend() {
    private val connected = MutableStateFlow(false)
    private val state = MutableStateFlow(BytezukuState.NotRunning)

    @Volatile
    private var service: IShellService? = null

    @Volatile
    private var binding = false

    private val serviceArgs = Shizuku.UserServiceArgs(
        ComponentName(context.packageName, BytezukuShellService::class.java.name),
    )
        .daemon(false)
        .processNameSuffix("shell")
        .debuggable(BuildConfig.DEBUG)
        .version(BuildConfig.VERSION_CODE)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            binding = false
            service = binder?.takeIf { it.pingBinder() }?.let { IShellService.Stub.asInterface(it) }
            connected.value = service != null
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            binding = false
            service = null
            connected.value = false
        }
    }

    override val isConnected: StateFlow<Boolean> = connected.asStateFlow()
    val bytezukuState: StateFlow<BytezukuState> = state.asStateFlow()

    fun start() {
        Shizuku.addBinderReceivedListenerSticky { updateState() }
        Shizuku.addBinderDeadListener {
            binding = false
            service = null
            connected.value = false
            updateState()
        }
        Shizuku.addRequestPermissionResultListener { _, _ ->
            updateState()
            connect()
        }
    }

    fun requestPermission() {
        runCatching { if (Shizuku.pingBinder()) Shizuku.requestPermission(PERMISSION_REQUEST_CODE) }
    }

    /** ByteZuku can die between any two calls here, and a dead binder must not crash the app. */
    fun connect() {
        updateState()
        if (state.value == BytezukuState.Ready && service == null && !binding) {
            binding = true
            runCatching { Shizuku.bindUserService(serviceArgs, connection) }.onFailure { binding = false }
        }
    }

    private fun updateState() {
        state.value = runCatching {
            when {
                !Shizuku.pingBinder() || Shizuku.isPreV11() -> BytezukuState.NotRunning
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> BytezukuState.Ready
                else -> BytezukuState.NeedsPermission
            }
        }.getOrDefault(BytezukuState.NotRunning)
    }

    override fun openSession(command: String): ShellSession {
        val remote = service ?: throw NotConnectedException()
        val descriptor = remote.exec(command)
        val input = ParcelFileDescriptor.AutoCloseInputStream(descriptor)
        return ShellSession(input) { runCatching { input.close() } }
    }

    private companion object {
        const val PERMISSION_REQUEST_CODE = 2000
    }
}
