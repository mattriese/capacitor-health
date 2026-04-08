package app.capgo.plugin.health

import java.time.Duration
import java.time.Instant
import kotlin.math.roundToInt

data class HeartRateIntervalNotificationConfig(
    val commitmentId: String,
    val taskName: String,
    val thresholdBpm: Int,
    val maxOrMin: String,
    val intervalInMinutes: Int,
    val completionMetric: Int,
    val completionMetricType: String,
    val timePeriodStartAt: Instant,
    val timePeriodEndAt: Instant,
    val reminderLeadMinutes: Int,
    val staleAfterMinutes: Int
)

data class HeartRateNotificationSample(
    val observedAt: Instant,
    val bpm: Int
)

data class ScheduledHeartRateIntervalReminder(
    val commitmentId: String,
    val title: String,
    val body: String,
    val scheduleAt: Instant,
    val dueAt: Instant
)

private data class HeartRateBucketThresholdSeconds(
    val bucketStart: Instant,
    val bucketEnd: Instant,
    val secondsAtOrAboveThreshold: Int
)

object HeartRateIntervalNotificationLogic {
    private const val HEART_RATE_BUCKET_SECONDS = 30L
    private const val HEART_RATE_EVENT_BREAK_SECONDS = 60L

    fun computeReminder(
        config: HeartRateIntervalNotificationConfig,
        samples: List<HeartRateNotificationSample>,
        now: Instant
    ): ScheduledHeartRateIntervalReminder? {
        if (config.maxOrMin != "max") {
            return null
        }
        if (now.isBefore(config.timePeriodStartAt) || !now.isBefore(config.timePeriodEndAt)) {
            return null
        }

        val relevantSamples = samples
            .asSequence()
            .filter { !it.observedAt.isBefore(config.timePeriodStartAt) && !it.observedAt.isAfter(now) }
            .sortedBy { it.observedAt }
            .toList()

        val requiredSeconds = when (config.completionMetricType) {
            "quantity" -> 1
            "seconds" -> config.completionMetric
            else -> return null
        }

        val lastEventAt = computeHeartRateEvents(
            bucketizeSamples(relevantSamples, config.thresholdBpm),
            requiredSeconds
        ).lastOrNull()

        val referenceTime = lastEventAt ?: config.timePeriodStartAt
        val dueAt = referenceTime.plus(Duration.ofMinutes(config.intervalInMinutes.toLong()))
        if (!dueAt.isAfter(now)) {
            return null
        }

        val rawScheduleAt = dueAt.minus(Duration.ofMinutes(config.reminderLeadMinutes.toLong()))
        val scheduleAt = if (!rawScheduleAt.isAfter(now)) {
            floorToMinute(now)
        } else {
            floorToMinute(rawScheduleAt)
        }

        return ScheduledHeartRateIntervalReminder(
            commitmentId = config.commitmentId,
            title = "Interval Reminder",
            body = buildBody(config),
            scheduleAt = scheduleAt,
            dueAt = dueAt
        )
    }

    private fun bucketizeSamples(
        samples: List<HeartRateNotificationSample>,
        thresholdBpm: Int
    ): List<HeartRateBucketThresholdSeconds> {
        val grouped = linkedMapOf<Instant, MutableList<Int>>()

        for (sample in samples) {
            val bucketStart = floorToThirtySeconds(sample.observedAt)
            val bucketValues = grouped.getOrPut(bucketStart) { mutableListOf() }
            bucketValues.add(sample.bpm)
        }

        return grouped.entries.map { (bucketStart, bpmValues) ->
            val sampleCount = bpmValues.size.coerceAtLeast(1)
            val countAtOrAbove = bpmValues.count { it >= thresholdBpm }
            val secondsAtOrAbove = ((countAtOrAbove.toDouble() / sampleCount) * 30.0)
                .roundToInt()
                .coerceIn(0, 30)

            HeartRateBucketThresholdSeconds(
                bucketStart = bucketStart,
                bucketEnd = bucketStart.plusSeconds(HEART_RATE_BUCKET_SECONDS),
                secondsAtOrAboveThreshold = secondsAtOrAbove
            )
        }.sortedBy { it.bucketStart }
    }

    private fun computeHeartRateEvents(
        buckets: List<HeartRateBucketThresholdSeconds>,
        requiredSeconds: Int
    ): List<Instant> {
        val events = mutableListOf<Instant>()
        var accumulatedSeconds = 0
        var previousBucketStart: Instant? = null

        for (bucket in buckets) {
            if (
                previousBucketStart != null &&
                Duration.between(previousBucketStart, bucket.bucketStart).seconds > HEART_RATE_EVENT_BREAK_SECONDS
            ) {
                accumulatedSeconds = 0
            }

            if (bucket.secondsAtOrAboveThreshold <= 0) {
                accumulatedSeconds = 0
            } else {
                accumulatedSeconds += bucket.secondsAtOrAboveThreshold
            }

            if (accumulatedSeconds >= requiredSeconds) {
                events.add(bucket.bucketEnd)
                accumulatedSeconds = 0
            }

            previousBucketStart = bucket.bucketStart
        }

        return events
    }

    private fun floorToThirtySeconds(instant: Instant): Instant {
        val epochSecond = instant.epochSecond
        return Instant.ofEpochSecond(epochSecond - (epochSecond % HEART_RATE_BUCKET_SECONDS))
    }

    private fun floorToMinute(instant: Instant): Instant {
        val epochSecond = instant.epochSecond
        return Instant.ofEpochSecond(epochSecond - (epochSecond % 60))
    }

    private fun buildBody(config: HeartRateIntervalNotificationConfig): String {
        val actionText = if (config.completionMetricType == "quantity") {
            "get your heart rate to ${config.thresholdBpm}+ BPM"
        } else {
            val minutes = config.completionMetric / 60.0
            val roundedMinutes = minutes.roundToInt()
            if (roundedMinutes > 0 && roundedMinutes.toDouble() == minutes) {
                "keep your heart rate at ${config.thresholdBpm}+ BPM for ${roundedMinutes} min"
            } else {
                "keep your heart rate at ${config.thresholdBpm}+ BPM"
            }
        }

        return "${config.reminderLeadMinutes}m warning: ${actionText} before the ${config.intervalInMinutes}m interval ends."
    }
}
