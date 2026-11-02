package win.libpaste.pasteularity.shell

import android.content.Context
import android.os.Build
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.AdbPairingRequiredException
import io.github.muntashirakon.adb.android.AdbMdns
import java.security.PrivateKey
import java.security.cert.Certificate
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class NotPairedException : ShellException("This headset has not been paired yet.")

class WirelessDebuggingOffException : ShellException("Wireless debugging is off.")

class PairingFailedException : ShellException("Pairing failed. Check the code and port, then try again.")

private class PstuAdbManager(private val keyStore: AdbKeyStore) : AbsAdbConnectionManager() {
    private val identity by lazy { keyStore.loadOrCreate() }

    init {
        setApi(Build.VERSION.SDK_INT)
        setTimeout(10, TimeUnit.SECONDS)
    }

    override fun getPrivateKey(): PrivateKey = identity.privateKey

    override fun getCertificate(): Certificate = identity.certificate

    override fun getDeviceName(): String = "Pasteularity"
}

/** Talks to the headset's own adbd over TLS, using the identity created at pairing time. */
class AdbBackend(private val context: Context) : StreamingShellBackend() {
    private val manager = PstuAdbManager(AdbKeyStore(context))
    private val connectLock = Mutex()
    private val connected = MutableStateFlow(false)

    override val isConnected: StateFlow<Boolean> = connected.asStateFlow()

    /**
     * Uses the raw destination on purpose. libadb's LocalServices.SHELL helper wraps any argument
     * containing a space in double quotes and rejects arguments that contain one, which would
     * turn every command here into a single "not found" word.
     */
    override fun openSession(command: String): ShellSession {
        val stream = manager.openStream("shell:$command")
        return ShellSession(stream.openInputStream()) { runCatching { stream.close() } }
    }

    override fun onConnectionLost() = refresh()

    fun refresh() {
        connected.value = manager.isConnected
    }

    suspend fun pair(port: Int, code: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            manager.pair(LOCALHOST, port, code)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(PairingFailedException())
        }
    }

    suspend fun connect(): Result<Unit> = withContext(Dispatchers.IO) {
        connectLock.withLock {
            try {
                if (!manager.isConnected) {
                    manager.disconnect()
                    manager.connectTls(context, DISCOVERY_TIMEOUT_MS)
                }
                refresh()
                if (connected.value) Result.success(Unit) else Result.failure(WirelessDebuggingOffException())
            } catch (e: AdbPairingRequiredException) {
                refresh()
                Result.failure(NotPairedException())
            } catch (e: InterruptedException) {
                refresh()
                Result.failure(WirelessDebuggingOffException())
            } catch (e: Exception) {
                refresh()
                Result.failure(WirelessDebuggingOffException())
            }
        }
    }

    suspend fun disconnect() = withContext(Dispatchers.IO) {
        connectLock.withLock {
            runCatching { manager.disconnect() }
            refresh()
        }
    }

    /** Emits the pairing port while the "Pair device with pairing code" dialog is open. */
    fun pairingPort(): Flow<Int?> = callbackFlow {
        val discovery = AdbMdns(context, AdbMdns.SERVICE_TYPE_TLS_PAIRING) { _, port ->
            trySend(port.takeIf { it > 0 })
        }
        discovery.start()
        awaitClose { discovery.stop() }
    }.distinctUntilChanged()

    private companion object {
        const val LOCALHOST = "127.0.0.1"
        const val DISCOVERY_TIMEOUT_MS = 4_000L
    }
}
