package com.example.sshvpn

import java.util.concurrent.atomic.AtomicLong

/** Live traffic counters for the Stats tab (bytes through SOCKS). */
object TunnelStats {
    val uplink = AtomicLong(0)
    val downlink = AtomicLong(0)

    fun reset() {
        uplink.set(0)
        downlink.set(0)
    }

    fun formatBytes(n: Long): String {
        val v = n.toDouble()
        return when {
            v < 1024 -> "${n} B"
            v < 1024 * 1024 -> String.format("%.1f KiB", v / 1024.0)
            v < 1024 * 1024 * 1024 -> String.format("%.1f MiB", v / (1024.0 * 1024))
            else -> String.format("%.2f GiB", v / (1024.0 * 1024 * 1024))
        }
    }
}
