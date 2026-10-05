package app.starlingmap

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssetOriginTest {
    private val video = arrayOf("android.webkit.resource.VIDEO_CAPTURE")
    private val audio = arrayOf("android.webkit.resource.AUDIO_CAPTURE")

    @Test fun theOriginAsWebViewSpellsItIsOurs() =
        assertTrue(AssetOrigin.isOurs("https://appassets.androidplatform.net/"))

    @Test fun theBareOriginIsOursToo() =
        assertTrue(AssetOrigin.isOurs("https://appassets.androidplatform.net"))

    @Test fun lookalikesAreNot() {
        for (o in listOf(
            "https://appassets.androidplatform.net.evil.example/",
            "https://evil.example/https://appassets.androidplatform.net/",
            "http://appassets.androidplatform.net/",
            "https://appassets.androidplatform.net/index.html",
            "https://appassets.androidplatform.net//",
            "https://APPASSETS.androidplatform.net/",
            "file:///",
            "null",
            "",
        )) assertFalse(o, AssetOrigin.isOurs(o))
    }

    @Test fun theScannerGetsTheCamera() =
        assertTrue(AssetOrigin.cameraAllowed("https://appassets.androidplatform.net/", video))

    @Test fun videoAlongsideAudioStillCountsAsVideo() =
        assertTrue(AssetOrigin.cameraAllowed("https://appassets.androidplatform.net/", audio + video))

    @Test fun audioAloneIsRefused() =
        assertFalse(AssetOrigin.cameraAllowed("https://appassets.androidplatform.net/", audio))

    @Test fun anotherOriginIsRefusedTheCamera() =
        assertFalse(AssetOrigin.cameraAllowed("https://starlingmap.app/", video))
}
