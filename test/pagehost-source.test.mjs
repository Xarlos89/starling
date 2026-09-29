// PageHost and the Tor proxy wiring are Kotlin, which nothing in this suite can
// run, and both broke "keep sharing when the app is closed" on real phones
// while the emulator checks passed (munzzyy/starling#6). These pin the two
// rules by reading the source, because the failure is silent: a share that
// looks on and posts nothing.
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

const kt = (name) =>
  readFileSync(new URL(`../android/app/src/main/kotlin/app/starlingmap/${name}`, import.meta.url), "utf8");

test("pushes into the page go through the main handler, not View.post", () => {
  const src = kt("PageHost.kt");
  assert.doesNotMatch(src, /\bv\.post\s*\{/, "View.post queues until the view has a window again");
  assert.match(src, /main\.post\s*\{[^}]*evaluateJavascript/);
});

test("reopening the app does not re-apply an unchanged proxy, which reloads the page", () => {
  const src = kt("MainActivity.kt");
  assert.match(src, /PageHost\.proxyApplied == rule\) return/);
  assert.match(src, /PageHost\.proxyApplied == "direct"\) return/);
});

test("a dead renderer is handled instead of taking the app and the share down", () => {
  const src = kt("PageHost.kt");
  assert.match(src, /override fun onRenderProcessGone\([\s\S]*?return true\s*\}/);
  assert.match(src, /LocationService\.endShare\(app, "renderer"\)/);
});

test("a still phone still wakes the page: a listener with no distance filter", () => {
  const src = kt("LocationService.kt");
  assert.match(src, /requestLocationUpdates\(provider, HEARTBEAT_MS, 0f, heartbeat, mainLooper\)/);
  assert.match(src, /removeUpdates\(heartbeat\)/);
});
