package pl.blizinski.tasksync

import kotlin.time.Duration

data class SyncConfig(
    val minPollInterval: Duration,
    val maxPollInterval: Duration,
)
