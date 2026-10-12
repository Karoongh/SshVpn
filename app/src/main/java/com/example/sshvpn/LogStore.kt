package com.example.sshvpn

import android.os.Handler
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** In-memory ring buffer of log lines (max 200). */
object LogStore {
    private const val MAX = 200
    private val lines = ArrayList<String>(MAX)
    private val main = Handler(Looper.getMainLooper())
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Volatile
    var listener: (() -> Unit)? = null

    @Synchronized
    fun append(msg: String) {
        val line = "[${fmt.format(Date())}] $msg"
        if (lines.size >= MAX) lines.removeAt(0)
        lines.add(line)
        main.post { listener?.invoke() }
    }

    @Synchronized
    fun clear() {
        lines.clear()
        main.post { listener?.invoke() }
    }

    @Synchronized
    fun text(): String = lines.joinToString("\n")

    @Synchronized
    fun snapshot(): List<String> = ArrayList(lines)
}
