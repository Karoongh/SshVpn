package com.example.sshvpn

import android.Manifest
import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private companion object {
        const val REQ_VPN = 1
        const val REQ_CONFIG_FILE = 2
        const val REQ_KEY_FILE = 3
        const val REQ_NOTIF = 4
        const val PREFS = "ssh_vpn"
        const val PREF_CONFIG = "config"
    }

    private lateinit var etLink: EditText
    private lateinit var etName: EditText
    private lateinit var etHost: EditText
    private lateinit var etPort: EditText
    private lateinit var etUser: EditText
    private lateinit var etPass: EditText
    private lateinit var etKey: EditText
    private lateinit var etPassphrase: EditText
    private lateinit var tvStatus: TextView
    private lateinit var btnConnect: Button

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        loadSaved()
        VpnState.listener = { render() }
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
            setPadding(dp(16), dp(16), dp(16), dp(24))
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }

        root.addView(TextView(this).apply {
            text = "SSH VPN"
            textSize = 24f
            gravity = Gravity.CENTER
        })

        tvStatus = TextView(this).apply {
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(8))
        }
        root.addView(tvStatus)

        btnConnect = Button(this).apply { setOnClickListener { onConnectClicked() } }
        root.addView(btnConnect)

        root.addView(section("وارد کردن کانفیگ"))
        etLink = edit("لینک یا کانفیگ (ssh://user:pass@host:22 ، host:22@user:pass ، JSON)", multiline = true)
        root.addView(etLink)
        root.addView(row(
            button("از کلیپ‌بورد") { pasteFromClipboard() },
            button("از فایل") { pickFile(REQ_CONFIG_FILE) },
            button("اعمال") { applyLink() },
        ))

        root.addView(section("مشخصات سرور"))
        etName = edit("نام (اختیاری)")
        etHost = edit("آدرس سرور (host)", ltr = true, type = InputType.TYPE_TEXT_VARIATION_URI)
        etPort = edit("پورت", ltr = true, type = InputType.TYPE_CLASS_NUMBER).apply { setText("22") }
        etUser = edit("نام کاربری", ltr = true)
        etPass = edit("رمز عبور", ltr = true, type = InputType.TYPE_TEXT_VARIATION_PASSWORD)
        listOf(etName, etHost, etPort, etUser, etPass).forEach { root.addView(it) }

        root.addView(section("کلید خصوصی (اختیاری)"))
        etKey = edit("متن کلید خصوصی (PEM / OpenSSH)", ltr = true, multiline = true)
        etPassphrase = edit("رمز کلید (اگر دارد)", ltr = true, type = InputType.TYPE_TEXT_VARIATION_PASSWORD)
        root.addView(etKey)
        root.addView(button("انتخاب فایل کلید") { pickFile(REQ_KEY_FILE) })
        root.addView(etPassphrase)

        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun section(title: String) = TextView(this).apply {
        text = title
        textSize = 16f
        setPadding(0, dp(18), 0, dp(4))
    }

    private fun edit(
        hintText: String,
        ltr: Boolean = false,
        multiline: Boolean = false,
        type: Int = InputType.TYPE_CLASS_TEXT,
    ) = EditText(this).apply {
        hint = hintText
        inputType = if (multiline) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE else type
        if (multiline) {
            minLines = 3
            gravity = Gravity.TOP or Gravity.START
        }
        if (ltr) {
            textDirection = View.TEXT_DIRECTION_LTR
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        }
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        setOnClickListener { onClick() }
    }

    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEach { v ->
            addView(v, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    private fun render() {
        val st = VpnState.status
        val label = when (st) {
            Status.DISCONNECTED -> "قطع"
            Status.CONNECTING -> "در حال اتصال..."
            Status.CONNECTED -> "متصل"
            Status.ERROR -> "خطا"
        }
        tvStatus.text = if (VpnState.message.isBlank()) label else "$label\n${VpnState.message}"
        btnConnect.text = if (st == Status.CONNECTED || st == Status.CONNECTING) "قطع اتصال" else "اتصال"
    }

    // ---------------------------------------------------------------- config handling

    private fun applyLink() {
        val cfg = ConfigParser.parse(etLink.text.toString())
        if (cfg == null) {
            toast("کانفیگ قابل تشخیص نیست")
            return
        }
        fill(cfg)
        toast("کانفیگ اعمال شد")
    }

    private fun fill(c: SshConfig) {
        etName.setText(c.name)
        etHost.setText(c.host)
        etPort.setText(c.port.toString())
        etUser.setText(c.username)
        etPass.setText(c.password)
        if (c.privateKey.isNotBlank()) etKey.setText(c.privateKey)
        if (c.passphrase.isNotBlank()) etPassphrase.setText(c.passphrase)
    }

    private fun readFields(): SshConfig? {
        val host = etHost.text.toString().trim()
        val user = etUser.text.toString().trim()
        val port = etPort.text.toString().trim().toIntOrNull() ?: 22
        if (host.isEmpty() || user.isEmpty() || port !in 1..65535) return null
        val pass = etPass.text.toString()
        val key = etKey.text.toString()
        if (pass.isEmpty() && key.isBlank()) return null
        return SshConfig(
            host = host,
            port = port,
            username = user,
            password = pass,
            privateKey = key,
            passphrase = etPassphrase.text.toString(),
            name = etName.text.toString().trim(),
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
            toast("کلیپ‌بورد خالی است")
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

    // ---------------------------------------------------------------- connect / disconnect

    private fun onConnectClicked() {
        val st = VpnState.status
        if (st == Status.CONNECTED || st == Status.CONNECTING) {
            startService(Intent(this, SshVpnService::class.java).setAction(SshVpnService.ACTION_STOP))
            return
        }

        // If a link is pasted but not applied yet, apply it first.
        if (etHost.text.isBlank() && etLink.text.isNotBlank()) applyLink()

        val cfg = readFields()
        if (cfg == null) {
            toast("آدرس، پورت، نام کاربری و رمز (یا کلید) را کامل کنید")
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
                toast("اجازه VPN داده نشد")
            }
            REQ_CONFIG_FILE -> if (resultCode == RESULT_OK) {
                data?.data?.let { uri ->
                    val text = readUri(uri)
                    if (text == null) toast("خواندن فایل ناموفق بود") else {
                        etLink.setText(text)
                        applyLink()
                    }
                }
            }
            REQ_KEY_FILE -> if (resultCode == RESULT_OK) {
                data?.data?.let { uri ->
                    val text = readUri(uri)
                    if (text == null) toast("خواندن فایل ناموفق بود") else etKey.setText(text)
                }
            }
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
