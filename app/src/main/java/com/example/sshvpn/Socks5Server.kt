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
 * SOCKS5 on 127.0.0.1 — CONNECT via SSH direct-tcpip, optional UDP ASSOCIATE via UDPGW.
 */
class Socks5Server(
    private val sessionProvider: () -> Session?,
    maxChannels: Int = 48,
    private val udpgw: UdpgwClient? = null,
) {

    companion object {
        private const val FAIL_LOG_INTERVAL_MS = 4_000L
        private const val MAX_OPENS_IN_FLIGHT = 12
        private const val PUBLIC_DNS = "1.1.1.1"
        /** Drop idle data sockets so stuck video channels free the gate. */
        private const val DATA_IDLE_TIMEOUT_MS = 120_000
    }

    private val limit = maxChannels.coerceIn(8, 128)
    private val poolSize = 32.coerceAtMost(limit + 8)
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
        val udpInfo = if (udpgw != null && udpgw.localRelayPort > 0) " udpRelay=${udpgw.localRelayPort}" else " udp=tcp-fallback"
        LogStore.append("SOCKS limit=$limit opens<=$MAX_OPENS_IN_FLIGHT$udpInfo")
        pool.execute {
            while (running) {
                try {
                    val c = s.accept()
                    c.tcpNoDelay = true
                    // Wait a few seconds for a free slot (helps speed test under load)
                    if (!gate.tryAcquire(4, TimeUnit.SECONDS)) {
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
        try { server?.close() } catch (_: Exception) {}
        pool.shutdownNow()
        try { pool.awaitTermination(2, TimeUnit.SECONDS) } catch (_: Exception) {}
    }

    private fun handle(client: Socket) {
        var channel: ChannelDirectTCPIP? = null
        var counted = false
        try {
            client.soTimeout = 20_000 // handshake must be quick
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

            if (cmd == 3) {
                skipAddr(cin, atyp)
                val relay = udpgw?.localRelayPort ?: -1
                if (relay <= 0) {
                    reply(cout, 7)
                    return
                }
                val rp = relay
                cout.write(
                    byteArrayOf(
                        5, 0, 0, 1,
                        127, 0, 0, 1,
                        (rp shr 8).toByte(), (rp and 0xff).toByte(),
                    )
                )
                cout.flush()
                client.soTimeout = 0
                try {
                    while (client.getInputStream().read() >= 0) { /* hold */ }
                } catch (_: Exception) {}
                return
            }

            if (cmd != 1) {
                reply(cout, 7)
                return
            }

            var host: String = readHost(cin, atyp) ?: return
            var port = cin.readUnsignedShort()

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

            // Idle timeout frees slots when a video stream stalls
            client.soTimeout = DATA_IDLE_TIMEOUT_MS

            val rin = ch.inputStream
            val rout = ch.outputStream
            val t = Thread { pump(rin, cout) }.also { it.isDaemon = true; it.start() }
            pump(cin, rout)
            t.join(2_000)
        } catch (_: Exception) {
        } finally {
            if (counted) active.decrementAndGet()
            try { channel?.disconnect() } catch (_: Exception) {}
            try { client.close() } catch (_: Exception) {}
        }
    }

    private fun skipAddr(cin: DataInputStream, atyp: Int) {
        when (atyp) {
            1 -> cin.readFully(ByteArray(4))
            3 -> {
                val n = cin.readUnsignedByte()
                cin.readFully(ByteArray(n))
            }
            4 -> cin.readFully(ByteArray(16))
            else -> {}
        }
        cin.readUnsignedShort()
    }

    private fun readHost(cin: DataInputStream, atyp: Int): String? = when (atyp) {
        1 -> {
            val b = ByteArray(4); cin.readFully(b)
            InetAddress.getByAddress(b).hostAddress
        }
        3 -> {
            val n = cin.readUnsignedByte()
            val b = ByteArray(n); cin.readFully(b)
            String(b, Charsets.US_ASCII)
        }
        4 -> {
            val b = ByteArray(16); cin.readFully(b)
            InetAddress.getByAddress(b).hostAddress
        }
        else -> null
    }

    private fun rewriteDestination(host: String, port: Int): Pair<String, Int> {
        if (host.startsWith("198.18.") && (port == 53 || port == 853 || port == 443 || port == 784 || port == 8853)) {
            return PUBLIC_DNS to if (port == 443 || port == 53) port else 853
        }
        return host to port
    }

    private fun shouldReject(host: String): Boolean {
        if (host == "127.0.0.1" || host == "0.0.0.0" || host == "::1") return true
        if (host.startsWith("198.18.")) return true
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
                    try { Thread.sleep(60L * (attempt + 1)) } catch (_: InterruptedException) { return null }
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
        val buf = ByteArray(64 * 1024)
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
