package com.q43b.tgflip

import android.os.Bundle
import android.text.InputType
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class MainActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private lateinit var settings: ServerSettings
    private var addressDialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        settings = ServerSettings(this)
        webView = findViewById(R.id.web_view)
        setUpWebView()

        val address = settings.getAddress()
        if (address == null) askForAddress() else webView.loadUrl(address)
    }

    private fun setUpWebView() {
        webView.settings.javaScriptEnabled = true  // the page is driven by JavaScript
        webView.settings.domStorageEnabled = true  // the page keeps the token in localStorage
        webView.webChromeClient = WebChromeClient()  // without this, prompt() and alert() are ignored
        webView.webViewClient = object : WebViewClient() {
            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                // Only the page itself failing means the server is unreachable
                if (request.isForMainFrame) askForAddress()
            }
        }
    }

    private fun askForAddress() {
        if (addressDialog?.isShowing == true) return

        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        input.setText(settings.getAddress() ?: DEFAULT_ADDRESS)

        addressDialog = AlertDialog.Builder(this)
            .setTitle(R.string.server_address_title)
            .setView(input)
            .setCancelable(false)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val address = input.text.toString().trim()
                settings.saveAddress(address)
                webView.loadUrl(address)
            }
            .show()
    }

    private companion object {
        const val DEFAULT_ADDRESS = "http://192.168.31.24:8000"
    }
}
