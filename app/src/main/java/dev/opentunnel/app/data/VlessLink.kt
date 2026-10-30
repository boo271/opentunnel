package dev.opentunnel.app.data

import java.net.URI
import java.net.URLDecoder

/**
 * Parses `vless://` share links. Only VLESS + REALITY over TCP is supported.
 * Example:
 *   vless://uuid@203.0.113.10:443?security=reality&sni=m.facebook.com&fp=chrome&pbk=...&sid=...&type=tcp#My%20server
 */
object VlessLink {

    fun parse(link: String): Result<Profile> {
        val text = link.trim()
        if (!text.startsWith("vless://", ignoreCase = true)) {
            return Result.failure(IllegalArgumentException("Paste a link that starts with vless://"))
        }

        val uri = runCatching { URI(text) }.getOrElse {
            return Result.failure(IllegalArgumentException("That link is not a valid URI"))
        }

        val uuid = uri.userInfo?.trim().orEmpty()
        if (uuid.isEmpty()) {
            return Result.failure(IllegalArgumentException("The link is missing its UUID"))
        }

        val host = uri.host?.removeSurrounding("[", "]")?.trim().orEmpty()
        if (host.isEmpty()) {
            return Result.failure(IllegalArgumentException("The link is missing a server address"))
        }

        val params = parseQuery(uri.rawQuery)
        val security = params["security"].orEmpty().ifBlank { "none" }
        if (!security.equals("reality", ignoreCase = true)) {
            return Result.failure(
                IllegalArgumentException("Only VLESS + REALITY links are supported (this one uses $security)"),
            )
        }

        if (params["pbk"].isNullOrBlank()) {
            return Result.failure(IllegalArgumentException("The link is missing the REALITY public key (pbk)"))
        }

        val type = params["type"].orEmpty().ifBlank { "tcp" }
        if (!type.equals("tcp", ignoreCase = true)) {
            return Result.failure(IllegalArgumentException("Only TCP REALITY links are supported (this one uses $type)"))
        }

        val port = if (uri.port > 0) uri.port else 443
        val name = uri.fragment
            ?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
            ?.trim()
            .orEmpty()
            .ifBlank { "My server" }
        val sni = params["sni"].orEmpty().ifBlank { params["host"].orEmpty().ifBlank { host } }

        return Result.success(
            Profile(
                name = name,
                protocol = Profile.PROTOCOL_VLESS,
                host = host,
                port = port,
                uuid = uuid,
                sni = sni,
                publicKey = params["pbk"].orEmpty(),
                shortId = params["sid"].orEmpty(),
                fingerprint = params["fp"].orEmpty().ifBlank { "chrome" },
                flow = params["flow"].orEmpty(),
            ),
        )
    }

    private fun parseQuery(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        return raw.split('&').mapNotNull { pair ->
            if (pair.isBlank()) return@mapNotNull null
            val key = pair.substringBefore('=')
            val value = pair.substringAfter('=', "")
            val decodedKey = runCatching { URLDecoder.decode(key, "UTF-8") }.getOrDefault(key)
            val decodedValue = runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
            decodedKey to decodedValue
        }.toMap()
    }
}
