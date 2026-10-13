package com.example.sshvpn

import com.jcraft.jsch.ChannelDirectTCPIP
import com.jcraft.jsch.Session
import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * SOCKS5 server (CONNECT only) on 127.0.0.1.
 * Forwards through SSH direct-tcpip with a configurable concurrency cap.
 */
class Socks5Server(
    private val sessionProvider: () -> Session?,
    maxChannels: Int = 48,
) {

    companion object {
        private const val FAIL_LOG_INTERVAL_MS = 4_000L
        private const val MAX_OPENS_IN_FLIGHT = 6
        private const val PUBLIC_DNS = "1.1.1.1"
    }

    private val limit = maxChannels.coerceIn(8, 128)
    private val poolSize = 24.coerceAtMost(limit + 8)
    private val pool = Executors.newFixedThreadPool(poolSize)
    private val gate = Semaphore(limit)
    private val openGate = Semaphore(MAX_OPENS_IN_FLIGHT)
    private val openLock = Any()
    private var server: ServerSocket? = null
    private val active = AtomicInteger(0)
    private val failCount = AtomicInteger(0)
    private val okCount = AtomicInteger(0)
    @Volatile private var lastFailLog = 0L

    @Volatile
    private var running = false

    val port: Int get() = server?.localPort ?: -1

    fun start() {
        val s = ServerSocket(0, 512, InetAddress.getByName("127.0.0.1"))
        server = s
        running = true
        LogStore.append("SOCKS concurrency limit=$limit (opens<=$MAX_OPENS_IN_FLIGHT)")
        pool.execute {
            while (running) {
                try {
                    val c = s.accept()
                    c.tcpNoDelay = true
                    c.soTimeout = 0
                    if (!gate.tryAcquire()) {
                        try { c.close() } catch (_: Exception) {}
                        continue
                    }
                    pool.execute {
                        try {
                            handle(c)
                        } finally {
                            gate.release()
                        }
                    }
                } catch (e: Exception) {
                    if (running) continue else break
                }
            }
        }
    }

    fun stop() {
        running = false
        try {
            server?.close()
        } catch (_: Exception) {
        }
        pool.shutdownNow()
        try {
            pool.awaitTermination(2, TimeUnit.SECONDS)
        } catch (_: Exception) {
        }
    }

    private fun handle(client: Socket) {
        var channel: ChannelDirectTCPIP? = null
        var counted = false
        try {
            val cin = DataInputStream(client.getInputStream())
            val cout = client.getOutputStream()

            if (cin.readUnsignedByte() != 5) return
            val nMethods = cin.readUnsignedByte()
            cin.readFully(ByteArray(nMethods))
            cout.write(byteArrayOf(5, 0))
            cout.flush()

            if (cin.readUnsignedByte() != 5) return
            val cmd = cin.readUnsignedByte()
            cin.readUnsignedByte()
            val atyp = cin.readUnsignedByte()
            var host: String = when (atyp) {
                1 -> {
                    val b = ByteArray(4); cin.readFully(b)
                    InetAddress.getByAddress(b).hostAddress ?: return
                }
                3 -> {
                    val len = cin.readUnsignedByte()
                    val b = ByteArray(len); cin.readFully(b)
                    String(b, Charsets.US_ASCII)
                }
                4 -> {
                    val b = ByteArray(16); cin.readFully(b)
                    InetAddress.getByAddress(b).hostAddress ?: return
                }
                else -> { reply(cout, 8); return }
            }
            var port = cin.readUnsignedShort()

            if (cmd != 1) {
                reply(cout, 7)
                return
            }

            // Rewrite tunnel-local / broken destinations so the user never has to
            // touch Private DNS or other system settings.
            val rewritten = rewriteDestination(host, port)
            host = rewritten.first
            port = rewritten.second

            if (shouldReject(host)) {
                reply(cout, 5)
                return
            }

            val session = sessionProvider()
            if (session == null || !session.isConnected) {
                reply(cout, 1)
                return
            }

            val ch = openDirectChannel(session, host, port)
            if (ch == null) {
                logFail(host, port, "channel is not opened")
                reply(cout, 5)
                return
            }
            channel = ch

            reply(cout, 0)
            counted = true
            active.incrementAndGet()
            val n = okCount.incrementAndGet()
            if (n <= 3 || n % 50 == 0) {
                LogStore.append("SOCKS ok $host:$port active=${active.get()}/$limit")
            }

            val rin = ch.inputStream
            val rout = ch.outputStream
            val t = Thread { pump(rin, cout) }.also {
                it.isDaemon = true
                it.start()
            }
            pump(cin, rout)
            t.join(2_000)
        } catch (_: Exception) {
        } finally {
            if (counted) active.decrementAndGet()
            try { channel?.disconnect() } catch (_: Exception) {}
            try { client.close() } catch (_: Exception) {}
        }
    }

    /**
     * Map fake tunnel DNS addresses (and DoT port 853) onto a real resolver
     * reachable through SSH so Android Private DNS works out of the box.
     */
    private fun rewriteDestination(host: String, port: Int): Pair<String, Int> {
        if (host.startsWith("198.18.") && (port == 53 || port == 853)) {
            return PUBLIC_DNS to port
        }
        // Some OEMs probe the VPN DNS IP on random ports — send DNS-ish ports to Cloudflare
        if (host.startsWith("198.18.") && port in listOf(53, 853, 443, 784, 8853)) {
            return PUBLIC_DNS to if (port == 443 || port == 53) port else 853
        }
        return host to port
    }

    private fun shouldReject(host: String): Boolean {
        if (host == "127.0.0.1" || host == "0.0.0.0" || host == "::1") return true
        // Remaining 198.18.x (non-DNS) is not a real internet host
        if (host.startsWith("198.18.")) return true
        // Iranian DPI sinkhole — never works via SSH
        if (host.startsWith("10.10.34.")) return true
        return false
    }

    private fun openDirectChannel(session: Session, host: String, port: Int): ChannelDirectTCPIP? {
        if (!openGate.tryAcquire(8, TimeUnit.SECONDS)) return null
        try {
            repeat(3) { attempt ->
                try {
                    val ch = synchronized(openLock) {
                        val c = session.openChannel("direct-tcpip") as ChannelDirectTCPIP
                        c.setHost(host)
                        c.setPort(port)
                        c.setOrgIPAddress("127.0.0.1")
                        c.setOrgPort(0)
                        c
                    }
                    ch.connect(12_000)
                    return ch
                } catch (_: Exception) {
                    try {
                        Thread.sleep(80L * (attempt + 1))
                    } catch (_: InterruptedException) {
                        return null
                    }
                }
            }
            return null
        } finally {
            openGate.release()
        }
    }

    private fun logFail(host: String, port: Int, message: String?) {
        val n = failCount.incrementAndGet()
        val now = System.currentTimeMillis()
        if (now - lastFailLog < FAIL_LOG_INTERVAL_MS) return
        lastFailLog = now
        LogStore.append(
            "SOCKS fail $host:$port (${message ?: "?"}) fails=$n ok=${okCount.get()} active=${active.get()}/$limit"
        )
    }

    private fun reply(out: OutputStream, code: Int) {
        out.write(byteArrayOf(5, code.toByte(), 0, 1, 0, 0, 0, 0, 0, 0))
        out.flush()
    }

    private fun pump(src: InputStream, dst: OutputStream) {
        val buf = ByteArray(32 * 1024)
        try {
            while (true) {
                val n = src.read(buf)
                if (n < 0) break
                dst.write(buf, 0, n)
                dst.flush()
            }
        } catch (_: Exception) {
        }
    }
}
