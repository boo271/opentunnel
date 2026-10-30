package dev.opentunnel.app.data

data class Profile(
    val name: String = "Cloudflare WARP (free)",
    val protocol: String = PROTOCOL_WARP_MASQUE,
    val host: String = "",
    val port: Int = 443,
    val uuid: String = "",
    val sni: String = "m.facebook.com",
    val publicKey: String = "",
    val shortId: String = "",
    val fingerprint: String = "chrome",
    val flow: String = "",
    val wgConfig: String = "",
) {
    val summary: String
        get() = when {
            protocol == PROTOCOL_WARP_MASQUE ->
                "Cloudflare WARP · SNI ${sni.ifBlank { "m.facebook.com" }}"
            protocol == PROTOCOL_WIREGUARD -> "WireGuard profile"
            host.isBlank() -> "No server configured"
            else -> "$host:$port · SNI $sni"
        }

    companion object {
        const val PROTOCOL_VLESS = "vless"
        const val PROTOCOL_WIREGUARD = "wireguard"
        const val PROTOCOL_WARP_MASQUE = "warp-masque"
    }
}

