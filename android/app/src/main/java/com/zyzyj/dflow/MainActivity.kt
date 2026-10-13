package com.zyzyj.dflow

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Shows the DFlow web UI served by the embedded Node process.
 *
 * The WebView loads http://127.0.0.1:PORT rather than a bundled file, because
 * the front end is a thin client over the Node API (dozens of endpoint calls
 * for the gallery, favourites, reverse-prompt queue and translation). Serving
 * the same files from the filesystem would leave every one of those calls
 * failing.
 */
class MainActivity : ComponentActivity() {

    private lateinit var webView: WebView
    private lateinit var splash: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var logText: TextView
    private lateinit var retryButton: Button
    private lateinit var progress: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermission()
        buildViews()
        // Start the server as soon as the activity exists so the two run in
        // parallel; the splash explains the wait instead of showing a blank page.
        NodeService.start(this)
        observeService()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
        if (granted != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    private fun buildViews() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        splash = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
        }

        val title = TextView(this).apply {
            text = "DFlow"
            textSize = 26f
        }
        statusText = TextView(this).apply {
            text = "正在启动本地服务…"
            textSize = 15f
            setPadding(0, 24, 0, 16)
        }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
        }
        retryButton = Button(this).apply {
            text = "重新启动服务"
            visibility = View.GONE
            setOnClickListener {
                retryButton.visibility = View.GONE
                statusText.text = "正在重新启动…"
                NodeService.start(this@MainActivity)
            }
        }
        val logScroll = ScrollView(this).apply {
            setPadding(0, 32, 0, 0)
        }
        logText = TextView(this).apply {
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
        }
        logScroll.addView(logText)

        splash.addView(title)
        splash.addView(statusText)
        splash.addView(progress)
        splash.addView(retryButton)
        splash.addView(logScroll)

        webView = WebView(this).apply {
            visibility = View.GONE
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true          // the front end keeps state in localStorage
            settings.allowFileAccess = false
            settings.mediaPlaybackRequiresUserGesture = false
            webViewClient = WebViewClient()
            webChromeClient = WebChromeClient()
        }

        root.addView(splash, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT)
        root.addView(webView, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT)
        setContentView(root)
    }

    private fun observeService() {
        lifecycleScope.launch {
            NodeService.state.collect { state ->
                when (state) {
                    is NodeService.State.Ready -> showWebView()
                    is NodeService.State.Failed -> {
                        statusText.text = state.reason
                        progress.visibility = View.GONE
                        retryButton.visibility = View.VISIBLE
                    }
                    NodeService.State.Bootstrapping -> {
                        statusText.text = "正在启动本地服务…"
                        progress.visibility = View.VISIBLE
                    }
                    NodeService.State.Stopped -> statusText.text = "服务已停止"
                }
            }
        }
        lifecycleScope.launch {
            NodeService.log.collect { text ->
                logText.text = text
                // Follow the tail so the newest line stays visible during a slow start.
                statusText.text = text.lineSequence().lastOrNull()?.take(120) ?: statusText.text
            }
        }
    }

    private fun showWebView() {
        if (webView.visibility == View.VISIBLE) return
        splash.visibility = View.GONE
        webView.visibility = View.VISIBLE
        webView.loadUrl("http://127.0.0.1:${NodeService.PORT}/")
    }

    override fun onResume() {
        super.onResume()
        // Coming back after a kill: if the server is ready but we are still on
        // the splash, go straight to the UI.
        if (NodeService.state.value is NodeService.State.Ready) showWebView()
    }

    @SuppressLint("GestureBackNavigation")
    @Deprecated("Handled for the in-page history; the API is required on older WebView.")
    override fun onBackPressed() {
        if (webView.visibility == View.VISIBLE && webView.canGoBack()) {
            webView.goBack()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }
}
