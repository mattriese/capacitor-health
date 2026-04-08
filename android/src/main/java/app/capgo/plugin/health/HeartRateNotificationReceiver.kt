package app.capgo.plugin.health

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.Instant

class HeartRateNotificationReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "HrNotifReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val manager = HeartRateNotificationManager(context)
        when (intent.action) {
            HeartRateNotificationManager.ACTION_RECONCILE_HEART_RATE_INTERVAL_NOTIFICATIONS -> {
                val pendingResult = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        manager.reconcileNotifications(Instant.now())
                    } catch (error: Exception) {
                        Log.e(TAG, "Failed reconciling HR interval notifications", error)
                    } finally {
                        pendingResult.finish()
                    }
                }
            }
            HeartRateNotificationManager.ACTION_SHOW_HEART_RATE_INTERVAL_REMINDER -> {
                val commitmentId = intent.getStringExtra(HeartRateNotificationManager.EXTRA_COMMITMENT_ID)
                val title = intent.getStringExtra(HeartRateNotificationManager.EXTRA_TITLE)
                val body = intent.getStringExtra(HeartRateNotificationManager.EXTRA_BODY)
                val dueAt = intent.getStringExtra(HeartRateNotificationManager.EXTRA_DUE_AT)

                if (commitmentId == null || title == null || body == null) {
                    Log.w(TAG, "Reminder alarm fired with incomplete extras")
                    return
                }

                manager.showReminderNotification(
                    commitmentId = commitmentId,
                    title = title,
                    body = body,
                    dueAt = dueAt
                )
            }
            Intent.ACTION_BOOT_COMPLETED -> {
                manager.reregisterPersistedAlarms()
            }
            else -> {
                Log.w(TAG, "Received unknown action: ${intent.action}")
            }
        }
    }
}
