package com.opensstp.android

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.net.VpnService
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ScrollView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class MainActivity : Activity() {
    private lateinit var server: EditText
    private lateinit var username: EditText
    private lateinit var password: EditText
    private lateinit var remember: CheckBox
    private lateinit var status: TextView
    private lateinit var connectButton: Button
    private var sessionCanBeStopped = false

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != SoftEtherVpnService.ACTION_STATUS) return
            val message = intent.getStringExtra(SoftEtherVpnService.EXTRA_STATUS) ?: ""
            val state = intent.getStringExtra(SoftEtherVpnService.EXTRA_STATE)
                ?: SoftEtherVpnService.STATE_DISCONNECTED
            // Traffic-only broadcasts intentionally omit the human-readable status text.
            if (intent.hasExtra(SoftEtherVpnService.EXTRA_STATUS)) renderStatus(message, state)
            val content = findViewById<ViewGroup>(android.R.id.content)
            val rxLabel = findTextViewByTag(content, "rx_speed")
            val txLabel = findTextViewByTag(content, "tx_speed")
            if (intent.hasExtra(SoftEtherVpnService.EXTRA_RX_RATE)) {
                rxLabel?.text = "↓ ${intent.getStringExtra(SoftEtherVpnService.EXTRA_RX_RATE)}"
            } else if (state == SoftEtherVpnService.STATE_DISCONNECTED || state == SoftEtherVpnService.STATE_ERROR) {
                rxLabel?.text = "↓ 0 B/s"
                txLabel?.text = "↑ 0 B/s"
            }
            if (intent.hasExtra(SoftEtherVpnService.EXTRA_TX_RATE)) {
                txLabel?.text = "↑ ${intent.getStringExtra(SoftEtherVpnService.EXTRA_TX_RATE)}"
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
            setPadding(dp(24), dp(28), dp(24), dp(24))
            setBackgroundColor(Color.rgb(13, 18, 28))
        }
        root.addView(TextView(this).apply {
            text = "Open-SSTP"
            textSize = 25f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.RIGHT
        }, match())
        root.addView(TextView(this).apply {
            text = "SOFTETHER VPN FOR ANDROID"
            textSize = 15f
            setTextColor(Color.rgb(155, 170, 190))
            gravity = Gravity.RIGHT
        }, params(top = 6))

        server = field("VPN Gate hostname or IP[:port]")
            .apply { setText(prefs.getString("server", "")) }
        username = field("Username (VPN Gate: vpn)")
            .apply { setText(prefs.getString("username", "vpn")) }
        password = field("Password (VPN Gate often blank)", true)
            .apply { setText(prefs.getString("password", "")) }
        root.addView(server, params(top = 22))
        root.addView(username, params(top = 12))
        root.addView(password, params(top = 12))

        remember = CheckBox(this).apply {
            text = "Remember me  |  مرا به خاطر بسپار"
            isChecked = prefs.getBoolean("remember", false)
            setTextColor(Color.rgb(220, 228, 240))
        }
        root.addView(remember, params(top = 8))

        connectButton = Button(this).apply {
            text = "Connect"
            isAllCaps = false
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(46, 119, 235))
        }
        root.addView(connectButton, params(top = 14))
        root.addView(TextView(this).apply {
            text = "CONNECTION STATUS"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(155, 170, 190))
            gravity = Gravity.RIGHT
        }, params(top = 18))
        status = TextView(this).apply {
            text = "NOT CONNECTED"
            textSize = 14f
            setTextColor(Color.rgb(255, 190, 90))
            gravity = Gravity.RIGHT
        }
        root.addView(status, params(top = 14))
        val metrics = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setBackgroundColor(Color.rgb(24, 32, 46))
        }
        val rxSpeed = TextView(this).apply {
            tag = "rx_speed"
            text = "↓ 0 B/s"
            textSize = 18f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        val txSpeed = TextView(this).apply {
            tag = "tx_speed"
            text = "↑ 0 B/s"
            textSize = 18f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        metrics.addView(rxSpeed, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        metrics.addView(txSpeed, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(metrics, params(top = 18))
        root.addView(TextView(this).apply {
            text = "Built-in SoftEther protocol engine. Enter a server and valid account details to connect."
            textSize = 12f
            setTextColor(Color.rgb(155, 170, 190))
            gravity = Gravity.RIGHT
        }, params(top = 10))
        val scroll = ScrollView(this).apply {
            fillViewport = true
            setBackgroundColor(Color.rgb(13, 18, 28))
            addView(root)
        }
        setContentView(scroll)
        remember.setOnCheckedChangeListener { _, checked ->
            if (!checked) prefs.edit().clear().apply()
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
        status.setTextColor(
            when (state) {
                SoftEtherVpnService.STATE_CONNECTED -> Color.rgb(63, 220, 164)
                SoftEtherVpnService.STATE_ERROR -> Color.rgb(255, 115, 115)
                else -> Color.rgb(255, 190, 90)
            }
        )
        // While connecting, keep the form editable; the native service validates server/port.
        connectButton.isEnabled = true
    }

    private fun securePrefs() = EncryptedSharedPreferences.create(
        this,
        "opensstp_secure_prefs",
        MasterKey.Builder(this).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    private fun field(hintText: String, isPassword: Boolean = false) = EditText(this).apply {
        hint = hintText
        textSize = 15f
        singleLine = true
        gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL
        setTextColor(Color.WHITE)
        setHintTextColor(Color.rgb(120, 137, 160))
        if (isPassword) inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        setPadding(dp(12), dp(10), dp(12), dp(10))
        setBackgroundColor(Color.rgb(24, 32, 46))
    }

    private fun findTextViewByTag(root: ViewGroup, targetTag: String): TextView? {
        for (index in 0 until root.childCount) {
            val child = root.getChildAt(index)
            if (child.tag == targetTag && child is TextView) return child
            if (child is ViewGroup) findTextViewByTag(child, targetTag)?.let { return it }
        }
        return null
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun match() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    private fun params(top: Int = 0) = match().apply { topMargin = dp(top) }
}
