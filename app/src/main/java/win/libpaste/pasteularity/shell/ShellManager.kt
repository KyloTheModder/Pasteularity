package win.libpaste.pasteularity.shell

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import win.libpaste.pasteularity.data.SettingsStore

enum class BackendKind(val label: String) { Adb("Wireless debugging"), Bytezuku("ByteZuku") }

enum class BackendPreference(val label: String) { Auto("Automatic"), Adb("Wireless debugging"), Bytezuku("ByteZuku") }

sealed interface ConnectionStatus {
    data class Connected(val kind: BackendKind) : ConnectionStatus
    data object Connecting : ConnectionStatus
    data object NotPaired : ConnectionStatus
    data object Disconnected : ConnectionStatus
}

/** Picks the active backend. Features only ever see this as a [ShellBackend]. */
class ShellManager(
    scope: CoroutineScope,
    val adb: AdbBackend,
    val bytezuku: BytezukuBackend,
    private val settings: SettingsStore,
) : ShellBackend {
    private val connecting = MutableStateFlow(false)

    val status: StateFlow<ConnectionStatus> = combine(
        settings.backendPreference,
        settings.adbPaired,
        adb.isConnected,
        bytezuku.isConnected,
        connecting,
    ) { preference, paired, adbUp, bytezukuUp, busy ->
        val useAdb = preference != BackendPreference.Bytezuku
        val useBytezuku = preference != BackendPreference.Adb
        when {
            useAdb && adbUp -> ConnectionStatus.Connected(BackendKind.Adb)
            useBytezuku && bytezukuUp -> ConnectionStatus.Connected(BackendKind.Bytezuku)
            busy -> ConnectionStatus.Connecting
            useAdb && !paired -> ConnectionStatus.NotPaired
            else -> ConnectionStatus.Disconnected
        }
    }.stateIn(scope, SharingStarted.Eagerly, ConnectionStatus.Disconnected)

    override val isConnected: StateFlow<Boolean> = status
        .map { it is ConnectionStatus.Connected }
        .stateIn(scope, SharingStarted.Eagerly, false)

    private fun active(): ShellBackend? = when (val current = status.value) {
        is ConnectionStatus.Connected -> if (current.kind == BackendKind.Adb) adb else bytezuku
        else -> null
    }

    override suspend fun exec(cmd: String, timeoutMs: Long): Result<String> =
        active()?.exec(cmd, timeoutMs) ?: Result.failure(NotConnectedException())

    override fun stream(cmd: String): Flow<ShellEvent> = flow {
        val backend = active() ?: throw NotConnectedException()
        backend.stream(cmd).collect { emit(it) }
    }

    /**
     * Silent reconnect loop. MainActivity runs it only while the app is on screen. It stays off
     * the main thread because libadb holds its lock for the whole of a slow connect, and it backs
     * off because each failed attempt runs a network discovery.
     */
    suspend fun keepConnected() = withContext(Dispatchers.IO) {
        var wait = MIN_RECONNECT_INTERVAL_MS
        while (isActive) {
            adb.refresh()
            if (isConnected.value) {
                wait = MIN_RECONNECT_INTERVAL_MS
            } else {
                attemptConnect()
                wait = (wait * 2).coerceAtMost(MAX_RECONNECT_INTERVAL_MS)
            }
            delay(wait)
        }
    }

    /** User-triggered reconnect, which also shows Connecting on the status chip. */
    suspend fun reconnect(): Result<Unit> {
        connecting.value = true
        try {
            return attemptConnect()
        } finally {
            connecting.value = false
        }
    }

    suspend fun pair(port: Int, code: String): Result<Unit> {
        connecting.value = true
        try {
            adb.pair(port, code).onFailure { return Result.failure(it) }
            settings.setAdbPaired(true)
            var last: Result<Unit> = Result.failure(WirelessDebuggingOffException())
            repeat(POST_PAIR_ATTEMPTS) {
                last = adb.connect()
                if (last.isSuccess) return last
                delay(POST_PAIR_DELAY_MS)
            }
            return last
        } finally {
            connecting.value = false
        }
    }

    suspend fun forgetPairing() {
        adb.disconnect()
        settings.setAdbPaired(false)
    }

    private suspend fun attemptConnect(): Result<Unit> {
        val preference = settings.backendPreference.first()
        if (preference != BackendPreference.Adb) {
            bytezuku.connect()
            if (bytezuku.isConnected.value) return Result.success(Unit)
        }
        if (preference != BackendPreference.Bytezuku && settings.adbPaired.first()) {
            val result = adb.connect()
            if (result.exceptionOrNull() is NotPairedException) settings.setAdbPaired(false)
            return result
        }
        return Result.failure(if (preference == BackendPreference.Bytezuku) NotConnectedException() else NotPairedException())
    }

    private companion object {
        const val MIN_RECONNECT_INTERVAL_MS = 5_000L
        const val MAX_RECONNECT_INTERVAL_MS = 30_000L
        const val POST_PAIR_ATTEMPTS = 4
        const val POST_PAIR_DELAY_MS = 1_500L
    }
}
