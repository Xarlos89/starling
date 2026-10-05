package app.starlingmap

import android.webkit.PermissionRequest

// WebView spells the page's origin as a URL, with the trailing slash.
object AssetOrigin {
    private const val BASE = "https://${MainActivity.ASSET_HOST}"

    fun isOurs(origin: String): Boolean = origin == BASE || origin == "$BASE/"

    fun cameraAllowed(origin: String, resources: Array<String>): Boolean =
        isOurs(origin) && PermissionRequest.RESOURCE_VIDEO_CAPTURE in resources
}
