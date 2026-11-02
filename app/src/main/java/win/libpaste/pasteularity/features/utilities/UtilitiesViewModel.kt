package win.libpaste.pasteularity.features.utilities

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import win.libpaste.pasteularity.AppContainer
import win.libpaste.pasteularity.shell.ShellEvent
import win.libpaste.pasteularity.shell.firmwareCommand
import win.libpaste.pasteularity.shell.getProps
import win.libpaste.pasteularity.shell.shellQuote
import win.libpaste.pasteularity.shell.userMessage

enum class UtilityTab(val label: String) {
    Device("Device"),
    Capture("Capture"),
    Logcat("Logcat"),
    WifiAdb("Wi-Fi ADB"),
    Tweaks("Tweaks"),
}

data class CaptureState(
    val screenshotBusy: Boolean = false,
    val recording: Boolean = false,
    val recordSeconds: Int = 60,
    val lastSaved: String? = null,
)

data class LogcatControls(
    val running: Boolean = false,
    val paused: Boolean = false,
    val tagFilter: String = "",
    val level: LogLevel = LogLevel.Info,
    val exporting: Boolean = false,
)

data class WifiAdbState(val ip: String = "", val port: String = "", val loading: Boolean = true) {
    val address: String
        get() = if (ip.isEmpty() || port.isEmpty()) "" else "$ip:$port"
}

data class UtilitiesState(
    val tab: UtilityTab = UtilityTab.Device,
    val device: DeviceInfo? = null,
    val capture: CaptureState = CaptureState(),
    val wifi: WifiAdbState = WifiAdbState(),
    val tweakValues: Map<String, String> = emptyMap(),
    val tweaksBusy: Boolean = false,
    val trimmingCaches: Boolean = false,
)

const val OUTPUT_ROOT = "/sdcard/Pasteularity"
private const val MAX_LOG_LINES = 3_000
private const val LOG_BACKLOG_LINES = 200
private const val LOG_PUBLISH_INTERVAL_MS = 250L

class UtilitiesViewModel(private val container: AppContainer) : ViewModel() {
    private val shell = container.shell
    private val messenger = container.messenger
    private val mutable = MutableStateFlow(UtilitiesState())
    val state = mutable.asStateFlow()

    // Kept apart from the rest of the state so a new log line only redraws the log.
    private val mutableControls = MutableStateFlow(LogcatControls())
    private val mutableLines = MutableStateFlow(emptyList<LogEntry>())
    val logControls = mutableControls.asStateFlow()
    val logLines = mutableLines.asStateFlow()

    private val logBuffer = LogBuffer(MAX_LOG_LINES)
    private var logJob: Job? = null
    private var nextLogId = 0L

    init {
        viewModelScope.launch {
            shell.isConnected.collect { connected -> if (connected) loadTab(state.value.tab) }
        }
    }

    fun selectTab(tab: UtilityTab) {
        mutable.update { it.copy(tab = tab) }
        loadTab(tab)
    }

    private fun loadTab(tab: UtilityTab) {
        when (tab) {
            UtilityTab.Device -> loadDevice()
            UtilityTab.WifiAdb -> loadWifi()
            UtilityTab.Tweaks -> loadTweaks()
            UtilityTab.Capture, UtilityTab.Logcat -> Unit
        }
    }

    fun loadDevice() {
        viewModelScope.launch {
            val props = async {
                shell.getProps(listOf(PROP_MODEL, PROP_HORIZON, PROP_ANDROID_BUILD, PROP_ANDROID)).getOrDefault(emptyMap())
            }
            val battery = async { shell.exec("dumpsys battery").getOrDefault("") }
            val storage = async { shell.exec("df -h /sdcard").getOrDefault("") }
            val network = async { shell.exec("ip -4 addr show wlan0").getOrDefault("") }
            val values = props.await()
            val batteryDump = battery.await()
            val usage = parseDfUsage(storage.await())
            val device = DeviceInfo(
                model = values[PROP_MODEL].orEmpty(),
                horizonVersion = values[PROP_HORIZON].orEmpty().ifEmpty { values[PROP_ANDROID_BUILD].orEmpty() },
                androidVersion = values[PROP_ANDROID].orEmpty(),
                batteryLevel = parseBatteryField(batteryDump, "level")?.let { "$it%" }.orEmpty(),
                batteryTemperature = formatBatteryTemperature(parseBatteryField(batteryDump, "temperature")),
                batteryStatus = batteryStatusLabel(parseBatteryField(batteryDump, "status")),
                storageUsed = usage?.first.orEmpty(),
                storageTotal = usage?.second.orEmpty(),
                ipAddress = parseIpAddress(network.await()).orEmpty(),
            )
            mutable.update { it.copy(device = device) }
        }
    }

    fun loadWifi() {
        viewModelScope.launch {
            mutable.update { it.copy(wifi = it.wifi.copy(loading = true)) }
            val ip = parseIpAddress(shell.exec("ip -4 addr show wlan0").getOrDefault("")).orEmpty()
            val ports = shell.getProps(listOf(PROP_TLS_PORT, PROP_TCP_PORT)).getOrDefault(emptyMap())
            val tlsPort = ports[PROP_TLS_PORT].orEmpty()
            val tcpPort = ports[PROP_TCP_PORT].orEmpty()
            val port = tlsPort.takeIf { it.isNotEmpty() && it != "0" } ?: tcpPort.takeIf { it != "0" }.orEmpty()
            mutable.update { it.copy(wifi = WifiAdbState(ip = ip, port = port, loading = false)) }
        }
    }

    fun takeScreenshot() {
        mutable.update { it.copy(capture = it.capture.copy(screenshotBusy = true)) }
        viewModelScope.launch {
            val path = "$OUTPUT_ROOT/screenshots/screenshot-${timestamp()}.png"
            val folder = shellQuote(path.substringBeforeLast('/'))
            shell.firmwareCommand("mkdir -p -- $folder && screencap -p ${shellQuote(path)}")
                .onSuccess {
                    messenger.success("Screenshot saved.")
                    mutable.update { it.copy(capture = it.capture.copy(lastSaved = path)) }
                }
                .onFailure { messenger.error(it.userMessage()) }
            mutable.update { it.copy(capture = it.capture.copy(screenshotBusy = false)) }
        }
    }

    fun setRecordSeconds(seconds: Int) = mutable.update { it.copy(capture = it.capture.copy(recordSeconds = seconds)) }

    fun startRecording() {
        val seconds = state.value.capture.recordSeconds
        mutable.update { it.copy(capture = it.capture.copy(recording = true)) }
        viewModelScope.launch {
            val path = "$OUTPUT_ROOT/recordings/recording-${timestamp()}.mp4"
            val folder = shellQuote(path.substringBeforeLast('/'))
            val timeoutMs = (seconds + RECORD_GRACE_SECONDS) * 1000L
            shell.firmwareCommand("mkdir -p -- $folder && screenrecord --time-limit $seconds ${shellQuote(path)}", timeoutMs)
                .onSuccess {
                    messenger.success("Recording saved.")
                    mutable.update { it.copy(capture = it.capture.copy(lastSaved = path)) }
                }
                .onFailure { messenger.error(it.userMessage()) }
            mutable.update { it.copy(capture = it.capture.copy(recording = false)) }
        }
    }

    /** SIGINT lets screenrecord finish writing the file, so the clip stays playable. */
    fun stopRecording() {
        viewModelScope.launch { shell.exec("pkill -INT screenrecord") }
    }

    fun openCaptures() = container.navigator.openInFiles(OUTPUT_ROOT)

    /** Everything here runs off the main thread: Horizon OS logs dozens of lines a second, more in games. */
    fun startLogcat() {
        if (logJob?.isActive == true) return
        mutableControls.update { it.copy(running = true, paused = false) }
        logJob = viewModelScope.launch(Dispatchers.Default) {
            val publisher = launch {
                while (isActive) {
                    delay(LOG_PUBLISH_INTERVAL_MS)
                    publishLog()
                }
            }
            try {
                shell.stream("logcat -v threadtime -T $LOG_BACKLOG_LINES").collect { event ->
                    if (event is ShellEvent.Line) logBuffer.add(parseLogLine(nextLogId++, event.text))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                messenger.error(e.userMessage())
            } finally {
                publisher.cancel()
                publishLog()
                mutableControls.update { it.copy(running = false) }
            }
        }
    }

    fun stopLogcat() {
        logJob?.cancel()
    }

    private fun publishLog(force: Boolean = false) {
        if (mutableControls.value.paused && !force) return
        logBuffer.takeChanges()?.let { mutableLines.value = it }
    }

    fun togglePause() = mutableControls.update { it.copy(paused = !it.paused) }

    fun clearLog() {
        logBuffer.clear()
        publishLog(force = true)
    }

    fun setLogTag(value: String) {
        mutableControls.update { it.copy(tagFilter = value) }
        applyLogFilter()
    }

    fun setLogLevel(value: LogLevel) {
        mutableControls.update { it.copy(level = value) }
        applyLogFilter()
    }

    private fun applyLogFilter() {
        val controls = mutableControls.value
        viewModelScope.launch(Dispatchers.Default) {
            logBuffer.setFilter(controls.tagFilter, controls.level)
            publishLog(force = true)
        }
    }

    /** The app writes to its own external folder, then shell moves the file somewhere easy to find. */
    fun exportLog(context: Context) {
        mutableControls.update { it.copy(exporting = true) }
        viewModelScope.launch {
            val target = "$OUTPUT_ROOT/logs/logcat-${timestamp()}.txt"
            val local = withContext(Dispatchers.IO) {
                context.getExternalFilesDir(null)?.let { dir ->
                    File(dir, "logcat-export.txt").apply {
                        bufferedWriter().use { writer -> logBuffer.visibleLines().forEach { writer.appendLine(it.raw) } }
                    }
                }
            }
            if (local == null) {
                messenger.error("Storage is not available.")
            } else {
                val folder = shellQuote(target.substringBeforeLast('/'))
                shell.exec("mkdir -p -- $folder && cp -f -- ${shellQuote(local.path)} ${shellQuote(target)}")
                    .onSuccess { messenger.success("Saved to $target.") }
                    .onFailure { messenger.error(it.userMessage()) }
                withContext(Dispatchers.IO) { local.delete() }
            }
            mutableControls.update { it.copy(exporting = false) }
        }
    }

    fun loadTweaks() {
        viewModelScope.launch {
            val values = tweaks.map { tweak ->
                async { tweak.title to shell.exec(tweak.read).getOrDefault("") }
            }.awaitAll().toMap()
            mutable.update { it.copy(tweakValues = values) }
        }
    }

    fun applyTweak(tweak: Tweak, option: TweakOption) {
        mutable.update { it.copy(tweaksBusy = true) }
        viewModelScope.launch {
            shell.firmwareCommand(option.apply)
                .onSuccess {
                    messenger.success("${tweak.title}: ${option.label}.")
                    val value = shell.exec(tweak.read).getOrDefault("")
                    mutable.update { it.copy(tweakValues = it.tweakValues + (tweak.title to value)) }
                }
                .onFailure { messenger.error(it.userMessage()) }
            mutable.update { it.copy(tweaksBusy = false) }
        }
    }

    fun trimCaches() {
        mutable.update { it.copy(trimmingCaches = true) }
        viewModelScope.launch {
            shell.firmwareCommand("pm trim-caches 999G", TRIM_TIMEOUT_MS)
                .onSuccess { messenger.success("App caches trimmed.") }
                .onFailure { messenger.error(it.userMessage()) }
            mutable.update { it.copy(trimmingCaches = false) }
        }
    }

    private fun timestamp(): String = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

    private companion object {
        const val PROP_MODEL = "ro.product.model"
        const val PROP_HORIZON = "ro.vros.build.version"
        const val PROP_ANDROID_BUILD = "ro.build.display.id"
        const val PROP_ANDROID = "ro.build.version.release"
        const val PROP_TLS_PORT = "service.adb.tls.port"
        const val PROP_TCP_PORT = "service.adb.tcp.port"
        const val RECORD_GRACE_SECONDS = 15
        const val TRIM_TIMEOUT_MS = 120_000L
    }
}
