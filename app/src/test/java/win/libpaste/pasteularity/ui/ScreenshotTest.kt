package win.libpaste.pasteularity.ui

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import win.libpaste.pasteularity.data.DisplayProfile
import win.libpaste.pasteularity.data.Snippet
import win.libpaste.pasteularity.features.apps.AppIconSource
import win.libpaste.pasteularity.features.apps.AppInfo
import win.libpaste.pasteularity.features.apps.AppsActions
import win.libpaste.pasteularity.features.apps.AppsScreen
import win.libpaste.pasteularity.features.apps.AppsState
import win.libpaste.pasteularity.features.display.DisplayActions
import win.libpaste.pasteularity.features.display.DisplayScreen
import win.libpaste.pasteularity.features.display.DisplayState
import win.libpaste.pasteularity.features.display.PROP_CPU
import win.libpaste.pasteularity.features.display.PROP_FOVEATION
import win.libpaste.pasteularity.features.display.PROP_GPU
import win.libpaste.pasteularity.features.display.PROP_REFRESH
import win.libpaste.pasteularity.features.display.PROP_TEXTURE_HEIGHT
import win.libpaste.pasteularity.features.display.PROP_TEXTURE_WIDTH
import win.libpaste.pasteularity.features.display.limitsFor
import win.libpaste.pasteularity.features.files.FileClipboard
import win.libpaste.pasteularity.features.files.FileEntry
import win.libpaste.pasteularity.features.files.FileOperation
import win.libpaste.pasteularity.features.files.FilesActions
import win.libpaste.pasteularity.features.files.FilesScreen
import win.libpaste.pasteularity.features.files.FilesState
import win.libpaste.pasteularity.features.files.OBB_ROOT
import win.libpaste.pasteularity.features.home.HomeActions
import win.libpaste.pasteularity.features.home.HomeScreen
import win.libpaste.pasteularity.features.home.HomeState
import win.libpaste.pasteularity.features.onboarding.OnboardingScreen
import win.libpaste.pasteularity.features.onboarding.OnboardingState
import win.libpaste.pasteularity.features.settings.SettingsActions
import win.libpaste.pasteularity.features.settings.SettingsScreen
import win.libpaste.pasteularity.features.terminal.LineKind
import win.libpaste.pasteularity.features.terminal.TerminalActions
import win.libpaste.pasteularity.features.terminal.TerminalLine
import win.libpaste.pasteularity.features.terminal.TerminalScreen
import win.libpaste.pasteularity.features.terminal.TerminalState
import win.libpaste.pasteularity.features.utilities.DeviceInfo
import win.libpaste.pasteularity.features.utilities.LogEntry
import win.libpaste.pasteularity.features.utilities.LogcatControls
import win.libpaste.pasteularity.features.utilities.UtilitiesActions
import win.libpaste.pasteularity.features.utilities.UtilitiesScreen
import win.libpaste.pasteularity.features.utilities.UtilitiesState
import win.libpaste.pasteularity.features.utilities.UtilityTab
import win.libpaste.pasteularity.features.utilities.parseLogLine
import win.libpaste.pasteularity.shell.BackendKind
import win.libpaste.pasteularity.shell.BackendPreference
import win.libpaste.pasteularity.shell.BytezukuState
import win.libpaste.pasteularity.shell.ConnectionStatus
import win.libpaste.pasteularity.ui.components.DotWebBackdrop
import win.libpaste.pasteularity.ui.components.DotWebState
import win.libpaste.pasteularity.ui.theme.PasteularityTheme

/** Renders each real screen with sample state, so the design can be reviewed without a headset. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-land-mdpi")
class ScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val connected = ConnectionStatus.Connected(BackendKind.Adb)

    private fun capture(name: String, content: @Composable () -> Unit) {
        compose.setContent {
            PasteularityTheme {
                DotWebBackdrop(remember { DotWebState(Offset(780f, 660f)) }) { content() }
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/preview/$name.png")
    }

    private fun shell(destination: Destination, status: ConnectionStatus = connected, content: @Composable () -> Unit) {
        capture(destination.name.lowercase()) {
            AppShell(destination, status, {}, {}, SnackbarHostState(), content)
        }
    }

    @Test
    fun onboarding() = capture("onboarding") {
        OnboardingScreen(
            state = OnboardingState(step = 2, pairingCode = "482913", port = "37215", portDetected = true),
            onStep = {},
            onCode = {},
            onPort = {},
            onOpenDeveloperOptions = {},
            onPair = {},
            onUseBytezuku = {},
            onSkip = {},
        )
    }

    @Test
    fun home() = shell(Destination.Home) {
        HomeScreen(HomeState(advancedOpen = true), true, HomeActions({}, {}, {}, {}, {}, {}, {}))
    }

    @Test
    fun terminal() = shell(Destination.Terminal) {
        TerminalScreen(
            state = TerminalState(
                lines = listOf(
                    TerminalLine(0, "/sdcard \$ df -h /sdcard", LineKind.Command),
                    TerminalLine(1, "Filesystem      Size  Used Avail Use% Mounted on", LineKind.Output),
                    TerminalLine(2, "/dev/fuse       108G   41G   67G  38% /storage/emulated", LineKind.Output),
                    TerminalLine(3, "/sdcard \$ cat /data/misc/foo", LineKind.Command),
                    TerminalLine(4, "cat: /data/misc/foo: Permission denied", LineKind.Output),
                    TerminalLine(5, "Exited with code 1.", LineKind.Status),
                ),
                input = "pm list packages -3",
            ),
            snippets = listOf(
                Snippet("Android version", "getprop ro.build.version.release"),
                Snippet("Sideloaded apps", "pm list packages -3"),
                Snippet("Storage", "df -h /sdcard"),
                Snippet("Battery", "dumpsys battery"),
            ),
            actions = TerminalActions({}, {}, {}, {}, {}, {}, {}, {}, { _, _ -> }, {}, {}),
        )
    }

    @Test
    fun files() = shell(Destination.Files) {
        val base = 1_761_800_000L
        fun folder(name: String) = FileEntry(name, "$OBB_ROOT/$name", true, false, 0, base)
        fun file(name: String, size: Long) = FileEntry(name, "$OBB_ROOT/$name", false, false, size, base - 86_400)
        FilesScreen(
            state = FilesState(
                path = OBB_ROOT,
                loading = false,
                entries = listOf(
                    folder("com.beatgames.beatsaber"),
                    folder("com.vrgames.superhot"),
                    folder("com.schellgames.blocks"),
                    file("main.1402.com.example.racer.obb", 2_147_483_648),
                    file("patch.1402.com.example.racer.obb", 318_767_104),
                ),
                selection = setOf("$OBB_ROOT/main.1402.com.example.racer.obb"),
                clipboard = FileClipboard(listOf("/sdcard/Download/a.obb", "/sdcard/Download/b.obb"), isCut = false),
                operation = FileOperation("Copying", 0.64f),
            ),
            actions = filesActions,
        )
    }

    @Test
    fun display() = shell(Destination.Display) {
        DisplayScreen(
            state = DisplayState(
                values = mapOf(
                    PROP_REFRESH to "90",
                    PROP_TEXTURE_WIDTH to "1832",
                    PROP_TEXTURE_HEIGHT to "1832",
                    PROP_FOVEATION to "2",
                    PROP_CPU to "",
                    PROP_GPU to "",
                ),
                limits = limitsFor("eureka"),
                loading = false,
            ),
            profiles = listOf(
                DisplayProfile("Performance", mapOf(PROP_REFRESH to "120", PROP_CPU to "4", PROP_GPU to "4")),
                DisplayProfile("Battery saver", mapOf(PROP_REFRESH to "72", PROP_FOVEATION to "3")),
            ),
            actions = displayActions,
        )
    }

    @Test
    fun apps() = shell(Destination.Apps) {
        val apps = listOf(
            AppInfo("com.beatgames.beatsaber", "Beat Saber", "1.40.3", false),
            AppInfo("com.schellgames.blocks", "Blocks", "2.1.0", false),
            AppInfo("com.vrgames.superhot", "Superhot VR", "1.0.26", false),
            AppInfo("com.example.racer", "Track Racer", "1402", false),
            AppInfo("org.videolan.vlc", "VLC", "3.6.0", false),
        )
        AppsScreen(
            state = AppsState(apps = apps, loading = false, selected = "com.beatgames.beatsaber"),
            actions = appsActions,
        )
    }

    @Test
    fun utilities() = shell(Destination.Utilities) {
        UtilitiesScreen(
            state = UtilitiesState(
                tab = UtilityTab.Device,
                device = DeviceInfo(
                    model = "Quest 3",
                    horizonVersion = "207",
                    androidVersion = "14",
                    batteryLevel = "83%",
                    batteryTemperature = "31.2 C",
                    batteryStatus = "Charging",
                    storageUsed = "41G",
                    storageTotal = "108G",
                    ipAddress = "192.168.1.42",
                ),
            ),
            logControls = idleLogControls,
            logLines = noLogLines,
            actions = utilitiesActions,
        )
    }

    @Test
    fun logcat() = capture("logcat") {
        val lines = listOf(
            "10-30 12:00:01.123  1234  1250 I VrApi   : FPS=72,Prd=45ms,Tear=0,Early=0,Stale=0",
            "10-30 12:00:02.123  1234  1250 I VrApi   : FPS=72,Prd=45ms,Tear=0,Early=0,Stale=1",
            "10-30 12:00:02.402   812   901 W OVRPlugin: Compositor layer fell back to low quality",
            "10-30 12:00:03.118  2210  2210 E Unity   : NullReferenceException in PlayerController",
            "10-30 12:00:03.124  1234  1250 I VrApi   : FPS=71,Prd=45ms,Tear=0,Early=0,Stale=2",
        )
        AppShell(Destination.Utilities, connected, {}, {}, SnackbarHostState()) {
            UtilitiesScreen(
                state = UtilitiesState(tab = UtilityTab.Logcat),
                logControls = mutableStateOf(LogcatControls(running = true)),
                logLines = mutableStateOf(lines.mapIndexed { index, line -> parseLogLine(index.toLong(), line) }),
                actions = utilitiesActions,
            )
        }
    }

    @Test
    fun tweaks() = capture("tweaks") {
        AppShell(Destination.Utilities, connected, {}, {}, SnackbarHostState()) {
            UtilitiesScreen(
                state = UtilitiesState(
                    tab = UtilityTab.Tweaks,
                    tweakValues = mapOf(
                        "Stay awake while charging" to "7",
                        "Capture size" to "",
                        "Window animation speed" to "0.5",
                    ),
                ),
                logControls = idleLogControls,
                logLines = noLogLines,
                actions = utilitiesActions,
            )
        }
    }

    @Test
    fun notPaired() = capture("not_paired") {
        AppShell(Destination.Files, ConnectionStatus.NotPaired, {}, {}, SnackbarHostState()) {
            NotConnectedState(ConnectionStatus.NotPaired, onSetup = {}, onReconnect = {})
        }
    }

    @Test
    fun settings() = shell(Destination.Settings) {
        SettingsScreen(
            status = connected,
            preference = BackendPreference.Auto,
            paired = true,
            bytezuku = BytezukuState.NotRunning,
            reconnecting = false,
            actions = SettingsActions({}, {}, {}, {}, {}),
        )
    }

    private val filesActions = FilesActions(
        {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {},
    )

    private val displayActions = DisplayActions({ _, _ -> }, { _, _ -> }, {}, {}, {}, {}, {}, {}, {})

    private val idleLogControls = mutableStateOf(LogcatControls())
    private val noLogLines = mutableStateOf(emptyList<LogEntry>())

    private val appsActions = AppsActions(
        {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {},
        icons = object : AppIconSource {
            override fun cachedIcon(packageName: String): ImageBitmap? = null
            override suspend fun loadIcon(packageName: String): ImageBitmap? = null
        },
    )

    private val utilitiesActions = UtilitiesActions(
        {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, { _, _ -> }, {},
    )
}
