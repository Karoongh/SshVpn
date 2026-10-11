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
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.SocketFactory
import hev.htproxy.TProxyService
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

class SshVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.example.sshvpn.START"
        const val ACTION_STOP = "com.example.sshvpn.STOP"
        const val EXTRA_CONFIG = "config"
        private const val CHANNEL_ID = "sshvpn"
        private const val NOTIF_ID = 1
        private const val MTU = 1500
    }

    private var worker: Thread? = null
    private var session: Session? = null
    private var socks: Socks5Server? = null
    private var tun: ParcelFileDescriptor? = null

    @Volatile
    private var stopping = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                shutdown("قطع شد", Status.DISCONNECTED)
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val cfg = intent.getStringExtra(EXTRA_CONFIG)?.let { SshConfig.fromJson(it) }
                if (cfg == null) {
                    VpnState.update(Status.ERROR, "کانفیگ نامعتبر است")
                    stopSelf()
                    return START_NOT_STICKY
                }
                if (worker?.isAlive == true) return START_NOT_STICKY
                startForegroundCompat("در حال اتصال...")
                stopping = false
                worker = Thread { runTunnel(cfg) }.also { it.start() }
            }
        }
        return START_NOT_STICKY
    }

    private fun runTunnel(cfg: SshConfig) {
        VpnState.update(Status.CONNECTING, "اتصال SSH به ${cfg.host}:${cfg.port} ...")
        try {
            // 1) SSH session
            val s = connectSsh(cfg)
            session = s

            // 2) local SOCKS5 -> SSH direct-tcpip
            val server = Socks5Server { session }
            server.start()
            socks = server

            // 3) TUN interface
            val builder = Builder()
                .setSession(cfg.name.ifBlank { cfg.host })
                .setMtu(MTU)
                .addAddress("198.18.0.1", 32)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("198.18.0.2")
            try {
                builder.addDisallowedApplication(packageName) // our own SSH socket must bypass the VPN
            } catch (_: Exception) {
            }
            val pfd = builder.establish() ?: throw IllegalStateException("دسترسی VPN داده نشد")
            tun = pfd

            // 4) tun2socks
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
                mapdns:
                  address: 198.18.0.2
                  port: 53
                  network: 100.64.0.0
                  netmask: 255.192.0.0
                  cache-size: 10000
                misc:
                  connect-timeout: 15000
                  log-level: warn
                """.trimIndent() + "\n"
            )
            if (!TProxyService.TProxyStartService(conf.absolutePath, pfd.fd)) {
                throw IllegalStateException("اجرای tun2socks ناموفق بود")
            }

            VpnState.update(Status.CONNECTED, "متصل به ${cfg.host}")
            updateNotification("متصل به ${cfg.host}")

            // 5) watchdog: when the SSH session drops, tear everything down
            while (!stopping) {
                Thread.sleep(2000)
                if (session?.isConnected != true) {
                    shutdown("اتصال SSH قطع شد", Status.ERROR)
                    return
                }
            }
        } catch (e: InterruptedException) {
            // shutting down
        } catch (e: Exception) {
            shutdown(describe(e), Status.ERROR)
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
        // NOTE: host keys are not pinned (like most "tunnel" apps). See README for hardening.
        s.setConfig("StrictHostKeyChecking", "no")
        s.setConfig("PreferredAuthentications", "publickey,password,keyboard-interactive")
        s.setServerAliveInterval(15000)
        s.setServerAliveCountMax(3)
        s.setSocketFactory(object : SocketFactory {
            override fun createSocket(host: String, port: Int): Socket {
                val sock = Socket()
                protect(sock)
                sock.tcpNoDelay = true
                sock.connect(InetSocketAddress(host, port), 15000)
                return sock
            }

            override fun getInputStream(socket: Socket): InputStream = socket.getInputStream()
            override fun getOutputStream(socket: Socket): OutputStream = socket.getOutputStream()
        })
        s.connect(20000)
        return s
    }

    private fun describe(e: Exception): String {
        val m = e.message ?: e.javaClass.simpleName
        return when {
            m.contains("Auth fail", true) -> "نام کاربری/رمز یا کلید اشتباه است"
            m.contains("timeout", true) || m.contains("timed out", true) -> "زمان اتصال تمام شد"
            m.contains("refused", true) -> "اتصال رد شد (آدرس یا پورت اشتباه)"
            m.contains("UnknownHost", true) || m.contains("Unable to resolve", true) -> "آدرس سرور پیدا نشد"
            else -> m
        }
    }

    @Synchronized
    private fun shutdown(message: String, status: Status) {
        stopping = true
        try { TProxyService.TProxyStopService() } catch (_: Throwable) {}
        try { socks?.stop() } catch (_: Exception) {}
        socks = null
        try { session?.disconnect() } catch (_: Exception) {}
        session = null
        try { tun?.close() } catch (_: Exception) {}
        tun = null
        VpnState.update(status, message)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onRevoke() {
        shutdown("دسترسی VPN لغو شد", Status.DISCONNECTED)
    }

    override fun onDestroy() {
        if (!stopping) shutdown("قطع شد", Status.DISCONNECTED)
        super.onDestroy()
    }

    private fun startForegroundCompat(text: String) {
        val n = buildNotification(text)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "SSH VPN", NotificationManager.IMPORTANCE_LOW)
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
            .setContentTitle("SSH VPN")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "قطع", stop).build())
            .setOngoing(true)
            .build()
    }
}
