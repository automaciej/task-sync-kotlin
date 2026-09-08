package pl.blizinski.tasksync.model

import kotlinx.serialization.Serializable

/**
 * A task's recurrence rule, in whichever native shape its source uses. Exactly one variant is
 * ever produced by a given source — see [RecurrenceStyle] for which. This is a sealed pair of
 * shapes rather than one source-agnostic struct: Todoist's recurrence is a server-parsed
 * natural-language string (the "fuzzy" capability), Microsoft Graph's is a structured pattern
 * object (the "explicit" capability), and the two do not share a common representation without
 * lossy translation in one direction — no consumer attempts that translation.
 *
 * `@Serializable` so it can live inside a provider's opaque `contentJson` (a Todoist/Microsoft
 * task content type embeds it directly). Previously this exact shape existed twice: once in
 * `microsoft-todo-kotlin` (structured variant only) and once in the app's own model.
 */
@Serializable
sealed interface RecurrenceRule {
    /**
     * A server-parsed natural-language rule (Todoist's `due.string`, e.g. "every day"), verbatim
     * — never parsed or reinterpreted, passed through and re-sent exactly as received. Backs
     * [RecurrenceStyle.FUZZY].
     */
    @Serializable
    data class TextRule(val text: String) : RecurrenceRule

    /**
     * Microsoft Graph's `patternedRecurrence`, restricted to the common patterns consumers
     * author. [frequency]'s six values mirror Graph's own `pattern.type` enum directly, so the
     * domain ↔ wire mapping in `microsoft-todo-kotlin` is a rename, not a reshaping. Backs
     * [RecurrenceStyle.EXPLICIT].
     */
    @Serializable
    data class StructuredRule(
        val frequency: RecurrenceFrequency,
        /** "every N ___", >= 1. */
        val interval: Int,
        /** Used by [RecurrenceFrequency.WEEKLY], and by the relative-monthly/yearly frequencies
         *  together with [weekIndex] (e.g. "the second Tuesday"). Empty = unconstrained. */
        val daysOfWeek: List<Weekday> = emptyList(),
        /** Used by [RecurrenceFrequency.ABSOLUTE_MONTHLY]/[RecurrenceFrequency.ABSOLUTE_YEARLY]. */
        val dayOfMonth: Int? = null,
        /** Used by [RecurrenceFrequency.ABSOLUTE_YEARLY]/[RecurrenceFrequency.RELATIVE_YEARLY]. */
        val month: Int? = null,
        /** Which occurrence of [daysOfWeek] within the period — relative-monthly/yearly only. */
        val weekIndex: WeekIndex? = null,
        val end: RecurrenceEnd = RecurrenceEnd.Never,
    ) : RecurrenceRule
}

/** Mirrors Microsoft Graph's `recurrencePattern.type` values exactly. */
@Serializable
enum class RecurrenceFrequency { DAILY, WEEKLY, ABSOLUTE_MONTHLY, RELATIVE_MONTHLY, ABSOLUTE_YEARLY, RELATIVE_YEARLY }

/** Mirrors Microsoft Graph's `recurrencePattern.index` values. */
@Serializable
enum class WeekIndex { FIRST, SECOND, THIRD, FOURTH, LAST }

@Serializable
enum class Weekday { SUNDAY, MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY, SATURDAY }

/** Mirrors Microsoft Graph's `recurrenceRange.type` values. */
@Serializable
sealed interface RecurrenceEnd {
    @Serializable
    object Never : RecurrenceEnd

    @Serializable
    data class AfterOccurrences(val count: Int) : RecurrenceEnd

    /** Epoch milliseconds, date-only (midnight UTC), matching [Task.dueDate]'s convention. */
    @Serializable
    data class OnDate(val date: Long) : RecurrenceEnd
}

/**
 * Which recurrence representation (if any) a source supports — drives which Repeat control a UI
 * renders. Two genuinely distinct capabilities, not two views of one thing: no consumer
 * translates a rule from one style into the other.
 */
enum class RecurrenceStyle {
    /** No recurrence field on this source's API at all (Google Tasks, GitHub Issues). */
    NONE,

    /** A free-text, server-parsed rule (Todoist's `due.string`) — [RecurrenceRule.TextRule]. */
    FUZZY,

    /** A structured pattern object (Microsoft Graph's `patternedRecurrence`) —
     *  [RecurrenceRule.StructuredRule]. */
    EXPLICIT,
}
