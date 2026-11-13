package com.q43b.tgflip

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi

// A Direct mode account's settings and login (2.0 step 3; dev builds until 2.0): Settings > Network >
// "Direct mode settings and login…", and opened by the chat list while the account isn't logged in.
// Rows: status, then the user's own api_id and api_hash (my.telegram.org), the proxy, where media goes,
// and Log in / Log out. Logging in follows TDLib: "Show a QR code" (scanned by Telegram on another
// phone: nothing to type on the keypad) or the phone number, then the code, the two-step password, and
// an email where Telegram asks for one. Each step is a dialog that opens by itself when TDLib gets there.
class DirectSetupActivity : AppCompatActivity() {
    private class Row(val label: String, val action: (() -> Unit)? = null)

    private lateinit var settings: DirectSettings
    private lateinit var adapter: ArrayAdapter<String>
    private lateinit var list: WrapListView
    private var rows: List<Row> = emptyList()
    private var dialog: AlertDialog? = null
    private var dialogFor: Any? = null  // the auth state (or QR link) the open dialog answers
    private var wantsLogin = false  // "Log in" was chosen: the phone number step may ask
    private var shownErrors = Tdlib.errorCount
    private val onChange: () -> Unit = { redraw(); askForStep() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_settings_group)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        val profile = Profiles.current(this)
        settings = DirectSettings(this, profile.id)
        findViewById<TextView>(R.id.group_title).text = getString(R.string.direct_title, profile.name)
        list = findViewById(R.id.group_list)
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf())
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ -> rows.getOrNull(position)?.action?.invoke() }
        if (settings.hasApi) Tdlib.start(this, profile.id)
    }

    override fun onStart() {
        super.onStart()
        Tdlib.listen(onChange)
        onChange()
    }

    override fun onStop() {
        Tdlib.unlisten(onChange)
        super.onStop()
    }

    override fun onDestroy() {
        dialog?.dismiss()
        super.onDestroy()
    }

    private val mine get() = Tdlib.isFor(settings.profileId)
    private val auth get() = if (mine) Tdlib.auth else null

    private fun statusText(): String = when (val a = auth) {
        null -> getString(if (!settings.hasApi) R.string.direct_status_needs_api else R.string.direct_status_stopped)
        is TdApi.AuthorizationStateWaitTdlibParameters -> getString(if (settings.hasApi) R.string.direct_status_starting else R.string.direct_status_needs_api)
        is TdApi.AuthorizationStateWaitPhoneNumber -> getString(R.string.direct_status_logged_out)
        is TdApi.AuthorizationStateWaitOtherDeviceConfirmation -> getString(R.string.direct_status_qr)
        is TdApi.AuthorizationStateWaitCode -> getString(R.string.direct_status_code)
        is TdApi.AuthorizationStateWaitPassword -> getString(R.string.direct_status_password)
        is TdApi.AuthorizationStateWaitEmailAddress, is TdApi.AuthorizationStateWaitEmailCode -> getString(R.string.direct_status_email)
        is TdApi.AuthorizationStateWaitRegistration -> getString(R.string.direct_status_no_account)
        is TdApi.AuthorizationStateWaitPremiumPurchase -> getString(R.string.direct_status_premium)
        is TdApi.AuthorizationStateReady -> getString(R.string.direct_status_ready, settings.me)
        is TdApi.AuthorizationStateLoggingOut -> getString(R.string.direct_status_logging_out)
        is TdApi.AuthorizationStateClosing, is TdApi.AuthorizationStateClosed -> getString(R.string.direct_status_stopped)
        else -> a.javaClass.simpleName
    }

    private fun connectionText(): String = when (if (mine) Tdlib.connection else null) {
        null -> "-"
        is TdApi.ConnectionStateWaitingForNetwork -> getString(R.string.direct_conn_network)
        is TdApi.ConnectionStateConnectingToProxy -> getString(R.string.direct_conn_proxy)
        is TdApi.ConnectionStateConnecting -> getString(R.string.direct_conn_connecting)
        is TdApi.ConnectionStateUpdating -> getString(R.string.direct_conn_updating)
        is TdApi.ConnectionStateReady -> getString(R.string.direct_conn_ready)
        else -> "?"
    }

    private fun redraw() {
        val loggedIn = auth is TdApi.AuthorizationStateReady
        val loggingIn = auth.let { it != null && it !is TdApi.AuthorizationStateReady && it !is TdApi.AuthorizationStateWaitTdlibParameters }
        val built = mutableListOf(
            Row(getString(R.string.direct_row_status, statusText())),
            Row(getString(R.string.direct_row_connection, connectionText())),
        )
        when {
            loggedIn -> built += Row(getString(R.string.direct_log_out)) { confirmLogOut() }
            auth is TdApi.AuthorizationStateWaitPhoneNumber -> built += Row(getString(R.string.direct_log_in)) { wantsLogin = true; askForStep(force = true) }
            loggingIn -> {
                built += Row(getString(R.string.direct_continue)) { askForStep(force = true) }
                // Telegram's next way to send the code (SMS, a call...), when it has one
                (auth as? TdApi.AuthorizationStateWaitCode)?.codeInfo?.let { info -> resendLabel(info) }?.let { label ->
                    built += Row(label) { resendCode() }
                }
                built += Row(getString(R.string.direct_cancel_login)) { cancelLogin() }
            }
            settings.hasApi && auth == null -> built += Row(getString(R.string.direct_connect)) { Tdlib.start(this, settings.profileId) }
        }
        built += Row(getString(R.string.direct_row_api_id, settings.apiId?.toString() ?: getString(R.string.not_set))) { if (!loggedIn) askApiId() else locked() }
        built += Row(getString(R.string.direct_row_api_hash, getString(if (settings.apiHash.isEmpty()) R.string.not_set else R.string.direct_set))) { if (!loggedIn) askApiHash() else locked() }
        built += Row(getString(R.string.direct_row_api_help)) { openMyTelegram() }
        built += Row(getString(R.string.direct_row_proxy, settings.proxy.describe() ?: getString(R.string.direct_proxy_none))) { askProxy() }
        built += Row(getString(R.string.direct_row_media, getString(
            when {
                !settings.hasSdCard -> R.string.direct_media_phone_no_sd
                settings.mediaOnSd -> R.string.direct_media_sd
                else -> R.string.direct_media_phone
            }))) { toggleMedia() }
        // A refusal (wrong code, wrong password, bad proxy...): say so, and ask that step again
        if (mine && Tdlib.errorCount != shownErrors) {
            shownErrors = Tdlib.errorCount
            Toast.makeText(this, getString(R.string.direct_refused, Tdlib.lastError.orEmpty()), Toast.LENGTH_LONG).show()
            dialogFor = null
        }
        rows = built
        val selected = list.selectedItemPosition
        adapter.clear()
        adapter.addAll(rows.map { it.label })
        if (selected in rows.indices) list.setSelection(selected)
    }

    // The dialog for where TDLib's login stands; opens by itself on each new step (the phone number
    // step only after "Log in"), or again from the "Continue" row when closed with Back
    private fun askForStep(force: Boolean = false) {
        val a = auth ?: return
        val key: Any = if (a is TdApi.AuthorizationStateWaitOtherDeviceConfirmation) a.link else a.javaClass
        if (!force && dialogFor == key && dialog?.isShowing == true) return
        if (!force && dialogFor == key) return  // closed with Back: the Continue row reopens it
        when (a) {
            is TdApi.AuthorizationStateWaitPhoneNumber -> if (wantsLogin) chooseLoginWay() else return
            is TdApi.AuthorizationStateWaitOtherDeviceConfirmation -> showQr(a.link)
            is TdApi.AuthorizationStateWaitCode -> askCode(a.codeInfo)
            is TdApi.AuthorizationStateWaitPassword -> askPassword(a)
            is TdApi.AuthorizationStateWaitEmailAddress -> ask(getString(R.string.direct_email_title), "",
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS) { Tdlib.send(TdApi.SetAuthenticationEmailAddress(it)) }
            is TdApi.AuthorizationStateWaitEmailCode -> ask(getString(R.string.direct_email_code_title, a.codeInfo.emailAddressPattern), "",
                InputType.TYPE_CLASS_NUMBER) { Tdlib.send(TdApi.CheckAuthenticationEmailCode(TdApi.EmailAddressAuthenticationCode(it))) }
            is TdApi.AuthorizationStateWaitRegistration -> message(getString(R.string.direct_no_account)) { Tdlib.send(TdApi.LogOut()) }
            is TdApi.AuthorizationStateWaitPremiumPurchase -> message(getString(R.string.direct_premium)) { Tdlib.send(TdApi.LogOut()) }
            else -> {
                dialog?.dismiss()
                dialogFor = null
                return
            }
        }
        dialogFor = key
    }

    private fun chooseLoginWay() {
        replaceDialog(AlertDialog.Builder(this)
            .setTitle(R.string.direct_login_how)
            .setItems(arrayOf(getString(R.string.direct_login_qr), getString(R.string.direct_login_phone))) { _, which ->
                if (which == 0) Tdlib.send(TdApi.RequestQrCodeAuthentication(LongArray(0)))
                else ask(getString(R.string.direct_phone_title), "+", InputType.TYPE_CLASS_PHONE) { phone ->
                    Tdlib.send(TdApi.SetAuthenticationPhoneNumber(phone, null))
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> wantsLogin = false })
    }

    // The QR code to scan from Telegram on another phone (Settings > Devices > Link Desktop Device).
    // TDLib changes the link every half a minute or so; a new link redraws it.
    private fun showQr(link: String) {
        val size = (resources.displayMetrics.widthPixels * 0.8).toInt()
        val image = ImageView(this).apply {
            setImageBitmap(qrBitmap(link, size))
            setBackgroundColor(Color.WHITE)
            adjustViewBounds = true
        }
        val hint = TextView(this).apply {
            setText(R.string.direct_qr_hint)
            val pad = (8 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, 0)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(image)
            addView(hint)
        }
        replaceDialog(AlertDialog.Builder(this)
            .setView(box)
            .setNegativeButton(R.string.direct_qr_cancel) { _, _ -> cancelLogin() })
        // The theme mustn't recolor the code: black on white always
        image.setBackgroundColor(Color.WHITE)
    }

    private fun qrBitmap(text: String, size: Int): Bitmap {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 2))
        val pixels = IntArray(matrix.width * matrix.height) { i ->
            if (matrix.get(i % matrix.width, i / matrix.width)) Color.BLACK else Color.WHITE
        }
        return Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.RGB_565)
    }

    private fun askCode(info: TdApi.AuthenticationCodeInfo) {
        val where = when (val t = info.type) {
            is TdApi.AuthenticationCodeTypeTelegramMessage -> getString(R.string.direct_code_telegram)
            is TdApi.AuthenticationCodeTypeSms -> getString(R.string.direct_code_sms, info.phoneNumber)
            is TdApi.AuthenticationCodeTypeCall -> getString(R.string.direct_code_call, info.phoneNumber)
            is TdApi.AuthenticationCodeTypeFragment -> getString(R.string.direct_code_fragment)
            is TdApi.AuthenticationCodeTypeSmsWord -> getString(R.string.direct_code_sms, info.phoneNumber) + " (" + t.firstLetter + "…)"
            is TdApi.AuthenticationCodeTypeSmsPhrase -> getString(R.string.direct_code_sms, info.phoneNumber) + " (" + t.firstWord + "…)"
            else -> getString(R.string.direct_code_other, info.phoneNumber)
        }
        val numeric = info.type !is TdApi.AuthenticationCodeTypeSmsWord && info.type !is TdApi.AuthenticationCodeTypeSmsPhrase
        ask(where, "", if (numeric) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT,
            extra = resendLabel(info)?.let { it to ::resendCode }) {
            Tdlib.send(TdApi.CheckAuthenticationCode(it))
        }
    }

    // "✉ Send the code by SMS instead" for Telegram's next way, or null when there is none
    private fun resendLabel(info: TdApi.AuthenticationCodeInfo): String? = when (info.nextType) {
        null -> null
        is TdApi.AuthenticationCodeTypeSms, is TdApi.AuthenticationCodeTypeSmsWord, is TdApi.AuthenticationCodeTypeSmsPhrase ->
            getString(R.string.direct_resend_sms)
        is TdApi.AuthenticationCodeTypeCall, is TdApi.AuthenticationCodeTypeFlashCall, is TdApi.AuthenticationCodeTypeMissedCall ->
            getString(R.string.direct_resend_call)
        else -> getString(R.string.direct_resend_other)
    }

    // Telegram may refuse until the first code's timeout has passed; its words then show as a toast
    private fun resendCode() {
        dialogFor = null  // the code step's dialog opens again, saying where the new code went
        Tdlib.send(TdApi.ResendAuthenticationCode(TdApi.ResendCodeReasonUserRequest()))
    }

    // Logging out ends any login step; TDLib closes, starts again and waits for a phone number
    private fun cancelLogin() {
        wantsLogin = false
        dialog?.dismiss()
        dialogFor = null
        Tdlib.send(TdApi.LogOut())
    }

    private fun askPassword(state: TdApi.AuthorizationStateWaitPassword) {
        val title = if (state.passwordHint.isEmpty()) getString(R.string.direct_password_title)
        else getString(R.string.direct_password_hint, state.passwordHint)
        // Visible on purpose, like the server token: a hidden password on a keypad is too error-prone
        ask(title, "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD) {
            Tdlib.send(TdApi.CheckAuthenticationPassword(it))
        }
    }

    private fun confirmLogOut() {
        AlertDialog.Builder(this)
            .setMessage(R.string.direct_log_out_ask)
            .setPositiveButton(R.string.direct_log_out_yes) { _, _ -> wantsLogin = false; Tdlib.send(TdApi.LogOut()) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
            .also { Themer.apply(it) }
    }

    private fun locked() = Toast.makeText(this, R.string.direct_api_locked, Toast.LENGTH_LONG).show()

    private fun askApiId() = ask(getString(R.string.direct_api_id_title), settings.apiId?.toString().orEmpty(), InputType.TYPE_CLASS_NUMBER) {
        settings.apiId = it.toIntOrNull()
        apiChanged()
    }

    private fun askApiHash() = ask(getString(R.string.direct_api_hash_title), settings.apiHash,
        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD) {
        settings.apiHash = it.lowercase()
        apiChanged()
    }

    // TDLib reads api_id/api_hash only when it starts
    private fun apiChanged() {
        if (settings.hasApi) {
            if (mine) Tdlib.restart(this) else Tdlib.start(this, settings.profileId)
        }
        redraw()
    }

    private fun openMyTelegram() {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://my.telegram.org"))) }
            .onFailure { Toast.makeText(this, R.string.direct_api_help_no_browser, Toast.LENGTH_LONG).show() }
    }

    private fun askProxy() {
        val kinds = listOf(getString(R.string.direct_proxy_none), getString(R.string.direct_proxy_mtproto), getString(R.string.direct_proxy_socks5))
        MessageMenu.choose(this, getString(R.string.direct_proxy_title), kinds) { which ->
            when (which) {
                0 -> saveProxy(DirectSettings.Proxy(DirectSettings.ProxyKind.NONE))
                1 -> proxyFields(DirectSettings.ProxyKind.MTPROTO)
                else -> proxyFields(DirectSettings.ProxyKind.SOCKS5)
            }
        }
    }

    // Server, port, and the secret (MTProto) or user and password (SOCKS5). A whole tg://proxy or
    // t.me/proxy link typed or pasted into the server field fills in everything.
    private fun proxyFields(kind: DirectSettings.ProxyKind) {
        val old = settings.proxy.takeIf { it.kind == kind }
        fun field(hint: Int, text: String, type: Int) = EditText(this).apply {
            setHint(hint)
            inputType = type
            setText(text)
        }
        val visible = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        val server = field(R.string.direct_proxy_server_hint, old?.server.orEmpty(), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        val port = field(R.string.port_hint, old?.port?.takeIf { it > 0 }?.toString().orEmpty(), InputType.TYPE_CLASS_NUMBER)
        val secret = field(R.string.direct_proxy_secret_hint, old?.secret.orEmpty(), visible)
        val user = field(R.string.direct_proxy_user_hint, old?.user.orEmpty(), visible)
        val password = field(R.string.direct_proxy_password_hint, old?.password.orEmpty(), visible)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(server)
            addView(port)
            if (kind == DirectSettings.ProxyKind.MTPROTO) addView(secret) else { addView(user); addView(password) }
        }
        AlertDialog.Builder(this)
            .setTitle(if (kind == DirectSettings.ProxyKind.MTPROTO) R.string.direct_proxy_mtproto else R.string.direct_proxy_socks5)
            .setView(box)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val link = DirectSettings.parseLink(server.text.toString())
                val proxy = link ?: DirectSettings.Proxy(kind, server.text.toString().trim(),
                    port.text.toString().trim().toIntOrNull() ?: 0, secret.text.toString().trim(),
                    user.text.toString().trim(), password.text.toString())
                val ok = proxy.server.isNotEmpty() && proxy.port in 1..65535 &&
                    (proxy.kind != DirectSettings.ProxyKind.MTPROTO || proxy.secret.isNotEmpty())
                if (ok) saveProxy(proxy)
                else Toast.makeText(this, R.string.direct_proxy_bad, Toast.LENGTH_LONG).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
            .also { Themer.apply(it) }
    }

    private fun saveProxy(proxy: DirectSettings.Proxy) {
        settings.proxy = proxy
        if (mine) Tdlib.applyProxy(proxy)
        redraw()
    }

    // Where TDLib keeps media; it reads the folder only when it starts. Files already downloaded stay
    // behind in the old place (TDLib downloads them again when needed).
    private fun toggleMedia() {
        if (!settings.hasSdCard) {
            Toast.makeText(this, R.string.direct_media_no_sd, Toast.LENGTH_LONG).show()
            return
        }
        settings.mediaOnSdChosen = !settings.mediaOnSdChosen
        if (mine) Tdlib.restart(this)
        redraw()
    }

    // extra: a third button (label to action), like "Send the code by SMS instead"
    private fun ask(title: String, initial: String, type: Int, extra: Pair<String, () -> Unit>? = null, onDone: (String) -> Unit) {
        val input = EditText(this).apply {
            inputType = type
            setText(initial)
            setSelection(initial.length)
        }
        replaceDialog(AlertDialog.Builder(this)
            .setTitle(title)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val text = input.text.toString().trim()
                if (text.isNotEmpty()) onDone(text)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .apply { if (extra != null) setNeutralButton(extra.first) { _, _ -> extra.second() } })
    }

    private fun message(text: String, onOk: () -> Unit) {
        replaceDialog(AlertDialog.Builder(this).setMessage(text).setPositiveButton(android.R.string.ok) { _, _ -> onOk() })
    }

    private fun replaceDialog(builder: AlertDialog.Builder) {
        dialog?.dismiss()
        dialog = builder.show().also { Themer.apply(it) }
    }

    // Left goes back, like the screens of Settings
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT && currentFocus is android.widget.ListView) {
            if (event.action == KeyEvent.ACTION_UP) finish()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    companion object {
        fun start(activity: Activity) = activity.startActivity(Intent(activity, DirectSetupActivity::class.java))
    }
}
