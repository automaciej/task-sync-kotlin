package pl.blizinski.tasksync.model

/**
 * Declares what a source's accounts can do, so a consuming UI can gate controls on capability
 * rather than branching on which provider it is. A null / empty field on a [Task] never asserts
 * "unsupported" on its own — this is the authoritative signal.
 *
 * The union of the fields the app previously hand-declared per source, plus [supportsNativeMove]
 * (which used to be implicit in whether the provider library overrode its `moveTask`).
 */
data class StoreCapabilities(
    val supportsDueTime: Boolean,
    val supportsPriority: Boolean,
    val supportsLabels: Boolean,
    val supportsManualOrdering: Boolean,
    val supportsSubtasks: Boolean,
    val supportsMultipleLists: Boolean,
    /** False for a source whose "list" is a pre-existing remote container the app neither
     *  creates nor deletes (GitHub Issues — a repository). */
    val supportsListCreation: Boolean,
    /** False for a source whose "delete" is not a true delete (GitHub Issues closes the issue). */
    val supportsManualDelete: Boolean,
    /** True when the source's API can move a task between lists in place, preserving its id.
     *  When false, [pl.blizinski.tasksync.store.TaskStore.moveTask] returns null and the caller
     *  falls back to create-in-new-list + delete-old. */
    val supportsNativeMove: Boolean,
    val recurrenceStyle: RecurrenceStyle,
)
