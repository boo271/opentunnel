package dev.opentunnel.app.service

import dev.opentunnel.app.data.Profile
import dev.opentunnel.app.data.ShareSettings
import org.json.JSONArray
import org.json.JSONObject

/** Builds the sing-box configuration for the active profile. */
object SingBoxConfig {

    /**
     * @param resolve maps a server hostname to an IPv4 literal before the tunnel
     *   exists, so the core never relies on the carrier's plaintext DNS.
     * @param share when set, adds a mixed HTTP + SOCKS inbound for the LAN.
     */
    fun build(
        profile: Profile,
        resolve: (String) -> String,
        warpSocksPort: Int = 0,
        share: ShareSettings? = null,
    ): String {
        val root = JSONObject()
        root.put(
            "log",
            JSONObject()
                .put("level", "info")
                .put("timestamp", true),
        )
        root.put("dns", defaultDns())
        val inbounds = tunInbound()
        if (share != null) inbounds.put(lanInbound(share))
        root.put("inbounds", inbounds)

        when (profile.protocol) {
            Profile.PROTOCOL_WARP_MASQUE -> {
                root.put("outbounds", masqueOutbounds(warpSocksPort))
            }

            Profile.PROTOCOL_WIREGUARD -> {
                val parsed = parseWireGuard(profile.wgConfig)
                root.put("endpoints", JSONArray().put(wireGuardEndpoint(parsed, resolve)))
                root.put(
                    "outbounds",
                    JSONArray().put(
                        JSONObject()
                            .put("type", "direct")
                            .put("tag", "direct"),
                    ),
                )
            }

            else -> {
                root.put("outbounds", vlessOutbounds(profile, resolve))
            }
        }

        root.put(
            "route",
            JSONObject()
                .put(
                    "rules",
                    JSONArray()
                        .put(JSONObject().put("action", "sniff"))
                        // Queries for the TUN DNS address would otherwise be forwarded
                        // to the outbound as plain UDP. Resolve them in-core instead.
                        .put(
                            JSONObject()
                                .put("protocol", "dns")
                                .put("action", "hijack-dns"),
                        ),
                )
                .put("final", "proxy")
                .put("auto_detect_interface", true),
        )

        return root.toString(2)
    }

    private fun defaultDns(): JSONObject {
        // DoH over the tunnel: plain UDP/53 is blocked on some carriers. The server
        // is an IP literal, so the resolver needs no bootstrap lookup.
        val servers = JSONArray()
            .put(
                JSONObject()
                    .put("type", "https")
                    .put("tag", "remote")
                    .put("server", "1.1.1.1")
                    .put("path", "/dns-query")
                    .put("detour", "proxy"),
            )
        return JSONObject()
            .put("servers", servers)
            .put("final", "remote")
    }

    private fun tunInbound(): JSONArray = JSONArray().put(
        JSONObject()
            .put("type", "tun")
            .put("tag", "tun-in")
            .put("address", JSONArray().put("172.19.0.1/30"))
            .put("mtu", 1500)
            .put("auto_route", true)
            .put("strict_route", false)
            .put("stack", "gvisor"),
    )

    // Tethered traffic never enters a VpnService; this listener handles it instead,
    // so LAN clients follow the same outbound as the TUN.
    private fun lanInbound(share: ShareSettings): JSONObject {
        val inbound = JSONObject()
            .put("type", "mixed")
            .put("tag", "lan-in")
            .put("listen", "0.0.0.0")
            .put("listen_port", share.port)
        if (share.hasCredentials) {
            inbound.put(
                "users",
                JSONArray().put(
                    JSONObject()
                        .put("username", share.username)
                        .put("password", share.password),
                ),
            )
        }
        return inbound
    }

    private fun masqueOutbounds(socksPort: Int): JSONArray {
        require(socksPort in 1..65535) { "WARP helper port is not ready" }
        return JSONArray()
            .put(
                JSONObject()
                    .put("type", "socks")
                    .put("tag", "proxy")
                    .put("server", "127.0.0.1")
                    .put("server_port", socksPort)
                    .put("version", "5"),
            )
            .put(
                JSONObject()
                    .put("type", "direct")
                    .put("tag", "direct"),
            )
    }

    private fun vlessOutbounds(profile: Profile, resolve: (String) -> String): JSONArray {
        val tls = JSONObject()
            .put("enabled", true)
            .put("server_name", profile.sni)
            .put(
                "utls",
                JSONObject()
                    .put("enabled", true)
                    .put("fingerprint", profile.fingerprint.ifBlank { "chrome" }),
            )
        if (profile.publicKey.isNotBlank()) {
            tls.put(
                "reality",
                JSONObject()
                    .put("enabled", true)
                    .put("public_key", profile.publicKey)
                    .put("short_id", profile.shortId),
            )
        }

        val proxy = JSONObject()
            .put("type", "vless")
            .put("tag", "proxy")
            .put("server", resolve(profile.host))
            .put("server_port", profile.port)
            .put("uuid", profile.uuid)
            .put("tls", tls)
        if (profile.flow.isNotBlank()) {
            proxy.put("flow", profile.flow)
        }

        return JSONArray()
            .put(proxy)
            .put(
                JSONObject()
                    .put("type", "direct")
                    .put("tag", "direct"),
            )
    }

    private fun wireGuardEndpoint(config: WireGuardConfig, resolve: (String) -> String): JSONObject {
        val peer = JSONObject()
            .put("address", resolve(config.endpointHost))
            .put("port", config.endpointPort)
            .put("public_key", config.peerPublicKey)
            .put(
                "allowed_ips",
                JSONArray().apply {
                    config.allowedIps.forEach { put(it) }
                },
            )
            .put("persistent_keepalive_interval", 25)
        if (config.preSharedKey != null) {
            peer.put("pre_shared_key", config.preSharedKey)
        }
        if (config.reserved != null) {
            peer.put(
                "reserved",
                JSONArray().apply { config.reserved.forEach { put(it) } },
            )
        }

        return JSONObject()
            .put("type", "wireguard")
            .put("tag", "proxy")
            .put("mtu", config.mtu)
            .put(
                "address",
                JSONArray().apply { config.addresses.forEach { put(it) } },
            )
            .put("private_key", config.privateKey)
            .put("peers", JSONArray().put(peer))
    }

    private data class WireGuardConfig(
        val privateKey: String,
        val addresses: List<String>,
        val peerPublicKey: String,
        val preSharedKey: String?,
        val endpointHost: String,
        val endpointPort: Int,
        val allowedIps: List<String>,
        val reserved: List<Int>?,
        val mtu: Int,
    )

    /** Parses [Interface] / [Peer] text from wgcf or a VPN provider. */
    private fun parseWireGuard(text: String): WireGuardConfig {
        require(text.isNotBlank()) { "WireGuard: paste a configuration first" }

        var privateKey = ""
        val addresses = mutableListOf<String>()
        var mtu = 1280
        var section = ""
        var peerPublicKey = ""
        var preSharedKey: String? = null
        var endpoint = ""
        val allowedIps = mutableListOf<String>()
        var reserved: List<Int>? = null

        for (raw in text.lineSequence()) {
            val line = raw.substringBefore('#').substringBefore(';').trim()
            if (line.isEmpty()) continue
            if (line.startsWith("[") && line.endsWith("]")) {
                section = line.substring(1, line.length - 1).trim().lowercase()
                continue
            }
            val split = line.indexOf('=')
            if (split <= 0) continue
            val key = line.substring(0, split).trim().lowercase()
            val value = line.substring(split + 1).trim()
            if (value.isEmpty()) continue

            when (section) {
                "interface" -> when (key) {
                    "privatekey" -> privateKey = value
                    "address" -> addresses += splitList(value)
                    "mtu" -> value.toIntOrNull()?.let { mtu = it.coerceIn(576, 1500) }
                    "reserved" -> reserved = splitList(value).mapNotNull(String::toIntOrNull)
                        .takeIf { it.size == 3 }
                }

                "peer" -> when (key) {
                    "publickey" -> peerPublicKey = value
                    "presharedkey" -> preSharedKey = value
                    "endpoint" -> endpoint = value
                    "allowedips" -> allowedIps += splitList(value)
                    "reserved" -> reserved = splitList(value).mapNotNull(String::toIntOrNull)
                        .takeIf { it.size == 3 }
                }
            }
        }

        require(privateKey.isNotBlank()) { "WireGuard: missing PrivateKey" }
        require(addresses.isNotEmpty()) { "WireGuard: missing Address" }
        require(peerPublicKey.isNotBlank()) { "WireGuard: missing Peer PublicKey" }
        require(endpoint.isNotBlank()) { "WireGuard: missing Endpoint" }

        val (host, port) = splitEndpoint(endpoint)
        require(host.isNotBlank() && port in 1..65535) { "WireGuard: invalid Endpoint '$endpoint'" }

        return WireGuardConfig(
            privateKey = privateKey,
            addresses = addresses,
            peerPublicKey = peerPublicKey,
            preSharedKey = preSharedKey,
            endpointHost = host,
            endpointPort = port,
            allowedIps = allowedIps.ifEmpty { listOf("0.0.0.0/0", "::/0") },
            reserved = reserved,
            mtu = mtu,
        )
    }

    private fun splitList(value: String): List<String> =
        value.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    private fun splitEndpoint(value: String): Pair<String, Int> {
        val text = value.trim()
        return if (text.startsWith("[")) {
            val close = text.indexOf(']')
            if (close < 0) return "" to 0
            val host = text.substring(1, close)
            val port = text.substring(close + 1).trimStart(':').toIntOrNull() ?: 0
            host to port
        } else {
            val index = text.lastIndexOf(':')
            if (index <= 0) text to 51820
            else text.substring(0, index) to (text.substring(index + 1).toIntOrNull() ?: 0)
        }
    }
}
