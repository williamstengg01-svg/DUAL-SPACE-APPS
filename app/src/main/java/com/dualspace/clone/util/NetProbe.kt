package com.dualspace.clone.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * One-shot network self-test that runs *inside a clone's process* shortly after its
 * Application is created, and writes what the clone can see to the log file:
 *
 *  - what ConnectivityManager reports (active network, INTERNET / VALIDATED capabilities),
 *  - whether a default NetworkCallback ever gets onAvailable (many apps show "no
 *    connection" until it does),
 *  - whether a plain HTTPS request works at socket level.
 *
 * Together these tell apart "the network is fine but the app thinks it is offline" from
 * "the app cannot reach the internet at all". It never throws and never touches the UI.
 */
object NetProbe {
    private const val TAG = "NetProbe"
    private const val DELAY_MS = 3_000L
    private const val CALLBACK_WAIT_MS = 4_000L

    @Volatile private var scheduled = false

    fun schedule(context: Context, label: String) {
        if (scheduled) return
        scheduled = true
        val app = context.applicationContext ?: context
        thread(name = "ds-net-probe", isDaemon = true) {
            try {
                Thread.sleep(DELAY_MS)
                run(app, label)
            } catch (t: Throwable) {
                DsLog.w(TAG, "probe crashed", t)
            }
        }
    }

    private fun run(context: Context, label: String) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (cm == null) {
            DsLog.w(TAG, "[$label] no ConnectivityManager")
            return
        }
        // 1. What the app is told about the current network.
        val active: Network? = runCatching { cm.activeNetwork }
            .onFailure { DsLog.w(TAG, "[$label] getActiveNetwork threw: $it") }.getOrNull()
        val caps = active?.let { n ->
            runCatching { cm.getNetworkCapabilities(n) }
                .onFailure { DsLog.w(TAG, "[$label] getNetworkCapabilities threw: $it") }.getOrNull()
        }
        val info = runCatching { @Suppress("DEPRECATION") cm.activeNetworkInfo }
            .onFailure { DsLog.w(TAG, "[$label] getActiveNetworkInfo threw: $it") }.getOrNull()
        DsLog.i(TAG, "[$label] activeNetwork=$active internet=${caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)} " +
            "validated=${caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)} " +
            "wifi=${caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)} cell=${caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)} " +
            "legacyInfo=${info?.let { "${it.typeName}/${it.detailedState}" }}")

        // 2. Does a default NetworkCallback fire? (This is the path most "offline" banners use.)
        val latch = CountDownLatch(1)
        var available: Network? = null
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { available = network; latch.countDown() }
        }
        val t0 = SystemClock.elapsedRealtime()
        val registered = runCatching { cm.registerDefaultNetworkCallback(cb); true }
            .onFailure { DsLog.w(TAG, "[$label] registerDefaultNetworkCallback threw: $it") }.getOrDefault(false)
        if (registered) {
            val fired = latch.await(CALLBACK_WAIT_MS, TimeUnit.MILLISECONDS)
            DsLog.i(TAG, "[$label] default NetworkCallback onAvailable=${if (fired) "yes ($available) after ${SystemClock.elapsedRealtime() - t0} ms" else "NO within $CALLBACK_WAIT_MS ms"}")
            runCatching { cm.unregisterNetworkCallback(cb) }
                .onFailure { DsLog.w(TAG, "[$label] unregisterNetworkCallback threw: $it") }
        }

        // 3. Socket-level reachability.
        val t1 = SystemClock.elapsedRealtime()
        val http = runCatching {
            val c = URL("https://connectivitycheck.gstatic.com/generate_204").openConnection() as HttpURLConnection
            c.connectTimeout = 5_000; c.readTimeout = 5_000; c.instanceFollowRedirects = false
            c.requestMethod = "GET"
            val code = c.responseCode
            c.disconnect()
            "HTTP $code"
        }.getOrElse { "failed: ${it.javaClass.simpleName}: ${it.message}" }
        DsLog.i(TAG, "[$label] https probe: $http in ${SystemClock.elapsedRealtime() - t1} ms")
    }
}
