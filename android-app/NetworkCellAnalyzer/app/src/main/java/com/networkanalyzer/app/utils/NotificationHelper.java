package com.networkanalyzer.app.utils;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.networkanalyzer.app.R;
import com.networkanalyzer.app.activities.MainActivity;

/**
 * Utility class for building and posting notifications used by the cell
 * monitoring service and its related alert subsystems.
 * <p>
 * Three categories of notifications are supported:
 * <ol>
 *     <li><b>Service notification</b> -- the persistent foreground-service
 *         notification that displays current signal information.</li>
 *     <li><b>Signal alerts</b> -- high-priority notifications posted when
 *         the signal power drops below the configured threshold.</li>
 *     <li><b>Handover notifications</b> -- informational notifications
 *         posted when a cell handover event is detected.</li>
 * </ol>
 * <p>
 * All notification channels are created in
 * {@link com.networkanalyzer.app.NetworkAnalyzerApp#onCreate()}.
 */
public final class NotificationHelper {

    private NotificationHelper() {
        // Utility class -- no instances.
    }

    // -------------------------------------------------------------------------
    // Foreground Service Notification
    // -------------------------------------------------------------------------

    /**
     * Builds (or updates) the persistent foreground-service notification.
     * <p>
     * The returned {@link Notification} object should be passed to
     * {@link android.app.Service#startForeground(int, Notification)}.
     *
     * @param context application or service context
     * @param title   notification title (e.g. "Monitoring Active")
     * @param text    notification body text (e.g. "LTE  -87 dBm  Cell 12345")
     * @return the built {@link Notification}
     */
    @NonNull
    public static Notification showServiceNotification(@NonNull Context context,
                                                        @NonNull String title,
                                                        @NonNull String text) {
        PendingIntent contentIntent = buildMainActivityPendingIntent(context);

        Notification notification = new NotificationCompat.Builder(context, Constants.CHANNEL_SERVICE)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(contentIntent)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .build();

        // Also push the update through NotificationManagerCompat so the
        // existing notification is refreshed if it is already visible.
        try {
            NotificationManagerCompat.from(context)
                    .notify(Constants.NOTIFICATION_SERVICE, notification);
        } catch (SecurityException ignored) {
            // POST_NOTIFICATIONS permission may not have been granted.
        }

        return notification;
    }

    // -------------------------------------------------------------------------
    // Signal Alert Notification
    // -------------------------------------------------------------------------

    /**
     * Posts a high-priority alert notification informing the user that the
     * signal has dropped below the configured threshold.
     *
     * @param context application or service context
     * @param title   alert title
     * @param message alert body text
     */
    public static void showSignalAlert(@NonNull Context context,
                                        @NonNull String title,
                                        @NonNull String message) {
        PendingIntent contentIntent = buildMainActivityPendingIntent(context);

        Notification notification = new NotificationCompat.Builder(context, Constants.CHANNEL_ALERTS)
                .setContentTitle(title)
                .setContentText(message)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setAutoCancel(true)
                .setContentIntent(contentIntent)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(message))
                .build();

        try {
            NotificationManagerCompat.from(context)
                    .notify(Constants.NOTIFICATION_ALERT, notification);
        } catch (SecurityException ignored) {
            // POST_NOTIFICATIONS permission may not have been granted.
        }
    }

    // -------------------------------------------------------------------------
    // Handover Notification
    // -------------------------------------------------------------------------

    /**
     * Posts a notification informing the user that a cell handover has occurred.
     *
     * @param context  application or service context
     * @param fromCell cell identity before the handover
     * @param toCell   cell identity after the handover
     */
    public static void showHandoverNotification(@NonNull Context context,
                                                 @NonNull String fromCell,
                                                 @NonNull String toCell) {
        PendingIntent contentIntent = buildMainActivityPendingIntent(context);

        String title = "Cell Handover Detected";
        String message = "Switched from cell " + fromCell + " to cell " + toCell;

        Notification notification = new NotificationCompat.Builder(context, Constants.CHANNEL_HANDOVER)
                .setContentTitle(title)
                .setContentText(message)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setAutoCancel(true)
                .setContentIntent(contentIntent)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(message))
                .build();

        try {
            NotificationManagerCompat.from(context)
                    .notify(Constants.NOTIFICATION_HANDOVER, notification);
        } catch (SecurityException ignored) {
            // POST_NOTIFICATIONS permission may not have been granted.
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Builds a {@link PendingIntent} that opens {@link MainActivity} when the
     * notification is tapped.
     */
    @NonNull
    private static PendingIntent buildMainActivityPendingIntent(@NonNull Context context) {
        Intent intent = new Intent(context, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }

        return PendingIntent.getActivity(context, 0, intent, flags);
    }
}
