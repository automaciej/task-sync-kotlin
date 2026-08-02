package pl.blizinski.tasksync

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * One row in an IndexedDB-backed store used by [IndexedDbLocalStore] or TaskCompass's own
 * wasmJs repositories (comparisons, workspaces, ...) — see TaskCompass's
 * Docs/designs/2026-08-02-web-indexeddb-persistence.md. [json] is the opaque serialized
 * payload — the same "opaque JSON blob + a few indexed metadata columns" shape Room's entities
 * already use for this library's Android target (see
 * ../task-sync-kotlin/src/androidMain/.../db/Entities.kt's `contentJson` columns), so every
 * consumer marshals/unmarshals content the same way Room does, just against a different
 * physical store.
 *
 * [index1]/[index2] are up to two additional string fields a store can look up by (e.g.
 * `remoteId`, `listLocalId`) — empty string when unused, never null: IndexedDB indexes handle
 * missing/null keys inconsistently across browsers, so a present-but-empty sentinel is safer
 * than trying to model "no value" at the index level.
 */
data class IdbRow(
    val key: String,
    val json: String,
    val index1: String = "",
    val index2: String = "",
)

/** Declares one object store's name and which of [IdbRow.index1]/[IdbRow.index2] it indexes —
 *  passed to [IndexedDbHelper] so it can create the store/indexes on first open
 *  (`onupgradeneeded`), mirroring Room's `@Entity`/`@Index` declarations. */
data class IdbStoreSpec(
    val name: String,
    /** Subset of `"index1"`, `"index2"` — which of [IdbRow]'s index fields this store indexes. */
    val indexedFields: List<String> = emptyList(),
)

class IndexedDbException(message: String) : Exception(message)

/**
 * Thin suspend-function wrapper over the browser's native IndexedDB API, reused by
 * [IndexedDbLocalStore] and TaskCompass's own wasmJs repositories. IndexedDB's native API is
 * callback/event-based (`IDBRequest.onsuccess`/`onerror`), not Promise-based; each method here
 * bridges exactly one request (or, for [put]/[delete], one transaction) into a Kotlin suspend
 * function via [suspendCancellableCoroutine].
 *
 * Every value crossing the Kotlin/JS boundary here is a plain [String], an [Int], or a
 * String-typed callback — deliberately not using Kotlin/Wasm's `external interface`/`JsAny`
 * machinery for `IDBDatabase`/`IDBObjectStore` handles. The underlying `IDBDatabase` connection
 * is instead opened once and cached entirely JS-side (`window.__idbConnections`, keyed by
 * [dbName] — see [openDbJs]), so this class never needs to hold or pass an opaque JS object
 * handle at all. This matches the interop style already proven by this codebase's other
 * wasmJs-only JS interop (`GoogleIdentityServices.kt`'s GIS wrapper), rather than introducing a
 * second style for no benefit here.
 */
class IndexedDbHelper(
    private val dbName: String,
    private val version: Int,
    private val stores: List<IdbStoreSpec>,
) {
    private var opened = false

    private suspend fun ensureOpen() {
        if (opened) return
        val spec = stores.joinToString("|") { s -> "${s.name}:${s.indexedFields.joinToString(",")}" }
        suspendCancellableCoroutine { cont ->
            openDbJs(
                dbName, version, spec,
                onSuccess = { if (cont.isActive) cont.resume(Unit) },
                onError = { msg -> if (cont.isActive) cont.resumeWithException(IndexedDbException(msg)) },
            )
        }
        opened = true
    }

    /** Inserts or replaces the row at [IdbRow.key]. */
    suspend fun put(storeName: String, row: IdbRow) {
        ensureOpen()
        suspendCancellableCoroutine { cont ->
            putRowJs(
                dbName, storeName, row.key, row.json, row.index1, row.index2,
                onSuccess = { if (cont.isActive) cont.resume(Unit) },
                onError = { msg -> if (cont.isActive) cont.resumeWithException(IndexedDbException(msg)) },
            )
        }
    }

    suspend fun get(storeName: String, key: String): IdbRow? {
        ensureOpen()
        return suspendCancellableCoroutine { cont ->
            getRowJs(
                dbName, storeName, key,
                onFound = { json, i1, i2 -> if (cont.isActive) cont.resume(IdbRow(key, json, i1, i2)) },
                onNotFound = { if (cont.isActive) cont.resume(null) },
                onError = { msg -> if (cont.isActive) cont.resumeWithException(IndexedDbException(msg)) },
            )
        }
    }

    suspend fun getAll(storeName: String): List<IdbRow> {
        ensureOpen()
        return suspendCancellableCoroutine { cont ->
            val rows = mutableListOf<IdbRow>()
            getAllRowsJs(
                dbName, storeName,
                onRow = { key, json, i1, i2 -> rows += IdbRow(key, json, i1, i2) },
                onDone = { if (cont.isActive) cont.resume(rows) },
                onError = { msg -> if (cont.isActive) cont.resumeWithException(IndexedDbException(msg)) },
            )
        }
    }

    /** [indexField] must be `"index1"` or `"index2"` and must have been declared in this
     *  store's [IdbStoreSpec.indexedFields] at open time. */
    suspend fun getAllByIndex(storeName: String, indexField: String, value: String): List<IdbRow> {
        ensureOpen()
        return suspendCancellableCoroutine { cont ->
            val rows = mutableListOf<IdbRow>()
            getAllByIndexJs(
                dbName, storeName, indexField, value,
                onRow = { key, json, i1, i2 -> rows += IdbRow(key, json, i1, i2) },
                onDone = { if (cont.isActive) cont.resume(rows) },
                onError = { msg -> if (cont.isActive) cont.resumeWithException(IndexedDbException(msg)) },
            )
        }
    }

    suspend fun delete(storeName: String, key: String) {
        ensureOpen()
        suspendCancellableCoroutine { cont ->
            deleteRowJs(
                dbName, storeName, key,
                onSuccess = { if (cont.isActive) cont.resume(Unit) },
                onError = { msg -> if (cont.isActive) cont.resumeWithException(IndexedDbException(msg)) },
            )
        }
    }
}

// -----------------------------------------------------------------------
// Raw JS interop — see IndexedDbHelper's doc comment for why every value crossing this boundary
// is a plain String/Int or a String-typed callback. The IDBDatabase connection itself lives
// entirely in JS (window.__idbConnections, keyed by dbName), never passed into Kotlin.
// -----------------------------------------------------------------------

@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
private fun openDbJs(
    dbName: String,
    version: Int,
    storeSpec: String, // "storeName1:index1,index2|storeName2:index1"
    onSuccess: () -> Unit,
    onError: (String) -> Unit,
): Unit = js(
    """{
        try {
            if (!window.__idbConnections) window.__idbConnections = {};
            if (window.__idbConnections[dbName]) { onSuccess(); return; }
            var req = indexedDB.open(dbName, version);
            req.onupgradeneeded = function(event) {
                var db = event.target.result;
                storeSpec.split('|').forEach(function(part) {
                    var pieces = part.split(':');
                    var storeName = pieces[0];
                    var indexNames = pieces[1] ? pieces[1].split(',').filter(function(s) { return s.length > 0; }) : [];
                    var store;
                    if (!db.objectStoreNames.contains(storeName)) {
                        store = db.createObjectStore(storeName, { keyPath: 'key' });
                    } else {
                        store = event.target.transaction.objectStore(storeName);
                    }
                    indexNames.forEach(function(indexName) {
                        if (!store.indexNames.contains(indexName)) {
                            store.createIndex(indexName, indexName, { unique: false });
                        }
                    });
                });
            };
            req.onsuccess = function(event) {
                window.__idbConnections[dbName] = event.target.result;
                onSuccess();
            };
            req.onerror = function(event) {
                onError(String(event.target.error));
            };
        } catch (e) {
            onError(String(e));
        }
    }"""
)

@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
private fun putRowJs(
    dbName: String,
    storeName: String,
    key: String,
    json: String,
    index1: String,
    index2: String,
    onSuccess: () -> Unit,
    onError: (String) -> Unit,
): Unit = js(
    """{
        try {
            var db = window.__idbConnections[dbName];
            var tx = db.transaction([storeName], 'readwrite');
            var store = tx.objectStore(storeName);
            store.put({ key: key, json: json, index1: index1, index2: index2 });
            tx.oncomplete = function() { onSuccess(); };
            tx.onerror = function(event) { onError(String(event.target.error)); };
        } catch (e) {
            onError(String(e));
        }
    }"""
)

@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
private fun getRowJs(
    dbName: String,
    storeName: String,
    key: String,
    onFound: (json: String, index1: String, index2: String) -> Unit,
    onNotFound: () -> Unit,
    onError: (String) -> Unit,
): Unit = js(
    """{
        try {
            var db = window.__idbConnections[dbName];
            var tx = db.transaction([storeName], 'readonly');
            var store = tx.objectStore(storeName);
            var req = store.get(key);
            req.onsuccess = function() {
                var result = req.result;
                if (result) { onFound(result.json, result.index1 || '', result.index2 || ''); }
                else { onNotFound(); }
            };
            req.onerror = function(event) { onError(String(event.target.error)); };
        } catch (e) {
            onError(String(e));
        }
    }"""
)

@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
private fun getAllRowsJs(
    dbName: String,
    storeName: String,
    onRow: (key: String, json: String, index1: String, index2: String) -> Unit,
    onDone: () -> Unit,
    onError: (String) -> Unit,
): Unit = js(
    """{
        try {
            var db = window.__idbConnections[dbName];
            var tx = db.transaction([storeName], 'readonly');
            var store = tx.objectStore(storeName);
            var req = store.getAll();
            req.onsuccess = function() {
                var results = req.result;
                for (var i = 0; i < results.length; i++) {
                    var r = results[i];
                    onRow(r.key, r.json, r.index1 || '', r.index2 || '');
                }
                onDone();
            };
            req.onerror = function(event) { onError(String(event.target.error)); };
        } catch (e) {
            onError(String(e));
        }
    }"""
)

@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
private fun getAllByIndexJs(
    dbName: String,
    storeName: String,
    indexField: String,
    value: String,
    onRow: (key: String, json: String, index1: String, index2: String) -> Unit,
    onDone: () -> Unit,
    onError: (String) -> Unit,
): Unit = js(
    """{
        try {
            var db = window.__idbConnections[dbName];
            var tx = db.transaction([storeName], 'readonly');
            var store = tx.objectStore(storeName);
            var index = store.index(indexField);
            var req = index.getAll(value);
            req.onsuccess = function() {
                var results = req.result;
                for (var i = 0; i < results.length; i++) {
                    var r = results[i];
                    onRow(r.key, r.json, r.index1 || '', r.index2 || '');
                }
                onDone();
            };
            req.onerror = function(event) { onError(String(event.target.error)); };
        } catch (e) {
            onError(String(e));
        }
    }"""
)

@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
private fun deleteRowJs(
    dbName: String,
    storeName: String,
    key: String,
    onSuccess: () -> Unit,
    onError: (String) -> Unit,
): Unit = js(
    """{
        try {
            var db = window.__idbConnections[dbName];
            var tx = db.transaction([storeName], 'readwrite');
            var store = tx.objectStore(storeName);
            store.delete(key);
            tx.oncomplete = function() { onSuccess(); };
            tx.onerror = function(event) { onError(String(event.target.error)); };
        } catch (e) {
            onError(String(e));
        }
    }"""
)
