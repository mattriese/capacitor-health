package app.capgo.plugin.health

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.feature.ExperimentalFeatureAvailabilityApi
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import kotlin.math.absoluteValue
import kotlin.math.roundToInt

class HeartRateNotificationManager(private val context: Context) {

    companion object {
        private const val TAG = "HrNotifManager"
        private const val PREFS_NAME = "health_hr_interval_notifications"
        private const val KEY_GENERATED_AT = "generated_at"
        private const val KEY_CONFIGS = "configs"
        private const val KEY_SCHEDULED_REMINDERS = "scheduled_reminders"
        private const val KEY_NEXT_RECONCILE_AT = "next_reconcile_at"
        private const val KEY_LAST_RECONCILED_AT = "last_reconciled_at"
        private const val REQUEST_CODE_BASE_REMINDER = 41000
        private const val REQUEST_CODE_RECONCILE = 41999
        private const val REMINDER_CHANNEL_ID = "hr_interval_reminders"
        private const val REMINDER_CHANNEL_NAME = "Heart Rate Interval Reminders"
        private const val RECONCILE_INTERVAL_MINUTES = 5L

        const val ACTION_RECONCILE_HEART_RATE_INTERVAL_NOTIFICATIONS =
            "app.capgo.plugin.health.ACTION_RECONCILE_HEART_RATE_INTERVAL_NOTIFICATIONS"
        const val ACTION_SHOW_HEART_RATE_INTERVAL_REMINDER =
            "app.capgo.plugin.health.ACTION_SHOW_HEART_RATE_INTERVAL_REMINDER"
        const val EXTRA_COMMITMENT_ID = "commitmentId"
        const val EXTRA_TITLE = "title"
        const val EXTRA_BODY = "body"
        const val EXTRA_DUE_AT = "dueAt"
    }

    data class PersistedScheduledReminder(
        val commitmentId: String,
        val title: String,
        val body: String,
        val scheduleAt: Instant,
        val dueAt: Instant
    )

    suspend fun configureHeartRateIntervalNotifications(
        generatedAt: String,
        commitments: List<HeartRateIntervalNotificationConfig>,
        now: Instant = Instant.now()
    ) {
        persistConfigs(generatedAt, commitments)
        reconcileNotifications(now)
    }

    fun clearHeartRateIntervalNotifications() {
        persistConfigs(null, emptyList())
        loadScheduledReminders().values.forEach { cancelReminder(it.commitmentId) }
        cancelReconcileAlarm()
        persistScheduledReminders(emptyList())
        setNextReconcileAt(null)
        setLastReconciledAt(null)
    }

    suspend fun reconcileNotifications(now: Instant = Instant.now()) {
        val client = getClientOrNull()
        if (client == null || !isBackgroundReadGranted(client)) {
            clearHeartRateIntervalNotifications()
            return
        }

        val configs = loadConfigs()
        val existing = loadScheduledReminders().toMutableMap()
        val desiredReminderStates = mutableListOf<PersistedScheduledReminder>()
        val desiredIds = mutableSetOf<String>()

        for (config in configs) {
            val reminder = if (now.isBefore(config.timePeriodStartAt)) {
                null
            } else {
                val samples = readHeartRateSamples(
                    client = client,
                    startAt = config.timePeriodStartAt,
                    endAt = now
                )
                HeartRateIntervalNotificationLogic.computeReminder(config, samples, now)
            }

            if (reminder == null) {
                if (existing.containsKey(config.commitmentId)) {
                    cancelReminder(config.commitmentId)
                }
                continue
            }

            desiredIds.add(config.commitmentId)
            val desired = PersistedScheduledReminder(
                commitmentId = reminder.commitmentId,
                title = reminder.title,
                body = reminder.body,
                scheduleAt = reminder.scheduleAt,
                dueAt = reminder.dueAt
            )
            desiredReminderStates.add(desired)

            val current = existing[config.commitmentId]
            if (current == null || current != desired) {
                scheduleReminder(desired)
            }
        }

        existing.keys.forEach { commitmentId ->
            if (!desiredIds.contains(commitmentId)) {
                cancelReminder(commitmentId)
            }
        }

        persistScheduledReminders(desiredReminderStates)
        setLastReconciledAt(now.toString())
        scheduleNextReconcile(now, configs)
    }

    fun reregisterPersistedAlarms() {
        loadScheduledReminders().values.forEach { scheduleReminder(it) }
        loadNextReconcileAt()?.let { scheduleReconcileAlarm(it) }
    }

    fun showReminderNotification(
        commitmentId: String,
        title: String,
        body: String,
        dueAt: String?
    ) {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "Notification permission missing, skipping HR reminder delivery")
            return
        }

        createReminderChannel()

        val launchIntent = context.packageManager
            .getLaunchIntentForPackage(context.packageName)
            ?.apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
        val contentIntent = launchIntent?.let {
            PendingIntent.getActivity(
                context,
                requestCodeForCommitment(commitmentId),
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, REMINDER_CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(Notification.BigTextStyle().bigText(body))
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setAutoCancel(true)
                .setContentIntent(contentIntent)
                .apply {
                    if (dueAt != null) {
                        setSubText("Due by $dueAt")
                    }
                }
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(Notification.BigTextStyle().bigText(body))
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setAutoCancel(true)
                .setContentIntent(contentIntent)
                .build()
        }

        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(notificationIdForCommitment(commitmentId), notification)
    }

    fun getDebugState(): JSObject {
        val generatedAt = prefs().getString(KEY_GENERATED_AT, null)
        val configsArray = JSArray()
        loadConfigs().forEach { config ->
            configsArray.put(JSObject().apply {
                put("commitmentId", config.commitmentId)
                put("taskName", config.taskName)
                put("thresholdBpm", config.thresholdBpm)
                put("maxOrMin", config.maxOrMin)
                put("intervalInMinutes", config.intervalInMinutes)
                put("completionMetric", config.completionMetric)
                put("completionMetricType", config.completionMetricType)
                put("timePeriodStartAt", config.timePeriodStartAt.toString())
                put("timePeriodEndAt", config.timePeriodEndAt.toString())
                put("reminderLeadMinutes", config.reminderLeadMinutes)
                put("staleAfterMinutes", config.staleAfterMinutes)
            })
        }

        val scheduledArray = JSArray()
        loadScheduledReminders().values.forEach { reminder ->
            scheduledArray.put(JSObject().apply {
                put("commitmentId", reminder.commitmentId)
                put("title", reminder.title)
                put("body", reminder.body)
                put("scheduleAt", reminder.scheduleAt.toString())
                put("dueAt", reminder.dueAt.toString())
            })
        }

        val availability = backgroundReadAvailability()
        val granted = getClientOrNull()?.let { client -> runCatching { isBackgroundReadGranted(client) }.getOrDefault(false) }
            ?: false

        return JSObject().apply {
            put("generatedAt", generatedAt)
            put("commitments", configsArray)
            put("scheduledReminders", scheduledArray)
            put("nextReconcileAt", loadNextReconcileAt()?.toString())
            put("lastReconciledAt", prefs().getString(KEY_LAST_RECONCILED_AT, null))
            put("backgroundReadAvailable", availability)
            put("backgroundReadGranted", granted)
        }
    }

    @OptIn(ExperimentalFeatureAvailabilityApi::class)
    fun backgroundReadAvailability(): Boolean {
        val client = getClientOrNull() ?: return false
        return client.features.getFeatureStatus(
            HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND
        ) == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
    }

    @OptIn(ExperimentalFeatureAvailabilityApi::class)
    fun isBackgroundReadGranted(client: HealthConnectClient): Boolean {
        val available = client.features.getFeatureStatus(
            HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND
        ) == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
        if (!available) {
            return false
        }

        return client.permissionController
            .getGrantedPermissions()
            .contains(HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND)
    }

    private fun scheduleNextReconcile(
        now: Instant,
        configs: List<HeartRateIntervalNotificationConfig>
    ) {
        val futureCandidates = mutableListOf<Instant>()
        val pollCandidate = now.plus(Duration.ofMinutes(RECONCILE_INTERVAL_MINUTES))

        configs.forEach { config ->
            when {
                now.isBefore(config.timePeriodStartAt) -> futureCandidates.add(config.timePeriodStartAt)
                now.isBefore(config.timePeriodEndAt) -> futureCandidates.add(pollCandidate)
            }
        }

        val nextReconcileAt = futureCandidates.minOrNull()
        if (nextReconcileAt == null) {
            cancelReconcileAlarm()
            setNextReconcileAt(null)
            return
        }

        scheduleReconcileAlarm(nextReconcileAt)
        setNextReconcileAt(nextReconcileAt.toString())
    }

    private fun scheduleReminder(reminder: PersistedScheduledReminder) {
        val intent = Intent(context, HeartRateNotificationReceiver::class.java).apply {
            action = ACTION_SHOW_HEART_RATE_INTERVAL_REMINDER
            putExtra(EXTRA_COMMITMENT_ID, reminder.commitmentId)
            putExtra(EXTRA_TITLE, reminder.title)
            putExtra(EXTRA_BODY, reminder.body)
            putExtra(EXTRA_DUE_AT, reminder.dueAt.toString())
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCodeForCommitment(reminder.commitmentId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                reminder.scheduleAt.toEpochMilli(),
                pendingIntent
            )
            return
        }

        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            reminder.scheduleAt.toEpochMilli(),
            pendingIntent
        )
    }

    private fun cancelReminder(commitmentId: String) {
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCodeForCommitment(commitmentId),
            Intent(context, HeartRateNotificationReceiver::class.java).apply {
                action = ACTION_SHOW_HEART_RATE_INTERVAL_REMINDER
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(pendingIntent)
        pendingIntent.cancel()
    }

    private fun scheduleReconcileAlarm(triggerAt: Instant) {
        val intent = Intent(context, HeartRateNotificationReceiver::class.java).apply {
            action = ACTION_RECONCILE_HEART_RATE_INTERVAL_NOTIFICATIONS
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE_RECONCILE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAt.toEpochMilli(),
                pendingIntent
            )
            return
        }

        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            triggerAt.toEpochMilli(),
            pendingIntent
        )
    }

    private fun cancelReconcileAlarm() {
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE_RECONCILE,
            Intent(context, HeartRateNotificationReceiver::class.java).apply {
                action = ACTION_RECONCILE_HEART_RATE_INTERVAL_NOTIFICATIONS
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(pendingIntent)
        pendingIntent.cancel()
    }

    private suspend fun readHeartRateSamples(
        client: HealthConnectClient,
        startAt: Instant,
        endAt: Instant
    ): List<HeartRateNotificationSample> {
        val samples = mutableListOf<HeartRateNotificationSample>()
        var pageToken: String? = null

        do {
            val response = client.readRecords(
                ReadRecordsRequest(
                    recordType = HeartRateRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(startAt, endAt),
                    pageSize = 1000,
                    pageToken = pageToken
                )
            )

            response.records.forEach { record ->
                record.samples.forEach { sample ->
                    samples.add(
                        HeartRateNotificationSample(
                            observedAt = sample.time,
                            bpm = sample.beatsPerMinute.roundToInt()
                        )
                    )
                }
            }

            pageToken = response.pageToken
        } while (pageToken != null)

        return samples.sortedBy { it.observedAt }
    }

    private fun persistConfigs(
        generatedAt: String?,
        commitments: List<HeartRateIntervalNotificationConfig>
    ) {
        val array = JSONArray()
        commitments.forEach { config ->
            array.put(JSONObject().apply {
                put("commitmentId", config.commitmentId)
                put("taskName", config.taskName)
                put("thresholdBpm", config.thresholdBpm)
                put("maxOrMin", config.maxOrMin)
                put("intervalInMinutes", config.intervalInMinutes)
                put("completionMetric", config.completionMetric)
                put("completionMetricType", config.completionMetricType)
                put("timePeriodStartAt", config.timePeriodStartAt.toString())
                put("timePeriodEndAt", config.timePeriodEndAt.toString())
                put("reminderLeadMinutes", config.reminderLeadMinutes)
                put("staleAfterMinutes", config.staleAfterMinutes)
            })
        }

        prefs().edit().apply {
            putString(KEY_GENERATED_AT, generatedAt)
            putString(KEY_CONFIGS, array.toString())
            apply()
        }
    }

    private fun loadConfigs(): List<HeartRateIntervalNotificationConfig> {
        val raw = prefs().getString(KEY_CONFIGS, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).map { index ->
                val obj = array.getJSONObject(index)
                HeartRateIntervalNotificationConfig(
                    commitmentId = obj.getString("commitmentId"),
                    taskName = obj.getString("taskName"),
                    thresholdBpm = obj.getInt("thresholdBpm"),
                    maxOrMin = obj.getString("maxOrMin"),
                    intervalInMinutes = obj.getInt("intervalInMinutes"),
                    completionMetric = obj.getInt("completionMetric"),
                    completionMetricType = obj.getString("completionMetricType"),
                    timePeriodStartAt = Instant.parse(obj.getString("timePeriodStartAt")),
                    timePeriodEndAt = Instant.parse(obj.getString("timePeriodEndAt")),
                    reminderLeadMinutes = obj.getInt("reminderLeadMinutes"),
                    staleAfterMinutes = obj.getInt("staleAfterMinutes")
                )
            }
        } catch (error: Exception) {
            Log.e(TAG, "Failed to parse persisted HR notification configs", error)
            emptyList()
        }
    }

    private fun persistScheduledReminders(reminders: List<PersistedScheduledReminder>) {
        val array = JSONArray()
        reminders.forEach { reminder ->
            array.put(JSONObject().apply {
                put("commitmentId", reminder.commitmentId)
                put("title", reminder.title)
                put("body", reminder.body)
                put("scheduleAt", reminder.scheduleAt.toString())
                put("dueAt", reminder.dueAt.toString())
            })
        }
        prefs().edit().putString(KEY_SCHEDULED_REMINDERS, array.toString()).apply()
    }

    private fun loadScheduledReminders(): Map<String, PersistedScheduledReminder> {
        val raw = prefs().getString(KEY_SCHEDULED_REMINDERS, null) ?: return emptyMap()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).associate { index ->
                val obj = array.getJSONObject(index)
                val reminder = PersistedScheduledReminder(
                    commitmentId = obj.getString("commitmentId"),
                    title = obj.getString("title"),
                    body = obj.getString("body"),
                    scheduleAt = Instant.parse(obj.getString("scheduleAt")),
                    dueAt = Instant.parse(obj.getString("dueAt"))
                )
                reminder.commitmentId to reminder
            }
        } catch (error: Exception) {
            Log.e(TAG, "Failed to parse persisted HR scheduled reminders", error)
            emptyMap()
        }
    }

    private fun requestCodeForCommitment(commitmentId: String): Int {
        return REQUEST_CODE_BASE_REMINDER + (commitmentId.hashCode().absoluteValue % 900)
    }

    private fun notificationIdForCommitment(commitmentId: String): Int {
        return 51000 + (commitmentId.hashCode().absoluteValue % 9000)
    }

    private fun setNextReconcileAt(nextReconcileAt: String?) {
        prefs().edit().putString(KEY_NEXT_RECONCILE_AT, nextReconcileAt).apply()
    }

    private fun loadNextReconcileAt(): Instant? {
        return prefs().getString(KEY_NEXT_RECONCILE_AT, null)?.let {
            runCatching { Instant.parse(it) }.getOrNull()
        }
    }

    private fun setLastReconciledAt(lastReconciledAt: String?) {
        prefs().edit().putString(KEY_LAST_RECONCILED_AT, lastReconciledAt).apply()
    }

    private fun createReminderChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }

        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (notificationManager.getNotificationChannel(REMINDER_CHANNEL_ID) != null) {
            return
        }

        val channel = NotificationChannel(
            REMINDER_CHANNEL_ID,
            REMINDER_CHANNEL_NAME,
            NotificationManager.IMPORTANCE_DEFAULT
        )
        notificationManager.createNotificationChannel(channel)
    }

    private fun getClientOrNull(): HealthConnectClient? {
        val status = HealthConnectClient.getSdkStatus(context)
        if (status != HealthConnectClient.SDK_AVAILABLE) {
            return null
        }
        return HealthConnectClient.getOrCreate(context)
    }

    private fun prefs(): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
}
