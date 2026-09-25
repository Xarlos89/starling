// Two honest members re-keying the same generation at the same time.
//
// applyRekey accepts exactly g+1, and a device that has moved to a new
// generation stops polling the channel it left (main.js doRekey and
// adoptRekey both call teardownNet). So two rotators working from the same
// generation each open a g+1 of their own, neither ever sees the other's
// wraps, and every other member goes with whichever re-key it processed
// first. The circle ends up on two channels and nobody is told: the rotators
// clear rosterPending themselves, and a follower's roster hash agrees with
// the rotator it followed because the pinned roster carries over unchanged.
//
// The daily timer's per-device offset (main.js rekeyDue) keeps rotators apart
// only while devices are online at the due moment. Sharing is live-when-open
// and iOS has no background, so after a quiet day every device is past due at
// once. Manual "new keys now", admissions and removals race the same way.
//
// The two checks against main.js describe the behaviour a fix should give and
// fail on main today. They assume one tie-break rule, stated in winnerOf; any
// deterministic rule works, and if the fix picks another, change winnerOf and
// the role assignment in each check. The two protocol checks pass today: one
// is the precondition, the other shows the fix needs no new crypto, because
// every rotator wraps to every retained member.
import test from "node:test";
import assert from "node:assert/strict";

import { installDom, loadApp, settle } from "./dom-harness.mjs";

const harness = installDom();
const { internals, api } = await loadApp(harness);
const state = internals.state;

// Same stand-in main-guards uses: boot refuses to run without IndexedDB, and
// store.js falls back to memory when the database will not open.
globalThis.indexedDB ??= {
  open() {
    throw new Error("no indexeddb in the harness");
  },
  deleteDatabase() {
    const req = {};
    setTimeout(() => req.onsuccess?.(), 0);
    return req;
  },
};

const { openGeneration, buildRekey, applyRekey } = await import("../app/js/rekey.js");
const { epochAt, EPOCH_MS } = await import("../app/js/ratchet.js");
const { generateIdentity, newSeed, sealMessage, buildPost } = await import("../app/js/crypto.js");
const { b64uEncode } = await import("../app/js/wire.js");
const { dbSet, wipeAll } = await import("../app/js/store.js");

test.after(() => harness.stopTimers());

// Of two re-keys for the same generation, the one from the lower rotator
// member id wins.
const winnerOf = (a, b) => (a < b ? a : b);

// How long a check waits for the device to converge: one normal poll
// (net.js POLL_MS, 10 s) plus margin.
const CONVERGE_MS = 12_000;

const ok = (obj) => ({ ok: true, status: 200, json: async () => obj });
const rec = (id) => ({ memberId: id.memberId, epk: id.epk });
const channelOf = async (built) =>
  (await openGeneration({ seed: new Uint8Array(built.seed), g: built.g, e0: built.e0 })).channelId;

async function waitFor(cond, ms) {
  const end = Date.now() + ms;
  while (Date.now() < end) {
    if (cond()) return true;
    await settle(50);
  }
  return cond();
}

async function sortedIdentities(n) {
  const ids = [];
  for (let i = 0; i < n; i++) ids.push(await generateIdentity());
  return ids.sort((a, b) => (a.memberId < b.memberId ? -1 : 1));
}

// This device as `self`, in generation 0 of a circle with `peers`, every one
// of them pinned and in the generation's founding roster. Each peer gets its
// own copy of the same generation, to build its re-key from.
async function circleWith(self, peers) {
  await wipeAll();
  state.circles = [];
  state.chainWiped = null;
  state.locked = false;
  state.lock = null;
  state.vaultKey = null;
  state.demo = false;
  state.chainDestroyed = false;
  state.sharing = false;
  state.pinned = new Map();
  state.keyChanges.clear();
  state.rosterPending = null;
  state.invite = null;
  state.joining = null;
  state.joinRequests = [];
  state.missedRekey = false;
  state.identity = self;
  await dbSet("identity", self);
  const seed = newSeed();
  const e0 = epochAt(Date.now());
  state.gen = await openGeneration({ seed: new Uint8Array(seed), g: 0, e0 });
  state.gen.at = Date.now();
  const gens = [];
  for (const p of peers) {
    assert.ok(await internals.addPinned({ alg: p.alg, pk: b64uEncode(p.pk), epk: b64uEncode(p.epk), name: "Peer" }));
    gens.push(await openGeneration({ seed: new Uint8Array(seed), g: 0, e0 }));
  }
  state.genRoster = new Set(state.pinned.keys());
  window.__starlingErrors.length = 0;
  return gens;
}

// A rotator's re-key posts as the relay serves them: one entry for the
// member, one point per wrap, sealed and signed on the old channel exactly as
// net.js createSender does it.
async function servedRekey(identity, gen, built) {
  const points = [];
  let ts = Date.now();
  for (const fields of built.posts) {
    ts += 1;
    const e = await gen.ratchet.currentEpoch(ts);
    const key = await gen.ratchet.keyFor(e, identity.memberId, ts);
    const sealed = await sealMessage(key, gen.channelId, identity.memberId, e, ts, { v: 2, ts, ...fields });
    const post = await buildPost(identity, gen.channelId, e, sealed, ts);
    points.push({ e: post.e, ts: post.ts, srv: post.ts, n: post.n, c: post.c, sig: post.sig });
  }
  return { m: identity.memberId, alg: identity.alg, pk: b64uEncode(identity.pk), epk: b64uEncode(identity.epk), points };
}

// A relay that serves whatever `view` says a channel holds on its nth read,
// one entry per member ordered by member id like relay/src/index.js, and
// accepts every post.
function relay(view) {
  const reads = new Map();
  harness.onFetch(async (url, init) => {
    const m = /\/api\/v2\/f\/([0-9a-f]{32})(\/loc)?/.exec(url);
    if (!m) return null;
    const [, chan, loc] = m;
    if (loc || init?.method === "POST") return ok({ ok: true, now: Date.now() });
    const n = (reads.get(chan) || 0) + 1;
    reads.set(chan, n);
    const members = [...(view(chan, n) || [])].sort((a, b) => (a.m < b.m ? -1 : 1));
    return ok({ now: Date.now(), members });
  });
  return reads;
}

// ------------------------------------------------------------- against main.js

test("a follower that adopted the losing re-key moves to the winner's generation", async () => {
  const [y, z, x] = await sortedIdentities(3);
  assert.equal(winnerOf(y.memberId, z.memberId), y.memberId, "Y wins, Z loses");
  const [yGen, zGen] = await circleWith(x, [y, z]);
  const oldChannel = state.gen.channelId;

  const now = Date.now();
  const byY = await buildRekey({ identity: y, gen: yGen, recipients: [rec(x), rec(z)], now });
  const byZ = await buildRekey({ identity: z, gen: zGen, recipients: [rec(x), rec(y)], now });
  const yEntry = await servedRekey(y, yGen, byY);
  const zEntry = await servedRekey(z, zGen, byZ);
  const yChannel = await channelOf(byY);
  const zChannel = await channelOf(byZ);

  // The first read of the old channel comes before Y's post has landed, so
  // this device sees only Z's. Every later read holds both, as the relay
  // would for the next 24 hours.
  const reads = relay((chan, n) => (chan === oldChannel ? (n === 1 ? [zEntry] : [yEntry, zEntry]) : []));
  await internals.enterCircle();

  // Read off the relay rather than off state: a fixed device may already
  // have moved on by the time a check looks.
  assert.ok(await waitFor(() => reads.has(zChannel), 3000), "the device first followed Z, the loser, to Z's channel");

  const converged = await waitFor(() => state.gen?.channelId === yChannel, CONVERGE_MS);
  harness.onFetch(null);
  assert.ok(converged, "the device ends on the winner's channel, with Y and everyone else who followed Y");
});

test("a rotator whose re-key loses the race moves to the winner's generation", async () => {
  const [y, x, z] = await sortedIdentities(3);
  assert.equal(winnerOf(y.memberId, x.memberId), y.memberId, "Y wins, this device loses");
  const [yGen] = await circleWith(x, [y, z]);
  const oldChannel = state.gen.channelId;

  const byY = await buildRekey({ identity: y, gen: yGen, recipients: [rec(x), rec(z)], now: Date.now() });
  const yEntry = await servedRekey(y, yGen, byY);
  const yChannel = await channelOf(byY);

  // Y's wraps land in the same moment as this device's own, so nothing this
  // device read before rotating contained them.
  let yLanded = false;
  relay((chan) => (chan === oldChannel && yLanded ? [yEntry] : []));
  await internals.enterCircle();
  await settle();

  assert.equal(await api.rekeyCircle(), true, "this device rotated");
  assert.notEqual(state.gen.channelId, oldChannel, "and left the old channel");
  yLanded = true;

  const converged = await waitFor(() => state.gen?.channelId === yChannel, CONVERGE_MS);
  harness.onFetch(null);
  assert.ok(converged, "the losing rotator ends on the winner's channel instead of alone on its own");
});

// ------------------------------------------------------------- protocol level

test("precondition: after a quiet day every device is past due at once", () => {
  // Copied from main.js rekeyDue, which is not exported. The offset is at
  // most an hour, so any gap longer than 25 hours makes every member due,
  // whatever its id, and the first timer tick after opening the app fires.
  const REKEY_INTERVAL_MS = 24 * 60 * 60 * 1000;
  const due = (memberId, genAt, now) =>
    now - genAt > REKEY_INTERVAL_MS + (parseInt(memberId.slice(0, 6), 16) % 3600) * 1000;
  const reopened = 26 * 60 * 60 * 1000;
  for (const p of ["000000", "0e0f10", "7fffff", "ffffff"]) {
    assert.ok(due(p.padEnd(32, "0"), 0, reopened), `${p} is due`);
  }
});

test("a device that still holds the previous generation can open the winning re-key", async () => {
  // What a fix needs from the protocol, and it is already there: the loser's
  // previous generation opens the winner's wrap, because every rotator wraps
  // to every retained member. main.js commitGeneration destroys that
  // generation today; keeping it for a short grace window costs a few
  // minutes of chain keys, well inside the default one-hour history.
  const [y, x] = await sortedIdentities(2);
  const seed = newSeed();
  const e0 = epochAt(Date.now());
  const yGen = await openGeneration({ seed: new Uint8Array(seed), g: 0, e0 });
  const xPrev = await openGeneration({ seed: new Uint8Array(seed), g: 0, e0 });
  const now = e0 * EPOCH_MS + 1000;

  const byY = await buildRekey({ identity: y, gen: yGen, recipients: [rec(x)], now });
  const byX = await buildRekey({ identity: x, gen: xPrev, recipients: [rec(y)], now });
  assert.ok(byY && byX, "both rotated from the same generation");

  const post = byY.posts.find((p) => p.to === x.memberId);
  const applied = await applyRekey({ identity: x, gen: xPrev, msg: post, epoch: byY.epoch, senderId: y.memberId });
  assert.ok(applied, "the loser opens the winner's wrap from its previous generation");
  const landed = await openGeneration({ seed: applied.seed, g: applied.g, e0: applied.e0 });
  assert.equal(landed.channelId, await channelOf(byY), "and lands on the winner's channel");
});
