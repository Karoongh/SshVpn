package com.example.sshvpn

import android.os.Handler
import android.os.Looper

enum class Status { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

/** Tiny in-process state holder shared by the service and the activity. */
object VpnState {
    @Volatile var status: Status = Status.DISCONNECTED
        private set
    @Volatile var message: String = ""
        private set
    /** Local SOCKS5 port while tunnel is up; -1 when down. */
    @Volatile var socksPort: Int = -1

    var listener: (() -> Unit)? = null
    private val main = Handler(Looper.getMainLooper())

    fun update(status: Status, message: String = "") {
        this.status = status
        this.message = message
        if (status != Status.CONNECTED && status != Status.CONNECTING) {
            socksPort = -1
        }
        if (message.isNotBlank()) {
            LogStore.append("[${status.name}] $message")
        } else {
            LogStore.append("[${status.name}]")
        }
        main.post { listener?.invoke() }
    }
}
