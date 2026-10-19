package com.opensstp.android

import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.net.VpnService
import android.os.Bundle
import android.text.InputType
import android.text.TextUtils
import android.text.method.PasswordTransformationMethod
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    private lateinit var server: EditText
    private lateinit var username: EditText
    private lateinit var password: EditText
    private lateinit var remember: CompoundButton
    private lateinit var status: TextView
    private lateinit var hint: TextView
    private lateinit var pill: LinearLayout
    private lateinit var pillDot: View
    private lateinit var orb: PowerOrbView
    private lateinit var rxValue: TextView
    private lateinit var txValue: TextView
    private lateinit var connectButton: Button
    private lateinit var configList: LinearLayout
    private var sessionCanBeStopped = false
    private var selectedConfig: String? = null

    private class Field(val block: View, val edit: EditText)

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != SoftEtherVpnService.ACTION_STATUS) return
            val message = intent.getStringExtra(SoftEtherVpnService.EXTRA_STATUS) ?: ""
            val state = intent.getStringExtra(SoftEtherVpnService.EXTRA_STATE)
                ?: SoftEtherVpnService.STATE_DISCONNECTED
            // Traffic-only broadcasts intentionally omit the human-readable status text.
            if (intent.hasExtra(SoftEtherVpnService.EXTRA_STATUS)) renderStatus(message, state)
            if (intent.hasExtra(SoftEtherVpnService.EXTRA_RX_RATE)) {
                rxValue.text = intent.getStringExtra(SoftEtherVpnService.EXTRA_RX_RATE) ?: "0 B/s"
            } else if (state == SoftEtherVpnService.STATE_DISCONNECTED || state == SoftEtherVpnService.STATE_ERROR) {
                rxValue.text = "0 B/s"
                txValue.text = "0 B/s"
            }
            if (intent.hasExtra(SoftEtherVpnService.EXTRA_TX_RATE)) {
                txValue.text = intent.getStringExtra(SoftEtherVpnService.EXTRA_TX_RATE) ?: "0 B/s"
            }
        }
    }

    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) startVpn()
        else renderStatus("اجازهٔ ساخت تونل VPN داده نشد.", SoftEtherVpnService.STATE_DISCONNECTED)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = securePrefs()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(20), dp(22), dp(20), dp(28))
        }

        // ───────── Header ─────────
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        val logo = FrameLayout(this).apply {
            background = Ui.gradient(this@MainActivity, Ui.ACCENT, Ui.ACCENT2, 14f, android.graphics.drawable.GradientDrawable.Orientation.TL_BR)
        }
        logo.addView(IconView(this, IconKind.SHIELD, Color.WHITE), FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
        header.addView(logo, LinearLayout.LayoutParams(dp(42), dp(42)))
        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        titles.addView(label("Open-SSTP", 21f, Ui.TEXT, bold = true, gravity = Gravity.LEFT))
        titles.addView(label("SOFTETHER VPN FOR ANDROID", 10f, Ui.MUTED, gravity = Gravity.LEFT).apply { letterSpacing = 0.1f })
        header.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val addConfigButton = FrameLayout(this).apply {
            isClickable = true
            contentDescription = "افزودن کانفیگ"
            background = Ui.ripple(this@MainActivity, Ui.shape(this@MainActivity, Ui.CARD_HI, 14f, Ui.STROKE, 1f), 14f)
        }
        addConfigButton.addView(IconView(this, IconKind.PLUS, Ui.ACCENT_SOFT), FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
        header.addView(addConfigButton, LinearLayout.LayoutParams(dp(44), dp(44)))
        root.addView(header, match())

        // ───────── Hero orb + status ─────────
        orb = PowerOrbView(this).apply {
            isClickable = true
            contentDescription = "اتصال / قطع اتصال"
            setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.94f).scaleY(0.94f).setDuration(90L).start()
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f).setDuration(150L).start()
                }
                false
            }
        }
        root.addView(orb, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(220)).apply { topMargin = dp(10) })

        pillDot = View(this).apply { background = Ui.oval(Ui.IDLE) }
        status = label("قطع است", 13f, Ui.TEXT2, bold = true, gravity = Gravity.CENTER).apply { maxLines = 2 }
        pill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(14), dp(8), dp(16), dp(8))
            background = Ui.shape(this@MainActivity, Ui.alpha(Ui.IDLE, 30), 22f, Ui.alpha(Ui.IDLE, 90), 1f)
        }
        pill.addView(pillDot, LinearLayout.LayoutParams(dp(8), dp(8)))
        pill.addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
        root.addView(pill, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(2)
        })
        hint = label("برای اتصال، دکمهٔ بالا را لمس کنید", 12f, Ui.MUTED, gravity = Gravity.CENTER)
        root.addView(hint, params(top = 10))

        // ───────── Live speed card ─────────
        val metrics = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = Ui.shape(this@MainActivity, Ui.CARD, 20f, Ui.STROKE, 1f)
        }
        val (rxCell, rx) = speedCell(IconKind.ARROW_DOWN, Ui.GREEN, "DOWNLOAD", "rx_speed")
        val (txCell, tx) = speedCell(IconKind.ARROW_UP, Ui.ACCENT_SOFT, "UPLOAD", "tx_speed")
        rxValue = rx
        txValue = tx
        metrics.addView(rxCell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        metrics.addView(View(this).apply { setBackgroundColor(Ui.STROKE) }, LinearLayout.LayoutParams(dp(1), dp(34)).apply {
            marginStart = dp(8); marginEnd = dp(8)
        })
        metrics.addView(txCell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(metrics, params(top = 18))

        // ───────── Saved configs ─────────
        configList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        root.addView(configList, params(top = 24))

        // ───────── Connection form ─────────
        val form = card()
        form.addView(sectionHeader("اطلاعات اتصال", null), match())
        val serverField = buildField("سرور", IconKind.SERVER, "VPN Gate hostname or IP[:port]", input = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        val userField = buildField("نام کاربری", IconKind.USER, "Username (VPN Gate: vpn)", input = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
        val passField = buildField("رمز عبور", IconKind.LOCK, "Password (VPN Gate often blank)", isPassword = true)
        server = serverField.edit.apply { setText(prefs.getString("server", "")) }
        username = userField.edit.apply { setText(prefs.getString("username", "vpn")) }
        password = passField.edit.apply { setText(prefs.getString("password", "")) }
        form.addView(serverField.block, params(top = 14))
        form.addView(userField.block, params(top = 12))
        form.addView(passField.block, params(top = 12))

        remember = Switch(this).apply {
            isChecked = prefs.getBoolean("remember", false)
            val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
            thumbTintList = ColorStateList(states, intArrayOf(Color.WHITE, Ui.TEXT2))
            trackTintList = ColorStateList(states, intArrayOf(Ui.ACCENT, Ui.STROKE))
        }
        val rememberRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            isClickable = true
            setPadding(dp(2), dp(4), dp(2), dp(4))
            setOnClickListener { remember.toggle() }
        }
        val rememberText = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        rememberText.addView(label("مرا به خاطر بسپار", 14f, Ui.TEXT))
        rememberText.addView(label("Remember me", 11f, Ui.MUTED))
        rememberRow.addView(rememberText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        rememberRow.addView(remember, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        form.addView(rememberRow, params(top = 16))
        root.addView(form, params(top = 8))

        // ───────── Connect button ─────────
        connectButton = Button(this).apply {
            text = "Connect"
            textSize = 16f
            isAllCaps = false
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            stateListAnimator = null
            background = Ui.ripple(this@MainActivity, Ui.gradient(this@MainActivity, Ui.ACCENT, Ui.ACCENT2, 18f), 18f)
        }
        root.addView(connectButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)).apply { topMargin = dp(18) })
        root.addView(
            label("Built-in SoftEther protocol engine. Enter a server and valid account details to connect.", 11f, Ui.MUTED, gravity = Gravity.CENTER),
            params(top = 14)
        )

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Ui.BG0, Ui.BG1)
            )
            addView(root)
        }
        setContentView(scroll)

        renderConfigs(prefs)
        addConfigButton.setOnClickListener { promptSaveConfig(prefs) }
        orb.setOnClickListener { connectButton.performClick() }

        remember.setOnCheckedChangeListener { _, checked ->
            if (!checked) {
                prefs.edit()
                    .remove("server")
                    .remove("username")
                    .remove("password")
                    .putBoolean("remember", false)
                    .apply()
            }
        }

        connectButton.setOnClickListener {
            if (sessionCanBeStopped) {
                startService(
                    Intent(this, SoftEtherVpnService::class.java).setAction(SoftEtherVpnService.ACTION_DISCONNECT)
                )
                renderStatus("در حال قطع اتصال…", SoftEtherVpnService.STATE_DISCONNECTED)
                return@setOnClickListener
            }

            if (server.text.isNullOrBlank()) {
                server.error = "آدرس سرور را وارد کنید"
                server.requestFocus()
                return@setOnClickListener
            }
            if (remember.isChecked) {
                prefs.edit()
                    .putString("server", server.text.toString().trim())
                    .putString("username", username.text.toString().trim())
                    .putString("password", password.text.toString())
                    .putBoolean("remember", true)
                    .apply()
            } else {
                // Do not retain credentials after the user has unchecked Remember me.
                prefs.edit().clear().apply()
            }

            val permissionIntent = VpnService.prepare(this)
            if (permissionIntent != null) vpnPermission.launch(permissionIntent) else startVpn()
        }

        renderStatus("قطع است", SoftEtherVpnService.STATE_DISCONNECTED)

        // Gentle staggered entrance
        if (savedInstanceState == null) {
            for (i in 0 until root.childCount) {
                val child = root.getChildAt(i)
                child.alpha = 0f
                child.translationY = dp(16).toFloat()
                child.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setStartDelay(50L * i)
                    .setDuration(360L)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            this,
            statusReceiver,
            IntentFilter(SoftEtherVpnService.ACTION_STATUS),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        // Asking for status does not start a tunnel. It lets the UI catch up after being reopened.
        startService(Intent(this, SoftEtherVpnService::class.java).setAction(SoftEtherVpnService.ACTION_QUERY))
    }

    override fun onStop() {
        try { unregisterReceiver(statusReceiver) } catch (_: IllegalArgumentException) { }
        super.onStop()
    }

    private fun startVpn() {
        val intent = Intent(this, SoftEtherVpnService::class.java).apply {
            action = SoftEtherVpnService.ACTION_CONNECT
            putExtra(SoftEtherVpnService.EXTRA_SERVER, server.text.toString().trim())
            putExtra(SoftEtherVpnService.EXTRA_USERNAME, username.text.toString().trim())
            putExtra(SoftEtherVpnService.EXTRA_PASSWORD, password.text.toString())
        }
        ContextCompat.startForegroundService(this, intent)
        renderStatus("در حال اتصال به سرور…", SoftEtherVpnService.STATE_CONNECTING)
    }

    private fun renderStatus(message: String, state: String) {
        status.text = message
        sessionCanBeStopped = state == SoftEtherVpnService.STATE_CONNECTED || state == SoftEtherVpnService.STATE_CONNECTING
        connectButton.text = if (sessionCanBeStopped) "Disconnect" else "Connect"
        connectButton.background = Ui.ripple(
            this,
            if (sessionCanBeStopped) Ui.gradient(this, Ui.DANGER_A, Ui.DANGER_B, 18f)
            else Ui.gradient(this, Ui.ACCENT, Ui.ACCENT2, 18f),
            18f
        )
        val color = when (state) {
            SoftEtherVpnService.STATE_CONNECTED -> Ui.GREEN
            SoftEtherVpnService.STATE_CONNECTING -> Ui.CYAN
            SoftEtherVpnService.STATE_ERROR -> Ui.RED
            else -> Ui.TEXT2
        }
        status.setTextColor(color)
        pillDot.background = Ui.oval(color)
        pill.background = Ui.shape(this, Ui.alpha(color, 30), 22f, Ui.alpha(color, 90), 1f)
        orb.setState(state)
        hint.text = if (sessionCanBeStopped) "برای قطع اتصال، دکمهٔ بالا را لمس کنید"
        else "برای اتصال، دکمهٔ بالا را لمس کنید"
        // While connecting, keep the form editable; the native service validates server/port.
        connectButton.isEnabled = true
    }

    private fun promptSaveConfig(prefs: SharedPreferences) {
        val serverValue = server.text.toString().trim()
        if (serverValue.isBlank()) {
            server.error = "اول آدرس سرور را وارد کنید"
            server.requestFocus()
            Toast.makeText(this, "آدرس سرور را وارد کنید و دوباره + را بزنید.", Toast.LENGTH_SHORT).show()
            return
        }

        val nameInput = EditText(this).apply {
            hint = "مثلاً سرور شخصی"
            setSingleLine(true)
            setText("کانفیگ ${readConfigs(prefs).length() + 1}")
        }
        AlertDialog.Builder(this)
            .setTitle("افزودن کانفیگ")
            .setMessage("اطلاعات فعلی سرور، نام کاربری و رمز عبور ذخیره می‌شود.")
            .setView(nameInput)
            .setNegativeButton("انصراف", null)
            .setPositiveButton("ذخیره") { _, _ ->
                val configName = nameInput.text.toString().trim()
                if (configName.isBlank()) {
                    Toast.makeText(this, "برای کانفیگ یک نام وارد کنید.", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val configs = readConfigs(prefs)
                val item = JSONObject()
                    .put("name", configName)
                    .put("server", serverValue)
                    .put("username", username.text.toString().trim())
                    .put("password", password.text.toString())
                var replaced = false
                for (i in 0 until configs.length()) {
                    if (configs.optJSONObject(i)?.optString("name") == configName) {
                        configs.put(i, item)
                        replaced = true
                        break
                    }
                }
                if (!replaced) configs.put(item)
                prefs.edit().putString("configs_json", configs.toString()).apply()
                selectedConfig = configName
                renderConfigs(prefs)
                Toast.makeText(this, "کانفیگ «$configName» ذخیره شد.", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun readConfigs(prefs: SharedPreferences): JSONArray {
        return try {
            JSONArray(prefs.getString("configs_json", "[]") ?: "[]")
        } catch (_: Exception) {
            JSONArray()
        }
    }

    private fun renderConfigs(prefs: SharedPreferences) {
        if (!::configList.isInitialized) return
        configList.removeAllViews()
        val configs = readConfigs(prefs)
        if (configs.length() == 0) return

        configList.addView(sectionHeader("کانفیگ‌های ذخیره‌شده", configs.length().toString()), params(bottom = 4))

        for (i in 0 until configs.length()) {
            val item = configs.optJSONObject(i) ?: continue
            val configName = item.optString("name", "کانفیگ ${i + 1}")
            val configServer = item.optString("server", "")
            val selected = configName == selectedConfig

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutDirection = View.LAYOUT_DIRECTION_RTL
                setPadding(dp(8), dp(10), dp(12), dp(10))
                isClickable = true
                background = Ui.ripple(
                    this@MainActivity,
                    Ui.shape(
                        this@MainActivity,
                        if (selected) Ui.alpha(Ui.ACCENT, 28) else Ui.CARD,
                        18f,
                        if (selected) Ui.ACCENT else Ui.STROKE,
                        1f
                    ),
                    18f
                )
                setOnClickListener {
                    selectedConfig = configName
                    server.setText(item.optString("server", ""))
                    username.setText(item.optString("username", "vpn"))
                    password.setText(item.optString("password", ""))
                    renderConfigs(prefs)
                    Toast.makeText(this@MainActivity, "کانفیگ «$configName» انتخاب شد.", Toast.LENGTH_SHORT).show()
                }
            }

            val avatar = FrameLayout(this).apply {
                background = Ui.oval(Ui.alpha(if (selected) Ui.ACCENT else Ui.IDLE, 38))
            }
            avatar.addView(
                IconView(this, IconKind.SERVER, if (selected) Ui.ACCENT_SOFT else Ui.TEXT2),
                FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER)
            )
            row.addView(avatar, LinearLayout.LayoutParams(dp(40), dp(40)))

            val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(label(configName, 14f, Ui.TEXT, bold = true).apply {
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
            })
            texts.addView(label(configServer, 12f, Ui.TEXT2).apply {
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
                textDirection = View.TEXT_DIRECTION_LTR
            }, params(top = 2))
            row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(12) })

            val deleteButton = FrameLayout(this).apply {
                isClickable = true
                contentDescription = "حذف $configName"
                background = Ui.ripple(this@MainActivity, Ui.oval(Ui.alpha(Ui.RED, 28)), 19f)
                setOnClickListener {
                    AlertDialog.Builder(this@MainActivity)
                        .setMessage("کانفیگ «$configName» حذف شود؟")
                        .setNegativeButton("انصراف", null)
                        .setPositiveButton("حذف") { _, _ ->
                            val current = readConfigs(prefs)
                            val updated = JSONArray()
                            for (j in 0 until current.length()) {
                                val entry = current.optJSONObject(j) ?: continue
                                if (entry.optString("name") != configName) updated.put(entry)
                            }
                            prefs.edit().putString("configs_json", updated.toString()).apply()
                            if (selectedConfig == configName) selectedConfig = null
                            renderConfigs(prefs)
                        }
                        .show()
                }
            }
            deleteButton.addView(IconView(this, IconKind.CLOSE, Ui.RED), FrameLayout.LayoutParams(dp(16), dp(16), Gravity.CENTER))
            row.addView(deleteButton, LinearLayout.LayoutParams(dp(38), dp(38)).apply { marginStart = dp(8) })

            configList.addView(row, params(top = 8))
        }
    }

    private fun securePrefs() = EncryptedSharedPreferences.create(
        this,
        "opensstp_secure_prefs",
        MasterKey.Builder(this).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    // ───────── UI building blocks ─────────

    private fun label(t: String, sp: Float, color: Int, bold: Boolean = false, gravity: Int = Gravity.RIGHT): TextView =
        TextView(this).apply {
            text = t
            textSize = sp
            setTextColor(color)
            this.gravity = gravity
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutDirection = View.LAYOUT_DIRECTION_RTL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        background = Ui.shape(this@MainActivity, Ui.CARD, 22f, Ui.STROKE, 1f)
    }

    private fun sectionHeader(title: String, badge: String?): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutDirection = View.LAYOUT_DIRECTION_RTL
        addView(label(title, 13f, Ui.TEXT2, bold = true))
        if (badge != null) {
            addView(
                label(badge, 11f, Ui.ACCENT_SOFT, bold = true, gravity = Gravity.CENTER).apply {
                    setPadding(dp(8), dp(2), dp(8), dp(2))
                    background = Ui.shape(this@MainActivity, Ui.alpha(Ui.ACCENT, 36), 10f)
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) }
            )
        }
    }

    private fun speedCell(kind: IconKind, color: Int, title: String, tagName: String): Pair<View, TextView> {
        val cell = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        val badge = FrameLayout(this).apply { background = Ui.oval(Ui.alpha(color, 36)) }
        badge.addView(IconView(this, kind, color), FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER))
        cell.addView(badge, LinearLayout.LayoutParams(dp(38), dp(38)))
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, 0, 0)
        }
        col.addView(label(title, 10f, Ui.MUTED, bold = true, gravity = Gravity.LEFT).apply { letterSpacing = 0.08f })
        val value = label("0 B/s", 15f, Ui.TEXT, bold = true, gravity = Gravity.LEFT).apply {
            tag = tagName
            isSingleLine = true
        }
        col.addView(value)
        cell.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return Pair(cell, value)
    }

    private fun buildField(
        title: String,
        icon: IconKind,
        hintText: String,
        isPassword: Boolean = false,
        input: Int = InputType.TYPE_CLASS_TEXT
    ): Field {
        val block = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        block.addView(label(title, 12f, Ui.TEXT2), params(bottom = 6))
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(8), 0, dp(14), 0)
            background = Ui.shape(this@MainActivity, Ui.FIELD, 14f, Ui.STROKE, 1f)
        }
        val iconView = IconView(this, icon, Ui.MUTED)
        row.addView(iconView, LinearLayout.LayoutParams(dp(20), dp(20)))

        val edit = EditText(this).apply {
            hint = hintText
            textSize = 15f
            setTextColor(Ui.TEXT)
            setHintTextColor(Ui.MUTED)
            background = null
            inputType = if (isPassword) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else input
            isSingleLine = true
            gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL
            setPadding(dp(10), 0, dp(10), 0)
        }
        row.addView(edit, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))

        if (isPassword) {
            var visible = false
            val eye = FrameLayout(this).apply {
                isClickable = true
                contentDescription = "نمایش رمز عبور"
                background = Ui.ripple(this@MainActivity, Ui.oval(Color.TRANSPARENT), 19f)
            }
            val eyeIcon = IconView(this, IconKind.EYE, Ui.MUTED)
            eye.addView(eyeIcon, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
            eye.setOnClickListener {
                visible = !visible
                val sel = edit.selectionEnd
                edit.transformationMethod = if (visible) null else PasswordTransformationMethod.getInstance()
                if (sel >= 0) edit.setSelection(sel)
                eyeIcon.changeKind(if (visible) IconKind.EYE_OFF else IconKind.EYE)
            }
            row.addView(eye, LinearLayout.LayoutParams(dp(38), dp(38)))
        }

        edit.setOnFocusChangeListener { _, focused ->
            row.background = Ui.shape(
                this, Ui.FIELD, 14f,
                if (focused) Ui.ACCENT else Ui.STROKE,
                if (focused) 1.5f else 1f
            )
            iconView.applyTint(if (focused) Ui.ACCENT else Ui.MUTED)
        }
        block.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))
        return Field(block, edit)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun match() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    private fun params(top: Int = 0, bottom: Int = 0) = match().apply {
        topMargin = dp(top)
        bottomMargin = dp(bottom)
    }
}
