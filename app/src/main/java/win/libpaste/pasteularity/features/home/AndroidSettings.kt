package win.libpaste.pasteularity.features.home

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings

data class AndroidSettingsPage(val label: String, val activity: String)

private const val SETTINGS_PACKAGE = "com.android.settings"
private const val HOME_ACTIVITY = "com.android.settings.Settings"
private const val DEVELOPER_ACTIVITY = "com.android.settings.Settings\$DevelopmentSettingsDashboardActivity"

private fun page(label: String, name: String) =
    AndroidSettingsPage(label, "com.android.settings.Settings\$${name}Activity")

/** Android's own Settings sections. Each one was opened and checked on a Quest 3 running Horizon OS v207. */
private val knownPages = listOf(
    page("Network and internet", "NetworkDashboard"),
    page("Wi-Fi", "WifiSettings"),
    page("Connected devices", "ConnectedDeviceDashboard"),
    page("Apps", "AppDashboard"),
    page("Notifications", "ConfigureNotificationSettings"),
    page("Battery", "PowerUsageSummary"),
    page("Storage", "StorageDashboard"),
    page("Sound", "SoundSettings"),
    page("Display", "DisplaySettings"),
    page("Accessibility", "AccessibilitySettings"),
    page("Security", "SecurityDashboard"),
    page("Privacy", "PrivacyDashboard"),
    page("Location", "LocationSettings"),
    page("Accounts", "AccountDashboard"),
    page("Users", "UserSettings"),
    page("System", "SystemDashboard"),
    page("Developer options", "DevelopmentSettingsDashboard"),
)

/**
 * Opens real `com.android.settings` screens. Newer Horizon OS keeps the Settings home page from
 * every app and from shell, and sends the normal settings intent to Meta's own app, but the
 * sections below stay exported. They need no shell access, so they work before pairing too.
 */
class AndroidSettings(private val context: Context) {
    val availablePages: List<AndroidSettingsPage> by lazy { knownPages.filter { isOpenable(it.activity) } }

    val hasHomePage: Boolean
        get() = isOpenable(HOME_ACTIVITY)

    fun openHomePage(): Boolean = openActivity(HOME_ACTIVITY)

    fun open(page: AndroidSettingsPage): Boolean = openActivity(page.activity)

    fun openDeveloperOptions(): Boolean =
        (isOpenable(DEVELOPER_ACTIVITY) && openActivity(DEVELOPER_ACTIVITY)) ||
            start(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) ||
            start(Intent(Settings.ACTION_SETTINGS))

    private fun isOpenable(activity: String): Boolean = runCatching {
        context.packageManager.getActivityInfo(ComponentName(SETTINGS_PACKAGE, activity), 0).exported
    }.getOrDefault(false)

    private fun openActivity(activity: String): Boolean =
        start(Intent().setComponent(ComponentName(SETTINGS_PACKAGE, activity)))

    private fun start(intent: Intent): Boolean =
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
}
