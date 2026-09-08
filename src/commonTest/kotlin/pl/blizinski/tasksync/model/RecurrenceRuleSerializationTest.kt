package pl.blizinski.tasksync.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [RecurrenceRule] embeds directly in a provider's opaque `contentJson` (Todoist/Microsoft task
 * content types hold one), so every variant must survive a JSON round-trip unchanged — the
 * "FIRST resurrection" bug in the recurring-tasks work was exactly a round-trip defect.
 */
class RecurrenceRuleSerializationTest {

    private val json = Json { ignoreUnknownKeys = true }

    private inline fun <reified T> roundTrip(value: T): T =
        json.decodeFromString<T>(json.encodeToString(value))

    @Test
    fun textRule_roundTrips() {
        val rule: RecurrenceRule = RecurrenceRule.TextRule("every! last day")
        assertEquals(rule, roundTrip(rule))
    }

    @Test
    fun structuredRule_minimal_roundTrips() {
        val rule: RecurrenceRule = RecurrenceRule.StructuredRule(
            frequency = RecurrenceFrequency.DAILY,
            interval = 1,
        )
        assertEquals(rule, roundTrip(rule))
    }

    @Test
    fun structuredRule_relativeMonthly_withAllFields_roundTrips() {
        val rule: RecurrenceRule = RecurrenceRule.StructuredRule(
            frequency = RecurrenceFrequency.RELATIVE_MONTHLY,
            interval = 2,
            daysOfWeek = listOf(Weekday.TUESDAY, Weekday.THURSDAY),
            weekIndex = WeekIndex.SECOND,
            end = RecurrenceEnd.AfterOccurrences(10),
        )
        assertEquals(rule, roundTrip(rule))
    }

    @Test
    fun structuredRule_absoluteYearly_onDateEnd_roundTrips() {
        val rule: RecurrenceRule = RecurrenceRule.StructuredRule(
            frequency = RecurrenceFrequency.ABSOLUTE_YEARLY,
            interval = 1,
            dayOfMonth = 15,
            month = 6,
            end = RecurrenceEnd.OnDate(1_800_000_000_000L),
        )
        assertEquals(rule, roundTrip(rule))
    }

    @Test
    fun recurrenceEndNever_isTheDefault_andRoundTrips() {
        val rule = RecurrenceRule.StructuredRule(frequency = RecurrenceFrequency.WEEKLY, interval = 1)
        assertEquals(RecurrenceEnd.Never, rule.end)
        assertEquals(rule.end, (roundTrip<RecurrenceRule>(rule) as RecurrenceRule.StructuredRule).end)
    }
}
