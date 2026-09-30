# Android app

A hand-written Kotlin WebView wrapper around the same `app/` code that runs
at starlingmap.app. Capacitor, Cordova, Trusted Web Activity, Google Play
Services: none of it is in here. The web app and the Android app share one
codebase in `app/`; the wrapper adds native capability where the web
platform cannot reach (background location, hardware-backed biometrics, a
panic-wipe hook, Orbot integration) and otherwise gets out of the way.

## Building

You need an Android SDK (platform 36, build-tools 36) with `ANDROID_HOME`
pointing at it, or an `android/local.properties` with `sdk.dir=`; the repo
ships neither. Then, from the repo root:

```
npm ci
bash tools/sync-vendor.sh
cd android
./gradlew assembleDebug     # unsigned debug build, installable directly
./gradlew assembleRelease   # unsigned release build (see Signing below)
```

`npm ci` installs the pinned Leaflet dependency from `package-lock.json`.
`tools/sync-vendor.sh` copies the unminified `leaflet-src.js`, `leaflet.css`,
and marker images from `node_modules/leaflet` into `app/vendor/leaflet`,
which is gitignored and not committed. The Gradle build then copies
`app/**` (minus `sw.js`, which the wrapper never registers) into the app's
assets at build time, so `app/` stays the single source of truth for both
the website and the Android build. Run `npm ci` and the sync script again
any time `app/` or the Leaflet version changes before rebuilding.

## Releases

`tools/release-android.sh` is the only thing that produces a signed build.
It runs the same `npm ci` / sync-vendor / `assembleRelease` steps, then
signs the resulting unsigned APK and AAB with the local upload keystore
using `apksigner`, and finally:

- uploads the signed AAB to Google Play (Play App Signing re-signs it for
  distribution; see `docs/play-listing.md` for the signing key facts), and
- attaches the signed APK to a GitHub release, so anyone can download and
  verify a Starling build without going through either store.

Signing is deliberately kept out of Gradle and out of CI. The keystore
lives at `~/keys/starling-upload.jks` outside the repo, and its password is
in the system keyring, never in a file or an environment variable checked
into anything. CI only ever produces unsigned build artifacts.

## Reproducibility

The release build is configured to be a deterministic, unsigned artifact
that anyone can rebuild and compare byte-for-byte against what Cole
publishes:

- `vcsInfo.include = false`, so the build does not embed a git commit hash
  that would differ between two otherwise-identical checkouts.
- PNG cruncher disabled, so resource compression does not vary by machine
  or AAPT2 version quirks.
- `minifyEnabled false`, no R8/ProGuard shrinking, no baseline profiles. A
  smaller APK is not worth losing the ability for a third party to read the
  code straight out of the build.
- Dependency locking on (`package-lock.json` for the JS side, Gradle's
  dependency locking for the native side), so a rebuild months later pulls
  the exact versions we built with, not "whatever is current."
- Zip entry timestamps are zeroed automatically by AGP's reproducible build
  support, so the same inputs produce the same output bytes regardless of
  when the build ran.

**To verify a build yourself:** clone the repo at the tag matching a
published release, run the build steps above, and diff your resulting
unsigned APK against Cole's signed one after stripping the signature block
(`apksigner` can extract the pre-signature APK, or use
`unzip -l`/content hash comparison on everything outside `META-INF/`). This
is the same process F-Droid's own reproducible-builds verification runs,
and it runs it on every Starling version it builds: the APK F-Droid ships
is the developer-signed one, released only because F-Droid's own build
matched it.

## PanicKit

The app registers a PanicKit responder (`info.guardianproject.panic`).
Pairing with a trigger app like Ripple happens through the visible
`ACTION_CONNECT` flow, the same as connecting any other panic-response app;
Starling shows what it will do and asks nothing else. Once connected,
receiving `ACTION_TRIGGER` from that same paired app wipes immediately, with
no confirmation dialog, because the entire point of a panic trigger is that
it has to work without a second decision under pressure. The wipe stops a
running share, deletes the Keystore wrap key and the app's notification
channels, then hands the rest to the system's clear-data path, which removes
all app data, WebView storage and cookies included, and kills the process.
The in-app panic wipe and the duress passcode run the same code, from the
bridge thread, so it touches no view. The responder
verifies the sender package against the connected trigger app; an unpaired
or spoofed sender is ignored.

## Orbot

Two independent paths, both supported:

- **Per-app VPN mode.** Orbot's per-app proxying works with zero code on
  Starling's side. Turn it on for Starling in Orbot and its traffic routes
  through Tor like any other app's.
- **In-app SOCKS toggle.** Settings has a proxy toggle that routes requests
  through Orbot's SOCKS port using `androidx.webkit`'s
  `ProxyController` (SOCKS5, so hostname resolution happens proxy-side and
  DNS rides through Tor too), feature-detected at runtime so it silently
  does nothing on WebView versions that lack `PROXY_OVERRIDE` support
  instead of breaking. This path needs Orbot's **Power User Mode** turned
  on, since Orbot only exposes its SOCKS port to other apps in that mode.
  With the toggle on, location fixes come from GPS only: the network
  provider works by sending nearby wifi and cell identifiers to an
  off-device lookup service, which is the kind of side channel the toggle
  exists to avoid.

The port is not assumed. Turning the toggle on registers a receiver and asks
Orbot for its status (`ACTION_START` with our package name); Orbot answers
with a broadcast carrying the live SOCKS port, and the proxy is re-applied if
it differs from the 9050 default. That receiver has to be registered
`RECEIVER_EXPORTED`, since Orbot is a separate app, and the manifest needs a
`<queries>` entry for `org.torproject.android` or the request is dropped
silently on Android 11 and up. NetCipher used to be the polite way to do
this; it has been unmaintained since 2020 and never actually read the port
extra it declared a constant for, so this talks to Orbot directly.

Honest limits of the toggle, so nobody has to discover them the hard way:
if Orbot never answers, 9050 stands, and a wrong port fails closed rather
than leaking, but sharing stops until the ports agree. It covers the WebView
only. A link that leaves the app (say a
repo link in settings) opens in the system browser, which the toggle does
not proxy. And nothing binds 127.0.0.1:9050 to Orbot specifically; another
local app could squat the port, and while it would only ever see TLS to
the relay carrying E2EE payloads, it would see that much. Per-app VPN mode
has none of these caveats, which is why both paths are supported.

## Custom relay

Both the web app and the Android wrapper have a relay address setting.
Default is `https://starlingmap.app`. Anyone running their own relay
(`relay/` in this repo, deployed with `relay/deploy.sh`) can point their
client at it instead. This is the mechanism that keeps Starling out of
F-Droid's "tethered to a specific server" anti-feature category: using our
relay is a default, not a requirement.

## Your own server

Settings, Sharing, "Your own server" takes an https address and, while a share
runs, posts the phone's own position to it in OwnTracks' HTTP format
(`_type: location`, `lat`, `lon`, `tst`, `acc`, `alt`, `vel`, `cog`, `batt`), at
most every 15 seconds. `Forward.kt` sends it, not the page: a page fetch would
need CORS, which these servers do not answer, and the page can be frozen. A key
in the query (`?api_key=`, `?token=`) works, which covers colota-forwarder,
Reitti, Dawarich and Home Assistant's OwnTracks webhook. Nothing goes out while
Tor mode is on.

To test it on an emulator, debug builds trust user-added CAs
(`debug-overrides` in `network_security_config.xml`, which release builds
ignore). Make a throwaway CA and a certificate for `IP:10.0.2.2`, copy the CA
to `/data/misc/user/0/cacerts-added/<subject_hash_old>.0` after `adb root`, run
an https receiver on the host at port 8443, and set
`https://10.0.2.2:8443/owntracks?api_key=test`.

## GrapheneOS and de-googled Android

The app is built to run without Google anything, which makes GrapheneOS a
first-class target rather than an afterthought:

- No Google Play Services, Firebase, or push dependency. Location comes from
  the plain `LocationManager`, so it works with no sandboxed Play services
  installed.
- The WebView requirement is satisfied by GrapheneOS's own Vanadium, which
  tracks current Chromium; there is nothing version-fragile in the app's use
  of WebCrypto, IndexedDB, or ES modules.
- Install paths that do not touch Google: F-Droid
  (<https://f-droid.org/packages/app.starlingmap/>, the same developer-signed
  APK after its reproducible-build check), or add it to Tern
  (<https://github.com/munzzyy/tern>), which follows the GitHub releases
  page; every release carries a stable `starling.apk` asset name for that.
- GrapheneOS's per-app Network and Sensors toggles degrade the app the way
  you would hope: no network means the poller backs off and the Off-grid
  basemap still renders; denying location just means nothing to share.
- Release verification happens on the no-GMS AOSP emulator image
  (`system-images;android-36;default`), which is the closest stand-in for a
  de-googled device that automated testing can get.

## Sharing with the screen off

Everything that seals and posts a position runs in the page, and a WebView
page is not built to be a background worker. Three things stood between a
locked phone and its circle, and each one has its own fix.

### Chromium freezes hidden pages

A page that has been hidden for a while is frozen: five minutes in WebView
133, one minute from Chromium 143 on. A fix the service pushes still reaches a
frozen page, because `evaluateJavascript` runs regardless, but everything a
post needs after that (IndexedDB, WebCrypto, `fetch`, timers) waits until the
page is visible again. This was munzzyy/starling#6: phones locked and put down
went quiet a minute or five later and stayed quiet until somebody opened the
app, at which point the whole backlog went out at once. The only thing that
thaws a page is being visible, so while a share runs `PageHost` calls
`dispatchWindowVisibilityChanged(View.VISIBLE)` on the WebView, and a second
later `GONE` again. Chromium unfreezes the page and starts its freeze clock
over; nothing is drawn, because the real window is still hidden. It happens
when the page's own `freeze` event says it was just frozen, and as a fallback
when the page stops answering: every push is answered from a task of the
page's own (a `MessageChannel` message), which only runs if the page is really
running. The page asks `StarlingNative.windowShown()` instead of trusting
`document.visibilityState` wherever "visible" has to mean a person is looking
(notifications, the autolock, the poll pace), since for that second it reads
"visible".

### A swiped app has no window to be visible in

Once a WebView has been attached to a window, Chromium only counts it visible
while it is attached to one, so a page carried past a swipe could never be
thawed. With "keep sharing when the app is closed" on, the page moves into a
window of its own when the activity goes: a `Presentation` on a private
virtual display the app creates and owns. It needs no permission, its root
view is `GONE` so it never draws or gets a surface, and no other app can see a
private display. Opening the app moves the page back and releases the display.
If a phone refuses the presentation, the page is left with no window, and the
watchdog below ends the share out loud when the page freezes.

### The CPU sleeps between fixes

Android holds a wake lock while it hands a fix over and lets go when the
listener returns, which is before the page has started on it. The service now
holds one partial wake lock during a share, with a 30 second ceiling per fix,
and the page releases it as soon as its post settles. A phone lying still
wakes about every 15 seconds for as long as it takes to seal and post, usually
well under a second.

### Around the same failure


- If the page stops answering for three minutes and waking it does nothing,
  the share ends with a record and a notice (route `stalled`), rather than
  leaving a notification that says "sharing" over a page that posts nothing.
- If Android stops the service itself, which it does about a minute after the
  app leaves the screen when battery use is set to Restricted, the page is
  told, the share ends with a record (route `system`), and opening the app
  puts it back on.
- A share that ends while nobody is looking wakes the page once more, so its
  goodbye still reaches the circle before the page is let go.
- Location switched off shows on the notification and on the line under your
  name, and the last position is not sent again as if it were live.
- A minute with no fix at all wakes the phone and sends the last position
  again, and five minutes of silence with location on renews the location
  requests.
- A share start from a page whose window is not showing waits for the window
  instead of failing, since Android refuses to start a location service from
  the background.
- Back, during a share, leaves the app the way Home does. Android finishes the
  activity on Back unless it was opened from the home screen, and a Starling
  opened from its own notification used to close, taking the share with it.

### The battery exemption

AOSP already exempts a location foreground service from Doze's network and
wake lock limits, and the emulator measurements showed no difference with or
without the exemption. Phones add their own battery management on top, though,
and the open source trackers people compare this to (Traccar, GPSLogger,
OwnTracks) all ask for it, so the app does too: once, when a share starts on a
phone that is optimizing Starling, with its own explanation before the system
dialog, and "Not now" is final. Settings shows the current state under
Sharing, with the one button that changes it. "Restricted" cannot be fixed by
the dialog, so both the card and Settings send the person to the app's own
settings page for it. This is what `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` is
for; `WAKE_LOCK` is for the wake lock above. Neither grants access to
anything.

### The sharing report

Settings, Sharing, "Copy sharing report" puts a plain text summary on the
clipboard for a bug report: app, Android and WebView versions, the device
model, permission and battery states, Battery Saver, idle state, standby
bucket, and counts and ages for fixes, posts, freezes and wake-ups. It is
built from a fixed list of fields in `sharehealth.js`, each held to the shape
it should have, so it cannot carry a position, a key, a name, a place, a
circle or a relay address. The app never sends it anywhere.

### Testing it

The freeze only shows after the delay, so a test shorter than five minutes (or
one, on a newer WebView) passes whether or not any of this works. On a
debuggable build or a userdebug image, WebView reads flags from
`/data/local/tmp/webview-command-line`, and this shortens the delay to a
minute:

```
adb shell 'echo "_ --enable-features=stop-in-background:DelayForBackgroundTabFreezingMills/60000" > /data/local/tmp/webview-command-line'
```

Force-stop the app afterwards so the WebView reads it. The page's `freeze` and
`resume` events, and the counts in the sharing report, show each freeze and
each wake-up.

## Known WebView-specific limits

- **No service worker.** `sw.js` is never registered inside the wrapper.
  Assets are bundled into the APK and served through
  `WebViewAssetLoader` at `https://appassets.androidplatform.net/`, which is
  a secure context (WebCrypto and IndexedDB work normally), so the app does
  not need offline caching the way the installable web PWA does; the app
  itself is already the offline copy.
- **Biometric unlock uses Android Keystore, not WebAuthn PRF.** The web app's
  biometric app-lock path depends on the WebAuthn PRF extension, which is
  not available inside a plain WebView (`navigator.credentials` is absent).
  The wrapper skips WebAuthn entirely and adds a native bridge instead: an
  AES-GCM key in the Android Keystore with
  `setUserAuthenticationRequired(true)`, unlocked through `BiometricPrompt`
  with a `CryptoObject`. It is hardware-gated the same way WebAuthn PRF is,
  and Android invalidates the key automatically if the user's biometric
  enrollment changes (new fingerprint added, face re-enrolled), which is the
  correct behavior since a changed enrollment means a different set of
  biometrics can now unlock the vault. `lock.js` treats this as a third
  record type behind the same interface the passcode and WebAuthn paths use,
  so the crypto and storage logic do not fork per platform.
- **IndexedDB eviction.** Android can evict an app's storage under disk
  pressure the same as it can for any app; a WebView-hosted IndexedDB is not
  automatically exempt just because it is native-adjacent. The app calls
  `navigator.storage.persist()` on startup to ask the OS to treat its
  storage as non-evictable, which Android generally honors for apps with
  meaningful usage, but this is a request, not a guarantee, and users should
  not treat on-device storage as more durable than it is.
