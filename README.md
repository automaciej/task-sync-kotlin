# task-sync-kotlin

[![](https://jitpack.io/v/automaciej/task-sync-kotlin.svg)](https://jitpack.io/#automaciej/task-sync-kotlin)

Schema-agnostic offline-first sync engine for Android task-list sources
(Google Tasks, Microsoft To Do, ...). Extracted from
[google-tasks-kotlin](https://github.com/automaciej/google-tasks-kotlin) so
every source library can share one implementation of the local Room cache,
optimistic writes, pending-op queue, and adaptive background polling,
instead of each source reimplementing its own copy.

Used by [google-tasks-kotlin](https://github.com/automaciej/google-tasks-kotlin)
and [microsoft-todo-kotlin](https://github.com/automaciej/microsoft-todo-kotlin).

## Is this actually reusable, or is it hardcoded to those two sources?

It's genuinely generic, not just in name. The engine (`SyncEngine`,
`PendingOpsProcessor`, `LocalStore<T, TList>`) is written against two type
parameters, `T` (a record's content) and `TList` (a list's content), and it
never inspects a single field of either — only structural equality (`==`)
to detect whether server content changed. Content is persisted as an opaque
serialized column, not individual typed table columns. A consuming library
supplies its own `T`/`TList` types and a `NetworkSource<T, TList>`
implementation for its REST API; the engine only ever routes bytes through,
via [`NetworkSource`](src/commonMain/kotlin/pl/blizinski/tasksync/NetworkSource.kt)
and [`LocalStore`](src/commonMain/kotlin/pl/blizinski/tasksync/LocalStore.kt).

To add a new source, a consumer implements `NetworkSource<T, TList>` and
wires up `RoomLocalStore`, `SyncEngine`, `PendingOpsProcessor`, and
`AdaptivePoller` around it — no changes to this library required.

## Features

- **Offline-first reads and writes.** Local Room cache is the source read
  from; writes apply locally first and sync in the background.
- **Pending-op queue with op-merging**, so redundant local edits collapse
  before ever reaching the network:
  - `CREATE` + `DELETE` on the same not-yet-synced record cancel out locally
    and never hit the server.
  - Multiple consecutive `UPDATE`s collapse to the latest one.
  - A chain of cross-list moves (A → B → C) collapses to a single A → C
    move, and a batch of moves into the same destination list is chained via
    `previousRemoteId` so it lands in the order it was moved, not reversed.
- **Full and incremental pull** with tombstone/zombie detection: a record
  hard-deleted locally when its remote counterpart disappears from a full
  pull, or when an incremental pull reports it deleted — without ever
  deleting a record that just moved to another list, or one whose remote API
  simply omits completed items from a full listing.
- **Three-way conflict awareness**: `SyncedRecord.lastSyncedContent` keeps a
  merge-base snapshot so a source library can distinguish "changed locally",
  "changed remotely", and "changed both" instead of blind overwrite.
- **Adaptive background polling** via `AdaptivePoller` + `SyncWorker`
  (`androidx.work.WorkManager`), with an `instanceKey` per connected account
  so multiple concurrently-connected accounts (e.g. two Google accounts, or
  a Google + a Microsoft account) run independent, non-interfering polling
  chains and share nothing but the engine's code.
- **`writeMutex`-serialized sync cycles**, so a local write, a background
  poll, and a `forceSync()` call can never interleave and corrupt a
  not-yet-flushed op.
- **Structured error classification** (`SyncErrorKind`: push/pull failure,
  auth failure, consent required, advanced-protection block) via a
  source-supplied `SyncErrorClassifier`, so a consuming app can react
  specifically (e.g. prompt reconnect) rather than treating every failure
  the same.

## What it is *not*, and where it won't fit

- **Not multiplatform yet, despite the Kotlin Multiplatform plugin.** Only
  `androidTarget` is currently declared in `build.gradle.kts`; the sync
  coordinator, `RoomLocalStore`, and the WorkManager-based scheduler all
  live in `androidMain` and depend on `androidx.room` /
  `androidx.work`. The `commonMain` interfaces (`NetworkSource`, `LocalStore`,
  `SyncConfig`, the `Records`/`PendingOp` data classes) are platform-neutral
  by design, so adding an iOS target is a matter of providing an iOS
  `LocalStore` implementation and scheduler — but that doesn't exist today.
  If you need iOS or desktop right now, this isn't a drop-in.
- **Modeled specifically around a two-level "lists containing completable
  records" shape.** The engine's generic envelope
  (`SyncedRecord`/`SyncedListRecord`) tracks exactly: which list a record
  belongs to, whether it's completed, and its opaque content — nothing else
  structural. That fits Google Tasks, Microsoft To Do, and similar
  flat task-list APIs well. It does **not** natively model richer relational
  schemas — e.g. GitHub Issues' labels, milestones, assignees, or threaded
  comments — since those aren't "list membership + completion," they're
  separate related entities. Reusing this engine for a source like that
  would mean flattening those relations into a record's opaque content (so
  they ride along for free, but can't be queried/filtered by the engine
  itself) or introducing a genuinely different `LocalStore` shape — the
  engine's current interfaces don't do that job for you.
- **Relies on each source's REST API being pollable by "changed since
  timestamp."** `NetworkSource.getRecords(remoteListId, updatedMin)` expects
  a plain epoch-ms cursor. A source whose only efficient incremental
  mechanism is a genuine delta/continuation token (rather than a
  last-modified filter) has to adapt that token into this shape itself, or
  it loses the efficiency the engine assumes — see
  `microsoft-todo-kotlin`'s known limitation below for a concrete case of
  this.

## Usage

Add the JitPack repository:

```kotlin
dependencyResolutionManagement {
    repositories {
        maven { url = uri("https://jitpack.io") }
    }
}
```

Add the dependency:

```kotlin
dependencies {
    implementation("com.github.automaciej:task-sync-kotlin:0.1.0")
}
```

## Build

```
./gradlew build
```
