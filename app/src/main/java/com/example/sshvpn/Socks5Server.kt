package com.example.sshvpn

import com.jcraft.jsch.ChannelDirectTCPIP
import com.jcraft.jsch.Session
import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * SOCKS5 on 127.0.0.1 — CONNECT via SSH direct-tcpip.
 * Higher parallel opens (16) to approach commercial clients like Npv on same panel.
 */
class Socks5Server(
    private val sessionProvider: () -> Session?,
    maxChannels: Int = 48,
    private val udpgw: UdpgwClient? = null,
) {

    companion object {
        private const val FAIL_LOG_INTERVAL_MS = 8_000L
        private const val MAX_OPENS_IN_FLIGHT = 10
        private const val DATA_IDLE_TIMEOUT_MS = 180_000
        private const val BUF = 64 * 1024
        private const val HOST_BACKOFF_MS = 20_000L
    }

    private val limit = maxChannels.coerceIn(8, 64)
    private val poolSize = (limit + 16).coerceIn(24, 64)
    private val pool = Executors.newFixedThreadPool(poolSize)
    private val gate = Semaphore(limit)
    private val openGate = Semaphore(MAX_OPENS_IN_FLIGHT)
    private val openLock = Any()
    private var server: ServerSocket? = null
    private val active = AtomicInteger(0)
    private val connectionIds = AtomicInteger(0)
    private val failCount = AtomicInteger(0)
    private val okCount = AtomicInteger(0)
    private val hostFailUntil = ConcurrentHashMap<String, Long>()
    @Volatile private var lastFailLog = 0L

    @Volatile
    private var running = false

    val port: Int get() = server?.localPort ?: -1

    fun start() {
        val s = ServerSocket(0, 512, InetAddress.getByName("127.0.0.1"))
        server = s
        running = true
        LogStore.append("SOCKS limit=$limit opens<=$MAX_OPENS_IN_FLIGHT (stable mode)")
        pool.execute {
            while (running) {
                try {
                    val c = s.accept()
                    c.tcpNoDelay = true
                    if (!gate.tryAcquire(3, TimeUnit.SECONDS)) {
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
                } catch (_: Exception) {
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
        hostFailUntil.clear()
    }

    private fun handle(client: Socket) {
        var channel: ChannelDirectTCPIP? = null
        var counted = false
        try {
            client.soTimeout = 15_000
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
                reply(cout, 7)
                return
            }

            if (cmd != 1) {
                reply(cout, 7)
                return
            }

            var host: String = readHost(cin, atyp) ?: return
            var port = cin.readUnsignedShort()

            if (shouldReject(host)) {
                reply(cout, 5)
                return
            }

            val until = hostFailUntil[host]
            if (until != null && System.currentTimeMillis() < until) {
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
                hostFailUntil[host] = System.currentTimeMillis() + HOST_BACKOFF_MS
                logFail(host, port, "channel is not opened")
                reply(cout, 5)
                return
            }
            channel = ch
            hostFailUntil.remove(host)
            val connectionId = connectionIds.incrementAndGet()
            val uplinkBytes = AtomicLong(0)
            val downlinkBytes = AtomicLong(0)
            val startedAt = System.currentTimeMillis()
            LogStore.append("SOCKS[$connectionId] channel connected $host:$port")

            reply(cout, 0)
            counted = true
            active.incrementAndGet()
            val n = okCount.incrementAndGet()
            if (n <= 3 || n % 100 == 0) {
                LogStore.append("SOCKS ok $host:$port active=${active.get()}/$limit")
            }

            client.soTimeout = DATA_IDLE_TIMEOUT_MS

            val rin = ch.inputStream
            val rout = ch.outputStream
            val t = Thread {
                pump(rin, cout, TunnelStats.downlink, downlinkBytes, "SOCKS[$connectionId] SSH->client")
            }.also { it.isDaemon = true; it.start() }
            pump(cin, rout, TunnelStats.uplink, uplinkBytes, "SOCKS[$connectionId] client->SSH")
            t.join(2_000)
            LogStore.append("SOCKS[$connectionId] finished ${host}:$port up=${uplinkBytes.get()}B down=${downlinkBytes.get()}B age=${System.currentTimeMillis() - startedAt}ms")
        } catch (e: Exception) {
            LogStore.append("SOCKS handler error ${e.javaClass.simpleName}: ${e.message}")
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

    private fun shouldReject(host: String): Boolean {
        if (host == "127.0.0.1" || host == "0.0.0.0" || host == "::1") return true
        return false
    }

    private fun openDirectChannel(session: Session, host: String, port: Int): ChannelDirectTCPIP? {
        if (!openGate.tryAcquire(8, TimeUnit.SECONDS)) return null
        try {
            repeat(3) { attempt ->
                if (!session.isConnected) return null
                try {
                    // Only openChannel needs the session lock; connect runs outside.
                    val ch = synchronized(openLock) {
                        val c = session.openChannel("direct-tcpip") as ChannelDirectTCPIP
                        c.setHost(host)
                        c.setPort(port)
                        c.setOrgIPAddress("127.0.0.1")
                        c.setOrgPort(0)
                        c
                    }
                    ch.connect(15_000)
                    if (ch.isConnected) return ch
                    try { ch.disconnect() } catch (_: Exception) {}
                } catch (_: Exception) {
                    try { Thread.sleep(40L * (attempt + 1)) } catch (_: InterruptedException) { return null }
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

    private fun pump(
        src: InputStream,
        dst: OutputStream,
        counter: AtomicLong,
        connectionCounter: AtomicLong,
        label: String,
    ) {
        val buf = ByteArray(BUF)
        try {
            while (true) {
                val n = src.read(buf)
                if (n < 0) {
                    LogStore.append("$label EOF after ${connectionCounter.get()}B")
                    break
                }
                if (n == 0) continue
                dst.write(buf, 0, n)
                // Flush every read: JSch's channel output may buffer small writes.
                // Waiting for 64 KiB can deadlock TLS handshakes, whose ClientHello
                // is usually only a few KiB and waits for the server response.
                dst.flush()
                counter.addAndGet(n.toLong())
                connectionCounter.addAndGet(n.toLong())
            }
        } catch (e: Exception) {
            LogStore.append("$label stopped after ${connectionCounter.get()}B: ${e.javaClass.simpleName}: ${e.message ?: "no message"}")
        }
    }
}
