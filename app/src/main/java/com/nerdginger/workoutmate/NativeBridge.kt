package com.nerdginger.workoutmate

import android.webkit.JavascriptInterface
import org.json.JSONArray
import java.util.concurrent.Executors

/**
 * The only door between the page and Android.
 *
 * Every method here is reachable by any JavaScript running in the WebView, so
 * the surface is kept deliberately small and nothing returns a secret: the page
 * can ask *whether* an API key exists and ask for a request to be made with it,
 * but can never read it back.
 *
 * These methods are invoked on a WebView binder thread, not the UI thread —
 * anything touching the Activity is marshalled by the callee, and the Claude
 * call gets its own executor so a slow request can't block the bridge.
 */
class NativeBridge(
    private val activity: MainActivity,
    private val restTimer: RestTimer,
) {

    private val secureStore = SecureStore(activity)
    private val claude = ClaudeClient(secureStore)
    private val network = Executors.newSingleThreadExecutor { r ->
        Thread(r, "wm-claude").apply { isDaemon = true }
    }

    // ---- Capability probing ----

    /** Lets the same page degrade gracefully if it is ever opened outside the app. */
    @JavascriptInterface
    fun isNative(): Boolean = true

    @JavascriptInterface
    fun appVersion(): String = BuildConfig.VERSION_NAME

    // ---- Backup and restore ----

    @JavascriptInterface
    fun saveFile(body: String, suggestedName: String, callbackId: String) {
        activity.startSaveFile(body, suggestedName, callbackId)
    }

    @JavascriptInterface
    fun openFile(callbackId: String) {
        activity.startOpenFile(callbackId)
    }

    /**
     * Rolling local snapshot written after each saved session. This is a
     * corruption safety net, not a substitute for a real export — it does not
     * survive uninstalling the app.
     */
    @JavascriptInterface
    fun writeSnapshot(stamp: String, body: String): String =
        BackupIo.writeSnapshot(activity, stamp, body).fold(
            onSuccess = { it },
            onFailure = { "" },
        )

    @JavascriptInterface
    fun listSnapshots(): String = JSONArray(BackupIo.listSnapshots(activity)).toString()

    @JavascriptInterface
    fun readSnapshot(name: String): String =
        BackupIo.readSnapshot(activity, name).getOrDefault("")

    // ---- Rest timer ----

    @JavascriptInterface
    fun startRest(seconds: Int) = activity.runOnUiThread { restTimer.start(seconds) }

    @JavascriptInterface
    fun cancelRest() = activity.runOnUiThread { restTimer.cancel() }

    @JavascriptInterface
    fun isResting(): Boolean = restTimer.isRunning

    @JavascriptInterface
    fun keepScreenOn(on: Boolean) = activity.setKeepScreenOn(on)

    // ---- API key (write-only from the page's perspective) ----

    @JavascriptInterface
    fun isSecureStoreAvailable(): Boolean = secureStore.isAvailable

    @JavascriptInterface
    fun hasApiKey(): Boolean = secureStore.hasApiKey()

    @JavascriptInterface
    fun setApiKey(value: String): Boolean = secureStore.setApiKey(value)

    @JavascriptInterface
    fun clearApiKey(): Boolean = secureStore.clearApiKey()

    @JavascriptInterface
    fun getModel(): String = secureStore.model()

    @JavascriptInterface
    fun setModel(value: String): Boolean = secureStore.setModel(value)

    // ---- Build me a routine ----

    /**
     * [requestJson] carries the prompt and the routine-plan output schema; the
     * key and model are added on the Kotlin side.
     */
    @JavascriptInterface
    fun callClaude(requestJson: String, callbackId: String) {
        network.execute {
            when (val result = claude.createMessage(requestJson)) {
                is ClaudeClient.Result.Ok -> activity.respond(callbackId, true, result.text)
                is ClaudeClient.Result.Failure -> activity.respond(callbackId, false, result.message)
            }
        }
    }
}
