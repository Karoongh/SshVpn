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
 * Forwards through SSH direct-tcpip with a hard concurrency cap to protect
 * the phone (CPU/heat) and the SSH session (MaxSessions / channel limits).
 */
class Socks5Server(private val sessionProvider: () -> Session?) {

    companion object {
        // Keep concurrent SSH channels modest — many phones overheat above this.
        private const val MAX_CONCURRENT = 48
        private const val FAIL_LOG_INTERVAL_MS = 5_000L
    }

    private val pool = Executors.newFixedThreadPool(16)
    private val gate = Semaphore(MAX_CONCURRENT)
    private var server: ServerSocket? = null
    private val active = AtomicInteger(0)
    private val failCount = AtomicInteger(0)
    @Volatile private var lastFailLog = 0L

    @Volatile
    private var running = false

    val port: Int get() = server?.localPort ?: -1

    fun start() {
        val s = ServerSocket(0, 256, InetAddress.getByName("127.0.0.1"))
        server = s
        running = true
        pool.execute {
            while (running) {
                try {
                    val c = s.accept()
                    c.tcpNoDelay = true
                    c.soTimeout = 0
                    // Drop if saturated — better than melting the device
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
            val host: String = when (atyp) {
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
            val port = cin.readUnsignedShort()

            if (cmd != 1) {
                reply(cout, 7)
                return
            }

            val session = sessionProvider()
            if (session == null || !session.isConnected) {
                reply(cout, 1)
                return
            }

            val ch = session.openChannel("direct-tcpip") as ChannelDirectTCPIP
            channel = ch
            ch.setHost(host)
            ch.setPort(port)
            ch.setOrgIPAddress("127.0.0.1")
            ch.setOrgPort(0)

            try {
                ch.connect(15_000)
            } catch (e: Exception) {
                logFail(host, port, e.message)
                reply(cout, 5)
                return
            }

            reply(cout, 0)
            counted = true
            active.incrementAndGet()

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

    private fun logFail(host: String, port: Int, message: String?) {
        val n = failCount.incrementAndGet()
        val now = System.currentTimeMillis()
        // Rate-limit: at most one fail line every few seconds
        if (now - lastFailLog < FAIL_LOG_INTERVAL_MS) return
        lastFailLog = now
        LogStore.append("SOCKS fail $host:$port (${message ?: "?"}) totalFails=$n active=${active.get()}")
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
