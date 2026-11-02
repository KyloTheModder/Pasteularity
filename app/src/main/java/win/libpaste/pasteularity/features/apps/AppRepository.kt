package win.libpaste.pasteularity.features.apps

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.util.LruCache
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import win.libpaste.pasteularity.features.files.OBB_ROOT
import win.libpaste.pasteularity.features.files.isValidPackageName
import win.libpaste.pasteularity.features.files.joinPath
import win.libpaste.pasteularity.features.files.packageFromObbName
import win.libpaste.pasteularity.shell.CommandFailedException
import win.libpaste.pasteularity.shell.ShellBackend
import win.libpaste.pasteularity.shell.ShellException
import win.libpaste.pasteularity.shell.shellQuote

@Immutable
data class AppInfo(
    val packageName: String,
    val label: String,
    val versionName: String,
    val isSystem: Boolean,
)

interface AppIconSource {
    /** An icon that is already in memory, so a row scrolling back into view shows it right away. */
    fun cachedIcon(packageName: String): ImageBitmap?

    suspend fun loadIcon(packageName: String): ImageBitmap?
}

const val EXPORT_ROOT = "/sdcard/Pasteularity/apks"

private const val INSTALL_TIMEOUT_MS = 10 * 60 * 1000L
private const val COPY_TIMEOUT_MS = 6 * 60 * 60 * 1000L
private const val INSTALL_TEMP = "/data/local/tmp/pstu-install.apk"
private const val ICON_SIZE_PX = 96
private const val ICON_CACHE_SIZE = 128
private const val PARALLEL_LABEL_LOADS = 4
private const val PARALLEL_ICON_LOADS = 3

/** Parses `pm path`, which prints one `package:/path/to.apk` line per split. */
fun parsePackagePaths(output: String): List<String> =
    output.lines().map { it.trim() }.filter { it.startsWith("package:") }.map { it.removePrefix("package:") }

@OptIn(ExperimentalCoroutinesApi::class)
class AppRepository(private val context: Context, private val shell: ShellBackend) : AppIconSource {
    private val packageManager: PackageManager = context.packageManager
    private val labelDispatcher = Dispatchers.IO.limitedParallelism(PARALLEL_LABEL_LOADS)
    private val iconDispatcher = Dispatchers.IO.limitedParallelism(PARALLEL_ICON_LOADS)
    private val icons = LruCache<String, ImageBitmap>(ICON_CACHE_SIZE)

    /** Labels come from each app's own resources, which is the slow part, so they load in parallel. */
    suspend fun list(): Result<List<AppInfo>> = try {
        withContext(Dispatchers.IO) {
            val apps = packageManager.getInstalledPackages(0)
                .distinctBy { it.packageName }
                .map { info -> async(labelDispatcher) { toAppInfo(info) } }
                .awaitAll()
                .filterNotNull()
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
            Result.success(apps)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    private fun toAppInfo(info: PackageInfo): AppInfo? {
        val application = info.applicationInfo ?: return null
        return AppInfo(
            packageName = info.packageName,
            label = runCatching { application.loadLabel(packageManager).toString() }.getOrDefault(info.packageName),
            versionName = info.versionName.orEmpty(),
            isSystem = application.flags and ApplicationInfo.FLAG_SYSTEM != 0,
        )
    }

    override fun cachedIcon(packageName: String): ImageBitmap? = icons[packageName]

    override suspend fun loadIcon(packageName: String): ImageBitmap? =
        icons[packageName] ?: withContext(iconDispatcher) {
            runCatching { renderIcon(packageManager.getApplicationIcon(packageName)) }
                .getOrNull()
                ?.also { icons.put(packageName, it) }
        }

    /**
     * Draws at one fixed size. Some Quest apps ship an icon with no intrinsic size, which makes
     * `Drawable.toBitmap()` throw, and others ship one far larger than a list row needs.
     */
    private fun renderIcon(icon: Drawable): ImageBitmap {
        val bitmap = Bitmap.createBitmap(ICON_SIZE_PX, ICON_SIZE_PX, Bitmap.Config.ARGB_8888)
        icon.mutate().apply {
            setBounds(0, 0, ICON_SIZE_PX, ICON_SIZE_PX)
            draw(Canvas(bitmap))
        }
        return bitmap.asImageBitmap()
    }

    /** VR apps often have no LAUNCHER intent, so monkey with the Oculus VR category is the fallback. */
    suspend fun launch(packageName: String): Result<Unit> {
        packageManager.getLaunchIntentForPackage(packageName)?.let { intent ->
            val started = runCatching {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            if (started.isSuccess) return Result.success(Unit)
        }
        val quoted = shellQuote(packageName)
        return shell.exec("monkey -p $quoted -c com.oculus.intent.category.VR 1 || monkey -p $quoted 1").map { }
    }

    suspend fun forceStop(packageName: String): Result<Unit> =
        shell.exec("am force-stop ${shellQuote(packageName)}").map { }

    /**
     * Clears the external cache only. Shell cannot reach an app's private cache, and `pm clear
     * --cache-only` is not used because older firmware ignores the flag and wipes all app data.
     */
    suspend fun clearCache(packageName: String): Result<Unit> {
        val folder = shellQuote("/sdcard/Android/data/$packageName/cache")
        return shell.exec("if [ -d $folder ]; then find $folder -mindepth 1 -delete; fi").map { }
    }

    suspend fun clearData(packageName: String): Result<Unit> =
        shell.exec("pm clear ${shellQuote(packageName)}", INSTALL_TIMEOUT_MS).map { }

    suspend fun uninstall(packageName: String): Result<Unit> {
        icons.remove(packageName)
        return shell.exec("pm uninstall ${shellQuote(packageName)}", INSTALL_TIMEOUT_MS).map { }
    }

    suspend fun exportApk(packageName: String): Result<String> {
        val paths = parsePackagePaths(shell.exec("pm path ${shellQuote(packageName)}").getOrElse { return Result.failure(it) })
        if (paths.isEmpty()) return Result.failure(ShellException("Could not find the APK for this app."))
        val folder = joinPath(EXPORT_ROOT, packageName)
        val copies = paths.joinToString(" && ") { "cp -f -- ${shellQuote(it)} ${shellQuote(folder)}/" }
        return shell.exec("mkdir -p -- ${shellQuote(folder)} && $copies", COPY_TIMEOUT_MS).map { folder }
    }

    /** adbd's own temp folder is used because the installer can read it on every firmware. */
    suspend fun install(apkPath: String, obbFolder: String?, onStep: (String) -> Unit): Result<String> {
        val packageName = if (obbFolder != null) {
            onStep("Reading APK")
            resolvePackage(apkPath, obbFolder).getOrElse { return Result.failure(it) }
        } else {
            null
        }

        onStep("Installing")
        val temp = shellQuote(INSTALL_TEMP)
        val installed = shell.exec(
            "cp -f -- ${shellQuote(apkPath)} $temp && pm install -r $temp; rc=\$?; rm -f $temp; exit \$rc",
            INSTALL_TIMEOUT_MS,
        )
        installed.exceptionOrNull()?.let { failure ->
            val detail = (failure as? CommandFailedException)?.output?.lines()?.lastOrNull { it.isNotBlank() }
            return Result.failure(ShellException(detail ?: failure.message.orEmpty()))
        }

        if (obbFolder != null && packageName != null) {
            onStep("Copying OBB")
            val target = shellQuote(joinPath(OBB_ROOT, packageName))
            shell.exec(
                "mkdir -p -- $target && cp -rf -- ${shellQuote("$obbFolder/.")} $target/",
                COPY_TIMEOUT_MS,
            ).onFailure { return Result.failure(ShellException("App installed, but the OBB copy failed: ${it.message}")) }
        }
        if (packageName != null) icons.remove(packageName)
        return Result.success(packageName ?: "the app")
    }

    /** Works out the package before installing, so a bad OBB folder fails before anything changes. */
    private suspend fun resolvePackage(apkPath: String, obbFolder: String): Result<String> {
        val fromApk = readPackageFromApk(apkPath)
        if (fromApk != null) return Result.success(fromApk)
        val names = shell.exec("ls -1 -- ${shellQuote(obbFolder)}").getOrDefault("").lines()
        val fromObb = names.firstNotNullOfOrNull(::packageFromObbName)
        val fromFolder = obbFolder.substringAfterLast('/').takeIf(::isValidPackageName)
        return (fromObb ?: fromFolder)?.let { Result.success(it) }
            ?: Result.failure(ShellException("Could not work out which app this OBB belongs to."))
    }

    private suspend fun readPackageFromApk(apkPath: String): String? = withContext(Dispatchers.IO) {
        val cacheDir = context.externalCacheDir ?: return@withContext null
        val local = File(cacheDir, "pending.apk")
        try {
            shell.exec("cp -f -- ${shellQuote(apkPath)} ${shellQuote(local.path)}", COPY_TIMEOUT_MS).getOrNull()
                ?: return@withContext null
            packageManager.getPackageArchiveInfo(local.path, 0)?.packageName
        } finally {
            local.delete()
        }
    }
}
