package com.nerdginger.workoutmate

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.webkit.WebViewAssetLoader
import org.json.JSONObject

/**
 * Single-activity host for the WorkOutMate UI.
 *
 * The UI is HTML/JS living in `assets/www`, but it is deliberately *not* loaded
 * over `file://`. Every `file://` page gets an opaque origin, which makes
 * IndexedDB unreliable-to-unavailable and leaves `crypto.randomUUID` undefined.
 * [WebViewAssetLoader] serves the same files over a virtual https origin, so the
 * storage layer behaves exactly as it would in a normal browser.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var assetLoader: WebViewAssetLoader
    private lateinit var bridge: NativeBridge
    private lateinit var restTimer: RestTimer

    /** Payload waiting on the user to pick a destination in the SAF picker. */
    private var pendingWriteBody: String? = null
    private var pendingWriteCallback: String? = null
    private var pendingReadCallback: String? = null

    // "*/*" rather than a fixed type so the same picker serves both the JSON
    // backup and the CSV export; the suggested filename's extension is what
    // actually steers the destination app.
    private val createDocument =
        registerForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
            val callback = pendingWriteCallback
            val body = pendingWriteBody
            pendingWriteCallback = null
            pendingWriteBody = null
            if (uri == null || body == null || callback == null) {
                if (callback != null) respond(callback, false, "cancelled")
                return@registerForActivityResult
            }
            val result = BackupIo.writeText(this, uri, body)
            result.fold(
                onSuccess = { respond(callback, true, BackupIo.displayName(this, uri) ?: "backup") },
                onFailure = { respond(callback, false, it.message ?: "write failed") },
            )
        }

    private val openDocument =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val callback = pendingReadCallback
            pendingReadCallback = null
            if (uri == null || callback == null) {
                if (callback != null) respond(callback, false, "cancelled")
                return@registerForActivityResult
            }
            val result = BackupIo.readText(this, uri)
            result.fold(
                onSuccess = { respond(callback, true, it) },
                onFailure = { respond(callback, false, it.message ?: "read failed") },
            )
        }

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* best effort */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        restTimer = RestTimer(this) { event, remaining -> onRestEvent(event, remaining) }

        webView = WebView(this).apply {
            with(settings) {
                javaScriptEnabled = true
                domStorageEnabled = true
                // The asset loader supplies every resource; direct filesystem and
                // content:// reads from the page are never needed and are a
                // needless way to widen the bridge's blast radius.
                allowFileAccess = false
                allowContentAccess = false
                setSupportMultipleWindows(false)
                javaScriptCanOpenWindowsAutomatically = false
                mediaPlaybackRequiresUserGesture = false
                textZoom = 100
            }
            webViewClient = LocalAssetClient()
            webChromeClient = LoggingChromeClient()
            isVerticalScrollBarEnabled = true
            overScrollMode = WebView.OVER_SCROLL_NEVER
        }

        bridge = NativeBridge(this, restTimer)
        webView.addJavascriptInterface(bridge, "WMNative")

        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)

        setContentView(webView)
        installBackHandler()
        ensureNotificationPermission()

        if (savedInstanceState == null) {
            webView.loadUrl(INDEX_URL)
        } else {
            webView.restoreState(savedInstanceState)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onDestroy() {
        // Cancel silently: notifying would try to call into a WebView that is
        // about to be destroyed.
        restTimer.cancel(notify = false)
        webView.destroy()
        super.onDestroy()
    }

    /**
     * Hands the back press to the page first so it can close a modal or pop a
     * screen; only a `false` from JS lets the press fall through and leave the app.
     */
    private fun installBackHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                webView.evaluateJavascript(BACK_PRESSED_JS) { value ->
                    if (value?.trim() != "true") {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })
    }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    // ---- Calls made by NativeBridge (always marshalled onto the UI thread) ----

    fun startSaveFile(body: String, suggestedName: String, callbackId: String) =
        runOnUiThread {
            pendingWriteBody = body
            pendingWriteCallback = callbackId
            try {
                createDocument.launch(suggestedName)
            } catch (e: ActivityNotFoundException) {
                pendingWriteBody = null
                pendingWriteCallback = null
                respond(callbackId, false, "No app available to save files: ${e.message}")
            }
        }

    fun startOpenFile(callbackId: String) = runOnUiThread {
        pendingReadCallback = callbackId
        try {
            openDocument.launch(arrayOf(MIME_JSON, "application/octet-stream", "text/plain", "*/*"))
        } catch (e: ActivityNotFoundException) {
            pendingReadCallback = null
            respond(callbackId, false, "No app available to open files: ${e.message}")
        }
    }

    fun setKeepScreenOn(on: Boolean) = runOnUiThread {
        if (on) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    /** Delivers a result back to the JS promise registered under [callbackId]. */
    fun respond(callbackId: String, ok: Boolean, payload: String) = runOnUiThread {
        val js = "window.__wmNativeCallback && window.__wmNativeCallback(" +
            "${JSONObject.quote(callbackId)},$ok,${JSONObject.quote(payload)});"
        webView.evaluateJavascript(js, null)
    }

    /** Fires a `wm-native` DOM event on the page (rest ticks, timer finished). */
    fun emit(event: String, detailJson: String) = runOnUiThread {
        val js = "window.__wmNativeEvent && window.__wmNativeEvent(" +
            "${JSONObject.quote(event)},${JSONObject.quote(detailJson)});"
        webView.evaluateJavascript(js, null)
    }

    private fun onRestEvent(event: String, remainingSec: Int) {
        emit(event, JSONObject().put("remainingSec", remainingSec).toString())
    }

    private inner class LocalAssetClient : WebViewClient() {
        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest,
        ): WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)

        /**
         * Nothing outside the bundled assets should ever render inside the
         * WebView — a foreign page in here would sit on the same origin as the
         * JS bridge. Anything external is handed to the real browser instead.
         */
        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest,
        ): Boolean {
            val url = request.url
            if (url.host == ASSET_HOST) return false
            openExternally(url)
            return true
        }
    }

    private fun openExternally(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            // No browser installed; silently ignoring beats crashing the app.
        }
    }

    private class LoggingChromeClient : WebChromeClient() {
        override fun onConsoleMessage(message: ConsoleMessage): Boolean {
            if (BuildConfig.DEBUG) {
                android.util.Log.d(
                    "WorkOutMate",
                    "${message.messageLevel()} ${message.message()} @${message.lineNumber()}",
                )
            }
            return true
        }
    }

    companion object {
        const val ASSET_HOST = "appassets.androidplatform.net"
        const val MIME_JSON = "application/json"
        private const val INDEX_URL = "https://$ASSET_HOST/assets/www/index.html"
        private const val BACK_PRESSED_JS =
            "(function(){try{return !!(window.WM && WM.onBackPressed && WM.onBackPressed());}" +
                "catch(e){return false;}})()"
    }
}
