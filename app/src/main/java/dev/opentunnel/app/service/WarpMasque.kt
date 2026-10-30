package dev.opentunnel.app.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.util.Base64
import android.util.Log
import dev.opentunnel.app.Diag
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Free Cloudflare WARP over MASQUE (Connect-IP, RFC 9484) through the bundled usque
 * helper.
 *
 * Device registration is done here over HTTPS because usque's own `register` command
 * resolves over plaintext UDP/53, which some carriers block. The result is an
 * anonymous Cloudflare device stored in `filesDir/warp.json`.
 *
 * usque runs as a child process of this VPN app, so its outbound socket would enter
 * our own TUN. It is pointed at a loopback relay instead, and the relay opens the
 * real connection from the physical network:
 *
 *   app -> TUN -> sing-box -> usque SOCKS -> loopback relay -> Cloudflare WARP
 *
 * TLS, HTTP/2 and certificate pinning stay end-to-end between usque and Cloudflare.
 * Only MASQUE over HTTP/2 (TCP 443) is used, since QUIC is blocked on some carriers.
 */
object WarpMasque {

    const val DEFAULT_SNI = "m.facebook.com"

    private const val TAG = "WarpMasque"
    private const val API = "https://api.cloudflareclient.com"
    private const val API_VERSION = "v0a4471"
    private const val DEFAULT_H2_ENDPOINT = "162.159.198.2"
    private const val CLIENT_VERSION = "a-6.35-4471"
    private const val CONFIG_NAME = "warp.json"
    private const val HELPER_CONFIG_NAME = "warp-helper.json"

    private val JSON = "application/json; charset=UTF-8".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    class Session internal constructor(
        val socksPort: Int,
        private val relay: Relay,
        private val process: Process,
        private val logThread: Thread,
    ) {
        fun stop() {
            relay.close()
            runCatching { killProcess(process) }
            runCatching { logThread.interrupt() }
        }
    }

    /**
     * Registers the device if needed, starts the loopback relay and the usque helper,
     * and returns once the local SOCKS port accepts connections.
     */
    fun start(
        context: Context,
        service: VpnService,
        sni: String,
        onStatus: (String) -> Unit,
    ): Session {
        val configFile = File(context.filesDir, CONFIG_NAME)
        val config = loadOrRegister(configFile, onStatus)
        val endpoint = config.optString("endpoint_h2_v4").ifBlank { DEFAULT_H2_ENDPOINT }

        val relay = Relay(service, endpoint, 443)
        try {
            onStatus("Starting WARP tunnel…")
            var lastError: Throwable? = null
            repeat(3) {
                val port = freePort()
                val session = runCatching { launch(context, config, relay, port, sni) }
                    .onFailure { lastError = it }
                    .getOrNull()
                if (session != null) return session
            }
            throw lastError ?: IllegalStateException("Could not start the WARP helper")
        } catch (t: Throwable) {
            relay.close()
            throw t
        }
    }

    private fun launch(
        context: Context,
        config: JSONObject,
        relay: Relay,
        socksPort: Int,
        sni: String,
    ): Session {
        val binary = File(context.applicationInfo.nativeLibraryDir, "libusque.so")
        if (!binary.isFile) {
            throw IllegalStateException("This build does not include the WARP engine for this CPU")
        }
        val helperConfig = File(context.filesDir, HELPER_CONFIG_NAME)
        helperConfig.writeText(
            JSONObject(config.toString())
                .put("endpoint_h2_v4", "127.0.0.1")
                .put("endpoint_h2_v6", "")
                .toString(2),
        )
        Log.i(TAG, "WARP helper SOCKS on 127.0.0.1:$socksPort, relay on ${relay.port}")

        val process = ProcessBuilder(
            binary.absolutePath,
            "socks",
            "--http2",
            "-s", sni.ifBlank { DEFAULT_SNI },
            "-b", "127.0.0.1",
            "-p", socksPort.toString(),
            "-P", relay.port.toString(),
            "-c", helperConfig.absolutePath,
        ).redirectErrorStream(true).start()

        val log = ArrayDeque<String>()
        val logThread = thread(name = "warp-log", isDaemon = true) {
            runCatching {
                process.inputStream.bufferedReader().forEachLine { line ->
                    Log.i(TAG, line)
                    synchronized(log) {
                        log.addLast(line)
                        if (log.size > 40) log.removeFirst()
                    }
                }
            }
        }

        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            if (!isRunning(process)) {
                val tail = synchronized(log) { log.takeLast(4).joinToString(" | ") }
                throw IllegalStateException("WARP helper stopped: $tail")
            }
            if (canConnect(socksPort)) {
                return Session(socksPort, relay, process, logThread)
            }
            Thread.sleep(200)
        }

        runCatching { killProcess(process) }
        throw IllegalStateException("WARP helper did not start in time")
    }

    /**
     * Process.isAlive, timed waitFor and destroyForcibly are API 26; Android 7.x only
     * has destroy and exitValue, so the helper is stopped by polling.
     */
    private fun isRunning(process: Process): Boolean =
        runCatching { process.exitValue() }.isFailure

    private fun waitForExit(process: Process, millis: Long): Boolean {
        val deadline = System.currentTimeMillis() + millis
        while (System.currentTimeMillis() < deadline) {
            if (!isRunning(process)) return true
            Thread.sleep(50)
        }
        return !isRunning(process)
    }

    private fun killProcess(process: Process) {
        runCatching { process.destroy() }
        if (!waitForExit(process, 2000) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching { process.destroyForcibly() }
        }
    }

    private fun canConnect(port: Int): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 300) }
        true
    }.getOrDefault(false)

    private fun freePort(): Int = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use {
        it.localPort
    }

    /** True when an anonymous WARP device registration is already stored. */
    fun hasRegistration(context: Context): Boolean =
        File(context.filesDir, CONFIG_NAME).isFile

    /** Deletes the stored registration; the next connect enrolls a new device. */
    fun forgetRegistration(context: Context): Boolean {
        val deleted = File(context.filesDir, CONFIG_NAME).delete()
        Diag.log("WARP", "registration forgotten")
        return deleted
    }

    /**
     * Stores a WARP config produced elsewhere (usque `register`) to skip in-app
     * sign-up. Accepts `private_key`, `endpoint_pub_key` and optional endpoints.
     */
    fun importRegistration(context: Context, text: String): Result<Unit> = runCatching {
        require(text.isNotBlank()) { "Paste the contents of warp.json first" }
        val parsed = JSONObject(text.trim())
        require(parsed.optString("private_key").isNotBlank()) { "Missing private_key" }
        require(parsed.optString("endpoint_pub_key").isNotBlank()) { "Missing endpoint_pub_key" }

        if (parsed.optString("endpoint_h2_v4").isBlank()) {
            parsed.put("endpoint_h2_v4", DEFAULT_H2_ENDPOINT)
        }
        if (parsed.optString("endpoint_v4").isBlank()) {
            parsed.put("endpoint_v4", DEFAULT_H2_ENDPOINT)
        }

        File(context.filesDir, CONFIG_NAME).writeText(parsed.toString(2))
        Diag.log("WARP", "registration imported")
    }

    private fun loadOrRegister(file: File, onStatus: (String) -> Unit): JSONObject {
        if (file.isFile) {
            val cached = runCatching { JSONObject(file.readText()) }.getOrNull()
            if (cached != null &&
                cached.optString("private_key").isNotBlank() &&
                cached.optString("endpoint_pub_key").isNotBlank()
            ) {
                return cached
            }
        }

        onStatus("Creating a free WARP account…")
        val config = try {
            register()
        } catch (t: Throwable) {
            throw IllegalStateException(
                "WARP sign-up failed: this network cannot reach api.cloudflareclient.com. " +
                    "Connect to Wi-Fi once, or paste an existing WARP config in Settings. " +
                    "(${t.message ?: t.javaClass.simpleName})",
                t,
            )
        }
        file.writeText(config.toString(2))
        Diag.log("WARP", "new registration created")
        return config
    }

    private fun register(): JSONObject {
        val random = SecureRandom()
        val wgKey = ByteArray(32).also(random::nextBytes)
        val serial = ByteArray(8).also(random::nextBytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val tos = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).format(Date())

        val registration = JSONObject()
            .put("key", b64(wgKey))
            .put("install_id", "")
            .put("fcm_token", "")
            .put("tos", tos)
            .put("model", "PC")
            .put("serial_number", serial)
            .put("os_version", "")
            .put("key_type", "curve25519")
            .put("tunnel_type", "wireguard")
            .put("locale", "en_US")

        val account = call("POST", "$API/$API_VERSION/reg", registration.toString(), null)
        val id = account.optString("id")
        val token = account.optString("token")
        if (id.isBlank() || token.isBlank()) {
            throw IllegalStateException("Cloudflare registration returned an incomplete response")
        }

        val keyPair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"), random)
        }.generateKeyPair()
        val privateKey = keyPair.private as ECPrivateKey
        val publicKey = keyPair.public as ECPublicKey
        val publicPoint = uncompressedPoint(publicKey)

        val enrollment = JSONObject()
            // Must be an X.509 SubjectPublicKeyInfo like usque sends; a raw EC point
            // is rejected during the MASQUE handshake.
            .put("key", b64(publicKey.encoded))
            .put("key_type", "secp256r1")
            .put("tunnel_type", "masque")
            .put("name", "OpenTunnel")

        val updated = call("PATCH", "$API/$API_VERSION/reg/$id", enrollment.toString(), token)
        val peers = updated.getJSONObject("config").getJSONArray("peers").getJSONObject(0)
        val endpoint = peers.getJSONObject("endpoint")
        val addresses = updated.getJSONObject("config")
            .getJSONObject("interface")
            .getJSONObject("addresses")

        return JSONObject()
            .put("private_key", b64(sec1PrivateKey(privateKey, publicPoint)))
            .put("endpoint_v4", hostOnly(endpoint.optString("v4")))
            .put("endpoint_v6", hostOnly(endpoint.optString("v6")))
            .put("endpoint_h2_v4", DEFAULT_H2_ENDPOINT)
            .put("endpoint_h2_v6", "")
            .put("endpoint_pub_key", peers.optString("public_key"))
            .put("id", updated.optString("id", id))
            .put("access_token", token)
            .put("ipv4", addresses.optString("v4"))
            .put("ipv6", addresses.optString("v6"))
    }

    private fun call(method: String, url: String, body: String, token: String?): JSONObject {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "WARP for Android")
            .header("CF-Client-Version", CLIENT_VERSION)
            .header("Content-Type", "application/json; charset=UTF-8")
            .apply { if (token != null) header("Authorization", "Bearer $token") }
            .method(method, body.toRequestBody(JSON))
            .build()

        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("Cloudflare HTTP ${response.code}: ${text.take(180)}")
            }
            return JSONObject(text)
        }
    }

    private fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    /** Strips the `:0` / `[...]:0` decoration Cloudflare adds to endpoint addresses. */
    private fun hostOnly(value: String?): String {
        val text = value?.trim().orEmpty()
        if (text.startsWith("[")) {
            val end = text.indexOf(']')
            if (end > 0) return text.substring(1, end)
        }
        val first = text.indexOf(':')
        val last = text.lastIndexOf(':')
        return if (first > 0 && first == last) text.substring(0, first) else text
    }

    /** `0x04 || X || Y`, the uncompressed point form Cloudflare and usque exchange. */
    private fun uncompressedPoint(publicKey: ECPublicKey): ByteArray {
        val w = publicKey.w
        return byteArrayOf(0x04) + fixed(w.affineX, 32) + fixed(w.affineY, 32)
    }

    private fun fixed(value: BigInteger, size: Int): ByteArray {
        val bytes = value.toByteArray()
        val out = ByteArray(size)
        if (bytes.size > size) {
            bytes.copyInto(out, 0, bytes.size - size)
        } else {
            bytes.copyInto(out, size - bytes.size)
        }
        return out
    }

    /**
     * Go's `x509.ParseECPrivateKey` needs SEC1 with the named-curve OID, which Java
     * does not expose. Layout:
     *   SEQUENCE { INTEGER 1, OCTET STRING scalar, [0] OID prime256v1, [1] BIT STRING point }
     */
    private fun sec1PrivateKey(privateKey: ECPrivateKey, publicPoint: ByteArray): ByteArray {
        val body = ByteArrayOutputStream()
        body.write(byteArrayOf(0x02, 0x01, 0x01))
        body.write(byteArrayOf(0x04, 0x20))
        body.write(fixed(privateKey.s, 32))
        body.write(byteArrayOf(0xA0.toByte(), 0x0A, 0x06, 0x08))
        body.write(byteArrayOf(0x2A, 0x86.toByte(), 0x48, 0xCE.toByte(), 0x3D, 0x03, 0x01, 0x07))
        body.write(byteArrayOf(0xA1.toByte(), 0x44, 0x03, 0x42, 0x00))
        body.write(publicPoint)

        val content = body.toByteArray()
        require(content.size < 0x80) { "Unexpected SEC1 key size" }
        return byteArrayOf(0x30, content.size.toByte()) + content
    }

    /**
     * Loopback TCP relay. usque dials 127.0.0.1 here; the real connection is opened
     * from the physical network so it cannot be swallowed by our own TUN. Bytes are
     * spliced verbatim, keeping TLS and certificate pinning end-to-end.
     */
    internal class Relay(
        private val service: VpnService,
        private val targetHost: String,
        private val targetPort: Int,
    ) {
        private val server = ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"))
        val port: Int = server.localPort

        private val running = AtomicBoolean(true)
        private val sockets = Collections.synchronizedSet(mutableSetOf<Socket>())

        init {
            thread(name = "warp-relay", isDaemon = true) { acceptLoop() }
        }

        fun close() {
            running.set(false)
            runCatching { server.close() }
            synchronized(sockets) {
                sockets.forEach { runCatching { it.close() } }
                sockets.clear()
            }
        }

        private fun acceptLoop() {
            while (running.get()) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                thread(name = "warp-relay-conn", isDaemon = true) { forward(client) }
            }
        }

        private fun forward(client: Socket) {
            var upstream: Socket? = null
            try {
                sockets.add(client)
                val socket = upstreamSocket()
                upstream = socket
                sockets.add(socket)
                socket.connect(InetSocketAddress(targetHost, targetPort), 15000)
                Log.i(
                    TAG,
                    "WARP upstream ${socket.localAddress?.hostAddress}:${socket.localPort} " +
                        "-> $targetHost:$targetPort",
                )

                client.soTimeout = 0
                socket.soTimeout = 0
                thread(name = "warp-relay-up", isDaemon = true) {
                    pump(socket.getInputStream(), client.getOutputStream(), client, socket)
                }
                pump(client.getInputStream(), socket.getOutputStream(), client, socket)
            } catch (t: Throwable) {
                Log.w(TAG, "relay connection failed: ${t.message}")
            } finally {
                sockets.remove(client)
                upstream?.let { sockets.remove(it) }
                runCatching { client.close() }
                runCatching { upstream?.close() }
            }
        }

        /**
         * The relay socket must not enter our TUN, or the MASQUE handshake loops into
         * sing-box. It is created from the physical network's factory and protected as
         * well; the factory binding keeps it off the VPN even while routes change.
         */
        private fun upstreamSocket(): Socket {
            val physical = physicalNetwork()
            val socket = try {
                physical?.socketFactory?.createSocket() as? Socket ?: Socket()
            } catch (t: Throwable) {
                Log.w(TAG, "physical socket failed: ${t.message}")
                Socket()
            }
            if (!runCatching { service.protect(socket) }.getOrDefault(false)) {
                Log.w(TAG, "protect() refused the WARP relay socket")
            }
            Log.i(TAG, "WARP upstream socket via ${physical ?: "default network"}")
            return socket
        }

        /** Active physical network, or a validated non-VPN one while the tunnel is up. */
        private fun physicalNetwork(): Network? {
            val cm = service.getSystemService(ConnectivityManager::class.java) ?: return null
            fun usable(network: Network): Boolean {
                val caps = cm.getNetworkCapabilities(network) ?: return false
                return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            }

            cm.activeNetwork?.takeIf(::usable)?.let { return it }
            val candidates = cm.allNetworks.filter(::usable)
            return candidates.firstOrNull { network ->
                cm.getNetworkCapabilities(network)
                    ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
            } ?: candidates.firstOrNull()
        }

        private fun pump(input: InputStream, output: OutputStream, a: Socket, b: Socket) {
            try {
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    output.flush()
                }
            } catch (_: Throwable) {
            } finally {
                runCatching { a.close() }
                runCatching { b.close() }
            }
        }
    }
}
