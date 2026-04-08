package app.capgo.plugin.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class HeartRateIntervalNotificationLogicTest {

    @Test
    fun `quantity mode schedules from last HR spike event`() {
        val config = HeartRateIntervalNotificationConfig(
            commitmentId = "commitment-1",
            taskName = "__fitness_hr_90__",
            thresholdBpm = 90,
            maxOrMin = "max",
            intervalInMinutes = 120,
            completionMetric = 1,
            completionMetricType = "quantity",
            timePeriodStartAt = Instant.parse("2026-04-08T10:00:00Z"),
            timePeriodEndAt = Instant.parse("2026-04-08T22:00:00Z"),
            reminderLeadMinutes = 10,
            staleAfterMinutes = 15
        )
        val reminder = HeartRateIntervalNotificationLogic.computeReminder(
            config = config,
            samples = listOf(
                HeartRateNotificationSample(
                    observedAt = Instant.parse("2026-04-08T11:00:05Z"),
                    bpm = 95
                )
            ),
            now = Instant.parse("2026-04-08T11:30:00Z")
        )

        assertNotNull(reminder)
        assertEquals(
            Instant.parse("2026-04-08T12:50:00Z"),
            reminder?.scheduleAt
        )
        assertEquals(
            Instant.parse("2026-04-08T13:00:30Z"),
            reminder?.dueAt
        )
    }

    @Test
    fun `seconds mode accumulates across adjacent buckets`() {
        val config = HeartRateIntervalNotificationConfig(
            commitmentId = "commitment-2",
            taskName = "__fitness_hr_120__",
            thresholdBpm = 120,
            maxOrMin = "max",
            intervalInMinutes = 120,
            completionMetric = 60,
            completionMetricType = "seconds",
            timePeriodStartAt = Instant.parse("2026-04-08T10:00:00Z"),
            timePeriodEndAt = Instant.parse("2026-04-08T22:00:00Z"),
            reminderLeadMinutes = 10,
            staleAfterMinutes = 15
        )
        val reminder = HeartRateIntervalNotificationLogic.computeReminder(
            config = config,
            samples = listOf(
                HeartRateNotificationSample(
                    observedAt = Instant.parse("2026-04-08T11:00:05Z"),
                    bpm = 125
                ),
                HeartRateNotificationSample(
                    observedAt = Instant.parse("2026-04-08T11:00:15Z"),
                    bpm = 130
                ),
                HeartRateNotificationSample(
                    observedAt = Instant.parse("2026-04-08T11:00:35Z"),
                    bpm = 128
                ),
                HeartRateNotificationSample(
                    observedAt = Instant.parse("2026-04-08T11:00:45Z"),
                    bpm = 132
                )
            ),
            now = Instant.parse("2026-04-08T11:05:00Z")
        )

        assertNotNull(reminder)
        assertEquals(
            Instant.parse("2026-04-08T12:50:00Z"),
            reminder?.scheduleAt
        )
        assertEquals(
            Instant.parse("2026-04-08T13:01:00Z"),
            reminder?.dueAt
        )
    }

    @Test
    fun `returns null after the interval is already due`() {
        val config = HeartRateIntervalNotificationConfig(
            commitmentId = "commitment-3",
            taskName = "__fitness_hr_90__",
            thresholdBpm = 90,
            maxOrMin = "max",
            intervalInMinutes = 120,
            completionMetric = 1,
            completionMetricType = "quantity",
            timePeriodStartAt = Instant.parse("2026-04-08T10:00:00Z"),
            timePeriodEndAt = Instant.parse("2026-04-08T22:00:00Z"),
            reminderLeadMinutes = 10,
            staleAfterMinutes = 15
        )

        val reminder = HeartRateIntervalNotificationLogic.computeReminder(
            config = config,
            samples = emptyList(),
            now = Instant.parse("2026-04-08T12:30:00Z")
        )

        assertNull(reminder)
    }
}
