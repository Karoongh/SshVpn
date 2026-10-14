package com.example.sshvpn

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.ArrayDeque
import kotlin.math.max

/** Bottom-nav UI: Home | Configs | Stats | Logs | More */
class MainActivity : Activity() {

    private companion object {
        const val REQ_VPN = 1
        const val REQ_NOTIF = 4

        val BG = Color.parseColor("#0B1220")
        val CARD = Color.parseColor("#1A2332")
        val ACCENT = Color.parseColor("#3B82F6")
        val TEXT = Color.parseColor("#F1F5F9")
        val MUTED = Color.parseColor("#94A3B8")
        val SUCCESS = Color.parseColor("#22C55E")
        val ERROR = Color.parseColor("#EF4444")
        val WARNING = Color.parseColor("#F59E0B")
        val INPUT_BG = Color.parseColor("#243044")
        val NAV_BG = Color.parseColor("#111827")
    }

    private lateinit var profiles: ProfileStore
    private var editingId: String? = null

    private lateinit var contentHost: FrameLayout
    private lateinit var tabHome: LinearLayout
    private lateinit var tabConfigs: LinearLayout
    private lateinit var tabSpeed: LinearLayout
    private lateinit var tabLogs: LinearLayout
    private lateinit var tabSettings: LinearLayout

    private lateinit var tvHomeStatus: TextView
    private lateinit var tvActiveProfile: TextView
    private lateinit var btnMain: Button

    private lateinit var configsList: LinearLayout
    private lateinit var tvLogs: TextView
    private lateinit var chartUp: TrafficChartView
    private lateinit var chartDown: TrafficChartView
    private lateinit var tvUplinkTotal: TextView
    private lateinit var tvDownlinkTotal: TextView
    private lateinit var etMaxChannels: EditText

    private val navButtons = ArrayList<TextView>()
    private var currentTab = 0

    private val mainHandler = Handler(Looper.getMainLooper())
    private var speedRunning = false
    private val upSamples = ArrayDeque<Float>(60)
    private val downSamples = ArrayDeque<Float>(60)
    private var lastUpBytes = 0L
    private var lastDownBytes = 0L
    private val speedRunnable = Runnable { tickStats() }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG
        window.navigationBarColor = NAV_BG
        profiles = ProfileStore(this)
        editingId = profiles.getActiveId()
        buildUi()
        VpnState.listener = { runOnUiThread { renderHome(); if (currentTab == 3) refreshLogs() } }
        LogStore.listener = { runOnUiThread { if (currentTab == 3) refreshLogs() } }
        renderHome()
        showTab(0)
    }

    override fun onDestroy() {
        stopSpeedMonitor()
        VpnState.listener = null
        LogStore.listener = null
        super.onDestroy()
    }

    override fun onPause() {
        stopSpeedMonitor()
        super.onPause()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
        }
        contentHost = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        tabHome = buildHomeTab()
        tabConfigs = buildConfigsTab()
        tabSpeed = buildSpeedTab()
        tabLogs = buildLogsTab()
        tabSettings = buildSettingsTab()
        listOf(tabHome, tabConfigs, tabSpeed, tabLogs, tabSettings).forEach {
            it.visibility = View.GONE
            contentHost.addView(it, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(NAV_BG)
            setPadding(0, dp(6), 0, dp(10))
            elevation = 12f
        }
        listOf("Home", "Configs", "Stats", "Logs", "More").forEachIndexed { i, label ->
            val tv = TextView(this).apply {
                text = label
                textSize = 11f
                gravity = Gravity.CENTER
                setTextColor(MUTED)
                setPadding(0, dp(8), 0, dp(8))
                setOnClickListener { showTab(i) }
            }
            navButtons.add(tv)
            nav.addView(tv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        root.addView(contentHost)
        root.addView(nav)
        setContentView(root)
    }

    private fun showTab(index: Int) {
        currentTab = index
        listOf(tabHome, tabConfigs, tabSpeed, tabLogs, tabSettings).forEachIndexed { i, v ->
            v.visibility = if (i == index) View.VISIBLE else View.GONE
        }
        navButtons.forEachIndexed { i, tv ->
            tv.setTextColor(if (i == index) ACCENT else MUTED)
            tv.typeface = if (i == index) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
        when (index) {
            0 -> renderHome()
            1 -> refreshConfigsList()
            2 -> startSpeedMonitor()
            3 -> refreshLogs()
        }
        if (index != 2) stopSpeedMonitor()
    }

    private fun buildHomeTab(): LinearLayout {
        val scroll = ScrollView(this).apply { setBackgroundColor(BG) }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(24))
            gravity = Gravity.CENTER_HORIZONTAL
        }
        col.addView(TextView(this).apply {
            text = "Karoon Tunnel"
            textSize = 24f
            setTextColor(TEXT)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        })
        col.addView(TextView(this).apply {
            text = "Secure SSH VPN"
            textSize = 12f
            setTextColor(MUTED)
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(20))
        })
        tvActiveProfile = TextView(this).apply {
            textSize = 13f
            setTextColor(MUTED)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(12))
        }
        col.addView(tvActiveProfile)
        val statusCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(CARD, 16)
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        tvHomeStatus = TextView(this).apply {
            textSize = 15f
            setTextColor(TEXT)
            gravity = Gravity.CENTER
        }
        statusCard.addView(tvHomeStatus)
        col.addView(statusCard, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(28) })
        btnMain = Button(this).apply {
            text = "Connect"
            textSize = 18f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            background = rounded(ACCENT, 28)
            setPadding(0, dp(18), 0, dp(18))
            setOnClickListener { onMainButton() }
        }
        col.addView(btnMain, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        col.addView(TextView(this).apply {
            text = "Tap the button to connect or disconnect.\nSelect a config from the Configs tab first."
            textSize = 12f
            setTextColor(MUTED)
            gravity = Gravity.CENTER
            setPadding(0, dp(20), 0, 0)
        })
        scroll.addView(col)
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT))
        }
    }

    private fun renderHome() {
        val st = VpnState.status
        val profile = profiles.getActive()
        tvActiveProfile.text = profile?.let { "Profile: ${it.displayName}" } ?: "No profile selected"
        val (label, color) = when (st) {
            Status.DISCONNECTED -> "Disconnected" to MUTED
            Status.CONNECTING -> "Connecting..." to WARNING
            Status.RECONNECTING -> "Reconnecting..." to WARNING
            Status.CONNECTED -> "Connected" to SUCCESS
            Status.ERROR -> "Error" to ERROR
        }
        tvHomeStatus.setTextColor(color)
        tvHomeStatus.text = if (VpnState.message.isBlank()) label else "$label\n${VpnState.message}"
        when (st) {
            Status.DISCONNECTED, Status.ERROR -> {
                btnMain.text = "Connect"
                btnMain.isEnabled = true
                btnMain.background = rounded(ACCENT, 28)
            }
            Status.CONNECTING, Status.RECONNECTING -> {
                btnMain.text = if (st == Status.RECONNECTING) "Reconnecting..." else "Connecting..."
                btnMain.isEnabled = true
                btnMain.background = rounded(WARNING, 28)
            }
            Status.CONNECTED -> {
                btnMain.text = "Disconnect"
                btnMain.isEnabled = true
                btnMain.background = rounded(ERROR, 28)
            }
        }
    }

    private fun onMainButton() {
        when (VpnState.status) {
            Status.CONNECTED, Status.CONNECTING, Status.RECONNECTING -> onDisconnect()
            Status.DISCONNECTED, Status.ERROR -> onConnect()
        }
    }

    private fun buildConfigsTab(): LinearLayout {
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setPadding(dp(12), dp(16), dp(12), dp(8))
        }
        outer.addView(TextView(this).apply {
            text = "Saved configs"
            textSize = 18f
            setTextColor(TEXT)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(4), 0, 0, dp(8))
        })
        outer.addView(row(smallBtn("Import link") { showImportDialog() }, smallBtn("Add new") { showEditDialog(null) }))
        val scroll = ScrollView(this)
        configsList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(configsList)
        outer.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        return outer
    }

    private fun refreshConfigsList() {
        configsList.removeAllViews()
        val list = profiles.list()
        if (list.isEmpty()) {
            configsList.addView(TextView(this).apply {
                text = "No saved configs yet.\nImport a link or add one."
                setTextColor(MUTED)
                gravity = Gravity.CENTER
                setPadding(0, dp(40), 0, 0)
            })
            return
        }
        val activeId = profiles.getActiveId()
        list.forEach { p ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(CARD, 12)
                setPadding(dp(14), dp(12), dp(14), dp(12))
            }
            card.addView(TextView(this).apply {
                text = p.displayName + if (p.id == activeId) "  ✓" else ""
                textSize = 15f
                setTextColor(if (p.id == activeId) ACCENT else TEXT)
                typeface = Typeface.DEFAULT_BOLD
            })
            card.addView(TextView(this).apply {
                text = "${p.config.host}:${p.config.port} · ${p.config.username}"
                textSize = 12f
                setTextColor(MUTED)
                setPadding(0, dp(2), 0, dp(8))
            })
            card.addView(row(
                smallBtn("Select") {
                    profiles.setActiveId(p.id)
                    editingId = p.id
                    toast("Selected: ${p.displayName}")
                    showTab(0)
                },
                smallBtn("Edit") { showEditDialog(p) },
                smallBtn("Delete") {
                    AlertDialog.Builder(this).setTitle("Delete config?").setMessage(p.displayName)
                        .setPositiveButton("Delete") { _, _ ->
                            profiles.delete(p.id)
                            if (editingId == p.id) editingId = profiles.getActiveId()
                            refreshConfigsList(); renderHome()
                        }.setNegativeButton("Cancel", null).show()
                },
            ))
            configsList.addView(card, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) })
        }
    }

    private fun showImportDialog() {
        val input = EditText(this).apply {
            hint = "ssh://user:pass@host:port or paste config"
            setTextColor(TEXT); setHintTextColor(MUTED); setBackgroundColor(INPUT_BG)
            setPadding(dp(12), dp(12), dp(12), dp(12)); minLines = 3; gravity = Gravity.TOP or Gravity.START
        }
        AlertDialog.Builder(this).setTitle("Import config").setView(input)
            .setPositiveButton("Import") { _, _ ->
                val cfg = ConfigParser.parse(input.text.toString())
                if (cfg == null) { toast("Could not parse config"); LogStore.append("Import failed: could not parse config") }
                else {
                    val id = ProfileStore.newId()
                    profiles.save(Profile(id, cfg)); editingId = id
                    toast("Imported ${cfg.host}"); LogStore.append("Config imported for ${cfg.host}:${cfg.port}")
                    refreshConfigsList(); renderHome()
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun showEditDialog(existing: Profile?) {
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(4)) }
        fun field(hint: String, value: String, type: Int = InputType.TYPE_CLASS_TEXT) = EditText(this).apply {
            this.hint = hint; setText(value); setTextColor(TEXT); setHintTextColor(MUTED); setBackgroundColor(INPUT_BG)
            setPadding(dp(10), dp(10), dp(10), dp(10)); inputType = type
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) }
        }
        val c = existing?.config
        val etName = field("Name", c?.name ?: "")
        val etHost = field("Host", c?.host ?: "")
        val etPort = field("SSH Port", (c?.port ?: 22).toString(), InputType.TYPE_CLASS_NUMBER)
        val etUser = field("Username", c?.username ?: "")
        val etPass = field("Password", c?.password ?: "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val etUdpgw = field("UDPGW Port", (c?.udpgwPort ?: 7300).toString(), InputType.TYPE_CLASS_NUMBER)
        listOf(etName, etHost, etPort, etUser, etPass, etUdpgw).forEach { form.addView(it) }
        val scroll = ScrollView(this).apply { addView(form) }
        AlertDialog.Builder(this).setTitle(if (existing == null) "New config" else "Edit config").setView(scroll)
            .setPositiveButton("Save") { _, _ ->
                val host = etHost.text.toString().trim(); val user = etUser.text.toString().trim()
                val port = etPort.text.toString().toIntOrNull() ?: 22; val pass = etPass.text.toString()
                if (host.isEmpty() || user.isEmpty() || port !in 1..65535) { toast("Host, user and valid port required"); return@setPositiveButton }
                if (pass.isEmpty() && existing?.config?.privateKey.isNullOrBlank()) { toast("Password required"); return@setPositiveButton }
                val cfg = SshConfig(host = host, port = port, username = user, password = pass,
                    privateKey = existing?.config?.privateKey ?: "", passphrase = existing?.config?.passphrase ?: "",
                    name = etName.text.toString().trim(),
                    udpgwPort = etUdpgw.text.toString().toIntOrNull()?.takeIf { it in 1..65535 } ?: 7300,
                    transparentDns = existing?.config?.transparentDns ?: true,
                    protocol = existing?.config?.protocol ?: "SSH-Direct", maxChannels = etMaxChannelsSafe())
                val id = existing?.id ?: ProfileStore.newId()
                profiles.save(Profile(id, cfg)); editingId = id; toast("Saved"); refreshConfigsList(); renderHome()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun buildSpeedTab(): LinearLayout {
        val scroll = ScrollView(this).apply { setBackgroundColor(BG) }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(16))
        }
        col.addView(TextView(this).apply {
            text = "Stats"
            textSize = 20f
            setTextColor(TEXT)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(16))
        })

        val upHeader = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        upHeader.addView(TextView(this).apply {
            text = "Uplink"
            textSize = 14f
            setTextColor(MUTED)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        tvUplinkTotal = TextView(this).apply {
            text = "0 B"
            textSize = 14f
            setTextColor(TEXT)
            typeface = Typeface.DEFAULT_BOLD
        }
        upHeader.addView(tvUplinkTotal)
        col.addView(upHeader)
        chartUp = TrafficChartView(this, Color.parseColor("#F59E0B"))
        col.addView(chartUp, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(120)
        ).apply { topMargin = dp(6); bottomMargin = dp(20) })

        val downHeader = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        downHeader.addView(TextView(this).apply {
            text = "Downlink"
            textSize = 14f
            setTextColor(MUTED)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        tvDownlinkTotal = TextView(this).apply {
            text = "0 B"
            textSize = 14f
            setTextColor(TEXT)
            typeface = Typeface.DEFAULT_BOLD
        }
        downHeader.addView(tvDownlinkTotal)
        col.addView(downHeader)
        chartDown = TrafficChartView(this, Color.parseColor("#F59E0B"))
        col.addView(chartDown, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(120)
        ).apply { topMargin = dp(6); bottomMargin = dp(12) })

        col.addView(TextView(this).apply {
            text = "Charts update while this tab is open. Connect first to see traffic."
            textSize = 12f
            setTextColor(MUTED)
            setPadding(0, dp(8), 0, 0)
        })
        scroll.addView(col)
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(scroll, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT,
            ))
        }
    }

    private fun startSpeedMonitor() {
        if (speedRunning) return
        speedRunning = true
        upSamples.clear()
        downSamples.clear()
        lastUpBytes = TunnelStats.uplink.get()
        lastDownBytes = TunnelStats.downlink.get()
        chartUp.setSamples(emptyList())
        chartDown.setSamples(emptyList())
        tickStats()
    }

    private fun stopSpeedMonitor() {
        speedRunning = false
        mainHandler.removeCallbacks(speedRunnable)
    }

    private fun tickStats() {
        if (!speedRunning || currentTab != 2) return
        val up = TunnelStats.uplink.get()
        val down = TunnelStats.downlink.get()
        val dUp = (up - lastUpBytes).coerceAtLeast(0).toFloat()
        val dDown = (down - lastDownBytes).coerceAtLeast(0).toFloat()
        lastUpBytes = up
        lastDownBytes = down
        if (upSamples.size >= 60) upSamples.removeFirst()
        if (downSamples.size >= 60) downSamples.removeFirst()
        upSamples.addLast(dUp)
        downSamples.addLast(dDown)
        chartUp.setSamples(upSamples.toList())
        chartDown.setSamples(downSamples.toList())
        tvUplinkTotal.text = TunnelStats.formatBytes(up)
        tvDownlinkTotal.text = TunnelStats.formatBytes(down)
        mainHandler.postDelayed(speedRunnable, 1000)
    }

    private fun buildLogsTab(): LinearLayout {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(BG); setPadding(dp(12), dp(16), dp(12), dp(8)) }
        col.addView(TextView(this).apply { text = "Logs"; textSize = 18f; setTextColor(TEXT); typeface = Typeface.DEFAULT_BOLD; setPadding(0, 0, 0, dp(8)) })
        col.addView(row(smallBtn("Copy") { copyLogs() }, smallBtn("Clear") { LogStore.clear(); refreshLogs() }))
        val scroll = ScrollView(this)
        tvLogs = TextView(this).apply {
            textSize = 12f; setTextColor(TEXT); setTextIsSelectable(true); typeface = Typeface.MONOSPACE
            setPadding(dp(10), dp(10), dp(10), dp(10)); background = rounded(CARD, 12)
        }
        scroll.addView(tvLogs)
        col.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        return col
    }

    private fun refreshLogs() { if (::tvLogs.isInitialized) tvLogs.text = LogStore.text().ifBlank { "No logs yet." } }
    private fun copyLogs() {
        val t = LogStore.text(); if (t.isBlank()) { toast("No logs"); return }
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Karoon Tunnel Logs", t))
        toast("Logs copied")
    }

    private fun buildSettingsTab(): LinearLayout {
        val scroll = ScrollView(this).apply { setBackgroundColor(BG) }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(20), dp(16), dp(24)) }
        col.addView(TextView(this).apply { text = "Settings"; textSize = 18f; setTextColor(TEXT); typeface = Typeface.DEFAULT_BOLD; setPadding(0, 0, 0, dp(12)) })
        col.addView(TextView(this).apply { text = "Max channels (8–128)"; textSize = 13f; setTextColor(MUTED) })
        etMaxChannels = EditText(this).apply {
            setText((profiles.getActive()?.config?.maxChannels ?: 48).toString())
            inputType = InputType.TYPE_CLASS_NUMBER; setTextColor(TEXT); setHintTextColor(MUTED); setBackgroundColor(INPUT_BG)
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        col.addView(etMaxChannels, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8); topMargin = dp(4) })
        col.addView(smallBtn("Save channels to active profile") {
            val p = profiles.getActive() ?: run { toast("No active profile"); return@smallBtn }
            val maxCh = etMaxChannelsSafe()
            profiles.save(Profile(p.id, p.config.copy(maxChannels = maxCh)))
            toast("Saved max channels = $maxCh")
        })
        col.addView(TextView(this).apply { text = "Contact"; textSize = 16f; setTextColor(TEXT); typeface = Typeface.DEFAULT_BOLD; setPadding(0, dp(24), 0, dp(8)) })
        col.addView(TextView(this).apply { text = "Telegram bot link will be added here later."; textSize = 13f; setTextColor(MUTED) })
        col.addView(TextView(this).apply { text = "Karoon Tunnel · v1.7"; textSize = 12f; setTextColor(MUTED); gravity = Gravity.CENTER; setPadding(0, dp(32), 0, 0) })
        scroll.addView(col)
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT))
        }
    }

    private fun etMaxChannelsSafe(): Int =
        if (::etMaxChannels.isInitialized) etMaxChannels.text.toString().toIntOrNull()?.coerceIn(8, 128) ?: 48 else 48

    private fun onConnect() {
        try {
            val p = profiles.getActive()
            if (p == null) { toast("Select or add a config first"); showTab(1); return }
            var cfg = p.config
            if (::etMaxChannels.isInitialized) {
                val maxCh = etMaxChannelsSafe()
                if (maxCh != cfg.maxChannels) { cfg = cfg.copy(maxChannels = maxCh); profiles.save(Profile(p.id, cfg)) }
            }
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
            val prepare = VpnService.prepare(this)
            if (prepare != null) startActivityForResult(prepare, REQ_VPN) else startTunnel(cfg)
        } catch (t: Throwable) { LogStore.append("Connect failed: ${t.message}"); toast("Error: ${t.message}") }
    }

    private fun onDisconnect() {
        try {
            LogStore.append("Disconnect requested")
            startService(Intent(this, SshVpnService::class.java).setAction(SshVpnService.ACTION_STOP))
            VpnState.update(Status.DISCONNECTED, "Disconnected")
        } catch (t: Throwable) { toast("Disconnect failed: ${t.message}") }
    }

    private fun startTunnel(cfg: SshConfig) {
        LogStore.append("Starting tunnel to ${cfg.host}:${cfg.port}")
        val intent = Intent(this, SshVpnService::class.java).setAction(SshVpnService.ACTION_START).putExtra(SshVpnService.EXTRA_CONFIG, cfg.toJson())
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_VPN) {
            if (resultCode == RESULT_OK) profiles.getActive()?.let { startTunnel(it.config) }
            else { toast("VPN permission denied"); LogStore.append("VPN permission denied by user") }
        }
    }

    private fun smallBtn(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label; textSize = 11f; setTextColor(TEXT); background = rounded(INPUT_BG, 10); setOnClickListener { onClick() }
    }

    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEach { v -> addView(v, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(4) }) }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) }
    }

    private fun rounded(color: Int, radiusDp: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radiusDp).toFloat() }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}

/** Line chart for live traffic rate (bytes per second samples). */
class TrafficChartView(ctx: Context, private val lineColor: Int) : View(ctx) {
    private var samples: List<Float> = emptyList()
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = lineColor
        style = Paint.Style.STROKE
        strokeWidth = 3f
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#334155")
        strokeWidth = 1f
    }
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1A2332")
        style = Paint.Style.FILL
    }

    fun setSamples(s: List<Float>) {
        samples = s
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRoundRect(0f, 0f, w, h, 16f, 16f, bgPaint)
        val rows = 6
        for (i in 0..rows) {
            val y = h * i / rows
            canvas.drawLine(8f, y, w - 8f, y, gridPaint)
        }
        val cols = 10
        for (i in 0..cols) {
            val x = w * i / cols
            canvas.drawLine(x, 0f, x, h, gridPaint)
        }
        if (samples.size < 2) return
        val maxV = max(samples.maxOrNull() ?: 1f, 1f)
        val path = android.graphics.Path()
        samples.forEachIndexed { i, v ->
            val x = i.toFloat() / (samples.size - 1).coerceAtLeast(1) * (w - 16f) + 8f
            val y = h - (v / maxV) * (h * 0.85f) - 8f
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, linePaint)
    }
}
