package com.networkanalyzer.app.utils;

/**
 * Central registry of string/integer constants shared across the app:
 * notification-channel IDs, broadcast-intent actions, shared-prefs
 * keys, request-code constants, and default tuning parameters.
 * <p>
 * Non-instantiable; all members are {@code public static final}.
 */
public final class Constants {

    private Constants() {}

    // Notification Channels
    public static final String CHANNEL_SERVICE = "cell_monitor_service";
    public static final String CHANNEL_ALERTS = "signal_alerts";
    public static final String CHANNEL_HANDOVER = "handover_events";

    // Notification IDs
    public static final int NOTIFICATION_SERVICE = 1001;
    public static final int NOTIFICATION_ALERT = 1002;
    public static final int NOTIFICATION_HANDOVER = 1003;

    // Collection Interval (ms)
    public static final long DEFAULT_COLLECTION_INTERVAL = 10_000; // 10 seconds

    // Server defaults
    public static final String DEFAULT_SERVER_URL = "http://192.168.0.139:5000/";

    // SharedPreferences
    public static final String PREFS_NAME = "network_analyzer_prefs";
    public static final String PREF_SERVER_URL = "server_url";
    public static final String PREF_AUTH_TOKEN = "auth_token";
    public static final String PREF_REFRESH_TOKEN = "refresh_token";
    public static final String PREF_USER_EMAIL = "user_email";
    public static final String PREF_USER_NAME = "user_name";
    public static final String PREF_DEVICE_ID = "device_id";
    public static final String PREF_GUEST_MODE = "guest_mode";
    public static final String PREF_COLLECTION_INTERVAL = "collection_interval";
    public static final String PREF_MONITORING_ACTIVE = "monitoring_active";
    public static final String PREF_BIOMETRIC_ENABLED = "biometric_enabled";
    public static final String PREF_DARK_MODE = "dark_mode";
    public static final String PREF_SIGNAL_THRESHOLD = "signal_threshold";
    public static final String PREF_ALERT_ENABLED = "alert_enabled";

    // Network Types
    public static final String NETWORK_2G = "2G";
    public static final String NETWORK_3G = "3G";
    public static final String NETWORK_4G = "4G";
    public static final String NETWORK_5G = "5G";
    public static final String NETWORK_UNKNOWN = "Unknown";

    // Signal quality thresholds (dBm)
    public static final int SIGNAL_EXCELLENT = -65;
    public static final int SIGNAL_GOOD = -75;
    public static final int SIGNAL_FAIR = -85;
    public static final int SIGNAL_POOR = -95;
    public static final int SIGNAL_NO_SIGNAL = -120;

    // Intent Actions
    public static final String ACTION_CELL_DATA_UPDATED = "com.networkanalyzer.CELL_DATA_UPDATED";
    public static final String ACTION_HANDOVER_DETECTED = "com.networkanalyzer.HANDOVER_DETECTED";
    public static final String ACTION_STOP_MONITORING = "com.networkanalyzer.STOP_MONITORING";

    // API Endpoints (relative paths)
    public static final String API_LOGIN = "auth/login";
    public static final String API_REGISTER = "auth/register";
    public static final String API_REFRESH = "auth/refresh";
    public static final String API_RECEIVE_DATA = "api/cell/ingest";
    public static final String API_RECEIVE_BATCH = "api/cell/ingest/batch";
    public static final String API_GET_STATS = "api/stats/device";
    public static final String API_AVG_ALL = "api/stats/fleet";
    public static final String API_CENTRAL_STATS = "api/stats/central";
    public static final String API_SPEED_TEST = "speed-test";

    // Export
    public static final String EXPORT_DIR = "NetworkAnalyzer";
    public static final String CSV_FILENAME_PREFIX = "cell_data_export_";
    public static final String PDF_FILENAME_PREFIX = "cell_report_";

    // Handover
    public static final long HANDOVER_WINDOW_MS = 60_000; // 1 minute window for ping-pong detection
    public static final int PING_PONG_THRESHOLD = 3; // min switches to flag ping-pong

    // Prediction
    public static final int PREDICTION_WINDOW_SIZE = 30; // samples for trend analysis
}
