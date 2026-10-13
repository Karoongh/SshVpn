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
 * Connection / speed test. When [socksPort] > 0, traffic goes through the
 * local SOCKS5 proxy (SSH tunnel).
 */
object SpeedTest {

    private val LATENCY_URLS = listOf(
        "https://www.cloudflare.com/cdn-cgi/trace",
        "https://1.1.1.1/cdn-cgi/trace",
        "https://www.google.com/generate_204",
    )
    private val DOWN_URLS = listOf(
        "https://speed.cloudflare.com/__down?bytes=",
        "https://proof.ovh.net/files/1Mb.dat",
    )

    fun run(
        socksPort: Int,
        downloadBytes: Int = 1_500_000,
        uploadBytes: Int = 750_000,
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

    private fun open(url: String, proxy: Proxy, connectMs: Int = 20_000, readMs: Int = 45_000): HttpURLConnection {
        val c = URL(url).openConnection(proxy) as HttpURLConnection
        c.connectTimeout = connectMs
        c.readTimeout = readMs
        c.useCaches = false
        c.instanceFollowRedirects = true
        return c
    }

    private fun measureLatency(proxy: Proxy): Long {
        var last: Exception? = null
        for (url in LATENCY_URLS) {
            repeat(2) {
                try {
                    val t0 = System.nanoTime()
                    val c = open(url, proxy, connectMs = 12_000, readMs = 15_000)
                    try {
                        c.requestMethod = "GET"
                        c.connect()
                        // generate_204 may have empty body
                        try { c.inputStream.use { it.readBytes() } } catch (_: Exception) {}
                        return (System.nanoTime() - t0) / 1_000_000L
                    } finally {
                        c.disconnect()
                    }
                } catch (e: Exception) {
                    last = e
                    try { Thread.sleep(300) } catch (_: Exception) {}
                }
            }
        }
        throw last ?: RuntimeException("latency failed")
    }

    private fun measureDownload(proxy: Proxy, bytes: Int): Pair<Double, Long> {
        var last: Exception? = null
        // Prefer Cloudflare sized download; fallback to fixed 1MB file
        val attempts = listOf(
            DOWN_URLS[0] + bytes,
            DOWN_URLS[0] + (bytes / 2),
            DOWN_URLS[1],
        )
        for (url in attempts) {
            try {
                val c = open(url, proxy)
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
                    if (sec > 0 && total > 0) return (total / sec) to total
                } finally {
                    c.disconnect()
                }
            } catch (e: Exception) {
                last = e
                try { Thread.sleep(400) } catch (_: Exception) {}
            }
        }
        throw last ?: RuntimeException("download failed")
    }

    private fun measureUpload(proxy: Proxy, bytes: Int): Pair<Double, Long> {
        var last: Exception? = null
        repeat(2) { attempt ->
            val size = if (attempt == 0) bytes else bytes / 2
            try {
                val c = open("https://speed.cloudflare.com/__up", proxy)
                try {
                    c.requestMethod = "POST"
                    c.doOutput = true
                    c.setFixedLengthStreamingMode(size)
                    c.setRequestProperty("Content-Type", "application/octet-stream")
                    val chunk = ByteArray(16 * 1024)
                    val t0 = System.nanoTime()
                    c.outputStream.use { out: OutputStream ->
                        var left = size
                        while (left > 0) {
                            val n = minOf(left, chunk.size)
                            out.write(chunk, 0, n)
                            left -= n
                        }
                        out.flush()
                    }
                    try { c.inputStream.use { it.readBytes() } } catch (_: Exception) {
                        try { c.errorStream?.use { it.readBytes() } } catch (_: Exception) {}
                    }
                    val sec = (System.nanoTime() - t0) / 1_000_000_000.0
                    if (sec > 0) return (size / sec) to size.toLong()
                } finally {
                    c.disconnect()
                }
            } catch (e: Exception) {
                last = e
                try { Thread.sleep(400) } catch (_: Exception) {}
            }
        }
        // Upload is optional for diagnosis — return 0 rather than failing whole test
        if (last != null) return 0.0 to 0L
        return -1.0 to 0L
    }
}
