package com.example.glorytun

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.math.roundToLong

enum class SpeedTestRoute(
    val title: String,
    val transportType: Int?,
) {
    WIFI("Wi-Fi", NetworkCapabilities.TRANSPORT_WIFI),
    CELLULAR("SIM", NetworkCapabilities.TRANSPORT_CELLULAR),
    BONDING("ボンディング", null),
}

data class SpeedTestResult(
    val route: SpeedTestRoute,
    val speedMbps: Double? = null,
    val latencyMs: Long? = null,
    val downloadedBytes: Long = 0L,
    val elapsedMs: Long = 0L,
    val errorMessage: String? = null,
) {
    val succeeded: Boolean get() = errorMessage == null
}

class NetworkSpeedTester(context: Context) {
    private val appContext = context.applicationContext
    private val connectivityManager =
        appContext.getSystemService(ConnectivityManager::class.java)

    suspend fun measure(route: SpeedTestRoute): SpeedTestResult = withContext(Dispatchers.IO) {
        val startedAtMs = SystemClock.elapsedRealtime()
        var lease: NetworkLease? = null
        try {
            lease = acquireNetwork(route)
            if (route.transportType != null && lease == null) {
                return@withContext SpeedTestResult(
                    route = route,
                    elapsedMs = SystemClock.elapsedRealtime() - startedAtMs,
                    errorMessage = "${route.title} の有効な回線が見つかりません"
                )
            }

            val network = lease?.network
            val latencyMs = measureLatency(network)
            val download = measureDownload(network)

            SpeedTestResult(
                route = route,
                speedMbps = download.mbps,
                latencyMs = latencyMs,
                downloadedBytes = download.bytes,
                elapsedMs = SystemClock.elapsedRealtime() - startedAtMs,
            )
        } catch (e: Exception) {
            SpeedTestResult(
                route = route,
                elapsedMs = SystemClock.elapsedRealtime() - startedAtMs,
                errorMessage = e.message ?: "測定に失敗しました"
            )
        } finally {
            lease?.release?.invoke()
        }
    }

    private suspend fun acquireNetwork(route: SpeedTestRoute): NetworkLease? {
        val transportType = route.transportType ?: return null
        findValidatedNetwork(transportType)?.let { return NetworkLease(it) {} }
        return requestValidatedNetwork(transportType)
    }

    private fun findValidatedNetwork(transportType: Int): Network? {
        return connectivityManager.allNetworks.firstOrNull { network ->
            connectivityManager.getNetworkCapabilities(network)
                ?.isUsableForTransport(transportType) == true
        }
    }

    private suspend fun requestValidatedNetwork(transportType: Int): NetworkLease? =
        suspendCancellableCoroutine { continuation ->
            val completed = AtomicBoolean(false)
            lateinit var callback: ConnectivityManager.NetworkCallback

            fun unregisterQuietly() {
                runCatching { connectivityManager.unregisterNetworkCallback(callback) }
            }

            fun finish(lease: NetworkLease?) {
                if (!completed.compareAndSet(false, true)) return
                if (lease == null) unregisterQuietly()
                continuation.resume(lease)
            }

            callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    val capabilities = connectivityManager.getNetworkCapabilities(network)
                    if (capabilities?.isUsableForTransport(transportType) == true) {
                        finish(NetworkLease(network) { unregisterQuietly() })
                    }
                }

                override fun onCapabilitiesChanged(
                    network: Network,
                    networkCapabilities: NetworkCapabilities,
                ) {
                    if (networkCapabilities.isUsableForTransport(transportType)) {
                        finish(NetworkLease(network) { unregisterQuietly() })
                    }
                }

                override fun onUnavailable() {
                    finish(null)
                }
            }

            continuation.invokeOnCancellation {
                if (completed.compareAndSet(false, true)) unregisterQuietly()
            }

            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .addTransportType(transportType)
                .build()

            runCatching {
                connectivityManager.requestNetwork(request, callback, NETWORK_REQUEST_TIMEOUT_MS)
            }.onFailure {
                finish(null)
            }
        }

    private fun NetworkCapabilities.isUsableForTransport(transportType: Int): Boolean {
        return hasTransport(transportType) &&
            hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
            hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
    }

    private fun measureLatency(network: Network?): Long {
        val samples = mutableListOf<Long>()
        repeat(LATENCY_SAMPLE_COUNT) {
            val startedAtNs = SystemClock.elapsedRealtimeNanos()
            val connection = openConnection(network, latencyUrl())
            try {
                connection.inputStream.use { input ->
                    if (input.read() < 0) throw IllegalStateException("遅延測定の応答が空です")
                }
                samples.add((SystemClock.elapsedRealtimeNanos() - startedAtNs) / 1_000_000L)
            } finally {
                connection.disconnect()
            }
        }
        return median(samples)
    }

    private fun measureDownload(network: Network?): DownloadMeasurement {
        val startedAtNs = SystemClock.elapsedRealtimeNanos()
        val connection = openConnection(network, downloadUrl())
        var totalBytes = 0L

        try {
            BufferedInputStream(connection.inputStream).use { input ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    totalBytes += read
                    val elapsedMs = (SystemClock.elapsedRealtimeNanos() - startedAtNs) / 1_000_000L
                    if (elapsedMs >= DOWNLOAD_MAX_DURATION_MS && totalBytes > 0L) break
                }
            }
        } finally {
            connection.disconnect()
        }

        if (totalBytes <= 0L) throw IllegalStateException("速度測定の応答が空です")

        val elapsedSeconds = (SystemClock.elapsedRealtimeNanos() - startedAtNs) / 1_000_000_000.0
        val mbps = totalBytes * 8.0 / elapsedSeconds / 1_000_000.0
        return DownloadMeasurement(totalBytes, mbps)
    }

    private fun openConnection(network: Network?, url: URL): HttpURLConnection {
        val connection = if (network != null) {
            network.openConnection(url)
        } else {
            url.openConnection()
        } as HttpURLConnection

        connection.connectTimeout = HTTP_TIMEOUT_MS
        connection.readTimeout = HTTP_TIMEOUT_MS
        connection.useCaches = false
        connection.setRequestProperty("Accept-Encoding", "identity")
        connection.setRequestProperty("Cache-Control", "no-cache")
        val code = connection.responseCode
        if (code !in 200..299) {
            connection.disconnect()
            throw IllegalStateException("HTTP $code")
        }
        return connection
    }

    private fun latencyUrl(): URL =
        URL("$SPEED_TEST_BASE_URL?bytes=1&cacheBust=${SystemClock.elapsedRealtimeNanos()}")

    private fun downloadUrl(): URL =
        URL("$SPEED_TEST_BASE_URL?bytes=$DOWNLOAD_BYTES&cacheBust=${SystemClock.elapsedRealtimeNanos()}")

    private data class NetworkLease(
        val network: Network,
        val release: () -> Unit,
    )

    private data class DownloadMeasurement(
        val bytes: Long,
        val mbps: Double,
    )

    companion object {
        private const val SPEED_TEST_BASE_URL = "https://speed.cloudflare.com/__down"
        private const val DOWNLOAD_BYTES = 3_000_000
        private const val DOWNLOAD_MAX_DURATION_MS = 15_000L
        private const val NETWORK_REQUEST_TIMEOUT_MS = 8_000
        private const val HTTP_TIMEOUT_MS = 12_000
        private const val BUFFER_SIZE = 32 * 1024
        private const val LATENCY_SAMPLE_COUNT = 3

        internal fun formatSpeed(speedMbps: Double?): String {
            if (speedMbps == null) return "--"
            if (speedMbps < 1.0) {
                return "${(speedMbps * 1_000).roundToLong()} kbps"
            }
            return String.format(Locale.US, "%.1f Mbps", speedMbps)
        }

        internal fun formatLatency(latencyMs: Long?): String {
            return latencyMs?.let { "${it} ms" } ?: "--"
        }

        internal fun median(values: List<Long>): Long {
            if (values.isEmpty()) return 0L
            val sorted = values.sorted()
            return sorted[sorted.size / 2]
        }
    }
}
