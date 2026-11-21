package com.louij2.s1dash

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import java.net.Inet4Address
import java.net.InetAddress

/**
 * Which address to poll OpenHaldex at (#992). With the address set to "auto", OpenHaldex is
 * found by name: the firmware advertises itself as "openhaldex" (_http._tcp) on its own AP and
 * when it joins another network in bridge mode, such as a Tbox or phone hotspot whose subnet
 * changes. The last address found is kept for next time, and 192.168.1.1 (its own AP) is the
 * fallback. A typed address is used as it is.
 */
class OhAddress(
    setting: String,
    private var cached: String?,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val save: (String) -> Unit,
) {
    val auto = isAuto(setting)
    private val typed = setting.trim()
    /** Last good reply, or when we started; a search only after [SEARCH_AFTER_MS] without one (#1061). */
    private var lastGood = clock()
    private var lastSearch = clock()
    /** Where the current address came from, for the Settings screen. */
    @Volatile var source = ""
        private set
    @Volatile var host: String = when {
        !auto -> typed.also { source = "typed" }
        cached != null -> cached!!.also { source = "last found" }
        else -> DEFAULT.also { source = "default" }
    }
        private set

    /**
     * A poll failed. Returns true when it's time to look for OpenHaldex again: after [SEARCH_AFTER_MS]
     * with no good reply, then again every [SEARCH_AFTER_MS]. Time, not a count: the C6 shares one
     * radio between Wi-Fi and BLE and sometimes takes 2-5 s to answer, which isn't a lost connection.
     */
    fun failed(): Boolean {
        if (!auto) return false
        val now = clock()
        if (now - lastGood < SEARCH_AFTER_MS || now - lastSearch < SEARCH_AFTER_MS) return false
        lastSearch = now
        // The last address found has stopped answering: try OpenHaldex's own AP while searching.
        if (host != DEFAULT) { host = DEFAULT; source = "default" }
        return true
    }

    fun answered() { lastGood = clock() }

    fun found(hostPort: String) {
        if (!auto) return
        host = hostPort
        source = "found by name"
        lastGood = clock()
        if (hostPort != cached) { cached = hostPort; save(hostPort) }
    }

    companion object {
        const val DEFAULT = "192.168.1.1"
        const val SERVICE_TYPE = "_http._tcp."
        const val SEARCH_AFTER_MS = 20_000L

        fun isAuto(setting: String) = setting.isBlank() || setting.trim().equals("auto", ignoreCase = true)

        /** The firmware's name, allowing the " (2)" a second unit on the same network would get. */
        fun isOpenHaldex(serviceName: String?): Boolean {
            val n = serviceName?.trim()?.lowercase() ?: return false
            return n == "openhaldex" || n.startsWith("openhaldex (")
        }

        /** "a.b.c.d" or "a.b.c.d:port" -> the IPv4 address part, or null for a name. */
        fun ipv4Of(hostPort: String): ByteArray? {
            val h = hostPort.substringBefore(':')
            val parts = h.split('.')
            if (parts.size != 4) return null
            val b = ByteArray(4)
            for (i in 0..3) b[i] = (parts[i].toIntOrNull()?.takeIf { it in 0..255 } ?: return null).toByte()
            return b
        }

        fun sameSubnet(ip: ByteArray, net: ByteArray, prefix: Int): Boolean {
            if (ip.size != net.size) return false
            var bits = prefix
            for (i in ip.indices) {
                val m = when { bits >= 8 -> 0xFF; bits <= 0 -> 0; else -> (0xFF shl (8 - bits)) and 0xFF }
                if ((ip[i].toInt() and m) != (net[i].toInt() and m)) return false
                bits -= 8
            }
            return true
        }

        /**
         * The network to reach [hostPort] on: the one whose subnet holds it (OpenHaldex's AP as a
         * Wi-Fi client, so requests don't go out over mobile data). None when no network holds it,
         * such as this device's own hotspot: the system routes that itself. For a name, Wi-Fi as before.
         */
        fun networkFor(ctx: Context, hostPort: String): Network? {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            @Suppress("DEPRECATION") val all = cm.allNetworks
            val ip = ipv4Of(hostPort)
                ?: return all.firstOrNull { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
            return all.firstOrNull { n ->
                cm.getLinkProperties(n)?.linkAddresses?.any { la ->
                    la.address is Inet4Address && sameSubnet(ip, la.address.address, la.prefixLength)
                } == true
            }
        }
    }
}

/** Looks for the "openhaldex" service with Android's DNS-SD (NsdManager), and reports host:port. */
@SuppressLint("NewApi")
class OhFinder(ctx: Context, private val onFound: (String) -> Unit) {
    private val nsd = ctx.getSystemService(Context.NSD_SERVICE) as NsdManager
    @Volatile private var listener: NsdManager.DiscoveryListener? = null
    @Volatile private var resolving = false

    @Synchronized
    fun search() {
        if (listener != null) return
        val l = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) {
                if (OhAddress.isOpenHaldex(info.serviceName)) resolve(info)
            }
            override fun onServiceLost(info: NsdServiceInfo) {}
            override fun onDiscoveryStarted(type: String) {}
            override fun onDiscoveryStopped(type: String) {}
            override fun onStartDiscoveryFailed(type: String, code: Int) { synchronized(this@OhFinder) { listener = null } }
            override fun onStopDiscoveryFailed(type: String, code: Int) {}
        }
        listener = l
        try { nsd.discoverServices(OhAddress.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l) } catch (_: Exception) { listener = null }
    }

    @Suppress("DEPRECATION") // resolveService: the newer callback API needs Android 14
    private fun resolve(info: NsdServiceInfo) {
        if (resolving) return
        resolving = true
        nsd.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, code: Int) { resolving = false }
            override fun onServiceResolved(info: NsdServiceInfo) {
                resolving = false
                val a: InetAddress = info.host ?: return
                if (a !is Inet4Address) return
                val port = info.port
                onFound(a.hostAddress + if (port == 80 || port <= 0) "" else ":$port")
                stop() // found it; search again only if it stops answering
            }
        })
    }

    @Synchronized
    fun stop() {
        val l = listener ?: return
        listener = null
        try { nsd.stopServiceDiscovery(l) } catch (_: Exception) {}
    }
}
