package com.jadoo.amp.update

import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.jadoo.amp.R

/**
 * Receives the outcome of a PackageInstaller session started by [ApkUpdater].
 *
 * The important case is [PackageInstaller.STATUS_PENDING_USER_ACTION]: a
 * committed session does NOT install anything on its own — the system hands
 * back an Intent that must be launched to show the user the install
 * confirmation screen. Dropping it (the easy mistake here) makes the update
 * appear to silently do nothing.
 */
class InstallResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION") intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                }
                if (confirmation == null) {
                    Log.w(TAG, "Pending user action with no confirmation intent")
                    return
                }
                // Arrives with no activity on the stack in the general case, so
                // it needs its own task.
                confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                showConfirmation(context, confirmation)
            }

            PackageInstaller.STATUS_SUCCESS -> {
                cancelPrompt(context)
                // The update kills this process; it is not relaunched
                // automatically. PackageReplacedReceiver brings the DSP
                // service back so processing resumes without the user having
                // to reopen the app.
                Log.i(TAG, "Update installed successfully")
            }

            else -> {
                cancelPrompt(context)
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                Log.w(TAG, "Install failed (status=$status): $message")
                // STATUS_FAILURE_ABORTED is the user declining the system
                // prompt — expected, not worth a toast.
                if (status != PackageInstaller.STATUS_FAILURE_ABORTED) {
                    Toast.makeText(
                        context,
                        "Update install failed${if (message != null) ": $message" else ""}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    /**
     * Launches the system install confirmation, or — when Android will not
     * let us — posts a notification that launches it on tap.
     *
     * Since Android 10, an app with no visible window cannot start an
     * activity, and a BroadcastReceiver is not an exemption. The APK is tens
     * of megabytes, so leaving the app while it downloads is normal; when the
     * session is committed afterwards, startActivity() is silently dropped —
     * no exception, no prompt, the update simply never appears. A running
     * foreground service does not count as visible either, so JadOO's own DSP
     * service does not help here.
     *
     * A notification's content intent is launched by the user's tap, which
     * IS allowed from the background, so it is the one route that works on
     * every API level and every OEM's background-launch policy.
     */
    private fun showConfirmation(context: Context, confirmation: Intent) {
        if (isAppVisible()) {
            try {
                context.startActivity(confirmation)
                return
            } catch (e: Exception) {
                Log.w(TAG, "Direct install confirmation launch failed, falling back to notification", e)
            }
        }
        postPrompt(context, confirmation)
    }

    private fun isAppVisible(): Boolean {
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        // IMPORTANCE_FOREGROUND means an activity is on screen. The DSP
        // service alone only reaches IMPORTANCE_FOREGROUND_SERVICE, which
        // does not lift the background-activity-launch restriction.
        return info.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }

    private fun postPrompt(context: Context, confirmation: Intent) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "App updates", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val tap = PendingIntent.getActivity(
            context, NOTIFICATION_ID, confirmation,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("JadOO DSP update ready")
            .setContentText("Tap to install")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        try {
            nm.notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS denied on API 33+. Nothing left to show the
            // prompt with; the dialog's Install button still works on retry.
            Log.w(TAG, "Cannot post install prompt: notifications not permitted")
        }
    }

    private fun cancelPrompt(context: Context) {
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .cancel(NOTIFICATION_ID)
    }

    private companion object {
        const val TAG = "InstallResultReceiver"
        const val CHANNEL_ID = "jadoo_updates"
        const val NOTIFICATION_ID = 7201
    }
}
