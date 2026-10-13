package com.example.sshvpn

import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import kotlin.math.roundToInt

data class SpeedTestResult(
    val latencyMs: Long = -1,
    val downloadMbps: Double = -1.0,
    val uploadMbps: Double = -1.0,
    val downloadBytes: Long = 0,
    val uploadBytes: Long = 0,
    val error: String? = null,
    val viaProxy: Boolean = false,
)

/**
 * Simple connection test. When [socksPort] > 0, all traffic goes through the
 * local SOCKS5 proxy (the SSH tunnel) so results reflect real VPN speed.
 */
object SpeedTest {

    private const val DOWN_URL = "https://speed.cloudflare.com/__down?bytes="
    private const val UP_URL = "https://speed.cloudflare.com/__up"
    private const val LATENCY_URL = "https://www.cloudflare.com/cdn-cgi/trace"

    fun run(
        socksPort: Int,
        downloadBytes: Int = 2_000_000,
        uploadBytes: Int = 1_000_000,
        onProgress: (String) -> Unit = {},
    ): SpeedTestResult {
        val proxy = if (socksPort > 0) {
            Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort))
        } else {
            Proxy.NO_PROXY
        }
        val via = socksPort > 0

        return try {
            onProgress("Latency...")
            val latency = measureLatency(proxy)

            onProgress("Download...")
            val (downBps, downN) = measureDownload(proxy, downloadBytes)

            onProgress("Upload...")
            val (upBps, upN) = measureUpload(proxy, uploadBytes)

            SpeedTestResult(
                latencyMs = latency,
                downloadMbps = bpsToMbps(downBps),
                uploadMbps = bpsToMbps(upBps),
                downloadBytes = downN,
                uploadBytes = upN,
                viaProxy = via,
            )
        } catch (t: Throwable) {
            SpeedTestResult(error = t.message ?: t.javaClass.simpleName, viaProxy = via)
        }
    }

    private fun bpsToMbps(bps: Double): Double =
        if (bps < 0) -1.0 else ((bps * 8.0 / 1_000_000.0) * 100.0).roundToInt() / 100.0

    private fun open(url: String, proxy: Proxy): HttpURLConnection {
        val c = URL(url).openConnection(proxy) as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        c.useCaches = false
        c.instanceFollowRedirects = true
        return c
    }

    private fun measureLatency(proxy: Proxy): Long {
        val t0 = System.nanoTime()
        val c = open(LATENCY_URL, proxy)
        try {
            c.requestMethod = "GET"
            c.connect()
            c.inputStream.use { it.readBytes() }
            return (System.nanoTime() - t0) / 1_000_000L
        } finally {
            c.disconnect()
        }
    }

    private fun measureDownload(proxy: Proxy, bytes: Int): Pair<Double, Long> {
        val c = open(DOWN_URL + bytes, proxy)
        try {
            c.requestMethod = "GET"
            c.connect()
            val t0 = System.nanoTime()
            var total = 0L
            c.inputStream.use { input ->
                val buf = ByteArray(32 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                }
            }
            val sec = (System.nanoTime() - t0) / 1_000_000_000.0
            if (sec <= 0 || total <= 0) return -1.0 to total
            return (total / sec) to total
        } finally {
            c.disconnect()
        }
    }

    private fun measureUpload(proxy: Proxy, bytes: Int): Pair<Double, Long> {
        val c = open(UP_URL, proxy)
        try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.setFixedLengthStreamingMode(bytes)
            c.setRequestProperty("Content-Type", "application/octet-stream")
            val chunk = ByteArray(32 * 1024)
            val t0 = System.nanoTime()
            c.outputStream.use { out: OutputStream ->
                var left = bytes
                while (left > 0) {
                    val n = minOf(left, chunk.size)
                    out.write(chunk, 0, n)
                    left -= n
                }
                out.flush()
            }
            // Read response to complete the request
            try { c.inputStream.use { it.readBytes() } } catch (_: Exception) {
                try { c.errorStream?.use { it.readBytes() } } catch (_: Exception) {}
            }
            val sec = (System.nanoTime() - t0) / 1_000_000_000.0
            if (sec <= 0) return -1.0 to 0L
            return (bytes / sec) to bytes.toLong()
        } finally {
            c.disconnect()
        }
    }
}
