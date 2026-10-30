package dev.opentunnel.app.data

import android.content.Context
import org.json.JSONObject
import java.security.SecureRandom

/**
 * LAN sharing: a mixed HTTP + SOCKS proxy bound to 0.0.0.0 by the core, so a PC on
 * the phone's hotspot can use the tunnel. A random password is generated on first
 * use; the listener is never exposed without credentials unless the user clears them.
 */
data class ShareSettings(
    val enabled: Boolean = false,
    val port: Int = DEFAULT_PORT,
    val username: String = "opentunnel",
    val password: String = "",
) {
    val hasCredentials: Boolean
        get() = username.isNotBlank() && password.isNotBlank()

    companion object {
        const val DEFAULT_PORT = 1080
    }
}

class ShareStore(context: Context) {

    private val prefs = context.getSharedPreferences("opentunnel", Context.MODE_PRIVATE)

    fun load(): ShareSettings {
        val raw = prefs.getString(KEY, null)
        if (raw != null) {
            val parsed = runCatching {
                val json = JSONObject(raw)
                ShareSettings(
                    enabled = json.optBoolean("enabled", false),
                    port = json.optInt("port", ShareSettings.DEFAULT_PORT).coerceIn(1024, 65535),
                    username = json.optString("username", "opentunnel"),
                    password = json.optString("password", ""),
                )
            }.getOrNull()
            if (parsed != null) {
                if (parsed.password.isBlank()) {
                    val fixed = parsed.copy(password = newPassword())
                    save(fixed)
                    return fixed
                }
                return parsed
            }
        }
        val fresh = ShareSettings(password = newPassword())
        save(fresh)
        return fresh
    }

    fun save(settings: ShareSettings) {
        val json = JSONObject()
            .put("enabled", settings.enabled)
            .put("port", settings.port)
            .put("username", settings.username)
            .put("password", settings.password)
        prefs.edit().putString(KEY, json.toString()).apply()
    }

    private companion object {
        const val KEY = "lan_share"

        // No 0/O/1/l/I: the password is often typed on a PC by hand.
        fun newPassword(): String {
            val alphabet = "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
            val random = SecureRandom()
            return (1..16).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
        }
    }
}
