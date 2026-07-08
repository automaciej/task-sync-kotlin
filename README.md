# task-sync-kotlin

[![](https://jitpack.io/v/automaciej/task-sync-kotlin.svg)](https://jitpack.io/#automaciej/task-sync-kotlin)

Schema-agnostic offline-first sync engine for Android task-list sources
(Google Tasks, Microsoft To Do, ...). Extracted from
[google-tasks-kotlin](https://github.com/automaciej/google-tasks-kotlin) so
every source library can share one implementation of the local Room cache,
optimistic writes, pending-op queue, and adaptive background polling,
instead of each source reimplementing its own copy.

A consuming library implements [`NetworkSource<T, TList>`](src/commonMain/kotlin/pl/blizinski/tasksync/NetworkSource.kt)
against its own REST API — the only place that knows that API's request/response
shapes and date formats — and wires up [`RoomLocalStore`](src/androidMain/kotlin/pl/blizinski/tasksync/RoomLocalStore.kt),
[`SyncEngine`](src/androidMain/kotlin/pl/blizinski/tasksync/SyncEngine.kt),
[`PendingOpsProcessor`](src/androidMain/kotlin/pl/blizinski/tasksync/PendingOpsProcessor.kt),
and [`AdaptivePoller`](src/androidMain/kotlin/pl/blizinski/tasksync/AdaptivePoller.kt)
around it. `T`/`TList` stay fully opaque to this engine — it never inspects
individual fields, only structural equality — so it has no knowledge of any
specific source's schema.

Used by [google-tasks-kotlin](https://github.com/automaciej/google-tasks-kotlin)
and [microsoft-todo-kotlin](https://github.com/automaciej/microsoft-todo-kotlin).

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
