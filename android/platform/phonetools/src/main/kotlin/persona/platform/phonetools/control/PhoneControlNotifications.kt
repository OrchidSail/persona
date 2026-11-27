package persona.platform.phonetools.control

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import persona.platform.phonetools.R
import java.util.concurrent.atomic.AtomicReference

internal interface PhoneControlNotificationHandler {
    fun onNotificationOpenInline(taskId: String)
    fun onNotificationOpenFullscreen(taskId: String)
    fun onNotificationCancelTask(taskId: String)
}

internal object PhoneControlNotificationBridge {
    private val handlerRef = AtomicReference<PhoneControlNotificationHandler?>(null)

    fun register(handler: PhoneControlNotificationHandler) {
        handlerRef.set(handler)
    }

    fun unregister(handler: PhoneControlNotificationHandler) {
        val _ = handlerRef.compareAndSet(handler, null)
    }

    internal fun dispatch(action: String, taskId: String) {
        val h = handlerRef.get() ?: return
        when (action) {
            PhoneControlNotifications.ACTION_OPEN_INLINE -> h.onNotificationOpenInline(taskId)
            PhoneControlNotifications.ACTION_OPEN_FULLSCREEN -> h.onNotificationOpenFullscreen(taskId)
            PhoneControlNotifications.ACTION_CANCEL_TASK -> h.onNotificationCancelTask(taskId)
        }
    }
}

class PhoneControlNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val taskId = intent.getStringExtra(PhoneControlNotifications.EXTRA_TASK_ID).orEmpty()
        PhoneControlNotificationBridge.dispatch(action, taskId)
    }
}

/**
 * Non-intrusive notification coordinator for Plan C phone control:
 * - High-priority heads-up channel [CHANNEL_ATTENTION] (`phone_control_attention`) alerts the user
 *   with inline / fullscreen / cancel quick actions without ever stealing main-screen foreground.
 * - Default-importance channel [CHANNEL_RESULT] (`phone_control_result`) posts calm terminal outcomes.
 */
internal class PhoneControlNotifications(
    private val context: Context,
) {
    private val appContext = context.applicationContext
    private val manager: NotificationManager?
        get() = appContext.getSystemService(NotificationManager::class.java)

    fun showAttentionBanner(
        request: HandoverRequest,
        appLabel: String?,
        canInline: Boolean,
    ): Boolean {
        val nm = manager ?: return false
        return runCatching {
            ensureChannels(nm)
            if (!nm.areNotificationsEnabled()) return@runCatching false

            val useInlinePrimary = canInline && !request.requiresForeground
            val title = if (!appLabel.isNullOrBlank()) {
                appContext.getString(R.string.phonetools_notif_attention_title_app, appLabel)
            } else {
                appContext.getString(R.string.phonetools_notif_attention_title)
            }
            val body = request.reason.ifBlank {
                appContext.getString(R.string.phonetools_overlay_attention_msg)
            }

            val defaultAction = if (useInlinePrimary) ACTION_OPEN_INLINE else ACTION_OPEN_FULLSCREEN
            val builder = Notification.Builder(appContext, CHANNEL_ATTENTION)
                .setSmallIcon(persona.core.designsystem.R.drawable.ic_stat)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(Notification.BigTextStyle().bigText(body))
                .setCategory(Notification.CATEGORY_REMINDER)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setOnlyAlertOnce(true)
                .setAutoCancel(false)
                .setContentIntent(buildBroadcastPendingIntent(defaultAction, request.taskId, 10))

            if (useInlinePrimary) {
                val _ = builder.addAction(
                    Notification.Action.Builder(
                        null,
                        appContext.getString(R.string.phonetools_notif_action_inline),
                        buildBroadcastPendingIntent(ACTION_OPEN_INLINE, request.taskId, 11),
                    ).build(),
                )
                val _ = builder.addAction(
                    Notification.Action.Builder(
                        null,
                        appContext.getString(R.string.phonetools_notif_action_fullscreen),
                        buildBroadcastPendingIntent(ACTION_OPEN_FULLSCREEN, request.taskId, 12),
                    ).build(),
                )
            } else {
                val _ = builder.addAction(
                    Notification.Action.Builder(
                        null,
                        appContext.getString(R.string.phonetools_notif_action_fullscreen_primary),
                        buildBroadcastPendingIntent(ACTION_OPEN_FULLSCREEN, request.taskId, 12),
                    ).build(),
                )
            }
            val _ = builder.addAction(
                Notification.Action.Builder(
                    null,
                    appContext.getString(R.string.phonetools_notif_action_cancel),
                    buildBroadcastPendingIntent(ACTION_CANCEL_TASK, request.taskId, 13),
                ).build(),
            )

            nm.notify(NOTIF_ID_ATTENTION, builder.build())
            true
        }.getOrDefault(false)
    }

    fun cancelAttentionBanner() {
        val _ = runCatching { manager?.cancel(NOTIF_ID_ATTENTION) }
    }

    fun showResultNotification(status: String, summary: String, isTimeout: Boolean = false) {
        val nm = manager ?: return
        val _ = runCatching {
            ensureChannels(nm)
            if (!nm.areNotificationsEnabled()) return@runCatching
            val titleRes = when (status) {
                "done" -> R.string.phonetools_notif_result_done_title
                "cancelled" -> R.string.phonetools_notif_result_cancelled_title
                else -> R.string.phonetools_notif_result_failed_title
            }
            val body = when {
                isTimeout -> appContext.getString(R.string.phonetools_notif_result_timeout_body)
                summary.isNotBlank() -> summary
                else -> appContext.getString(titleRes)
            }
            val builder = Notification.Builder(appContext, CHANNEL_RESULT)
                .setSmallIcon(persona.core.designsystem.R.drawable.ic_stat)
                .setContentTitle(appContext.getString(titleRes))
                .setContentText(body)
                .setStyle(Notification.BigTextStyle().bigText(body))
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setAutoCancel(true)
            appContext.packageManager.getLaunchIntentForPackage(appContext.packageName)?.let { launchIntent ->
                val _ = builder.setContentIntent(
                    PendingIntent.getActivity(
                        appContext,
                        20,
                        launchIntent,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    ),
                )
            }
            nm.notify(NOTIF_ID_RESULT, builder.build())
        }
    }

    private fun ensureChannels(nm: NotificationManager) {
        val attention = NotificationChannel(
            CHANNEL_ATTENTION,
            appContext.getString(R.string.phonetools_notif_attention_channel),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            enableVibration(true)
            vibrationPattern = longArrayOf(0L, 120L, 80L, 120L)
        }
        val result = NotificationChannel(
            CHANNEL_RESULT,
            appContext.getString(R.string.phonetools_notif_result_channel),
            NotificationManager.IMPORTANCE_DEFAULT,
        )
        nm.createNotificationChannel(attention)
        nm.createNotificationChannel(result)
    }

    private fun buildBroadcastPendingIntent(action: String, taskId: String, reqCode: Int): PendingIntent {
        val intent = Intent(appContext, PhoneControlNotificationReceiver::class.java)
            .setAction(action)
            .setData(Uri.parse("persona-phone://control/$action/$taskId"))
            .putExtra(EXTRA_TASK_ID, taskId)
        return PendingIntent.getBroadcast(
            appContext,
            reqCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    companion object {
        const val CHANNEL_ATTENTION = "phone_control_attention"
        const val CHANNEL_RESULT = "phone_control_result"
        const val ACTION_OPEN_INLINE = "persona.phone.control.OPEN_INLINE"
        const val ACTION_OPEN_FULLSCREEN = "persona.phone.control.OPEN_FULLSCREEN"
        const val ACTION_CANCEL_TASK = "persona.phone.control.CANCEL_TASK"
        const val EXTRA_TASK_ID = "task_id"
        const val NOTIF_ID_ATTENTION = 41001
        const val NOTIF_ID_RESULT = 41002
    }
}
