# task-sync-kotlin

[![](https://jitpack.io/v/automaciej/task-sync-kotlin.svg)](https://jitpack.io/#automaciej/task-sync-kotlin)

The shared foundation for a family of Kotlin task-list source libraries
([google-tasks-kotlin](https://github.com/automaciej/google-tasks-kotlin),
[microsoft-todo-kotlin](https://github.com/automaciej/microsoft-todo-kotlin),
[github-issues-kotlin](https://github.com/automaciej/github-issues-kotlin),
[todoist-kotlin](https://github.com/automaciej/todoist-kotlin)). It provides three
things those libraries would otherwise each reimplement:

1. **A schema-agnostic offline-first sync engine** — local cache, optimistic
   writes, a pending-op queue with op-merging, full/incremental pull with
   tombstone detection, three-way conflict awareness, and adaptive background
   polling.
2. **One shared task vocabulary** — `Task`, `TaskList`, `RecurrenceRule`,
   `SyncStatus`, `StoreCapabilities`, `TaskDraft`, `AccessTokenProvider`
   (package `pl.blizinski.tasksync.model`). Every source library speaks these
   types, so a consuming app can treat all of them uniformly.
3. **One store contract + a factory** — `TaskStore` (the single interface each
   source exposes) plus `buildAndroidTaskStore(...)` / `buildWasmTaskStore(...)`,
   which wire the engine, the local store, and a source's `NetworkSource` +
   `ContentAdapter` together. A source library is then ~40 lines of
   provider-specific code, not a hand-rolled ~300-line store class.

## How generic is it, really?

Fully. The engine (`SyncEngine`, `PendingOpsProcessor`, `LocalStore<T, TList>`)
is written against two type parameters — `T` (a record's content) and `TList`
(a list's content) — and never inspects a field of either. It only compares two
`T` values for structural equality (`==`) to detect whether server content
changed, and persists content as an opaque serialized column, not typed table
columns. A consuming library supplies its own `@Serializable T`/`TList` types
and a `NetworkSource<T, TList>` for its REST API; the engine routes bytes
through, nothing more. The one content-adjacent field promoted into the generic
envelope is `isCompleted`, because every target source has some completion
concept and the engine's zombie-detection needs it.

**Adding a new source:** implement `NetworkSource<T, TList>` (your REST calls) and
`ContentAdapter<T, TList>` (map your `T` ⇄ the shared `Task`, fold a `TaskDraft`
into a `T`), declare a `StoreCapabilities`, and call `buildAndroidTaskStore(...)`.
No change to this library required.

## What's in the box

### Sync engine (`commonMain` + `androidMain` / `wasmJsMain`)

- **Offline-first reads and writes.** The local cache (Room on Android,
  IndexedDB on wasmJs, in-memory as a fallback/test double) is what the UI
  reads; writes apply locally first and sync in the background.
- **Pending-op queue with op-merging**, so redundant local edits collapse
  before reaching the network: `CREATE`+`DELETE` on a not-yet-synced record
  cancel out; consecutive `UPDATE`s collapse to the latest; a chain of
  cross-list moves A→B→C collapses to A→C, and a batch of moves into one list
  is chained via `previousRemoteId` so it lands in the order it was moved.
- **Full and incremental pull** with tombstone/zombie detection — a record is
  hard-deleted locally when its remote counterpart genuinely disappears, but
  not when it merely moved lists or its API omits completed items from a full
  listing.
- **Three-way merge** via an optional provider-supplied `ContentMerger<T>`
  (passed to `buildAndroidTaskStore` / `buildWasmTaskStore`). When configured,
  a sync cycle pulls before it flushes and folds the server's non-conflicting
  field changes into a record that still has unpushed local edits — using
  `SyncedRecord.lastSyncedContent` as the merge base — so e.g. a title edited
  on one device and notes edited on another both survive. Fields changed on
  both sides are resolved last-writer-wins. With no merger supplied, a record
  with pending local ops wins wholesale (unchanged historical behavior).
- **Adaptive background polling** (`AdaptivePoller` + `SyncWorker` /
  `androidx.work`), with an `instanceKey` per connected account so multiple
  concurrently-connected accounts run independent, non-interfering polling
  chains and share nothing but this library's code.
- **`writeMutex`-serialized cycles**, so a local write, a background poll, and a
  `forceSync()` can't interleave and corrupt a not-yet-flushed op.
- **Structured error classification** (`SyncErrorKind`: push/pull failure, auth
  failure, consent required, advanced-protection block) via a source-supplied
  `SyncErrorClassifier`. `HttpStatusSyncErrorClassifier` covers the common
  "401 → auth failed" case.

### Shared model + store contract (`commonMain`)

- **`pl.blizinski.tasksync.model`** — `Task` (a superset of every source's
  fields, all nullable/defaulted; a null value never means "unsupported" — that's
  `StoreCapabilities`' job), `TaskList`, `TaskRef`, `TaskLink`,
  `RecurrenceRule` (`TextRule` for natural-language rules, `StructuredRule` for
  pattern objects) + `RecurrenceStyle`, `SyncStatus` / `PublicSyncError` /
  `FatalStorageError`, `StoreConfig`, `AccessTokenProvider`, `StoreCapabilities`,
  `TaskDraft`.
- **`pl.blizinski.tasksync.store`** — `TaskStore` (the interface every source
  exposes: reactive `Flow`s of lists/tasks/`SyncStatus`, optimistic write
  methods keyed on plain source-local ids, `forceSync()`/`fullSync()`),
  `ContentAdapter<T, TList>`, `DefaultTaskStore` (the shared wiring), and the
  `buildAndroidTaskStore(...)` / `buildWasmTaskStore(...)` factories.

## Targets

`androidTarget` (Room + `androidx.work`) and `wasmJs` (IndexedDB, sync-on-demand
— no background poller). The `commonMain` model + `TaskStore` + engine
interfaces are platform-neutral; an iOS or desktop target needs only a
`LocalStore` implementation and a scheduler for that platform.

> JitPack builds publish the **android** artifact only (`-PjitpackBuild=true` in
> `jitpack.yml` skips wasmJs) — publishing more than one Kotlin/Multiplatform
> target together breaks Gradle module-metadata resolution for downstream KMP
> consumers. The wasmJs target is built from a sibling checkout during local
> development.

## Where it may not fit

- **Modeled around a two-level "lists containing completable records" shape.**
  The generic envelope (`SyncedRecord`/`SyncedListRecord`) tracks list
  membership, completion, and opaque content — nothing else structural. That
  fits Google Tasks, Microsoft To Do, Todoist, and GitHub Issues. A source with
  a genuinely relational schema (threaded comments, milestones as first-class
  entities, ...) would have to flatten those into a record's opaque content — they
  ride along, but the engine can't query or filter on them.
- **Assumes each source's API is pollable by "changed since timestamp."**
  `NetworkSource.getRecords(remoteListId, updatedMin)` takes an epoch-ms cursor.
  A source whose only efficient incremental mechanism is a genuine
  delta/continuation token must adapt that into this shape itself, or fall back
  to full pulls (see microsoft-todo-kotlin's known limitation).

## Usage

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories { maven { url = uri("https://jitpack.io") } }
}
```

```kotlin
// build.gradle.kts
dependencies {
    implementation("com.github.automaciej:task-sync-kotlin:v0.3.0")
}
```

You normally don't depend on this directly — you depend on one of the source
libraries, which pulls it in. Depend on it directly only to build a source
library of your own.

## Build

```
./build.sh build
```
