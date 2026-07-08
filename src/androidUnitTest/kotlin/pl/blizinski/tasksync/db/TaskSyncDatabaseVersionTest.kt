package pl.blizinski.tasksync.db

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Guards against repeating the production incident where `TaskSyncDatabase.CURRENT_VERSION`
 * started at 1 while a consuming source (google-tasks-kotlin) had already shipped an on-disk
 * schema at version 5, making Room treat every existing install as an unmigrated downgrade —
 * "fixed" at the time by a destructive fallback that silently dropped and recreated every local
 * task, list, and pending op. That broke every reference to those local ids held elsewhere —
 * TaskCompass's comparison history and workspace-list-memberships both went permanently
 * orphaned, since neither is backed up anywhere but the on-device database that got wiped.
 * See TaskCompass's Docs/designs/2026-07-08-shared-task-sync-engine.md implementation log.
 *
 * [MINIMUM_SAFE_VERSION] is the highest schema version any known consuming source has shipped
 * on disk under this file-naming scheme. Bump it — with a real `Migration` in that source's own
 * repo — whenever a new consuming source's history requires it; never lower it.
 */
private const val MINIMUM_SAFE_VERSION = 6

class TaskSyncDatabaseVersionTest {
    @Test
    fun versionNeverRegressesBelowMinimumSafeVersion() {
        assertTrue(
            TaskSyncDatabase.CURRENT_VERSION >= MINIMUM_SAFE_VERSION,
            "TaskSyncDatabase.CURRENT_VERSION (${TaskSyncDatabase.CURRENT_VERSION}) dropped " +
                "below $MINIMUM_SAFE_VERSION — this will make Room treat existing installs as " +
                "an unmigrated downgrade. If you're adding a new consuming source with no " +
                "prior schema, that's fine and doesn't need a version bump; if you're " +
                "intentionally raising the version further, raise MINIMUM_SAFE_VERSION here " +
                "too, and make sure a real Migration (not a destructive fallback) exists in " +
                "every consuming source with data to preserve.",
        )
    }
}
