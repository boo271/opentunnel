package dev.opentunnel.app.update

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import dev.opentunnel.app.BuildConfig
import dev.opentunnel.app.Diag
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Optional updates from GitHub releases. A check only reads the latest release; an
 * install happens only after the user taps through, and the download is accepted only
 * when its SHA-256 matches the release's SHA256SUMS.txt and its signing certificate
 * matches the installed app.
 */
object UpdateManager {

    private const val REPO = "boo271/opentunnel"
    private const val API = "https://api.github.com/repos/$REPO/releases/latest"
    private const val TAG = "UpdateManager"
    private const val PREFS = "updates"
    private const val KEY_AUTO = "auto"
    private const val KEY_LAST_CHECK = "lastCheck"
    private const val KEY_SKIPPED = "skippedVersion"
    private const val DAY_MS = 24L * 60L * 60L * 1000L
    private const val RETRY_MS = 60L * 60L * 1000L

    data class Release(
        val version: String,
        val tag: String,
        val pageUrl: String,
        val apkName: String,
        val apkUrl: String,
        val apkSize: Long,
        val sumsUrl: String?,
    )

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data class UpToDate(val version: String) : State
        data class Available(val release: Release) : State
        data class Downloading(val release: Release, val percent: Int) : State
        data class Ready(val release: Release, val file: File) : State
        data class Error(val message: String, val release: Release? = null) : State
    }

    enum class InstallResult { Started, NeedsPermission, Unavailable }

    private lateinit var app: Context
    private lateinit var prefs: SharedPreferences
    private var scope: CoroutineScope? = null
    private var checkJob: Job? = null
    private var downloadJob: Job? = null
    private var lastAttempt = 0L

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        File(app.cacheDir, "updates").mkdirs()
        updateDir().listFiles()
            ?.filter { it.name.endsWith(".part") }
            ?.forEach { runCatching { it.delete() } }
    }

    fun autoCheckEnabled(): Boolean = if (::prefs.isInitialized) prefs.getBoolean(KEY_AUTO, true) else true

    fun setAutoCheck(enabled: Boolean) {
        if (::prefs.isInitialized) prefs.edit().putBoolean(KEY_AUTO, enabled).apply()
    }

    fun skippedVersion(): String? =
        if (::prefs.isInitialized) prefs.getString(KEY_SKIPPED, null) else null

    fun skip(version: String) {
        if (::prefs.isInitialized) prefs.edit().putString(KEY_SKIPPED, version).apply()
    }

    /** Runs at most once a day, and only while the user has not turned checking off. */
    fun maybeAutoCheck() {
        if (scope == null || !autoCheckEnabled()) return
        val now = System.currentTimeMillis()
        if (now - lastAttempt < RETRY_MS) return
        val last = prefs.getLong(KEY_LAST_CHECK, 0L)
        if (last > 0 && now - last < DAY_MS) return
        when (_state.value) {
            is State.Idle, is State.UpToDate, is State.Error -> check()
            else -> Unit
        }
    }

    fun checkNow() {
        if (scope == null) return
        check()
    }

    private fun check() {
        if (checkJob?.isActive == true) return
        if (_state.value is State.Downloading || _state.value is State.Ready) return
        lastAttempt = System.currentTimeMillis()
        checkJob = scope?.launch {
            _state.value = State.Checking
            val result = withContext(Dispatchers.IO) { runCatching { fetchLatest() } }
            result.onSuccess { release ->
                prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
                _state.value = if (Version.isNewer(release.version, BuildConfig.VERSION_NAME)) {
                    State.Available(release)
                } else {
                    State.UpToDate(release.version)
                }
                Diag.log(TAG, "check: latest ${release.tag}")
            }.onFailure { error ->
                val message = when (error) {
                    is UpdateException -> error.message ?: "Could not check for updates."
                    is IOException -> "Couldn't reach GitHub. Check your connection."
                    else -> "Could not check for updates."
                }
                _state.value = State.Error(message)
                Diag.log(TAG, "check failed: ${error.message}")
            }
        }
    }

    private fun fetchLatest(): Release {
        val request = Request.Builder()
            .url(API)
            .header("User-Agent", "OpenTunnel/${BuildConfig.VERSION_NAME}")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .build()
        client.newCall(request).execute().use { response ->
            when (response.code) {
                200 -> Unit
                403, 429 -> throw UpdateException("GitHub rate limit reached. Try again later.")
                404 -> throw UpdateException("No releases found on GitHub.")
                else -> throw UpdateException("GitHub returned HTTP ${response.code}.")
            }
            val body = response.body?.string() ?: throw UpdateException("Empty response from GitHub.")
            return parseRelease(body)
        }
    }

    private fun parseRelease(body: String): Release {
        val json = runCatching { JSONObject(body) }
            .getOrElse { throw UpdateException("Unexpected response from GitHub.") }
        val tag = json.optString("tag_name")
        val version = Version.normalize(tag)
            ?: throw UpdateException("Unexpected release tag.")
        val page = json.optString("html_url")
            .takeIf { it.startsWith("https://github.com/") }
            ?: "https://github.com/$REPO/releases"

        val assets = json.optJSONArray("assets") ?: JSONArray()
        var apk: JSONObject? = null
        var sums: JSONObject? = null
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            val name = asset.optString("name")
            if (name == "SHA256SUMS.txt") {
                sums = asset
                continue
            }
            if (name.endsWith(".apk", ignoreCase = true)) {
                val arm64 = name.contains("arm64", ignoreCase = true)
                val currentIsArm64 = apk?.optString("name")?.contains("arm64", ignoreCase = true) == true
                if (apk == null || (arm64 && !currentIsArm64)) apk = asset
            }
        }

        val apkAsset = apk ?: throw UpdateException("This release has no APK.")
        val apkName = apkAsset.optString("name")
        val apkUrl = apkAsset.optString("browser_download_url")
        if (!apkUrl.startsWith("https://")) throw UpdateException("Unexpected download link.")
        val sumsUrl = sums?.optString("browser_download_url")
            ?.takeIf { it.startsWith("https://") }

        return Release(
            version = version,
            tag = tag,
            pageUrl = page,
            apkName = apkName,
            apkUrl = apkUrl,
            apkSize = apkAsset.optLong("size", 0L),
            sumsUrl = sumsUrl,
        )
    }

    fun download() {
        val release = when (val current = _state.value) {
            is State.Available -> current.release
            is State.Error -> current.release
            else -> null
        } ?: return
        if (downloadJob?.isActive == true) return
        _state.value = State.Downloading(release, 0)
        downloadJob = scope?.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { downloadAndVerify(release) { percent -> reportProgress(release, percent) } }
            }
            result.onSuccess { file ->
                _state.value = State.Ready(release, file)
                Diag.log(TAG, "downloaded ${release.tag}")
            }.onFailure { error ->
                if (error is CancellationException) {
                    _state.value = State.Available(release)
                } else {
                    val message = if (error is UpdateException) {
                        error.message ?: "Download failed."
                    } else {
                        "Download failed."
                    }
                    _state.value = State.Error(message, release)
                    Diag.log(TAG, "download failed: ${error.message}")
                }
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
    }

    private fun reportProgress(release: Release, percent: Int) {
        val current = _state.value
        if (current is State.Downloading && current.release == release) {
            _state.value = State.Downloading(release, percent)
        }
    }

    private suspend fun downloadAndVerify(release: Release, onProgress: (Int) -> Unit): File {
        val dir = updateDir()
        dir.listFiles()?.forEach { runCatching { it.delete() } }
        val part = File(dir, "update.apk.part")
        val target = File(dir, release.apkName.replace(Regex("[^A-Za-z0-9._-]"), "_"))
        val digest = MessageDigest.getInstance("SHA-256")
        var bytes = 0L
        var lastPercent = -1
        var lastUpdate = 0L

        try {
            val request = Request.Builder()
                .url(release.apkUrl)
                .header("User-Agent", "OpenTunnel/${BuildConfig.VERSION_NAME}")
                .build()
            client.newCall(request).execute().use { response ->
                if (response.code == 403 || response.code == 429) {
                    throw UpdateException("GitHub rate limit reached. Try again later.")
                }
                if (!response.isSuccessful) {
                    throw UpdateException("GitHub returned HTTP ${response.code} while downloading.")
                }
                val body = response.body ?: throw UpdateException("Empty download.")
                val total = if (release.apkSize > 0) release.apkSize else body.contentLength()
                body.byteStream().use { input ->
                    FileOutputStream(part).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                            bytes += read
                            val percent = if (total > 0) ((bytes * 100) / total).toInt().coerceIn(0, 100) else -1
                            val now = System.currentTimeMillis()
                            if (percent != lastPercent && now - lastUpdate >= 120) {
                                lastPercent = percent
                                lastUpdate = now
                                onProgress(percent)
                            }
                        }
                    }
                }
            }
            if (release.apkSize > 0 && bytes != release.apkSize) {
                throw UpdateException("The download is incomplete.")
            }
            verifyChecksum(release, digest.digest())
            verifySignature(part)
            if (!part.renameTo(target)) throw UpdateException("Could not store the download.")
            return target
        } catch (error: Throwable) {
            runCatching { part.delete() }
            throw error
        }
    }

    private fun verifyChecksum(release: Release, actual: ByteArray) {
        val sumsUrl = release.sumsUrl
            ?: throw UpdateException("This release has no checksum file, so the update wasn't installed.")
        val text = try {
            val request = Request.Builder()
                .url(sumsUrl)
                .header("User-Agent", "OpenTunnel/${BuildConfig.VERSION_NAME}")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw UpdateException("Couldn't download the checksum file.")
                response.body?.string() ?: throw UpdateException("Empty checksum file.")
            }
        } catch (error: UpdateException) {
            throw error
        } catch (error: IOException) {
            throw UpdateException("Couldn't download the checksum file.")
        }

        val expected = text.lineSequence().mapNotNull { line ->
            val parts = line.trim().split(Regex("\\s+"), limit = 2)
            if (parts.size == 2 && parts[0].length == 64) {
                parts[0].lowercase() to parts[1].removePrefix("*").trim()
            } else {
                null
            }
        }.firstOrNull { it.second == release.apkName }?.first
            ?: throw UpdateException("The checksum file doesn't list this APK.")

        val actualHex = actual.joinToString("") { "%02x".format(it) }
        if (!expected.equals(actualHex, ignoreCase = true)) {
            throw UpdateException("Checksum mismatch. The update wasn't installed.")
        }
    }

    @Suppress("DEPRECATION")
    private fun verifySignature(file: File) {
        val pm = app.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        val current = runCatching { pm.getPackageInfo(app.packageName, flags) }.getOrNull()
            ?: throw UpdateException("Couldn't read this app's signing key.")
        val archive = runCatching { pm.getPackageArchiveInfo(file.absolutePath, flags) }.getOrNull()
            ?: throw UpdateException("The download is not a valid APK.")
        if (archive.packageName != app.packageName) {
            throw UpdateException("The download is a different app.")
        }
        if (archive.versionCode <= current.versionCode) {
            throw UpdateException("The download is not newer than the installed app.")
        }
        val currentKeys = signerHashes(current)
        val archiveKeys = signerHashes(archive)
        if (currentKeys.isEmpty() || archiveKeys.isEmpty() || currentKeys != archiveKeys) {
            throw UpdateException("The download is not signed with this app's key, so it wasn't installed.")
        }
    }

    @Suppress("DEPRECATION")
    private fun signerHashes(info: PackageInfo): Set<String> {
        val signatures: Array<Signature>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signing = info.signingInfo
            signing?.apkContentsSigners ?: signing?.signingCertificateHistory
        } else {
            info.signatures
        }
        return signatures.orEmpty().mapNotNull { signature ->
            runCatching {
                MessageDigest.getInstance("SHA-256")
                    .digest(signature.toByteArray())
                    .joinToString("") { "%02x".format(it) }
            }.getOrNull()
        }.toSet()
    }

    fun install(context: Context): InstallResult {
        val ready = _state.value as? State.Ready ?: return InstallResult.Unavailable
        if (!ready.file.isFile) {
            _state.value = State.Error("The downloaded file is gone. Download it again.", ready.release)
            return InstallResult.Unavailable
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            return InstallResult.NeedsPermission
        }
        return try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", ready.file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = ClipData.newRawUri("update", uri)
            }
            if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            InstallResult.Started
        } catch (_: ActivityNotFoundException) {
            InstallResult.Unavailable
        } catch (_: Exception) {
            InstallResult.Unavailable
        }
    }

    fun openInstallSettings(context: Context) {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
        } else {
            Intent(Settings.ACTION_SECURITY_SETTINGS)
        }
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onFailure { runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS)) } }
    }

    private fun updateDir(): File = File(app.cacheDir, "updates").apply { mkdirs() }

    private class UpdateException(message: String) : Exception(message)
}
