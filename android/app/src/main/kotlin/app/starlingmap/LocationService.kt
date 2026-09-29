package app.starlingmap

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.core.content.ContextCompat
import org.json.JSONObject

// Keeps location flowing while the screen is off or the app is backgrounded.
// Runs only between an explicit start from the page (user turned sharing on,
// app in the foreground, permission already granted) and the matching stop.
// While-in-use only: the app never requests background location permission.
//
// Swiping the task away ends the share, unless the person turned on "keep
// sharing when the app is closed". With that on, PageHost holds the page past
// the window, so there is still something alive to seal each position, and
// this service stays up and keeps feeding it.
class LocationService : Service(), LocationListener {

    companion object {
        // Also read by the panic wipe, which deletes the channel.
        const val CHANNEL = "share"
        private const val NOTIF_ID = 1
        private const val ACTION_STOP = "app.starlingmap.STOP_SHARE"
        private const val MIN_TIME_MS = 3000L
        private const val MIN_DIST_M = 5f
        // A phone lying still passes no distance filter, so without this the
        // page hears nothing and, with no window, its own send timer barely
        // runs: the share goes quiet and looks stopped to everyone watching.
        // This listener has no distance filter and wakes the page at least
        // once per send interval.
        private const val HEARTBEAT_MS = 15000L

        // The activity plants a sink to push fixes into the page. Static is
        // fine: one process, one WebView.
        @Volatile
        var sink: ((String) -> Unit)? = null

        // Read by PageHost to decide whether a page with no window still has a
        // job. Set here rather than inferred from the notification, because the
        // question gets asked during teardown.
        @Volatile
        var running = false

        fun start(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, LocationService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, LocationService::class.java))
        }

        // Ends a share for a reason the person did not choose, leaving the same
        // trace a swipe or the notification's Stop leaves.
        fun endShare(ctx: Context, route: String) {
            recordEnded(ctx, route)
            stop(ctx)
        }

        // The record goes down BEFORE the notification: that notification can
        // be swiped away with no unlock at all below Android 12, so it is the
        // record, not the notification, that has to survive. It lives in the
        // same private prefs file the whole app data directory does, so a panic
        // wipe's clearApplicationUserData takes it with everything else.
        private fun recordEnded(ctx: Context, route: String) {
            ctx.getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE).edit()
                .putString(MainActivity.PREF_STOP_ROUTE, route)
                .putLong(MainActivity.PREF_STOP_TS, System.currentTimeMillis())
                .apply()
            Events.post(
                ctx,
                ctx.getString(R.string.notif_swiped_title),
                ctx.getString(R.string.notif_swiped_text),
                "share-ended",
            )
        }
    }

    private var watching = false

    // Spelled out rather than a lambda: on API 29 the other callbacks are not
    // default methods yet, and the platform calls them.
    private val heartbeat = object : LocationListener {
        override fun onLocationChanged(location: Location) = this@LocationService.onLocationChanged(location)

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
        }

        override fun onProviderEnabled(provider: String) {
        }

        override fun onProviderDisabled(provider: String) {
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        running = true
        if (intent?.action == ACTION_STOP) {
            // A user action, not a failure: the page turns sharing off cleanly.
            sink?.invoke(JSONObject().put("stopped", true).toString())
            postShareEnded("notif")
            stopSelf()
            return START_NOT_STICKY
        }
        // startForeground itself throws if location permission vanished between
        // the activity's check and this callback; that stack is the framework's,
        // not the activity's try/catch, so it must be handled here.
        try {
            startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } catch (e: Exception) {
            sink?.invoke(JSONObject().put("error", "location service refused: ${e.message}").put("code", 2).toString())
            stopSelf()
            return START_NOT_STICKY
        }
        startWatching()
        return START_NOT_STICKY
    }

    private fun startWatching() {
        if (watching) return
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        // The network provider resolves position by shipping nearby wifi and
        // cell identifiers to an off-device lookup service. With Tor mode on,
        // the user has asked for exactly not that, so fixes come from GPS
        // alone even when that means slower or no indoor lock.
        val torOn = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE)
            .getBoolean(MainActivity.PREF_TOR, false)
        val providers =
            if (torOn) listOf(LocationManager.GPS_PROVIDER)
            else listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        var any = false
        for (provider in providers) {
            if (!lm.allProviders.contains(provider)) continue
            try {
                lm.requestLocationUpdates(provider, MIN_TIME_MS, MIN_DIST_M, this, mainLooper)
                lm.requestLocationUpdates(provider, HEARTBEAT_MS, 0f, heartbeat, mainLooper)
                any = true
            } catch (e: SecurityException) {
                // permission revoked between the page's start call and here
            }
        }
        if (!any) {
            sink?.invoke(JSONObject().put("error", "no location provider").put("code", 2).toString())
            stopSelf()
            return
        }
        watching = true
    }

    override fun onLocationChanged(location: Location) {
        val fix = JSONObject()
            .put("lat", location.latitude)
            .put("lon", location.longitude)
            .put("ts", location.time)
        if (location.hasAccuracy()) fix.put("acc", location.accuracy.toDouble())
        if (location.hasSpeed()) fix.put("spd", location.speed.toDouble())
        if (location.hasBearing()) fix.put("hdg", location.bearing.toDouble())
        sink?.invoke(fix.toString())
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
    }

    override fun onProviderEnabled(provider: String) {
    }

    override fun onProviderDisabled(provider: String) {
    }

    // Swiping the app out of recents kills the page that encrypts and posts
    // positions, so the share is dead from that moment no matter what this
    // service does. It always ended the share here; now it also says so,
    // because a share that ends in silence looks like a working one.
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (PageHost.keepSharing(this) && PageHost.alive) {
            // The window is gone and the share is not. Nothing to write down
            // and nothing to stop: the page is still here, still holding the
            // keys, and the fixes below still reach it.
            super.onTaskRemoved(rootIntent)
            return
        }
        postShareEnded("swipe")
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    // Shared with the Stop-button branch so both ways of ending a share leave
    // the same trace.
    private fun postShareEnded(route: String) = recordEnded(this, route)

    override fun onDestroy() {
        running = false
        // A share that ends with nothing on screen takes the page with it. Not
        // instantly: its stop path still has a departure to get onto the relay.
        PageHost.releaseSoon()
        if (watching) {
            val lm = getSystemService(LOCATION_SERVICE) as LocationManager
            lm.removeUpdates(this)
            lm.removeUpdates(heartbeat)
            watching = false
        }
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, LocationService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stopAction = Notification.Action.Builder(null, getString(R.string.notif_stop), stop).apply {
            // Android 12+ only, see THREAT-MODEL.md for the pre-12 gap.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setAuthenticationRequired(true)
        }.build()
        // Same strings both versions: already generic, nothing to redact here.
        val publicVersion = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_starling)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_starling)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setContentIntent(open)
            .setOngoing(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .addAction(stopAction)
            .build()
    }
}
