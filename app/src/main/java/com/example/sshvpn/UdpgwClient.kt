package com.example.sshvpn

import com.jcraft.jsch.ChannelDirectTCPIP
import com.jcraft.jsch.Session
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Minimal badvpn-udpgw client over an SSH direct-tcpip channel to remote 127.0.0.1:udpgwPort.
 *
 * Wire format on the TCP stream (PacketProto + udpgw):
 *   uint16 LE length | flags(u8) | conid(u16 LE) | ipv4(4 BE) | port(u16 BE) | payload
 */
class UdpgwClient(
    private val sessionProvider: () -> Session?,
    private val remotePort: Int,
) {
    companion object {
        private const val FLAG_KEEPALIVE = 1 shl 0
        private const val FLAG_REBIND = 1 shl 1
        private const val FLAG_DNS = 1 shl 2
    }

    private val nextConId = AtomicInteger(1)
    private val conIds = ConcurrentHashMap<String, Int>()

    @Volatile private var channel: ChannelDirectTCPIP? = null
    @Volatile private var out: OutputStream? = null
    @Volatile private var running = false
    private var reader: Thread? = null

    /** local UDP port that SOCKS UDP ASSOCIATE clients send to */
    var localRelayPort: Int = -1
        private set

    private var relaySocket: DatagramSocket? = null
    private var relayThread: Thread? = null

    // Map conid -> client address that sent the original SOCKS UDP packet
    private val clientByConId = ConcurrentHashMap<Int, Pair<InetAddress, Int>>()

    fun start(): Boolean {
        if (remotePort !in 1..65535) return false
        return try {
            ensureChannel()
            val ds = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
            relaySocket = ds
            localRelayPort = ds.localPort
            running = true
            reader = Thread({ readLoop() }, "udpgw-read").also { it.isDaemon = true; it.start() }
            relayThread = Thread({ relayLoop(ds) }, "udpgw-relay").also { it.isDaemon = true; it.start() }
            LogStore.append("UDPGW ready via SSH 127.0.0.1:$remotePort (local relay=${localRelayPort})")
            true
        } catch (e: Exception) {
            LogStore.append("UDPGW start failed: ${e.message}")
            stop()
            false
        }
    }

    fun stop() {
        running = false
        try { relaySocket?.close() } catch (_: Exception) {}
        relaySocket = null
        try { channel?.disconnect() } catch (_: Exception) {}
        channel = null
        out = null
    }

    private fun ensureChannel() {
        val existing = channel
        if (existing != null && existing.isConnected) return
        val session = sessionProvider() ?: throw IllegalStateException("no SSH session")
        if (!session.isConnected) throw IllegalStateException("SSH not connected")
        val ch = session.openChannel("direct-tcpip") as ChannelDirectTCPIP
        ch.setHost("127.0.0.1")
        ch.setPort(remotePort)
        ch.setOrgIPAddress("127.0.0.1")
        ch.setOrgPort(0)
        ch.connect(15_000)
        channel = ch
        out = ch.outputStream
        // start reader if needed
        if (reader?.isAlive != true) {
            reader = Thread({ readLoop() }, "udpgw-read").also { it.isDaemon = true; it.start() }
        }
    }

    private fun key(ip: InetAddress, port: Int) = "${ip.hostAddress}:$port"

    private fun conIdFor(ip: InetAddress, port: Int, dns: Boolean): Int {
        val k = key(ip, port)
        return conIds.getOrPut(k) { nextConId.getAndIncrement() and 0xFFFF }
    }

    /** Accept a raw UDP payload destined to [dstHost]:[dstPort] from SOCKS client [from]. */
    fun sendUdp(
        dstHost: InetAddress,
        dstPort: Int,
        payload: ByteArray,
        payloadOff: Int,
        payloadLen: Int,
        from: InetAddress,
        fromPort: Int,
    ) {
        try {
            ensureChannel()
            val dns = dstPort == 53
            val conid = conIdFor(dstHost, dstPort, dns)
            clientByConId[conid] = from to fromPort

            val ip = dstHost.address
            if (ip.size != 4) return // IPv4 only for now

            var flags = 0
            if (dns) flags = flags or FLAG_DNS

            // flags(1) + conid(2) + ipv4(4) + port(2) + data
            val bodyLen = 1 + 2 + 4 + 2 + payloadLen
            val packet = ByteBuffer.allocate(2 + bodyLen).order(ByteOrder.LITTLE_ENDIAN)
            packet.putShort(bodyLen.toShort())
            packet.put(flags.toByte())
            packet.putShort(conid.toShort())
            // IP + port in network byte order
            packet.order(ByteOrder.BIG_ENDIAN)
            packet.put(ip)
            packet.putShort(dstPort.toShort())
            packet.put(payload, payloadOff, payloadLen)

            val o = out ?: return
            synchronized(this) {
                o.write(packet.array(), 0, packet.position())
                o.flush()
            }
        } catch (e: Exception) {
            // force reconnect next time
            try { channel?.disconnect() } catch (_: Exception) {}
            channel = null
            out = null
        }
    }

    private fun relayLoop(ds: DatagramSocket) {
        val buf = ByteArray(65535)
        while (running) {
            try {
                val dp = DatagramPacket(buf, buf.size)
                ds.receive(dp)
                // SOCKS5 UDP request header at start of datagram
                parseAndForwardSocksUdp(buf, dp.length, dp.address, dp.port)
            } catch (_: Exception) {
                if (!running) break
            }
        }
    }

    private fun parseAndForwardSocksUdp(buf: ByteArray, len: Int, from: InetAddress, fromPort: Int) {
        // RSV RSV FRAG ATYP ...
        if (len < 7) return
        if (buf[2].toInt() != 0) return // FRAG not supported
        val atyp = buf[3].toInt() and 0xff
        var o = 4
        val dst: InetAddress
        val dstPort: Int
        when (atyp) {
            1 -> {
                if (len < 10) return
                dst = InetAddress.getByAddress(buf.copyOfRange(4, 8))
                dstPort = ((buf[8].toInt() and 0xff) shl 8) or (buf[9].toInt() and 0xff)
                o = 10
            }
            3 -> {
                val n = buf[4].toInt() and 0xff
                if (len < 5 + n + 2) return
                val host = String(buf, 5, n, Charsets.US_ASCII)
                dst = InetAddress.getByName(host)
                val p = 5 + n
                dstPort = ((buf[p].toInt() and 0xff) shl 8) or (buf[p + 1].toInt() and 0xff)
                o = p + 2
            }
            else -> return
        }
        val dataLen = len - o
        if (dataLen < 0) return
        sendUdp(dst, dstPort, buf, o, dataLen, from, fromPort)
    }

    private fun readLoop() {
        val ch = channel ?: return
        val input: InputStream = try { ch.inputStream } catch (_: Exception) { return }
        val header = ByteArray(2)
        while (running) {
            try {
                if (!readFully(input, header, 2)) break
                val bodyLen = (header[0].toInt() and 0xff) or ((header[1].toInt() and 0xff) shl 8)
                if (bodyLen < 1 + 2 + 4 + 2 || bodyLen > 65535) continue
                val body = ByteArray(bodyLen)
                if (!readFully(input, body, bodyLen)) break

                val bb = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN)
                val flags = bb.get().toInt() and 0xff
                if (flags and FLAG_KEEPALIVE != 0) continue
                val conid = bb.short.toInt() and 0xFFFF
                bb.order(ByteOrder.BIG_ENDIAN)
                val ipBytes = ByteArray(4)
                bb.get(ipBytes)
                val port = bb.short.toInt() and 0xFFFF
                val data = ByteArray(bb.remaining())
                bb.get(data)

                val client = clientByConId[conid] ?: continue
                val socks = buildSocksUdpResponse(ipBytes, port, data)
                val ds = relaySocket ?: continue
                ds.send(DatagramPacket(socks, socks.size, client.first, client.second))
            } catch (_: Exception) {
                if (!running) break
                try { Thread.sleep(200) } catch (_: Exception) {}
                try { ensureChannel() } catch (_: Exception) {}
            }
        }
    }

    private fun buildSocksUdpResponse(ip: ByteArray, port: Int, data: ByteArray): ByteArray {
        val out = ByteArray(10 + data.size)
        out[0] = 0; out[1] = 0; out[2] = 0 // RSV RSV FRAG
        out[3] = 1 // IPv4
        System.arraycopy(ip, 0, out, 4, 4)
        out[8] = (port shr 8).toByte()
        out[9] = (port and 0xff).toByte()
        System.arraycopy(data, 0, out, 10, data.size)
        return out
    }

    private fun readFully(input: InputStream, buf: ByteArray, len: Int): Boolean {
        var o = 0
        while (o < len) {
            val n = input.read(buf, o, len - o)
            if (n < 0) return false
            o += n
        }
        return true
    }
}
