package com.networkanalyzer.app;

import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.os.Build;

import com.networkanalyzer.app.services.OfflineSyncWorker;
import com.networkanalyzer.app.utils.Constants;
import com.networkanalyzer.app.utils.PreferenceManager;

public class NetworkAnalyzerApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        PreferenceManager.init(this);
        createNotificationChannels();
        OfflineSyncWorker.schedule(this);
    }

    private void createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager == null) {
                return;
            }

            // Foreground service channel
            NotificationChannel serviceChannel = new NotificationChannel(
                    Constants.CHANNEL_SERVICE,
                    "Cell Monitoring Service",
                    NotificationManager.IMPORTANCE_LOW
            );
            serviceChannel.setDescription("Persistent notification while monitoring cell data");
            serviceChannel.setShowBadge(false);
            manager.createNotificationChannel(serviceChannel);

            // Alert channel
            NotificationChannel alertChannel = new NotificationChannel(
                    Constants.CHANNEL_ALERTS,
                    "Signal Alerts",
                    NotificationManager.IMPORTANCE_HIGH
            );
            alertChannel.setDescription("Alerts for signal quality changes and thresholds");
            alertChannel.enableVibration(true);
            manager.createNotificationChannel(alertChannel);

            // Handover channel
            NotificationChannel handoverChannel = new NotificationChannel(
                    Constants.CHANNEL_HANDOVER,
                    "Handover Events",
                    NotificationManager.IMPORTANCE_DEFAULT
            );
            handoverChannel.setDescription("Notifications for cell handover events");
            manager.createNotificationChannel(handoverChannel);
        }
    }
}
