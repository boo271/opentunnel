package dev.opentunnel.app.data

import android.content.Context
import org.json.JSONObject

class ProfileStore(context: Context) {

    private val prefs = context.getSharedPreferences("opentunnel", Context.MODE_PRIVATE)

    fun load(): Profile {
        val raw = prefs.getString(KEY_PROFILE, null) ?: return Profile()
        return runCatching {
            val json = JSONObject(raw)
            Profile(
                name = json.optString("name", "My server"),
                protocol = json.optString("protocol", Profile.PROTOCOL_VLESS),
                host = json.optString("host", ""),
                port = json.optInt("port", 443),
                uuid = json.optString("uuid", ""),
                sni = json.optString("sni", "m.facebook.com"),
                publicKey = json.optString("publicKey", ""),
                shortId = json.optString("shortId", ""),
                fingerprint = json.optString("fingerprint", "chrome"),
                flow = json.optString("flow", ""),
                wgConfig = json.optString("wgConfig", ""),
            )
        }.getOrDefault(Profile())
    }

    fun save(profile: Profile) {
        val json = JSONObject()
            .put("name", profile.name)
            .put("protocol", profile.protocol)
            .put("host", profile.host)
            .put("port", profile.port)
            .put("uuid", profile.uuid)
            .put("sni", profile.sni)
            .put("publicKey", profile.publicKey)
            .put("shortId", profile.shortId)
            .put("fingerprint", profile.fingerprint)
            .put("flow", profile.flow)
            .put("wgConfig", profile.wgConfig)
        prefs.edit().putString(KEY_PROFILE, json.toString()).apply()
    }

    private companion object {
        const val KEY_PROFILE = "profile"
    }
}

