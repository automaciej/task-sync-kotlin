package pl.blizinski.tasksync.store

/**
 * Provider-supplied three-way merge for one record's opaque content [T]. The sync engine stays
 * schema-agnostic — it never inspects [T] — so this is the single hook where a provider library
 * folds a concurrent server edit and a not-yet-pushed local edit into one [T] (e.g. the title
 * changed on another device while the notes changed locally).
 *
 * Wired via [buildAndroidTaskStore]/[buildWasmTaskStore]'s optional `merger` argument. When a
 * provider supplies none, the engine keeps its historical behavior: a record with pending local
 * ops wins wholesale over any concurrent remote change, with no field-level merge.
 */
fun interface ContentMerger<T> {
    /**
     * @param base   content as of the last successful pull (the merge base); null when the
     *               record has never completed a pull. A merger with no base should still fill
     *               fields the local copy never set from [remote] rather than drop them — see
     *               [contentMerger], which handles this with an empty-base stand-in.
     * @param local  current local content, including edits not yet pushed to the server.
     * @param remote content just pulled from the server.
     * @param preferLocal tie-breaker for a field changed on BOTH sides since [base]: true keeps
     *               [local]'s value, false takes [remote]'s. The engine sets it by
     *               last-writer-wins — the newest pending local op's timestamp vs. the server's
     *               own updated-at. A field changed on only one side is always taken from that
     *               side, regardless of this flag.
     * @return the reconciled content. The engine stores it locally and the pending ops push it
     *         to the server on the same sync cycle.
     */
    fun merge(base: T?, local: T, remote: T, preferLocal: Boolean): T
}

/**
 * Scope for expressing a [ContentMerger] as a per-field pick. Every piece of three-way logic
 * lives here; a provider merger only names its editable fields. Built by [contentMerger].
 */
class MergeScope<T> internal constructor(
    base: T?,
    /** Current local content, including edits not yet pushed. */
    val local: T,
    /** Content just pulled from the server. */
    val remote: T,
    emptyBase: T,
    private val preferLocal: Boolean,
) {
    /** The real merge base, or an all-fields-unset stand-in when the record was never pulled. */
    private val base: T = base ?: emptyBase

    /**
     * Value for one field: taken from whichever side changed it since the base; if both sides
     * changed it, [preferLocal] decides. With no real base the empty stand-in means a field the
     * local copy never set reads as "unchanged locally" and is filled from [remote].
     */
    fun <F> pick(field: (T) -> F): F {
        val b = field(base)
        val l = field(local)
        val r = field(remote)
        return when {
            l == b -> r
            r == b -> l
            else -> if (preferLocal) l else r
        }
    }

    /**
     * Which side to take a group of fields that must move together (e.g. a due date and its
     * has-time flag). Returns [local] or [remote] — read the individual fields off the result.
     * A group counts as changed on a side if any of [fields] differs from the base there.
     */
    fun sideForGroup(vararg fields: (T) -> Any?): T {
        val localChanged = fields.any { it(local) != it(base) }
        val remoteChanged = fields.any { it(remote) != it(base) }
        return when {
            !localChanged -> remote
            !remoteChanged -> local
            else -> if (preferLocal) local else remote
        }
    }
}

/**
 * Builds a [ContentMerger] from a per-field pick.
 *
 * @param emptyBase [T] with every locally-editable field at its unset value — the stand-in base
 *   for a record created locally and not yet pulled back.
 * @param build receives a [MergeScope] and returns the merged content. Convention:
 *   `remote.copy(title = pick { it.title }, ...)` so every server-owned field is carried through
 *   from the just-pulled remote and only the editable fields are three-way merged.
 */
fun <T> contentMerger(emptyBase: T, build: MergeScope<T>.() -> T): ContentMerger<T> =
    ContentMerger { base, local, remote, preferLocal ->
        MergeScope(base, local, remote, emptyBase, preferLocal).build()
    }
