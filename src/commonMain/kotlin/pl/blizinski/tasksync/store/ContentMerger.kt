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
     *               record has never completed a pull — a merger with no base should return
     *               [local].
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
