package com.sebparoc.sebviewer

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.net.Inet4Address
import java.net.InetAddress

/** Finds sebviewer hosts on the local network through mDNS / DNS-SD. */
class HostDiscovery(context: Context, private val onChange: (List<Host>) -> Unit) {
    data class Host(val name: String, val address: String, val port: Int)

    private val appContext = context.applicationContext
    private val nsd = appContext.getSystemService(NsdManager::class.java)
    private val wifi = appContext.getSystemService(WifiManager::class.java)
    private val connectivity = appContext.getSystemService(ConnectivityManager::class.java)
    private var lock: WifiManager.MulticastLock? = null
    private val main = Handler(Looper.getMainLooper())
    private val hosts = LinkedHashMap<String, Host>()
    private val pending = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var listener: NsdManager.DiscoveryListener? = null

    fun start() {
        if (listener != null) return
        try {
            lock = wifi?.createMulticastLock("sebviewer")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            Log.w(TAG, "multicast lock failed", e)
        }
        val l = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "discovery start failed: $errorCode")
                listener = null
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                main.post {
                    pending.addLast(info)
                    resolveNext()
                }
            }
            override fun onServiceLost(info: NsdServiceInfo) {
                main.post {
                    if (hosts.remove(info.serviceName) != null) publish()
                }
            }
        }
        listener = l
        try {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l)
        } catch (e: Exception) {
            Log.w(TAG, "discoverServices failed", e)
            listener = null
        }
    }

    private fun resolveNext() {
        if (resolving) return
        val info = pending.removeFirstOrNull() ?: return
        resolving = true
        @Suppress("DEPRECATION")
        nsd.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                main.post { resolving = false; resolveNext() }
            }
            override fun onServiceResolved(si: NsdServiceInfo) {
                val addr = if (Build.VERSION.SDK_INT >= 34) {
                    pickAddress(si.hostAddresses)
                } else {
                    @Suppress("DEPRECATION") si.host?.hostAddress
                }
                val name = si.attributes["name"]?.let { String(it) } ?: si.serviceName
                main.post {
                    if (addr != null) {
                        hosts[si.serviceName] = Host(name, addr, si.port)
                        publish()
                    }
                    resolving = false
                    resolveNext()
                }
            }
        })
    }

    /**
     * A PC can list several addresses (Wi-Fi, Tailscale, Docker...). Take the one on the
     * phone's own network; Tailscale's only works with its app on, which you don't need here.
     */
    private fun pickAddress(addrs: List<InetAddress>): String? {
        val v4 = addrs.filterIsInstance<Inet4Address>()
        val local = localNetworks()
        return (v4.firstOrNull { a -> local.any { inSubnet(a, it) } }
            ?: v4.firstOrNull { !isTailscale(it) }
            ?: addrs.firstOrNull())?.hostAddress
    }

    private fun localNetworks(): List<LinkAddress> = try {
        @Suppress("DEPRECATION")
        connectivity.allNetworks.flatMap { connectivity.getLinkProperties(it)?.linkAddresses.orEmpty() }
    } catch (e: Exception) {
        emptyList()
    }

    private fun inSubnet(a: InetAddress, net: LinkAddress): Boolean {
        val x = a.address
        val y = net.address.address
        if (x.size != y.size) return false
        var bits = net.prefixLength
        for (i in x.indices) {
            if (bits <= 0) break
            val mask = if (bits >= 8) 0xFF else (0xFF shl (8 - bits)) and 0xFF
            if ((x[i].toInt() and mask) != (y[i].toInt() and mask)) return false
            bits -= 8
        }
        return true
    }

    private fun isTailscale(a: Inet4Address): Boolean {
        val b = a.address
        return (b[0].toInt() and 0xFF) == 100 && (b[1].toInt() and 0xFF) in 64..127
    }

    private fun publish() = onChange(hosts.values.toList())

    fun stop() {
        listener?.let {
            try { nsd.stopServiceDiscovery(it) } catch (_: Exception) {}
        }
        listener = null
        pending.clear()
        resolving = false
        try { lock?.release() } catch (_: Exception) {}
        lock = null
    }

    companion object {
        private const val TAG = "HostDiscovery"
        const val SERVICE_TYPE = "_sebviewer._tcp."
    }
}
