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

    var listener: (() -> Unit)? = null
    private val main = Handler(Looper.getMainLooper())

    fun update(status: Status, message: String = "") {
        this.status = status
        this.message = message
        if (message.isNotBlank()) {
            LogStore.append("[${status.name}] $message")
        } else {
            LogStore.append("[${status.name}]")
        }
        main.post { listener?.invoke() }
    }
}
