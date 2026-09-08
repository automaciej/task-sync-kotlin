package pl.blizinski.tasksync.model

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Per-account configuration for a [pl.blizinski.tasksync.store.TaskStore]. Replaces the four
 * identical `*StoreConfig` classes. [dbName] is required and must be unique per connected
 * account — it names both the Room database file and the background-poll work slot.
 */
data class StoreConfig(
    val dbName: String,
    val minPollInterval: Duration = 1.minutes,
    val maxPollInterval: Duration = 30.minutes,
    val maxRecentErrors: Int = 50,
)
