package com.louij2.s1dash

import android.content.Context
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Finds OpenHaldex by asking every address on this device's small private subnets for
 * /api/dashboard (#992 follow-up). Needed when this device IS the hotspot OpenHaldex joined in
 * bridge mode (the Tbox): Android's name lookup (NsdManager) only searches networks the device
 * has joined, never its own hotspot, so the search by name finds nothing there.
 */
object OhSweep {
    private const val PARALLEL = 32
    private const val CONNECT_MS = 400
    private const val READ_MS = 1500

    /** This device's IPv4 subnets that are worth sweeping: private, /24 to /30, interface up, not mobile data or VPN. */
    fun localSubnets(): List<Pair<ByteArray, Int>> = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual && !skipInterface(it.name) }
            .flatMap { ni -> ni.interfaceAddresses.filter { it.address is Inet4Address }.map { it.address.address to it.networkPrefixLength.toInt() } }
            .filter { (ip, prefix) -> isPrivate(ip) && prefix in 24..30 }
    } catch (_: Exception) { emptyList() }

    /** Mobile data, VPNs and Wi-Fi Direct: never where OpenHaldex is. */
    fun skipInterface(name: String): Boolean =
        listOf("rmnet", "ccmni", "tun", "ppp", "dummy", "p2p", "ipsec", "clat", "v4-").any { name.startsWith(it) }

    fun isPrivate(ip: ByteArray): Boolean {
        val a = ip[0].toInt() and 0xFF; val b = ip[1].toInt() and 0xFF
        return a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168)
    }

    /** Every host address on [own]'s /[prefix] subnet except [own], the network and the broadcast address. */
    fun hosts(own: ByteArray, prefix: Int): List<String> {
        if (prefix !in 24..30) return emptyList()
        val base = ((own[0].toInt() and 0xFF) shl 24) or ((own[1].toInt() and 0xFF) shl 16) or ((own[2].toInt() and 0xFF) shl 8) or (own[3].toInt() and 0xFF)
        val mask = -1 shl (32 - prefix)
        val net = base and mask
        val size = 1 shl (32 - prefix)
        return (1 until size - 1).map { net + it }.filter { it != base }.map {
            "${(it ushr 24) and 0xFF}.${(it ushr 16) and 0xFF}.${(it ushr 8) and 0xFF}.${it and 0xFF}"
        }
    }

    /** OpenHaldex's /api/dashboard always has these two keys; a router's web page won't. */
    fun looksLikeOpenHaldex(body: String): Boolean = body.contains("\"lockTarget\"") && body.contains("\"mode\"")

    /** The first address that answers like OpenHaldex, or null. Takes a few seconds. */
    fun find(ctx: Context): String? {
        val candidates = localSubnets().flatMap { (ip, prefix) -> hosts(ip, prefix) }.distinct()
        if (candidates.isEmpty()) return null
        val pool = Executors.newFixedThreadPool(PARALLEL)
        val hit = AtomicReference<String?>(null)
        try {
            for (host in candidates) pool.execute { if (hit.get() == null && probe(ctx, host)) hit.compareAndSet(null, host) }
            pool.shutdown()
            pool.awaitTermination(30, TimeUnit.SECONDS)
        } finally {
            pool.shutdownNow()
        }
        return hit.get()
    }

    private fun probe(ctx: Context, host: String): Boolean = try {
        val url = URL("http://$host/api/dashboard")
        val c = (OhAddress.networkFor(ctx, host)?.openConnection(url) ?: url.openConnection()) as HttpURLConnection
        c.connectTimeout = CONNECT_MS
        c.readTimeout = READ_MS
        try { c.responseCode == 200 && looksLikeOpenHaldex(c.inputStream.bufferedReader().readText()) } finally { c.disconnect() }
    } catch (_: Exception) { false }
}
