// The check-in timer rules on their own, at their boundaries. checkin-timer.test.mjs
// does the same thing in main.js
import test from "node:test";
import assert from "node:assert/strict";

import { DUE_WARN_MS, DUE_WINDOW_MS, dueFrom, storedTimer, warnDue } from "../app/js/checkin.js";

const NOW = 1_800_000_000_000;

test("dueFrom keeps a deadline within a day of the message and drops one further out", () => {
  assert.equal(dueFrom({ ts: NOW, due: NOW + 60_000 }), NOW + 60_000);
  assert.equal(dueFrom({ ts: NOW, due: NOW + DUE_WINDOW_MS }), NOW + DUE_WINDOW_MS, "the window is inclusive");
  assert.equal(dueFrom({ ts: NOW, due: NOW + DUE_WINDOW_MS + 1 }), null);

  // the sender missed it and thouse who watch must know.
  assert.equal(dueFrom({ ts: NOW, due: NOW - 60_000 }), NOW - 60_000);
  assert.equal(dueFrom({ ts: NOW, due: NOW - DUE_WINDOW_MS }), NOW - DUE_WINDOW_MS);
  assert.equal(dueFrom({ ts: NOW, due: NOW - DUE_WINDOW_MS - 1 }), null);
});

test("dueFrom takes nothing but a finite number from a decrypted message", () => {
  for (const due of [undefined, null, NaN, Infinity, -Infinity, String(NOW), [NOW], { valueOf: () => NOW }, true]) {
    assert.equal(dueFrom({ ts: NOW, due }), null, `due ${String(due)}`);
  }
  for (const ts of [undefined, null, NaN, Infinity, String(NOW)]) {
    assert.equal(dueFrom({ ts, due: NOW }), null, `ts ${String(ts)}`);
  }
  assert.equal(dueFrom(null), null);
  assert.equal(dueFrom(undefined), null);
  assert.equal(dueFrom({}), null);
});

test("warnDue is true for the five minutes before the deadline and at no other time", () => {
  const due = NOW;
  assert.equal(warnDue(due, due - DUE_WARN_MS - 1), false);
  assert.equal(warnDue(due, due - DUE_WARN_MS), true);
  assert.equal(warnDue(due, due - 1), true);
  assert.equal(warnDue(due, due), false, "at the deadline the warning is over: overdue takes it from here");
  assert.equal(warnDue(due, due + 1), false);

  for (const bad of [0 / 0, Infinity, null, undefined, String(due)]) {
    assert.equal(warnDue(bad, due - 1), false, `due ${String(bad)}`);
  }
});

test("storedTimer refuses a record that is not a timer", () => {
  for (const raw of [null, undefined, 0, "", "timer", NOW, true]) {
    assert.equal(storedTimer(raw, null, NOW), null, `raw ${String(raw)}`);
  }
  for (const due of [undefined, null, NaN, Infinity, String(NOW)]) {
    assert.equal(storedTimer({ due, member: "m1" }, null, NOW), null, `due ${String(due)}`);
  }
  for (const member of [undefined, null, "", 7, {}]) {
    assert.equal(storedTimer({ due: NOW, member }, null, NOW), null, `member ${String(member)}`);
  }
});

test("storedTimer keeps a missed deadline for a day and then lets it go", () => {
  const raw = { due: NOW, member: "m1" };
  assert.deepEqual(storedTimer(raw, null, NOW - 60_000), raw, "still running");
  assert.deepEqual(storedTimer(raw, null, NOW + 60_000), raw, "missed, and still owed a check-in");
  assert.deepEqual(storedTimer(raw, null, NOW + DUE_WINDOW_MS), raw);
  assert.equal(storedTimer(raw, null, NOW + DUE_WINDOW_MS + 1), null);
});

test("storedTimer belongs to the identity that armed it", () => {
  const raw = { due: NOW, member: "m1" };
  assert.deepEqual(storedTimer(raw, "m1", NOW), raw);
  assert.equal(storedTimer(raw, "m2", NOW), null, "another identity's timer is not this one's");
  assert.deepEqual(storedTimer(raw, null, NOW), raw, "no identity yet, as on boot before an unlock");
});

test("storedTimer returns only due and member, whatever else was stored", () => {
  const out = storedTimer({ due: NOW, member: "m1", name: "A", lat: 44.98, __proto__: { extra: 1 } }, "m1", NOW);
  assert.deepEqual(Object.keys(out).sort(), ["due", "member"]);
  assert.equal(out.extra, undefined);
});
