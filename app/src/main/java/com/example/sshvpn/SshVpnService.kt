package com.example.sshvpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.UIKeyboardInteractive
import com.jcraft.jsch.UserInfo
import hev.htproxy.TProxyService
import java.io.File

class SshVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.example.sshvpn.START"
        const val ACTION_STOP = "com.example.sshvpn.STOP"
        const val EXTRA_CONFIG = "config"
        private const val CHANNEL_ID = "karoon_vpn"
        private const val NOTIF_ID = 1
        // Balanced for SSH overhead without excessive fragmentation
        private const val MTU = 1280
    }

    private var worker: Thread? = null
    private var session: Session? = null
    private var socks: Socks5Server? = null
    private var tun: ParcelFileDescriptor? = null

    @Volatile
    private var stopping = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            when (intent?.action) {
                ACTION_STOP -> {
                    shutdown("Disconnected", Status.DISCONNECTED)
                    return START_NOT_STICKY
                }
                ACTION_START -> {
                    val cfg = intent.getStringExtra(EXTRA_CONFIG)?.let { SshConfig.fromJson(it) }
                    if (cfg == null) {
                        VpnState.update(Status.ERROR, "Invalid config")
                        stopSelf()
                        return START_NOT_STICKY
                    }
                    if (worker?.isAlive == true) return START_NOT_STICKY
                    startForegroundCompat("Connecting...")
                    stopping = false
                    worker = Thread({
                        try {
                            runTunnel(cfg)
                        } catch (t: Throwable) {
                            LogStore.append("Worker crashed: ${t.javaClass.simpleName}: ${t.message}")
                            shutdown("Worker crashed: ${t.message}", Status.ERROR)
                        }
                    }, "ssh-vpn-worker").also { it.start() }
                }
            }
        } catch (t: Throwable) {
            LogStore.append("Service start crashed: ${t.javaClass.simpleName}: ${t.message}")
            VpnState.update(Status.ERROR, "Service error: ${t.message}")
            try { stopSelf() } catch (_: Exception) {}
        }
        return START_NOT_STICKY
    }

    private fun runTunnel(cfg: SshConfig) {
        var stage = "SSH connection"
        try {
            VpnState.update(Status.CONNECTING, "SSH ${cfg.host}:${cfg.port} ...")
            LogStore.append("Connecting SSH (before VPN) to ${cfg.host}:${cfg.port}")
            val s = connectSsh(cfg)
            session = s
            LogStore.append("SSH session established")

            // Cap concurrency for stability on shared SSH panels (max 2 sessions, limited channels)
            val channels = cfg.maxChannels.coerceIn(8, 64)

            stage = "Starting local SOCKS5 proxy"
            VpnState.update(Status.CONNECTING, "Starting local proxy ...")
            // TCP-only SOCKS: stable path. Experimental UDPGW disabled in hot path
            // because a wrong wire format made apps slow/unstable.
            val server = Socks5Server({ session }, channels, udpgw = null)
            server.start()
            socks = server
            LogStore.append("Local SOCKS5 on port ${server.port} (tcp relay, maxChannels=$channels)")

            stage = "Creating Android VPN interface"
            VpnState.update(Status.CONNECTING, "Starting Android VPN ...")
            val builder = Builder()
                .setSession(cfg.name.ifBlank { "Karoon Tunnel" })
                .setMtu(MTU)
                .addAddress("198.18.0.1", 30)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("1.1.1.1")
                .addDnsServer("8.8.8.8")

            try {
                builder.allowFamily(OsConstants.AF_INET)
            } catch (_: Exception) {
            }
            if (Build.VERSION.SDK_INT >= 29) {
                try { builder.setMetered(false) } catch (_: Exception) {}
            }
            try {
                builder.addDisallowedApplication(packageName)
            } catch (_: Exception) {
            }

            val pfd = builder.establish()
                ?: throw IllegalStateException("VPN permission denied or another VPN is active")
            tun = pfd
            LogStore.append("VPN interface established (MTU=$MTU)")

            stage = "Starting traffic tunnel (hev-socks5-tunnel)"
            VpnState.update(Status.CONNECTING, "Starting traffic tunnel ...")
            if (!TProxyService.nativeReady) {
                throw IllegalStateException("Native library hev-socks5-tunnel failed to load on this device")
            }

            // udp:tcp = UDP (DNS etc.) carried as TCP CONNECT through SOCKS — stable over pure SSH.
            val conf = File(filesDir, "tproxy.yml")
            conf.writeText(
                """
                tunnel:
                  mtu: $MTU
                  ipv4: 198.18.0.1
                socks5:
                  port: ${server.port}
                  address: 127.0.0.1
                  udp: 'tcp'
                misc:
                  task-stack-size: 86016
                  connect-timeout: 12000
                  tcp-read-write-timeout: 180000
                  udp-read-write-timeout: 30000
                  log-level: warn
                """.trimIndent() + "\n"
            )
            LogStore.append("tproxy.yml written (udp=tcp); starting hev on fd=${pfd.fd}")
            if (!TProxyService.TProxyStartService(conf.absolutePath, pfd.fd)) {
                throw IllegalStateException("Failed to start hev-socks5-tunnel")
            }
            LogStore.append("Tunnel started successfully")

            VpnState.update(Status.CONNECTED, "Connected to ${cfg.host}")
            updateNotification("Connected to ${cfg.host}")

            while (!stopping) {
                Thread.sleep(1500)
                val sess = session
                if (sess == null || !sess.isConnected) {
                    shutdown("SSH connection lost", Status.ERROR)
                    return
                }
                // Proactively send keepalive traffic
                try {
                    sess.sendKeepAliveMsg()
                } catch (_: Exception) {
                }
            }
        } catch (e: InterruptedException) {
        } catch (t: Throwable) {
            val msg = "$stage failed: ${describeThrowable(t)}"
            LogStore.append(msg)
            shutdown(msg, Status.ERROR)
        }
    }

    private fun connectSsh(cfg: SshConfig): Session {
        val jsch = JSch()
        if (cfg.privateKey.isNotBlank()) {
            jsch.addIdentity(
                "key",
                cfg.privateKey.trim().toByteArray(),
                null,
                if (cfg.passphrase.isEmpty()) null else cfg.passphrase.toByteArray(),
            )
        }
        val s = jsch.getSession(cfg.username, cfg.host, cfg.port)
        if (cfg.password.isNotEmpty()) s.setPassword(cfg.password)

        s.setUserInfo(object : UserInfo, UIKeyboardInteractive {
            override fun getPassphrase(): String = cfg.passphrase
            override fun getPassword(): String = cfg.password
            override fun promptPassword(message: String?): Boolean = cfg.password.isNotEmpty()
            override fun promptPassphrase(message: String?): Boolean = true
            override fun promptYesNo(message: String?): Boolean = true
            override fun showMessage(message: String?) {
                if (!message.isNullOrBlank()) LogStore.append("SSH: $message")
            }

            override fun promptKeyboardInteractive(
                destination: String?,
                name: String?,
                instruction: String?,
                prompt: Array<out String>?,
                echo: BooleanArray?,
            ): Array<String>? {
                LogStore.append(
                    "keyboard-interactive prompt: ${prompt?.joinToString() ?: "(none)"}"
                )
                if (prompt.isNullOrEmpty()) return emptyArray()
                return Array(prompt.size) { cfg.password }
            }
        })

        s.setConfig("StrictHostKeyChecking", "no")
        s.setConfig("PreferredAuthentications", "publickey,keyboard-interactive,password")
        s.setConfig("compression.s2c", "none")
        s.setConfig("compression.c2s", "none")
        s.setConfig("cipher.s2c", "aes128-ctr,aes192-ctr,aes256-ctr,aes128-gcm@openssh.com,aes256-gcm@openssh.com")
        s.setConfig("cipher.c2s", "aes128-ctr,aes192-ctr,aes256-ctr,aes128-gcm@openssh.com,aes256-gcm@openssh.com")
        // Aggressive keepalive so shared panels do not drop the session
        s.setServerAliveInterval(5000)
        s.setServerAliveCountMax(6)
        s.connect(20000)
        return s
    }

    private fun describeThrowable(t: Throwable): String {
        val messages = generateSequence(t) { it.cause }
            .mapNotNull { it.message?.takeIf(String::isNotBlank) }
            .distinct()
            .toList()
        val m = messages.joinToString(" | ").ifBlank { t.javaClass.simpleName }
        return when {
            m.contains("Auth fail", true) || m.contains("authentication failed", true) ->
                "SSH authentication failed; check username, password, or private key. Details: $m"
            m.contains("timeout", true) || m.contains("timed out", true) ->
                "SSH connection timed out; check host, port, and network. Details: $m"
            m.contains("refused", true) ->
                "SSH connection refused; check host and port. Details: $m"
            m.contains("UnknownHost", true) || m.contains("Unable to resolve", true) || m.contains("unresolved", true) ->
                "Server hostname could not be resolved. Details: $m"
            m.contains("UnsatisfiedLink", true) || m.contains("native library", true) ->
                "Native tunnel library failed to load. Details: $m"
            m.contains("another VPN", true) || m.contains("permission denied", true) ->
                "Could not start VPN. Turn off any other active VPN and try again. Details: $m"
            else -> m.take(280)
        }
    }

    @Synchronized
    private fun shutdown(message: String, status: Status) {
        stopping = true
        try {
            if (TProxyService.nativeReady) TProxyService.TProxyStopService()
        } catch (_: Throwable) {}
        try { socks?.stop() } catch (_: Exception) {}
        socks = null
        try { session?.disconnect() } catch (_: Exception) {}
        session = null
        try { tun?.close() } catch (_: Exception) {}
        tun = null
        VpnState.update(status, message)
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
        stopSelf()
    }

    override fun onRevoke() {
        shutdown("VPN permission revoked", Status.DISCONNECTED)
    }

    override fun onDestroy() {
        if (!stopping) shutdown("Disconnected", Status.DISCONNECTED)
        super.onDestroy()
    }

    private fun startForegroundCompat(text: String) {
        val n = buildNotification(text)
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else if (Build.VERSION.SDK_INT >= 29) {
                @Suppress("DEPRECATION")
                startForeground(NOTIF_ID, n, 0)
            } else {
                startForeground(NOTIF_ID, n)
            }
        } catch (t: Throwable) {
            LogStore.append("startForeground typed failed: ${t.message}; retry plain")
            startForeground(NOTIF_ID, n)
        }
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Karoon Tunnel", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, SshVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Karoon Tunnel")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Disconnect", stop).build())
            .setOngoing(true)
            .build()
    }
}
