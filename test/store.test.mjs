// store.js is he in-memory fallback when IndexedDB is broken,
// and the panic wipe, which has to erase everything it can reach even
// when one of the things it attempts throws an error.
import test from "node:test";
import assert from "node:assert/strict";

// store.js holds module state, so every scenario loads its own copy.
let copy = 0;
const freshStore = () => import(`../app/js/store.js?copy=${copy++}`);

function withGlobals(t, fakes) {
  for (const [name, value] of Object.entries(fakes)) {
    const original = Object.getOwnPropertyDescriptor(globalThis, name);
    Object.defineProperty(globalThis, name, { value, configurable: true, writable: true });
    t.after(() => {
      if (original) Object.defineProperty(globalThis, name, original);
      else delete globalThis[name];
    });
  }
}

const later = (fn) => queueMicrotask(fn);

// A fake of everything wipeAll touches, recording what it does.
function fakeBrowser({ deleteFires = "onsuccess", failing = [] } = {}) {
  const log = [];
  const fail = (step) => failing.includes(step);
  return {
    log,
    globals: {
      indexedDB: {
        open(name) {
          const req = { result: { close: () => log.push(`close ${name}`) } };
          later(() => req.onsuccess());
          return req;
        },
        deleteDatabase(name) {
          log.push(`deleteDatabase ${name}`);
          const req = {};
          later(() => req[deleteFires]());
          return req;
        },
      },
      localStorage: {
        clear() {
          if (fail("localStorage")) throw new Error("storage denied");
          log.push("localStorage.clear");
        },
      },
      caches: {
        async keys() {
          if (fail("caches")) throw new Error("caches denied");
          return ["starling-shell-v1", "starling-shell-v2"];
        },
        async delete(key) {
          log.push(`caches.delete ${key}`);
          return true;
        },
      },
      navigator: {
        serviceWorker: {
          async getRegistrations() {
            if (fail("serviceWorker")) throw new Error("sw denied");
            return ["a", "b"].map((id) => ({ unregister: async () => log.push(`unregister ${id}`) }));
          },
        },
      },
    },
  };
}

const EVERYTHING = [
  "close starling",
  "deleteDatabase starling",
  "localStorage.clear",
  "caches.delete starling-shell-v1",
  "caches.delete starling-shell-v2",
  "unregister a",
  "unregister b",
];

async function roundTrip(store) {
  assert.equal(await store.dbGet("k"), undefined);
  await store.dbSet("k", { a: 1 });
  assert.deepEqual(await store.dbGet("k"), { a: 1 });
  await store.dbSet("k", "replaced");
  assert.equal(await store.dbGet("k"), "replaced");
  await store.dbDel("k");
  assert.equal(await store.dbGet("k"), undefined);
}

test("without IndexedDB the store runs in memory and says so", async (t) => {
  withGlobals(t, { indexedDB: undefined });
  const store = await freshStore();
  assert.equal(store.persistenceBroken(), false, "nothing has been asked of it yet");
  await roundTrip(store);
  assert.equal(store.persistenceBroken(), true);
});

test("an IndexedDB that throws on open falls back the same way", async (t) => {
  withGlobals(t, {
    indexedDB: {
      open() {
        throw new Error("SecurityError");
      },
    },
  });
  const store = await freshStore();
  await roundTrip(store);
  assert.equal(store.persistenceBroken(), true);
});

test("an IndexedDB whose open request errors falls back the same way", async (t) => {
  withGlobals(t, {
    indexedDB: {
      open() {
        const req = { error: new Error("UnknownError") };
        later(() => req.onerror());
        return req;
      },
    },
  });
  const store = await freshStore();
  await roundTrip(store);
  assert.equal(store.persistenceBroken(), true);
});

test("a working IndexedDB is not reported as broken", async (t) => {
  const kv = new Map();
  const request = (result) => {
    const req = { result };
    later(() => req.onsuccess());
    return req;
  };
  const objectStore = {
    get: (key) => request(kv.get(key)),
    put: (value, key) => request(void kv.set(key, value)),
    delete: (key) => request(void kv.delete(key)),
  };
  withGlobals(t, {
    indexedDB: { open: () => request({ transaction: () => ({ objectStore: () => objectStore }) }) },
  });
  const store = await freshStore();
  await roundTrip(store);
  assert.equal(store.persistenceBroken(), false);
});

test("the panic wipe drops the database, localStorage, every cache and every service worker", async (t) => {
  const browser = fakeBrowser();
  withGlobals(t, browser.globals);
  const store = await freshStore();
  await store.wipeAll();
  assert.deepEqual(browser.log, EVERYTHING);
});

test("the panic wipe empties the in-memory fallback too", async (t) => {
  withGlobals(t, { indexedDB: undefined });
  const store = await freshStore();
  await store.dbSet("identity", "secret");
  await store.dbSet("gen", "secret");
  await store.wipeAll();
  assert.equal(await store.dbGet("identity"), undefined);
  assert.equal(await store.dbGet("gen"), undefined);
});

test("a blocked or failed database delete does not hang the wipe or skip the rest", { timeout: 5000 }, async (t) => {
  for (const deleteFires of ["onblocked", "onerror"]) {
    await t.test(deleteFires, async (t) => {
      const browser = fakeBrowser({ deleteFires });
      withGlobals(t, browser.globals);
      const store = await freshStore();
      await store.wipeAll();
      assert.deepEqual(browser.log, EVERYTHING);
    });
  }
});

test("one step of the wipe throwing does not stop the steps after it", async (t) => {
  const cases = {
    localStorage: (line) => line !== "localStorage.clear",
    caches: (line) => !line.startsWith("caches.delete"),
    serviceWorker: (line) => !line.startsWith("unregister"),
  };
  for (const [step, survives] of Object.entries(cases)) {
    await t.test(step, async (t) => {
      const browser = fakeBrowser({ failing: [step] });
      withGlobals(t, browser.globals);
      const store = await freshStore();
      await store.wipeAll();
      assert.deepEqual(browser.log, EVERYTHING.filter(survives));
    });
  }
});

test("the wipe runs where none of the browser storage exists at all", async (t) => {
  withGlobals(t, { indexedDB: undefined, localStorage: undefined, caches: undefined, navigator: undefined });
  const store = await freshStore();
  await store.wipeAll();
});
