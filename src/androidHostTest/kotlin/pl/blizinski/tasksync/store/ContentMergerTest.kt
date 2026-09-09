package pl.blizinski.tasksync.store

import kotlin.test.Test
import kotlin.test.assertEquals

class ContentMergerTest {

    private data class C(
        val title: String = "",
        val notes: String? = null,
        val due: Long? = null,
        val dueHasTime: Boolean = false,
        val serverOnly: String = "",
    )

    private val merger = contentMerger(emptyBase = C(title = "")) {
        val dueSide = sideForGroup({ it.due }, { it.dueHasTime })
        remote.copy(
            title = pick { it.title },
            notes = pick { it.notes },
            due = dueSide.due,
            dueHasTime = dueSide.dueHasTime,
        )
    }

    private val base = C(title = "Base", notes = "base notes", due = 1_000L, serverOnly = "s0")

    @Test
    fun pick_disjointEdits_bothSurvive() {
        val local = base.copy(title = "Local")
        val remote = base.copy(notes = "remote notes", serverOnly = "s1")

        val merged = merger.merge(base, local, remote, preferLocal = true)

        assertEquals("Local", merged.title)
        assertEquals("remote notes", merged.notes)
        assertEquals("s1", merged.serverOnly, "field not picked comes straight from remote")
    }

    @Test
    fun pick_contestedField_preferLocalDecides() {
        val local = base.copy(title = "Local")
        val remote = base.copy(title = "Remote")

        assertEquals("Local", merger.merge(base, local, remote, preferLocal = true).title)
        assertEquals("Remote", merger.merge(base, local, remote, preferLocal = false).title)
    }

    @Test
    fun pick_nullBase_fillsFieldsLocalNeverSet() {
        val local = C(title = "My title")                       // notes/due unset
        val remote = C(title = "My title", notes = "server notes", due = 5_000L, serverOnly = "s")

        val merged = merger.merge(null, local, remote, preferLocal = true)

        assertEquals("server notes", merged.notes)
        assertEquals(5_000L, merged.due)
    }

    @Test
    fun sideForGroup_movesFieldsTogether() {
        // Local changed only dueHasTime; the whole (due, dueHasTime) group comes from local.
        val local = base.copy(due = 1_000L, dueHasTime = true)
        val remote = base.copy(title = "Remote title")

        val merged = merger.merge(base, local, remote, preferLocal = false)

        assertEquals(1_000L, merged.due)
        assertEquals(true, merged.dueHasTime)
        assertEquals("Remote title", merged.title)
    }

    @Test
    fun sideForGroup_contested_takesOneSideIntact() {
        val local = base.copy(due = 2_000L, dueHasTime = true)
        val remote = base.copy(due = 3_000L, dueHasTime = false)

        merger.merge(base, local, remote, preferLocal = true).let {
            assertEquals(2_000L, it.due); assertEquals(true, it.dueHasTime)
        }
        merger.merge(base, local, remote, preferLocal = false).let {
            assertEquals(3_000L, it.due); assertEquals(false, it.dueHasTime)
        }
    }
}
