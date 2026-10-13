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
import java.util.concurrent.atomic.AtomicInteger

/**
 * Minimal SOCKS5 server (CONNECT only, no auth) bound to 127.0.0.1.
 * Every connection is forwarded through an SSH "direct-tcpip" channel.
 */
class Socks5Server(private val sessionProvider: () -> Session?) {

    private val pool = Executors.newCachedThreadPool()
    private var server: ServerSocket? = null
    private val active = AtomicInteger(0)

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
                    pool.execute { handle(c) }
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
    }

    private fun handle(client: Socket) {
        var channel: ChannelDirectTCPIP? = null
        try {
            val cin = DataInputStream(client.getInputStream())
            val cout = client.getOutputStream()

            // Greeting
            if (cin.readUnsignedByte() != 5) return
            val nMethods = cin.readUnsignedByte()
            cin.readFully(ByteArray(nMethods))
            cout.write(byteArrayOf(5, 0))
            cout.flush()

            // Request
            if (cin.readUnsignedByte() != 5) return
            val cmd = cin.readUnsignedByte()
            cin.readUnsignedByte() // reserved
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

            if (cmd != 1) { // only CONNECT
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
            // Required by some SSH servers for direct-tcpip
            ch.setOrgIPAddress("127.0.0.1")
            ch.setOrgPort(0)

            try {
                ch.connect(20000)
            } catch (e: Exception) {
                LogStore.append("SOCKS fail $host:$port -> ${e.message}")
                reply(cout, 5)
                return
            }

            reply(cout, 0)
            val n = active.incrementAndGet()
            if (n <= 5 || n % 20 == 0) {
                LogStore.append("SOCKS open $host:$port (active=$n)")
            }

            val rin = ch.inputStream
            val rout = ch.outputStream
            val t = Thread { pump(rin, cout) }
            t.isDaemon = true
            t.start()
            pump(cin, rout)
            t.join(3000)
        } catch (_: Exception) {
        } finally {
            active.decrementAndGet()
            try { channel?.disconnect() } catch (_: Exception) {}
            try { client.close() } catch (_: Exception) {}
        }
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
