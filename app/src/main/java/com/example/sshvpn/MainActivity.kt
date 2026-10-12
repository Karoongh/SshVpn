package com.example.sshvpn

import android.Manifest
import android.app.Activity
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
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private companion object {
        const val REQ_VPN = 1
        const val REQ_CONFIG_FILE = 2
        const val REQ_KEY_FILE = 3
        const val REQ_NOTIF = 4
        const val PREFS = "karoon_vpn"
        const val PREF_CONFIG = "config"

        // Colors
        val BG = Color.parseColor("#0F172A")
        val CARD = Color.parseColor("#1E293B")
        val ACCENT = Color.parseColor("#3B82F6")
        val ACCENT_DARK = Color.parseColor("#2563EB")
        val TEXT = Color.parseColor("#F1F5F9")
        val MUTED = Color.parseColor("#94A3B8")
        val SUCCESS = Color.parseColor("#22C55E")
        val ERROR = Color.parseColor("#EF4444")
        val WARNING = Color.parseColor("#F59E0B")
        val INPUT_BG = Color.parseColor("#334155")
    }

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
    private lateinit var btnConnect: Button

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG
        buildUi()
        loadSaved()
        VpnState.listener = { runOnUiThread { render() } }
        render()
    }

    override fun onDestroy() {
        VpnState.listener = null
        super.onDestroy()
    }

    // ---------------------------------------------------------------- UI

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setPadding(dp(20), dp(24), dp(20), dp(32))
        }

        // Header
        root.addView(TextView(this).apply {
            text = "Karoon Tunnel"
            textSize = 28f
            setTextColor(TEXT)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        })
        root.addView(TextView(this).apply {
            text = "Secure SSH VPN"
            textSize = 13f
            setTextColor(MUTED)
            gravity = Gravity.CENTER
            setPadding(0, dp(2), 0, dp(16))
        })

        // Status card
        val statusCard = card()
        tvStatus = TextView(this).apply {
            textSize = 15f
            setTextColor(TEXT)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(14), dp(12), dp(14))
        }
        statusCard.addView(tvStatus)
        root.addView(statusCard)

        // Connect button
        btnConnect = Button(this).apply {
            textSize = 16f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            background = rounded(ACCENT, 14)
            setPadding(0, dp(16), 0, dp(16))
            setOnClickListener { onConnectClicked() }
        }
        val btnLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(12); bottomMargin = dp(8) }
        root.addView(btnConnect, btnLp)

        // Import section
        root.addView(section("Import Config"))
        etLink = field("Paste link or config (ssh://user:pass@host:port)", multiline = true)
        root.addView(etLink)
        root.addView(row(
            smallBtn("Clipboard") { pasteFromClipboard() },
            smallBtn("File") { pickFile(REQ_CONFIG_FILE) },
            smallBtn("Apply") { applyLink() },
        ))

        // Server section
        root.addView(section("Server"))
        etName = field("Profile name (optional)")
        etHost = field("Server address (host)", ltr = true, type = InputType.TYPE_TEXT_VARIATION_URI)
        etPort = field("SSH Port", ltr = true, type = InputType.TYPE_CLASS_NUMBER).apply { setText("22") }
        etUser = field("Username", ltr = true)
        etPass = field("Password", ltr = true, type = InputType.TYPE_TEXT_VARIATION_PASSWORD)
        listOf(etName, etHost, etPort, etUser, etPass).forEach { root.addView(it) }

        // Advanced section
        root.addView(section("Advanced"))
        etUdpgw = field("UDPGW Port", ltr = true, type = InputType.TYPE_CLASS_NUMBER).apply { setText("7300") }
        root.addView(etUdpgw)

        // Protocol spinner
        root.addView(label("Protocol"))
        spProtocol = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("SSH-Direct", "SSH-SSL", "SSH-WS", "SSH-Payload")
            )
            setBackgroundColor(INPUT_BG)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        root.addView(spProtocol)

        cbDns = CheckBox(this).apply {
            text = "Transparent DNS"
            setTextColor(TEXT)
            isChecked = true
            setPadding(0, dp(8), 0, dp(4))
        }
        root.addView(cbDns)

        // Private key section
        root.addView(section("Private Key (optional)"))
        etKey = field("Private key (PEM / OpenSSH)", ltr = true, multiline = true)
        etPassphrase = field("Key passphrase (if any)", ltr = true, type = InputType.TYPE_TEXT_VARIATION_PASSWORD)
        root.addView(etKey)
        root.addView(smallBtn("Select Key File") { pickFile(REQ_KEY_FILE) })
        root.addView(etPassphrase)

        // Footer
        root.addView(TextView(this).apply {
            text = "Karoon Tunnel  ·  v1.0"
            textSize = 11f
            setTextColor(MUTED)
            gravity = Gravity.CENTER
            setPadding(0, dp(24), 0, 0)
        })

        setContentView(ScrollView(this).apply {
            setBackgroundColor(BG)
            addView(root)
        })
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(CARD, 16)
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        layoutParams = lp
    }

    private fun section(title: String) = TextView(this).apply {
        text = title
        textSize = 14f
        setTextColor(ACCENT)
        typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(20), 0, dp(8))
    }

    private fun label(title: String) = TextView(this).apply {
        text = title
        textSize = 12f
        setTextColor(MUTED)
        setPadding(0, dp(8), 0, dp(4))
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
        background = rounded(INPUT_BG, 12)
        setPadding(dp(14), dp(14), dp(14), dp(14))
        inputType = if (multiline) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE else type
        if (multiline) {
            minLines = 3
            gravity = Gravity.TOP or Gravity.START
        }
        if (ltr) {
            textDirection = View.TEXT_DIRECTION_LTR
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(8) }
        layoutParams = lp
    }

    private fun smallBtn(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        textSize = 12f
        setTextColor(TEXT)
        background = rounded(INPUT_BG, 10)
        setOnClickListener { onClick() }
    }

    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEach { v ->
            addView(v, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(6)
            })
        }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(4) }
        layoutParams = lp
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
            if (st == Status.CONNECTED || st == Status.CONNECTING) ERROR else ACCENT, 14
        )
    }

    // ---------------------------------------------------------------- config

    private fun applyLink() {
        val cfg = ConfigParser.parse(etLink.text.toString())
        if (cfg == null) {
            toast("Could not parse config")
            return
        }
        fill(cfg)
        toast("Config applied")
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
        val idx = protocols.indexOf(c.protocol).takeIf { it >= 0 } ?: 0
        spProtocol.setSelection(idx)
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

    private fun loadSaved() {
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(PREF_CONFIG, null)
            ?.let { SshConfig.fromJson(it) }
            ?.let { fill(it) }
    }

    private fun save(cfg: SshConfig) {
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(PREF_CONFIG, cfg.toJson()).apply()
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
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(i, code)
    }

    private fun readUri(uri: Uri): String? = try {
        contentResolver.openInputStream(uri)?.use { it.readBytes().take(256 * 1024).toByteArray().toString(Charsets.UTF_8) }
    } catch (e: Exception) {
        null
    }

    // ---------------------------------------------------------------- connect

    private fun onConnectClicked() {
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
        save(cfg)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
        }

        val prepare = VpnService.prepare(this)
        if (prepare != null) {
            startActivityForResult(prepare, REQ_VPN)
        } else {
            startTunnel(cfg)
        }
    }

    private fun startTunnel(cfg: SshConfig) {
        val i = Intent(this, SshVpnService::class.java)
            .setAction(SshVpnService.ACTION_START)
            .putExtra(SshVpnService.EXTRA_CONFIG, cfg.toJson())
        startForegroundService(i)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_VPN -> if (resultCode == RESULT_OK) {
                readFields()?.let { startTunnel(it) }
            } else {
                toast("VPN permission denied")
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
