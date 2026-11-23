package com.miaocast.app.capture.shell

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 通过 Android 原生 `NsdManager` 发现本机无线调试的配对端口（`_adb-tls-pairing._tcp.`）
 * 与连接端口（`_adb-tls-connect._tcp.`），并优先过滤为本机 IP 地址，防止误连同局域网其他手机。
 */
class WirelessAdbDiscovery(context: Context) {
    companion object {
        private const val TAG = "MiPlayWirelessAdb"
        const val SERVICE_PAIRING = "_adb-tls-pairing._tcp."
        const val SERVICE_CONNECT = "_adb-tls-connect._tcp."

        fun collectLocalIpv4Addresses(): Set<String> {
            val result = linkedSetOf("127.0.0.1")
            runCatching {
                val ifaces = NetworkInterface.getNetworkInterfaces() ?: return@runCatching
                while (ifaces.hasMoreElements()) {
                    val nif = ifaces.nextElement()
                    val addrs = nif.inetAddresses
                    while (addrs.hasMoreElements()) {
                        val addr = addrs.nextElement()
                        if (addr is Inet4Address) {
                            addr.hostAddress?.let { result.add(it) }
                        }
                    }
                }
            }
            return result
        }
    }

    data class Endpoint(val host: String, val port: Int, val serviceName: String)

    private val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private val active = AtomicBoolean(false)
    private var activeListener: NsdManager.DiscoveryListener? = null
    private val latestEndpoint = AtomicReference<Endpoint?>(null)

    fun currentEndpoint(): Endpoint? = latestEndpoint.get()

    fun startWatching(serviceType: String, onFound: (Endpoint) -> Unit) {
        stopWatching()
        val mgr = nsd ?: return
        val localIps = collectLocalIpv4Addresses()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                Log.i(TAG, "NSD startWatching: $regType")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                @Suppress("DEPRECATION")
                runCatching {
                    mgr.resolveService(
                        serviceInfo,
                        object : NsdManager.ResolveListener {
                            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                                Log.w(TAG, "NSD resolve failed ($serviceType): code=$errorCode")
                            }

                            override fun onServiceResolved(info: NsdServiceInfo) {
                                val host = info.host?.hostAddress ?: return
                                val port = info.port
                                if (port !in 1..65535) return
                                // 仅接受本机地址，避免同 Wi-Fi 下其他开启无线调试的设备干扰
                                if (host !in localIps) {
                                    Log.i(TAG, "NSD skip non-local $serviceType at $host:$port")
                                    return
                                }
                                val ep = Endpoint("127.0.0.1", port, info.serviceName.orEmpty())
                                latestEndpoint.set(ep)
                                Log.i(TAG, "NSD resolved local $serviceType -> 127.0.0.1:$port")
                                onFound(ep)
                            }
                        },
                    )
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                val cur = latestEndpoint.get()
                if (cur != null && cur.serviceName == serviceInfo.serviceName) {
                    latestEndpoint.set(null)
                }
            }

            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                active.set(false)
                Log.w(TAG, "NSD start failed ($serviceType): code=$errorCode")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                active.set(false)
            }
        }
        activeListener = listener
        active.set(true)
        runCatching {
            mgr.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
        }.onFailure {
            active.set(false)
            activeListener = null
        }
    }

    fun stopWatching() {
        val listener = activeListener
        activeListener = null
        if (active.getAndSet(false) && listener != null) {
            runCatching { nsd?.stopServiceDiscovery(listener) }
        }
    }

    /** 一次性阻塞等待本机指定服务端口就绪（用于投送点火或「测试连接」）。 */
    fun discoverOnce(serviceType: String, timeoutMs: Long = 3_500L): Endpoint? {
        val latch = CountDownLatch(1)
        val found = AtomicReference<Endpoint?>(null)
        startWatching(serviceType) { ep ->
            if (found.compareAndSet(null, ep)) {
                latch.countDown()
            }
        }
        return try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            found.get() ?: latestEndpoint.get()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        } finally {
            stopWatching()
        }
    }
}
