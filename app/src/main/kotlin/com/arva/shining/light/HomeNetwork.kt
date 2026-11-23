package com.arva.shining.light

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import com.arva.shining.ConfigStore
import java.net.Inet4Address
import java.net.InetAddress

/** How far a request may go in deciding that the current Wi-Fi is home. */
enum class PasswordTrust {
    /** Background work and other apps: only a network already recognised as home. */
    KNOWN_NETWORK,

    /** The app is open and no home is recorded yet: the first network where the login works becomes home. */
    FIRST_USE,

    /** The user pressed Test: the network where the login works becomes home, replacing any earlier one. */
    CONFIRM,
}

/** The network to use for a request, and whether the password may be sent on it. */
data class LightRoute(val network: Network?, val maySendPassword: Boolean)

/** Decides where the OpenBeken password may go. It travels unencrypted, so it stays on the home Wi-Fi. */
interface PasswordGate {
    fun route(requested: Network?, trust: PasswordTrust): LightRoute

    /** The light accepted the password on [network]. */
    fun accepted(network: Network?, trust: PasswordTrust)
}

/**
 * A permission-free description of a Wi-Fi network. Android hides the network name and router MAC
 * without precise location, so this uses the subnet, gateway and DHCP search domain. Two networks
 * with identical settings look the same, which is why [OpenBekenLight] also requires an OpenBeken
 * login challenge before it sends the password.
 */
object NetworkFingerprint {
    fun of(subnet: String?, gateway: String?, domains: String?): String? {
        if (subnet.isNullOrBlank()) return null
        return listOf(subnet, gateway.orEmpty(), domains.orEmpty().trim().lowercase()).joinToString("|")
    }

    /** The network address of [address] with [prefixLength] bits, as "192.168.1.0/24". */
    fun subnet(address: ByteArray, prefixLength: Int): String {
        val masked = address.copyOf()
        for (i in masked.indices) {
            val bits = (prefixLength - i * 8).coerceIn(0, 8)
            masked[i] = (masked[i].toInt() and (0xFF shl (8 - bits))).toByte()
        }
        return masked.joinToString(".") { (it.toInt() and 0xFF).toString() } + "/$prefixLength"
    }
}

class HomeNetworkGuard(context: Context, private val store: ConfigStore) : PasswordGate {
    private val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)

    override fun route(requested: Network?, trust: PasswordTrust): LightRoute {
        val home = store.homeNetwork
        val candidates = if (requested != null) listOf(requested) else wifiNetworks()
        val prints = candidates.map { it to fingerprint(it) }
        prints.firstOrNull { (_, print) -> print != null && print == home }?.let { (network, _) ->
            return LightRoute(network, maySendPassword = true)
        }
        val wifi = prints.firstOrNull { (_, print) -> print != null }?.first
        val mayLearn = trust == PasswordTrust.CONFIRM || (trust == PasswordTrust.FIRST_USE && home == null)
        return LightRoute(requested ?: wifi, maySendPassword = wifi != null && mayLearn)
    }

    override fun accepted(network: Network?, trust: PasswordTrust) {
        if (trust == PasswordTrust.KNOWN_NETWORK || network == null) return
        if (trust == PasswordTrust.FIRST_USE && store.homeNetwork != null) return
        fingerprint(network)?.let { store.homeNetwork = it }
    }

    // Looks past a VPN, which would otherwise hide the Wi-Fi network underneath it.
    @Suppress("DEPRECATION")
    private fun wifiNetworks(): List<Network> {
        val active = connectivity.activeNetwork
        return connectivity.allNetworks.filter(::isPlainWifi).sortedByDescending { it == active }
    }

    private fun isPlainWifi(network: Network): Boolean {
        val caps = connectivity.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }

    private fun fingerprint(network: Network): String? {
        if (!isPlainWifi(network)) return null
        val link = connectivity.getLinkProperties(network) ?: return null
        val ipv4 = link.linkAddresses.firstOrNull { it.address is Inet4Address } ?: return null
        return NetworkFingerprint.of(
            NetworkFingerprint.subnet(ipv4.address.address, ipv4.prefixLength),
            defaultGateway(link)?.hostAddress,
            link.domains,
        )
    }

    private fun defaultGateway(link: LinkProperties): InetAddress? =
        link.routes.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway
}
