package app.starlingmap

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.GeolocationPermissions
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import org.json.JSONObject

// The page, owned by the process instead of by the activity.
//
// Everything that seals a position and posts it lives in the page, so the
// share used to die the moment Android tore the activity down: swipe the app
// out of recents and the keys went with the WebView. With "keep sharing when
// the app is closed" on, the WebView is built on the application context and
// held here, the activity borrows it for as long as it exists, and a task
// removal takes the window without taking the page.
//
// It is released the moment it stops earning its keep: the share ends, or the
// switch is off and the activity is gone. A page held past that is a set of
// live keys in the memory of an app the person believes they closed.
object PageHost {

    private var webView: WebView? = null
    private var bridge: StarlingBridge? = null
    private var loader: WebViewAssetLoader? = null
    private val main = Handler(Looper.getMainLooper())
    private var release: Runnable? = null

    // The proxy config last handed to ProxyController in this process, or null
    // before the first. Applying one reloads the page, so an unchanged config
    // is never applied twice: reopening the app would otherwise reload the
    // page that has been carrying a share, and the share with it.
    var proxyApplied: String? = null

    // The activity currently borrowing the page, or null while it is running
    // headless behind the share service.
    var activity: MainActivity? = null
        private set

    val alive: Boolean get() = webView != null

    // Built once per process, on the application context so no activity can be
    // held past its death. Callers pass the activity they want it attached to.
    @SuppressLint("SetJavaScriptEnabled")
    fun attach(host: MainActivity): WebView {
        release?.let { main.removeCallbacks(it) }
        release = null
        activity = host
        val app = host.applicationContext
        val existing = webView
        if (existing != null) {
            (existing.parent as? ViewGroup)?.removeView(existing)
            bridge?.activity = host
            return existing
        }

        // Debuggable builds only, which release APKs are not: this is how the
        // e2e checks drive the real page inside the real WebView.
        if ((app.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        val view = WebView(app)
        webView = view
        loader = WebViewAssetLoader.Builder()
            .addPathHandler("/", WebViewAssetLoader.AssetsPathHandler(app))
            .build()

        with(view.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            // The system font-size setting reaches WebView content only
            // through textZoom, and it scales px-sized text too.
            textZoom = (app.resources.configuration.fontScale * 100).toInt()
            setGeolocationEnabled(true)
            allowFileAccess = false
            allowContentAccess = false
            setSupportMultipleWindows(false)
            // Belt and suspenders on top of allowFileAccess = false: these
            // default to false already at this targetSdk, but a page that can
            // never reach file:// has no business asking for cross-origin
            // reads from one either, and explicit here means a future
            // targetSdk bump cannot quietly change the default under us.
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
        }
        // Not waived when the page is off screen: the whole point of holding it
        // is that it keeps sealing and posting positions with no window.
        view.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)

        val b = StarlingBridge(app)
        b.activity = host
        bridge = b
        view.addJavascriptInterface(b, "StarlingNative")

        view.webViewClient = object : WebViewClient() {
            // Without this override, WebView takes the whole app down when its
            // renderer dies, and the low-memory killer will take a renderer
            // with no window before it takes much else. A share was ending as a
            // silent process death, with no record and no notification. Now
            // the app survives it: the dead page is dropped, the share ends
            // the way any other outside stop does, and an open window gets a
            // fresh page, which puts the share back on.
            override fun onRenderProcessGone(
                view: WebView,
                detail: android.webkit.RenderProcessGoneDetail,
            ): Boolean {
                if (view !== webView) return true
                val ui = activity
                val sharing = LocationService.running
                destroy()
                if (sharing) LocationService.endShare(app, "renderer")
                ui?.recreate()
                return true
            }

            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest,
            ): WebResourceResponse? = loader?.shouldInterceptRequest(request.url)

            // The WebView only ever navigates inside the bundled app. A
            // starlingmap.app link CARRYING A FRAGMENT is a deep link (an
            // invite, a help beacon) and stays internal; a bare site link is
            // a trip to the website, which is a different thing from the app
            // and belongs in the system browser. Everything else goes to the
            // system too.
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest,
            ): Boolean {
                val url = request.url
                if (url.host == MainActivity.ASSET_HOST) return false
                if (url.host == MainActivity.APP_HOST && url.scheme == "https" && !url.fragment.isNullOrEmpty()) {
                    load(url.fragment)
                    return true
                }
                // No window means no browser trip: a headless page has nobody
                // in front of it to have tapped a link.
                val ui = activity ?: return true
                runCatching { ui.startActivity(Intent(Intent.ACTION_VIEW, url)) }
                return true
            }
        }

        view.webChromeClient = object : WebChromeClient() {
            override fun onGeolocationPermissionsShowPrompt(
                origin: String,
                callback: GeolocationPermissions.Callback,
            ) {
                if (origin != "https://${MainActivity.ASSET_HOST}") {
                    callback.invoke(origin, false, false)
                    return
                }
                val ui = activity
                if (ui == null) {
                    callback.invoke(origin, false, false)
                    return
                }
                if (ui.hasLocationPermission()) callback.invoke(origin, true, false)
                else ui.askGeolocation(origin, callback)
            }
        }

        LocationService.sink = { json -> deliverFix(json) }
        return view
    }

    // The activity is going away. The page stays only while it is holding a
    // share up on its own; otherwise it goes with the window.
    fun detachFrom(host: MainActivity, keepAlive: Boolean) {
        if (activity !== host) return
        activity = null
        bridge?.activity = null
        (webView?.parent as? ViewGroup)?.removeView(webView)
        if (!keepAlive) destroy()
    }

    fun load(fragment: String?) {
        val url = if (fragment.isNullOrEmpty()) MainActivity.START_URL else "${MainActivity.START_URL}#$fragment"
        webView?.loadUrl(url)
    }

    fun reload() {
        val v = webView ?: return
        main.post { if (webView === v) v.reload() }
    }

    // Script is only ever a literal here plus JSONObject.quote of the data;
    // nothing interpolates a value into code.
    //
    // Through the main handler, never View.post: a view with no window queues
    // its posts until it is attached again, so every fix pushed at a headless
    // page sat there unrun until somebody reopened the app.
    fun eval(script: String) {
        val v = webView ?: return
        main.post { if (webView === v) v.evaluateJavascript(script, null) }
    }

    fun deliverFix(json: String) = eval("globalThis.__starlingFix && __starlingFix(${JSONObject.quote(json)})")

    fun notice(message: String) = eval("globalThis.__starlingNotice && __starlingNotice(${JSONObject.quote(message)})")

    fun hashChange(fragment: String) = eval("location.hash = ${JSONObject.quote("#$fragment")}")

    fun bioReply(token: String, payload: String?) {
        val p = if (payload == null) "null" else JSONObject.quote(payload)
        eval("globalThis.__starlingBio && __starlingBio(${JSONObject.quote(token)}, $p)")
    }

    // Used by the activity for the Orbot-silence timer, which has to be
    // cancellable across an activity teardown.
    fun post(r: Runnable, delayMs: Long) {
        main.postDelayed(r, delayMs)
    }

    fun cancel(r: Runnable) {
        main.removeCallbacks(r)
    }

    // The share ended while nothing was on screen. Not immediate: the page's
    // own stop path still has a departure to get onto the relay, and killing
    // the WebView mid-flight would leave a live dot pointing at nobody.
    fun releaseSoon(delayMs: Long = 8000) {
        if (webView == null || activity != null) return
        release?.let { main.removeCallbacks(it) }
        val r = Runnable { if (activity == null) destroy() }
        release = r
        main.postDelayed(r, delayMs)
    }

    fun destroy() {
        release?.let { main.removeCallbacks(it) }
        release = null
        LocationService.sink = null
        val v = webView ?: return
        webView = null
        bridge = null
        loader = null
        activity = null
        (v.parent as? ViewGroup)?.removeView(v)
        v.destroy()
    }

    // Does the page have a reason to outlive the window? Only a running share
    // with the switch on.
    fun shouldKeepAlive(ctx: Context): Boolean =
        LocationService.running && keepSharing(ctx)

    fun keepSharing(ctx: Context): Boolean =
        ctx.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
            .getBoolean(MainActivity.PREF_KEEP_SHARING, false)

    fun setKeepSharing(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(MainActivity.PREF_KEEP_SHARING, on)
            .apply()
    }
}
