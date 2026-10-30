package dev.opentunnel.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.IpPrefix
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.annotation.RequiresApi
import dev.opentunnel.app.BuildConfig
import dev.opentunnel.app.Diag
import dev.opentunnel.app.MainActivity
import dev.opentunnel.app.R
import dev.opentunnel.app.data.Profile
import dev.opentunnel.app.data.ProfileStore
import dev.opentunnel.app.data.ShareStore
import io.nekohasekai.libbox.BridgeOptions
import io.nekohasekai.libbox.BridgeSession
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.ConnectionOwner
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LocalDNSTransport
import io.nekohasekai.libbox.NeighborUpdateListener
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.PlatformUser
import io.nekohasekai.libbox.RoutePrefix
import io.nekohasekai.libbox.RoutePrefixIterator
import io.nekohasekai.libbox.SetupOptions
import io.nekohasekai.libbox.ShellSession
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.SystemProxyStatus
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.libbox.WIFIState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URL
import java.net.URLEncoder
import java.net.NetworkInterface as JavaNetworkInterface
import io.nekohasekai.libbox.NetworkInterface as LibboxNetworkInterface
import io.nekohasekai.libbox.Notification as LibboxNotification

/**
 * Foreground VPN service. Hosts libbox and implements [PlatformInterface] so the core
 * can create the TUN device and protect its outbound sockets.
 */
class TunnelService : VpnService(), PlatformInterface {

    companion object {
        const val ACTION_START = "dev.opentunnel.app.action.START"
        const val ACTION_STOP = "dev.opentunnel.app.action.STOP"

        private const val TAG = "TunnelService"
        private const val CHANNEL_ID = "tunnel"
        private const val NOTIFICATION_ID = 1001

        private val _running = MutableStateFlow(false)
        val running = _running.asStateFlow()

        private val _status = MutableStateFlow("Disconnected")
        val status = _status.asStateFlow()

        @Volatile
        private var setupDone = false
        private val setupLock = Any()

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, TunnelService::class.java).setAction(ACTION_START),
            )
        }

        fun stop(context: Context) {
            runCatching {
                context.startService(
                    Intent(context, TunnelService::class.java).setAction(ACTION_STOP)
                )
            }
        }
    }

    @Volatile
    private var active = false

    @Volatile
    private var commandServer: CommandServer? = null

    private var engineThread: Thread? = null
    private var tun: ParcelFileDescriptor? = null
    private var myTunName: String = ""

    private var interfaceListener: InterfaceUpdateListener? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var warpSession: WarpMasque.Session? = null

    private val IPV4 = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopTunnel()
            else -> startTunnel()
        }
        return START_STICKY
    }

    private fun startTunnel() {
        if (active) return

        createChannel()
        startForegroundCompat(buildNotification("Connecting…"))

        val profile = ProfileStore(this).load()
        val configured = when (profile.protocol) {
            Profile.PROTOCOL_WIREGUARD -> profile.wgConfig.isNotBlank()
            Profile.PROTOCOL_WARP_MASQUE -> true
            else -> profile.host.isNotBlank() && profile.uuid.isNotBlank()
        }
        if (!configured) {
            _status.value = "Profile incomplete"
            notify("Profile incomplete", "Set your server details first.")
            stopSelf()
            return
        }

        val label = when (profile.protocol) {
            Profile.PROTOCOL_WIREGUARD -> "WireGuard"
            Profile.PROTOCOL_WARP_MASQUE -> "Cloudflare WARP"
            else -> "${profile.host}:${profile.port}"
        }

        active = true
        _running.value = true
        _status.value = "Connecting — $label…"
        Diag.log("Tunnel", "connect ${profile.protocol}")

        engineThread = Thread(
            { runEngine(profile, label) },
            "opentunnel-engine",
        ).also { it.start() }
    }

    private fun runEngine(profile: Profile, label: String) {
        try {
            // The WARP helper must be listening before sing-box needs its SOCKS port.
            var socksPort = 0
            if (profile.protocol == Profile.PROTOCOL_WARP_MASQUE) {
                val session = WarpMasque.start(
                    this,
                    this,
                    profile.sni.ifBlank { WarpMasque.DEFAULT_SNI },
                ) { message ->
                    _status.value = message
                    updateNotification(message)
                }
                warpSession = session
                if (!active) {
                    session.stop()
                    warpSession = null
                    return
                }
                socksPort = session.socksPort
            }

            // Runs off the main thread and before the TUN exists, so the core only
            // receives literal addresses.
            val share = ShareStore(this).load().takeIf { it.enabled }
            val config = SingBoxConfig.build(
                profile,
                { host -> resolveEndpoint(host) },
                socksPort,
                share,
            )
            setupLibbox()
            if (!active) return

            val server = CommandServer(handler, this)
            commandServer = server
            if (!active) {
                runCatching { server.close() }
                return
            }

            server.start()
            server.startOrReloadService(config, OverrideOptions().apply { autoRedirect = false })

            if (!active) {
                runCatching { server.closeService() }
                runCatching { server.close() }
                return
            }

            _status.value = "Connected — $label"
            updateNotification("Connected — $label")
            Diag.log("Tunnel", "connected ${profile.protocol}")
        } catch (t: Throwable) {
            Log.e(TAG, "engine failed", t)
            Diag.log("Tunnel", "engine failed\n" + Log.getStackTraceString(t))
            _status.value = "Failed: ${t.message ?: t.javaClass.simpleName}"
            notify("Connection failed", t.message ?: t.javaClass.simpleName)
            stopTunnel()
        }
    }

    private val handler = object : CommandServerHandler {
        override fun serviceStop() = stopTunnel()

        override fun serviceReload() {}

        override fun getSystemProxyStatus(): SystemProxyStatus = SystemProxyStatus().apply {
            available = false
            enabled = false
        }

        override fun setSystemProxyEnabled(enabled: Boolean) {}

        override fun triggerNativeCrash() {}

        override fun writeDebugMessage(message: String) {
            Log.d(TAG, message)
        }

        override fun connectSSHAgent(): Int = -1
    }

    private fun setupLibbox() {
        synchronized(setupLock) {
            if (setupDone) return
            Libbox.setup(
                SetupOptions().apply {
                    basePath = filesDir.absolutePath
                    workingPath = filesDir.absolutePath
                    tempPath = cacheDir.absolutePath
                    fixAndroidStack = true
                    logMaxLines = 3000
                    debug = BuildConfig.DEBUG
                    appVersion = BuildConfig.VERSION_NAME
                    appMarketingVersion = BuildConfig.VERSION_NAME
                },
            )
            setupDone = true
        }
    }

    private fun stopTunnel() {
        if (!active) {
            stopSelf()
            return
        }

        active = false
        _running.value = false
        // Keep the failure text visible on the home screen until the next connect.
        if (!_status.value.startsWith("Failed")) _status.value = "Disconnected"
        Diag.log("Tunnel", "disconnected")
        interfaceListener = null
        closeNetworkCallback()
        stopForegroundCompat()

        val server = commandServer
        commandServer = null
        val pfd = tun
        tun = null
        myTunName = ""
        val warp = warpSession
        warpSession = null

        Thread({
            runCatching { server?.closeService() }
            runCatching { server?.close() }
            runCatching { warp?.stop() }
            runCatching { pfd?.close() }
            stopSelf()
        }, "opentunnel-shutdown").start()
    }

    override fun onRevoke() {
        stopTunnel()
        super.onRevoke()
    }

    override fun onDestroy() {
        if (active) stopTunnel()
        closeNetworkCallback()
        super.onDestroy()
    }

    override fun openTun(options: TunOptions): Int {
        if (prepare(this) != null) throw IllegalStateException("VPN permission missing")

        val builder = Builder()
            .setSession("OpenTunnel")
            .setMtu(if (options.mtu > 0) options.mtu else 1500)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }

        val inet4 = collectPrefixes(options.inet4Address)
        val inet6 = collectPrefixes(options.inet6Address)
        for (prefix in inet4) builder.addAddress(prefix.address(), prefix.prefix())
        for (prefix in inet6) builder.addAddress(prefix.address(), prefix.prefix())

        if (options.autoRoute) {
            if (options.dnsMode?.value != Libbox.DNSModeDisabled) {
                for (address in collectStrings(options.dnsServerAddress)) {
                    builder.addDnsServer(address)
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val inet4Routes = collectPrefixes(options.inet4RouteAddress)
                if (inet4Routes.isNotEmpty()) {
                    for (prefix in inet4Routes) builder.addRoute(toIpPrefix(prefix))
                } else if (inet4.isNotEmpty()) {
                    builder.addRoute("0.0.0.0", 0)
                }

                val inet6Routes = collectPrefixes(options.inet6RouteAddress)
                if (inet6Routes.isNotEmpty()) {
                    for (prefix in inet6Routes) builder.addRoute(toIpPrefix(prefix))
                } else if (inet6.isNotEmpty()) {
                    builder.addRoute("::", 0)
                }

                for (prefix in collectPrefixes(options.inet4RouteExcludeAddress)) {
                    builder.excludeRoute(toIpPrefix(prefix))
                }
                for (prefix in collectPrefixes(options.inet6RouteExcludeAddress)) {
                    builder.excludeRoute(toIpPrefix(prefix))
                }
            } else {
                for (prefix in collectPrefixes(options.inet4RouteRange)) {
                    builder.addRoute(prefix.address(), prefix.prefix())
                }
                for (prefix in collectPrefixes(options.inet6RouteRange)) {
                    builder.addRoute(prefix.address(), prefix.prefix())
                }
            }

            for (pkg in collectStrings(options.includePackage)) {
                runCatching { builder.addAllowedApplication(pkg) }
            }
            for (pkg in collectStrings(options.excludePackage)) {
                runCatching { builder.addDisallowedApplication(pkg) }
            }
        }

        val pfd = builder.establish()
            ?: throw IllegalStateException("Android refused to create the tunnel")
        tun = pfd
        return pfd.fd
    }

    override fun autoDetectInterfaceControl(fd: Int) {
        if (!protect(fd)) Log.w(TAG, "protect failed for fd $fd")
    }

    override fun usePlatformAutoDetectInterfaceControl(): Boolean = true

    override fun getInterfaces(): NetworkInterfaceIterator {
        val entries = ArrayList<LibboxNetworkInterface>()
        try {
            val enumeration = JavaNetworkInterface.getNetworkInterfaces()
            while (enumeration.hasMoreElements()) {
                val ni = enumeration.nextElement()
                val entry = LibboxNetworkInterface()
                entry.name = ni.name
                entry.index = ni.index
                entry.mtu = runCatching { ni.mtu }.getOrDefault(1500)
                // Go's net.Flag* bits: Up=1, Broadcast=2, Loopback=4, PointToPoint=8,
                // Multicast=16. The core drops interfaces without FlagUp.
                entry.flags =
                    (if (ni.isUp) 1 else 0) or
                    (if (ni.supportsMulticast()) 16 else 0) or
                    (if (ni.isLoopback) 4 else 0) or
                    (if (ni.isPointToPoint) 8 else 0)
                entry.type = when {
                    ni.name?.startsWith("wlan") == true -> Libbox.InterfaceTypeWIFI
                    ni.name?.startsWith("ccmni") == true ||
                        ni.name?.startsWith("rmnet") == true -> Libbox.InterfaceTypeCellular
                    ni.name?.startsWith("eth") == true -> Libbox.InterfaceTypeEthernet
                    else -> Libbox.InterfaceTypeOther
                }
                entry.metered = false
                val addresses = ni.interfaceAddresses.mapNotNull { address ->
                    // Link-local IPv6 addresses carry a zone ("fe80::1%wlan0"), which
                    // netip.Prefix rejects.
                    address.address?.hostAddress?.substringBefore('%')?.let {
                        "$it/${address.networkPrefixLength}"
                    }
                }
                entry.addresses = StringArray(addresses.iterator())
                entries.add(entry)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "getInterfaces failed", t)
        }
        return object : NetworkInterfaceIterator {
            private val iterator = entries.iterator()

            override fun hasNext(): Boolean = iterator.hasNext()

            override fun next(): LibboxNetworkInterface = iterator.next()
        }
    }

    override fun findConnectionOwner(
        ipProtocol: Int,
        sourceAddress: String,
        sourcePort: Int,
        destinationAddress: String,
        destinationPort: Int,
    ): ConnectionOwner {
        val uid = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val cm = getSystemService(ConnectivityManager::class.java)
                cm.getConnectionOwnerUid(
                    ipProtocol,
                    InetSocketAddress(sourceAddress, sourcePort),
                    InetSocketAddress(destinationAddress, destinationPort),
                )
            } else {
                Libbox.findConnectionOwner(
                    ipProtocol,
                    sourceAddress,
                    sourcePort,
                    destinationAddress,
                    destinationPort,
                ).userId
            }
        } catch (t: Throwable) {
            Log.w(TAG, "findConnectionOwner failed", t)
            -1
        }
        if (uid == -1) throw IllegalStateException("connection owner not found")

        val owner = ConnectionOwner()
        owner.userId = uid
        val packages = packageManager.getPackagesForUid(uid)
        owner.userName = packages?.firstOrNull() ?: ""
        return owner
    }

    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        interfaceListener = listener
        if (networkCallback != null) {
            reportDefaultInterface()
            return
        }

        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = reportDefaultInterface()

            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities,
            ) = reportDefaultInterface()

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) =
                reportDefaultInterface()

            override fun onLost(network: Network) = reportDefaultInterface()
        }
        networkCallback = callback
        try {
            cm.registerDefaultNetworkCallback(callback)
            reportDefaultInterface()
        } catch (t: Throwable) {
            Log.e(TAG, "cannot start default interface monitor", t)
            networkCallback = null
        }
    }

    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        if (interfaceListener === listener) interfaceListener = null
        closeNetworkCallback()
    }

    private fun reportDefaultInterface() {
        val listener = interfaceListener ?: return
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val active = cm.activeNetwork ?: return
        val activeCaps = cm.getNetworkCapabilities(active)
        val physical = when {
            activeCaps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true ->
                findUnderlyingNetwork(cm)
            else -> active
        } ?: return
        val name = cm.getLinkProperties(physical)?.interfaceName ?: return
        if (name.isBlank() || name == myTunName) return
        val index = runCatching { JavaNetworkInterface.getByName(name)?.index ?: -1 }
            .getOrDefault(-1)
        listener.updateDefaultInterface(name, index, false, false)
    }

    /**
     * While our TUN owns the default route, ConnectivityManager reports the VPN as
     * active. The core needs the physical interface behind it; getUnderlyingNetworks
     * is hidden, so non-VPN networks are enumerated, validated first.
     */
    private fun findUnderlyingNetwork(cm: ConnectivityManager): Network? {
        val candidates = cm.allNetworks.filter { network ->
            val caps = cm.getNetworkCapabilities(network) ?: return@filter false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }
        return candidates.firstOrNull { network ->
            cm.getNetworkCapabilities(network)
                ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        } ?: candidates.firstOrNull()
    }

    private fun closeNetworkCallback() {
        val callback = networkCallback ?: return
        networkCallback = null
        runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(callback) }
    }

    /**
     * Resolves the endpoint before the tunnel exists, asking Cloudflare over DoH on
     * the physical network. Always returns an IPv4 literal.
     */
    private fun resolveEndpoint(host: String): String {
        val value = host.trim()
        if (value.isEmpty()) throw IllegalStateException("Server address is empty")
        if (value.contains(':')) return value
        if (IPV4.matches(value)) return value

        val cm = getSystemService(ConnectivityManager::class.java)
        val network = cm?.let { findUnderlyingNetwork(it) } ?: cm?.activeNetwork
        if (network != null) {
            queryDoh(network, value)?.let { return it }
        }

        val fallback = runCatching {
            InetAddress.getAllByName(value).firstOrNull { address -> address is Inet4Address }?.hostAddress
        }.getOrNull()
        if (fallback != null && IPV4.matches(fallback)) return fallback

        throw IllegalStateException("Could not resolve $value. Use the server's IP address instead.")
    }

    private fun queryDoh(network: Network, host: String): String? = runCatching {
        val encoded = URLEncoder.encode(host, "UTF-8")
        val connection = network.openConnection(
            URL("https://1.1.1.1/dns-query?name=$encoded&type=A"),
        ) as HttpURLConnection
        connection.connectTimeout = 5000
        connection.readTimeout = 5000
        connection.setRequestProperty("Accept", "application/dns-json")
        connection.setRequestProperty("User-Agent", "OpenTunnel")
        try {
            if (connection.responseCode != 200) return@runCatching null
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val answers = JSONObject(body).optJSONArray("Answer") ?: return@runCatching null
            for (index in 0 until answers.length()) {
                val record = answers.optJSONObject(index) ?: continue
                if (record.optInt("type") == 1) {
                    val address = record.optString("data")
                    if (IPV4.matches(address)) return@runCatching address
                }
            }
            null
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    override fun registerMyInterface(name: String?) {
        if (!name.isNullOrBlank()) myTunName = name
    }

    override fun includeAllNetworks(): Boolean = false

    override fun underNetworkExtension(): Boolean = false

    override fun clearDNSCache() {}

    override fun readWIFIState(): WIFIState? = null

    override fun localDNSTransport(): LocalDNSTransport? = null

    override fun startNeighborMonitor(listener: NeighborUpdateListener?) {}

    override fun closeNeighborMonitor(listener: NeighborUpdateListener?) {}

    override fun usePlatformBridge(): Boolean = false

    override fun createBridge(options: BridgeOptions?): BridgeSession =
        throw UnsupportedOperationException("bridge is not available on Android")

    override fun usePlatformShell(): Boolean = false

    override fun checkPlatformShell() =
        throw UnsupportedOperationException("shell is not available")

    override fun lookupUser(username: String?): PlatformUser =
        throw UnsupportedOperationException("platform users are not available")

    override fun openShellSession(
        user: PlatformUser?,
        command: String?,
        environ: StringIterator?,
        term: String?,
        rows: Int,
        cols: Int,
    ): ShellSession = throw UnsupportedOperationException("shell is not available")

    override fun readSystemSSHHostKey(): String = ""

    override fun lookupSFTPServer(): String = ""

    override fun tailscaleHostname(): String = "opentunnel"

    override fun sendNotification(notification: LibboxNotification) {}

    override fun cancelNotification(identifier: String, typeID: Int) {}

    private fun collectPrefixes(iterator: RoutePrefixIterator?): List<RoutePrefix> {
        if (iterator == null) return emptyList()
        val result = ArrayList<RoutePrefix>()
        while (iterator.hasNext()) result.add(iterator.next())
        return result
    }

    private fun collectStrings(iterator: StringIterator?): List<String> {
        if (iterator == null) return emptyList()
        val result = ArrayList<String>()
        while (iterator.hasNext()) result.add(iterator.next())
        return result
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun toIpPrefix(prefix: RoutePrefix): IpPrefix =
        IpPrefix(InetAddress.getByName(prefix.address()), prefix.prefix())

    private class StringArray(private val iterator: Iterator<String>) : StringIterator {
        override fun hasNext(): Boolean = iterator.hasNext()

        override fun next(): String = iterator.next()

        override fun len(): Int = 0
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    private fun updateNotification(text: String) {
        if (!active) return
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun notify(title: String, text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(
            NOTIFICATION_ID + 1,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true)
                .build(),
        )
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }
}
