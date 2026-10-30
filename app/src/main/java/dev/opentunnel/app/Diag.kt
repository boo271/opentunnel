package dev.opentunnel.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Local diagnostics: a small rolling log plus uncaught-exception capture. Nothing is
 * uploaded; entries are written after stripping addresses, ids and keys, and the file
 * only leaves the phone when the user shares it from Settings.
 */
object Diag {

    private const val FILE_NAME = "opentunnel.log"
    private const val BACKUP_NAME = "opentunnel.log.1"
    private const val MAX_BYTES = 96L * 1024
    private const val MAX_LINE = 4000
    private const val MAX_LINES = 400

    private val writer = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "opentunnel-diag").apply { isDaemon = true }
    }
    private val fileLock = Any()
    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    private val ipv4 = Regex("""\b\d{1,3}(?:\.\d{1,3}){3}\b""")
    private val ipv6 = Regex("""\b(?:[0-9a-fA-F]{0,4}:){3,7}[0-9a-fA-F]{0,4}\b""")
    private val uuid = Regex("""\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\b""")
    private val longHex = Regex("""\b[0-9a-fA-F]{32,}\b""")

    @Volatile
    private var logDir: File? = null

    fun init(context: Context) {
        if (logDir != null) return
        val dir = File(context.filesDir, "logs")
        dir.mkdirs()
        logDir = dir

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { recordCrash(dir, thread, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    fun log(tag: String, message: String) {
        val dir = logDir ?: return
        val line = line(tag, message)
        writer.execute { runCatching { append(dir, line) } }
    }

    /** Copies the report to the clipboard. Call from a screen, not in the background. */
    fun copy(context: Context): Boolean = runCatching {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("OpenTunnel diagnostics", report(context)))
        true
    }.getOrDefault(false)

    /**
     * Writes the report to the cache and opens the system share sheet. Returns false
     * when no app can receive it, so the caller can fall back to copying.
     */
    fun share(context: Context): Boolean = runCatching {
        val dir = File(context.cacheDir, "logs").apply { mkdirs() }
        val file = File(dir, "opentunnel-diagnostics.txt")
        file.writeText(report(context))
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "OpenTunnel diagnostics")
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Share logs"))
        true
    }.getOrDefault(false)

    /** Version, device and the newest log lines. Shared only on request. */
    fun report(context: Context): String {
        val dir = logDir ?: File(context.filesDir, "logs")
        val text = StringBuilder()
        text.append("OpenTunnel ").append(BuildConfig.VERSION_NAME)
            .append(" (").append(BuildConfig.VERSION_CODE).append(")\n")
        text.append("Android ").append(Build.VERSION.RELEASE)
            .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
        text.append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
        text.append(Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown").append("\n\n")

        val all = StringBuilder()
        runCatching { File(dir, BACKUP_NAME).takeIf { it.isFile }?.readText()?.let(all::append) }
        runCatching { File(dir, FILE_NAME).takeIf { it.isFile }?.readText()?.let(all::append) }
        val lines = all.lineSequence().filter { it.isNotBlank() }.toList()
        if (lines.isEmpty()) {
            text.append("No events recorded yet.\n")
        } else {
            lines.takeLast(MAX_LINES).forEach { text.append(it).append('\n') }
        }
        return text.toString()
    }

    private fun recordCrash(dir: File, thread: Thread, error: Throwable) {
        val trace = runCatching { Log.getStackTraceString(error) }.getOrDefault(error.toString())
        append(dir, line("Crash", "uncaught in ${thread.name}\n$trace"))
    }

    private fun line(tag: String, message: String): String {
        val stamp = synchronized(timeFormat) { timeFormat.format(Date()) }
        val cleaned = clean(message)
        return "$stamp $tag: $cleaned\n"
    }

    private fun append(dir: File, line: String) {
        synchronized(fileLock) {
            runCatching {
                val file = File(dir, FILE_NAME)
                if (file.length() > MAX_BYTES) {
                    val backup = File(dir, BACKUP_NAME)
                    backup.delete()
                    file.renameTo(backup)
                }
                file.appendText(line)
            }
        }
    }

    /** Removes addresses, UUIDs and long hex keys; caps the length of stack traces. */
    private fun clean(message: String): String {
        var value = uuid.replace(message, "<id>")
        value = ipv4.replace(value, "<ip>")
        value = longHex.replace(value, "<key>")
        value = ipv6.replace(value, "<ip>")
        if (value.length > MAX_LINE) value = value.take(MAX_LINE) + "…"
        return value
    }
}
