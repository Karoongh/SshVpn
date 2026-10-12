package com.example.sshvpn

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private companion object {
        const val REQ_VPN = 1
        const val REQ_CONFIG_FILE = 2
        const val REQ_KEY_FILE = 3
        const val REQ_NOTIF = 4

        val BG = Color.parseColor("#0F172A")
        val CARD = Color.parseColor("#1E293B")
        val ACCENT = Color.parseColor("#3B82F6")
        val TEXT = Color.parseColor("#F1F5F9")
        val MUTED = Color.parseColor("#94A3B8")
        val SUCCESS = Color.parseColor("#22C55E")
        val ERROR = Color.parseColor("#EF4444")
        val WARNING = Color.parseColor("#F59E0B")
        val INPUT_BG = Color.parseColor("#334155")
    }

    private lateinit var profiles: ProfileStore
    private var editingId: String? = null

    private lateinit var spProfile: Spinner
    private lateinit var etLink: EditText
    private lateinit var etName: EditText
    private lateinit var etHost: EditText
    private lateinit var etPort: EditText
    private lateinit var etUser: EditText
    private lateinit var etPass: EditText
    private lateinit var etKey: EditText
    private lateinit var etPassphrase: EditText
    private lateinit var etUdpgw: EditText
    private lateinit var cbDns: CheckBox
    private lateinit var spProtocol: Spinner
    private lateinit var tvStatus: TextView
    private lateinit var tvLogs: TextView
    private lateinit var btnConnect: Button
    private lateinit var logsPanel: LinearLayout
    private lateinit var homePanel: LinearLayout

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG
        profiles = ProfileStore(this)
        buildUi()
        refreshProfileSpinner()
        profiles.getActive()?.let {
            fill(it.config)
            editingId = it.id
        }
        VpnState.listener = { runOnUiThread { render() } }
        LogStore.listener = {
            runOnUiThread {
                if (::tvLogs.isInitialized) {
                    tvLogs.text = LogStore.text().ifBlank { "No logs yet." }
                }
            }
        }
        render()
        if (::tvLogs.isInitialized) {
            tvLogs.text = LogStore.text().ifBlank { "No logs yet." }
        }
    }

    override fun onDestroy() {
        VpnState.listener = null
        LogStore.listener = null
        super.onDestroy()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setPadding(dp(16), dp(20), dp(16), dp(28))
        }

        root.addView(TextView(this).apply {
            text = "Karoon Tunnel"
            textSize = 26f
            setTextColor(TEXT)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        })
        root.addView(TextView(this).apply {
            text = "Secure SSH VPN"
            textSize = 12f
            setTextColor(MUTED)
            gravity = Gravity.CENTER
            setPadding(0, dp(2), 0, dp(12))
        })

        val statusCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(CARD, 14)
        }
        tvStatus = TextView(this).apply {
            textSize = 14f
            setTextColor(TEXT)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        statusCard.addView(tvStatus)
        root.addView(statusCard)

        btnConnect = Button(this).apply {
            textSize = 15f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            background = rounded(ACCENT, 12)
            setPadding(0, dp(14), 0, dp(14))
            setOnClickListener { onConnectClicked() }
        }
        root.addView(btnConnect, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10); bottomMargin = dp(6) })

        root.addView(row(
            smallBtn("Save Profile") { saveCurrentProfile() },
            smallBtn("Delete") { deleteCurrentProfile() },
            smallBtn("Logs") { showLogs(true) },
        ))

        homePanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        logsPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }

        homePanel.addView(section("Profiles"))
        spProfile = Spinner(this).apply {
            setBackgroundColor(INPUT_BG)
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        homePanel.addView(spProfile)
        spProfile.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                val list = profiles.list()
                if (position in list.indices) {
                    val p = list[position]
                    editingId = p.id
                    profiles.setActiveId(p.id)
                    fill(p.config)
                }
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        homePanel.addView(section("Import Config"))
        etLink = field("Paste link or config (ssh://user:pass@host:port)", multiline = true)
        homePanel.addView(etLink)
        homePanel.addView(row(
            smallBtn("Clipboard") { pasteFromClipboard() },
            smallBtn("File") { pickFile(REQ_CONFIG_FILE) },
            smallBtn("Apply") { applyLink() },
        ))

        homePanel.addView(section("Server"))
        etName = field("Profile name")
        etHost = field("Server address", ltr = true, type = InputType.TYPE_TEXT_VARIATION_URI)
        etPort = field("SSH Port", ltr = true, type = InputType.TYPE_CLASS_NUMBER).apply { setText("22") }
        etUser = field("Username", ltr = true)
        etPass = field("Password", ltr = true, type = InputType.TYPE_TEXT_VARIATION_PASSWORD)
        listOf(etName, etHost, etPort, etUser, etPass).forEach { homePanel.addView(it) }

        homePanel.addView(section("Advanced"))
        etUdpgw = field("UDPGW Port", ltr = true, type = InputType.TYPE_CLASS_NUMBER).apply { setText("7300") }
        homePanel.addView(etUdpgw)
        homePanel.addView(label("Protocol"))
        spProtocol = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("SSH-Direct", "SSH-SSL", "SSH-WS", "SSH-Payload")
            )
            setBackgroundColor(INPUT_BG)
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        homePanel.addView(spProtocol)
        cbDns = CheckBox(this).apply {
            text = "Transparent DNS"
            setTextColor(TEXT)
            isChecked = true
        }
        homePanel.addView(cbDns)

        homePanel.addView(section("Private Key (optional)"))
        etKey = field("Private key (PEM / OpenSSH)", ltr = true, multiline = true)
        etPassphrase = field("Key passphrase", ltr = true, type = InputType.TYPE_TEXT_VARIATION_PASSWORD)
        homePanel.addView(etKey)
        homePanel.addView(smallBtn("Select Key File") { pickFile(REQ_KEY_FILE) })
        homePanel.addView(etPassphrase)

        logsPanel.addView(section("Connection Logs"))
        tvLogs = TextView(this).apply {
            textSize = 12f
            setTextColor(TEXT)
            setTextIsSelectable(true)
            typeface = Typeface.MONOSPACE
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = rounded(CARD, 12)
            minHeight = dp(280)
        }
        logsPanel.addView(tvLogs)
        logsPanel.addView(row(
            smallBtn("Copy All") { copyLogs() },
            smallBtn("Clear") { LogStore.clear(); toast("Logs cleared") },
            smallBtn("Back") { showLogs(false) },
        ))

        root.addView(homePanel)
        root.addView(logsPanel)

        root.addView(TextView(this).apply {
            text = "Karoon Tunnel  ·  v1.1"
            textSize = 11f
            setTextColor(MUTED)
            gravity = Gravity.CENTER
            setPadding(0, dp(20), 0, 0)
        })

        setContentView(ScrollView(this).apply {
            setBackgroundColor(BG)
            addView(root)
        })
    }

    private fun showLogs(show: Boolean) {
        homePanel.visibility = if (show) View.GONE else View.VISIBLE
        logsPanel.visibility = if (show) View.VISIBLE else View.GONE
        if (show) tvLogs.text = LogStore.text().ifBlank { "No logs yet." }
    }

    private fun copyLogs() {
        val t = LogStore.text()
        if (t.isBlank()) {
            toast("No logs to copy")
            return
        }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Karoon Tunnel Logs", t))
        toast("Logs copied")
    }

    private fun refreshProfileSpinner() {
        val list = profiles.list()
        val names = if (list.isEmpty()) listOf("(no saved profiles)") else list.map { it.displayName }
        spProfile.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
        val activeId = profiles.getActiveId()
        val idx = list.indexOfFirst { it.id == activeId }.takeIf { it >= 0 } ?: 0
        if (list.isNotEmpty()) spProfile.setSelection(idx)
    }

    private fun saveCurrentProfile() {
        val cfg = readFields() ?: run {
            toast("Fill host, port, user and password/key first")
            return
        }
        val id = editingId ?: ProfileStore.newId()
        profiles.save(Profile(id, cfg))
        editingId = id
        refreshProfileSpinner()
        toast("Profile saved")
        LogStore.append("Profile saved: ${cfg.name.ifBlank { cfg.host }}")
    }

    private fun deleteCurrentProfile() {
        val id = editingId ?: run {
            toast("No profile selected")
            return
        }
        profiles.delete(id)
        editingId = profiles.getActive()?.id
        profiles.getActive()?.let { fill(it.config) } ?: clearFields()
        refreshProfileSpinner()
        toast("Profile deleted")
        LogStore.append("Profile deleted")
    }

    private fun clearFields() {
        etName.setText("")
        etHost.setText("")
        etPort.setText("22")
        etUser.setText("")
        etPass.setText("")
        etKey.setText("")
        etPassphrase.setText("")
        etUdpgw.setText("7300")
        cbDns.isChecked = true
        spProtocol.setSelection(0)
    }

    private fun section(title: String) = TextView(this).apply {
        text = title
        textSize = 13f
        setTextColor(ACCENT)
        typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(16), 0, dp(6))
    }

    private fun label(title: String) = TextView(this).apply {
        text = title
        textSize = 12f
        setTextColor(MUTED)
        setPadding(0, dp(6), 0, dp(2))
    }

    private fun field(
        hintText: String,
        ltr: Boolean = false,
        multiline: Boolean = false,
        type: Int = InputType.TYPE_CLASS_TEXT,
    ) = EditText(this).apply {
        hint = hintText
        setHintTextColor(MUTED)
        setTextColor(TEXT)
        textSize = 14f
        background = rounded(INPUT_BG, 10)
        setPadding(dp(12), dp(12), dp(12), dp(12))
        inputType = if (multiline) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE else type
        if (multiline) {
            minLines = 2
            gravity = Gravity.TOP or Gravity.START
        }
        if (ltr) {
            textDirection = View.TEXT_DIRECTION_LTR
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(6) }
    }

    private fun smallBtn(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        textSize = 11f
        setTextColor(TEXT)
        background = rounded(INPUT_BG, 10)
        setOnClickListener { onClick() }
    }

    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEach { v ->
            addView(v, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(4)
            })
        }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(4); topMargin = dp(4) }
    }

    private fun rounded(color: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun render() {
        val st = VpnState.status
        val (label, color) = when (st) {
            Status.DISCONNECTED -> "Disconnected" to MUTED
            Status.CONNECTING -> "Connecting..." to WARNING
            Status.CONNECTED -> "Connected" to SUCCESS
            Status.ERROR -> "Error" to ERROR
        }
        tvStatus.setTextColor(color)
        tvStatus.text = if (VpnState.message.isBlank()) label else "$label\n${VpnState.message}"
        btnConnect.text = if (st == Status.CONNECTED || st == Status.CONNECTING) "Disconnect" else "Connect"
        btnConnect.background = rounded(
            if (st == Status.CONNECTED || st == Status.CONNECTING) ERROR else ACCENT, 12
        )
    }

    private fun applyLink() {
        val cfg = ConfigParser.parse(etLink.text.toString())
        if (cfg == null) {
            toast("Could not parse config")
            LogStore.append("Import failed: could not parse config")
            return
        }
        fill(cfg)
        toast("Config applied")
        LogStore.append("Config imported for ${cfg.host}:${cfg.port}")
    }

    private fun fill(c: SshConfig) {
        etName.setText(c.name)
        etHost.setText(c.host)
        etPort.setText(c.port.toString())
        etUser.setText(c.username)
        etPass.setText(c.password)
        if (c.privateKey.isNotBlank()) etKey.setText(c.privateKey)
        if (c.passphrase.isNotBlank()) etPassphrase.setText(c.passphrase)
        etUdpgw.setText(c.udpgwPort.toString())
        cbDns.isChecked = c.transparentDns
        val protocols = listOf("SSH-Direct", "SSH-SSL", "SSH-WS", "SSH-Payload")
        spProtocol.setSelection(protocols.indexOf(c.protocol).takeIf { it >= 0 } ?: 0)
    }

    private fun readFields(): SshConfig? {
        val host = etHost.text.toString().trim()
        val user = etUser.text.toString().trim()
        val port = etPort.text.toString().trim().toIntOrNull() ?: 22
        if (host.isEmpty() || user.isEmpty() || port !in 1..65535) return null
        val pass = etPass.text.toString()
        val key = etKey.text.toString()
        if (pass.isEmpty() && key.isBlank()) return null
        val udpgw = etUdpgw.text.toString().trim().toIntOrNull()?.takeIf { it in 1..65535 } ?: 7300
        return SshConfig(
            host = host,
            port = port,
            username = user,
            password = pass,
            privateKey = key,
            passphrase = etPassphrase.text.toString(),
            name = etName.text.toString().trim(),
            udpgwPort = udpgw,
            transparentDns = cbDns.isChecked,
            protocol = spProtocol.selectedItem?.toString() ?: "SSH-Direct",
        )
    }

    private fun pasteFromClipboard() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        if (text.isNullOrBlank()) {
            toast("Clipboard is empty")
            return
        }
        etLink.setText(text)
        applyLink()
    }

    private fun pickFile(code: Int) {
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            },
            code,
        )
    }

    private fun readUri(uri: Uri): String? = try {
        contentResolver.openInputStream(uri)?.use { it.readBytes().take(256 * 1024).toByteArray().toString(Charsets.UTF_8) }
    } catch (_: Exception) {
        null
    }

    private fun onConnectClicked() {
        try {
            val st = VpnState.status
            if (st == Status.CONNECTED || st == Status.CONNECTING) {
                startService(Intent(this, SshVpnService::class.java).setAction(SshVpnService.ACTION_STOP))
                return
            }
            if (etHost.text.isBlank() && etLink.text.isNotBlank()) applyLink()
            val cfg = readFields()
            if (cfg == null) {
                toast("Please fill host, port, username and password (or key)")
                return
            }
            val id = editingId ?: ProfileStore.newId()
            profiles.save(Profile(id, cfg))
            editingId = id
            refreshProfileSpinner()

            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
            }
            val prepare = VpnService.prepare(this)
            if (prepare != null) startActivityForResult(prepare, REQ_VPN) else startTunnel(cfg)
        } catch (e: Exception) {
            LogStore.append("Connect click crashed: ${e.javaClass.simpleName}: ${e.message}")
            toast("Error: ${e.message}")
        }
    }

    private fun startTunnel(cfg: SshConfig) {
        try {
            LogStore.append("Starting tunnel to ${cfg.host}:${cfg.port}")
            startForegroundService(
                Intent(this, SshVpnService::class.java)
                    .setAction(SshVpnService.ACTION_START)
                    .putExtra(SshVpnService.EXTRA_CONFIG, cfg.toJson())
            )
        } catch (e: Exception) {
            LogStore.append("startForegroundService failed: ${e.javaClass.simpleName}: ${e.message}")
            toast("Could not start service: ${e.message}")
            VpnState.update(Status.ERROR, "Could not start service: ${e.message}")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_VPN -> if (resultCode == RESULT_OK) {
                readFields()?.let { startTunnel(it) }
            } else {
                toast("VPN permission denied")
                LogStore.append("VPN permission denied by user")
            }
            REQ_CONFIG_FILE -> if (resultCode == RESULT_OK) {
                data?.data?.let { uri ->
                    val text = readUri(uri)
                    if (text == null) toast("Failed to read file") else {
                        etLink.setText(text)
                        applyLink()
                    }
                }
            }
            REQ_KEY_FILE -> if (resultCode == RESULT_OK) {
                data?.data?.let { uri ->
                    val text = readUri(uri)
                    if (text == null) toast("Failed to read file") else etKey.setText(text)
                }
            }
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
