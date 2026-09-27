package app.starlingmap

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import android.webkit.GeolocationPermissions
import android.webkit.WebView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature

// One screen: the bundled web app in a WebView on the fixed asset origin.
// Everything the page cannot do itself (foreground location, Keystore
// biometrics, Tor proxying) arrives through the StarlingNative bridge.
//
// The WebView itself belongs to PageHost, not to this activity, so a share can
// outlive the window it was started from. This is the window and the source of
// everything that needs an activity to happen at all.
class MainActivity : FragmentActivity() {

    companion object {
        const val ASSET_HOST = "appassets.androidplatform.net"
        const val START_URL = "https://$ASSET_HOST/index.html"
        const val APP_HOST = "starlingmap.app"
        const val PREFS = "starling"
        const val PREF_TOR = "tor"
        const val EVENTS_CHANNEL = "events"
        // A new id, not a raised importance on EVENTS_CHANNEL: a channel's
        // sound and vibration are as fixed after creation as its importance
        // is, so an existing install's routine channel can never grow the
        // SOS-specific alert this one exists for.
        const val SOS_CHANNEL = "events_sos"
        const val EVENTS_NOTIF_ID = 2
        const val PREF_STOP_ROUTE = "stop_route"
        const val PREF_STOP_TS = "stop_ts"
        const val PREF_KEEP_SHARING = "keep_sharing"
    }

    private lateinit var webView: WebView

    // Set while a location permission request is in flight for the share flow.
    private var pendingShareStart = false
    private var pendingGeoCallback: Pair<String, GeolocationPermissions.Callback>? = null

    private val locationPermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        val granted = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        pendingGeoCallback?.let { (origin, cb) ->
            cb.invoke(origin, granted, false)
            pendingGeoCallback = null
        }
        if (pendingShareStart) {
            pendingShareStart = false
            if (granted) startShareService()
            else sendFixError("denied", 1)
        }
    }

    private val notifPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* the share notification is a courtesy; sharing works without it */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // The screen holds a live map of the circle and, in the app switcher,
        // the OS otherwise thumbnails whatever was on screen. Not a setting:
        // someone at risk who needs this app is exactly the person who cannot
        // afford a shoulder-surfed or screen-recorded location, so it is on
        // for everybody, unconditionally.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)

        applyTorPref()

        // Either a fresh page or the one that has been holding a share up
        // while nothing was on screen. In the second case it is already booted
        // and must not be reloaded: a reload is what loses the keys.
        val booted = PageHost.alive
        webView = PageHost.attach(this)
        setContentView(webView)

        val fragment = intent?.takeIf { it.data?.host == APP_HOST }?.data?.fragment
        if (booted) {
            if (!fragment.isNullOrEmpty()) PageHost.hashChange(fragment)
        } else {
            PageHost.load(fragment)
        }
    }

    // Someone who turns Tor mode on and only then starts Orbot would other-
    // wise never hear the port, since a status broadcast is only trusted in
    // the window after we ask. Coming back to the app asks again.
    override fun onResume() {
        super.onResume()
        if (torEnabled()) OrbotStatus.ask(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val fragment = intent.data?.takeIf { it.host == APP_HOST }?.fragment ?: return
        // The page is live: hand the invite over as a hash change, which the
        // app treats exactly like a fresh boot with a fragment.
        PageHost.hashChange(fragment)
    }

    override fun onDestroy() {
        torSilenceCheck?.let { PageHost.cancel(it) }
        torSilenceCheck = null
        // A configuration change destroys this activity and immediately builds
        // another one, so the page is kept for the replacement regardless of
        // the switch.
        val keep = isChangingConfigurations || PageHost.shouldKeepAlive(this)
        PageHost.detachFrom(this, keep)
        if (!keep) {
            LocationService.stop(this)
            OrbotStatus.stop(this)
        }
        super.onDestroy()
    }

    // ------------------------------------------------------------- location

    fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    fun askGeolocation(origin: String, callback: GeolocationPermissions.Callback) {
        pendingGeoCallback = origin to callback
        requestLocationPermission()
    }

    private fun requestLocationPermission() {
        locationPermission.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ),
        )
    }

    // Called from the bridge when the page turns sharing on. Must run while
    // the app is foreground: a location foreground service cannot start from
    // the background without the background location permission we refuse to
    // ask for.
    fun startShareFlow() {
        if (hasLocationPermission()) {
            startShareService()
        } else {
            pendingShareStart = true
            requestLocationPermission()
        }
    }

    fun requestNotifyPermissionIfNeeded() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun startShareService() {
        requestNotifyPermissionIfNeeded()
        try {
            LocationService.start(this)
        } catch (e: SecurityException) {
            sendFixError("location service refused: ${e.message}", 2)
        } catch (e: IllegalStateException) {
            sendFixError("location service refused: ${e.message}", 2)
        }
    }

    private fun sendFixError(message: String, code: Int) {
        PageHost.deliverFix(
            org.json.JSONObject().put("error", message).put("code", code).toString(),
        )
    }

    // ------------------------------------------------------------------ tor

    fun torSupported(): Boolean = WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)

    fun torEnabled(): Boolean =
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(PREF_TOR, false)

    fun setTorEnabled(on: Boolean) {
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(PREF_TOR, on).apply()
        applyTorPref()
        // Orbot only answers the port question with Power User Mode on. If it
        // says nothing at all, the user is about to watch traffic stall on
        // the default port with no explanation; give them the one that helps.
        if (on) {
            val asked = android.os.SystemClock.elapsedRealtime()
            torSilenceCheck?.let { PageHost.cancel(it) }
            val check = Runnable {
                if (torEnabled() && OrbotStatus.lastAnswerAt < asked) {
                    PageHost.notice(
                        "Orbot did not answer. If sharing stalls, turn on Power User Mode in " +
                            "Orbot's settings, or use Orbot's per-app VPN mode instead.",
                    )
                }
            }
            torSilenceCheck = check
            PageHost.post(check, 8000)
        }
    }

    // The pending Orbot-silence warning, so activity teardown (including the
    // recreation a font-scale change causes) cancels it instead of leaving a
    // Runnable holding a dead page for eight seconds.
    private var torSilenceCheck: Runnable? = null

    // All WebView traffic through Orbot's SOCKS port, with no direct fallback:
    // if Orbot is not listening, requests fail instead of leaking. socks5://
    // is explicit because it matters: Chromium resolves hostnames proxy-side
    // for SOCKS5, so DNS rides through Tor too. The override only governs
    // connections opened after it lands, so the listener reloads the page and
    // strands whatever the old config had pooled.
    //
    // The port comes from Orbot itself when Orbot answers; 9050 is the
    // default and the fallback. Watching for the answer means a user who
    // moved Orbot's port gets working Tor instead of a share that fails
    // closed for a reason nothing on screen could explain.
    private fun applyTorPref() {
        if (!torSupported()) return
        val controller = ProxyController.getInstance()
        val reload = Runnable { PageHost.reload() }
        val executor = ContextCompat.getMainExecutor(this)
        if (torEnabled()) {
            OrbotStatus.start(this) { applyTorPref() }
            val config = ProxyConfig.Builder()
                .addProxyRule("socks5://127.0.0.1:${OrbotStatus.socksPort}")
                .build()
            controller.setProxyOverride(config, executor, reload)
        } else {
            OrbotStatus.stop(this)
            controller.clearProxyOverride(executor, reload)
        }
    }
}
