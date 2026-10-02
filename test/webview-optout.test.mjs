// The README promises no analytics and no third party requests. On a phone
// with Google's WebView, Safe Browsing lookups and WebView usage metrics are
// on unless the app opts out, so the manifest and PageHost both say no.
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

const read = (rel) => readFileSync(new URL(`../${rel}`, import.meta.url), "utf8");

test("the manifest opts the WebView out of Safe Browsing and metrics, inside <application>", () => {
  const manifest = read("android/app/src/main/AndroidManifest.xml");
  const app = manifest.slice(manifest.indexOf("<application"), manifest.indexOf("</application>"));
  assert.match(app, /<meta-data\s+android:name="android\.webkit\.WebView\.EnableSafeBrowsing"\s+android:value="false"\s*\/>/);
  assert.match(app, /<meta-data\s+android:name="android\.webkit\.WebView\.MetricsOptOut"\s+android:value="true"\s*\/>/);
});

test("PageHost turns Safe Browsing off on the WebView it builds", () => {
  const host = read("android/app/src/main/kotlin/app/starlingmap/PageHost.kt");
  const settings = host.slice(host.indexOf("with(view.settings) {"), host.indexOf("view.setRendererPriorityPolicy"));
  assert.match(settings, /\bsafeBrowsingEnabled = false\b/);
});
