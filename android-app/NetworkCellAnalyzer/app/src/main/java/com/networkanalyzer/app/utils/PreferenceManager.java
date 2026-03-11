package com.networkanalyzer.app.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.UUID;

/**
 * Centralised preferences manager for the Network Cell Analyzer application.
 * <p>
 * Sensitive credentials (authentication tokens) are stored in
 * {@link EncryptedSharedPreferences} backed by the Android Keystore, while
 * non-sensitive user settings use regular {@link SharedPreferences}.
 * <p>
 * The class supports two usage patterns that coexist in the codebase:
 * <ol>
 *     <li><b>Direct construction</b> -- {@code new PreferenceManager(context)},
 *         used by activities such as {@code LoginActivity} and
 *         {@code SplashActivity}.</li>
 *     <li><b>Singleton access</b> -- {@link #init(Context)} followed by
 *         {@link #getInstance()}, convenient for services and background
 *         components.</li>
 * </ol>
 */
public class PreferenceManager {

    private static final String TAG = "PreferenceManager";

    /** File name for the encrypted preferences store. */
    private static final String ENCRYPTED_PREFS_NAME = "network_analyzer_secure_prefs";

    // Keys for encrypted preferences (tokens only)
    private static final String KEY_AUTH_TOKEN = Constants.PREF_AUTH_TOKEN;
    private static final String KEY_REFRESH_TOKEN = Constants.PREF_REFRESH_TOKEN;

    // Keys for regular preferences
    private static final String KEY_USER_EMAIL = Constants.PREF_USER_EMAIL;
    private static final String KEY_USER_NAME = Constants.PREF_USER_NAME;
    private static final String KEY_DEVICE_ID = Constants.PREF_DEVICE_ID;
    private static final String KEY_SERVER_URL = Constants.PREF_SERVER_URL;
    private static final String KEY_COLLECTION_INTERVAL = Constants.PREF_COLLECTION_INTERVAL;
    private static final String KEY_MONITORING_ACTIVE = Constants.PREF_MONITORING_ACTIVE;
    private static final String KEY_BIOMETRIC_ENABLED = Constants.PREF_BIOMETRIC_ENABLED;
    private static final String KEY_DARK_MODE = Constants.PREF_DARK_MODE;
    private static final String KEY_SIGNAL_THRESHOLD = Constants.PREF_SIGNAL_THRESHOLD;
    private static final String KEY_ALERT_ENABLED = Constants.PREF_ALERT_ENABLED;

    /** Default signal threshold in dBm below which an alert is raised. */
    private static final int DEFAULT_SIGNAL_THRESHOLD = -110;

    private final SharedPreferences regularPrefs;
    private final SharedPreferences encryptedPrefs;

    /** Singleton instance (optional access pattern). */
    private static volatile PreferenceManager sInstance;

    // -------------------------------------------------------------------------
    // Construction & Singleton
    // -------------------------------------------------------------------------

    /**
     * Creates a new {@code PreferenceManager} bound to the given context.
     * <p>
     * If encrypted preferences cannot be initialised (e.g. Keystore failure on
     * some devices), the manager falls back to regular {@link SharedPreferences}
     * for token storage and logs a warning.
     *
     * @param context any {@link Context}; the application context is derived internally
     */
    public PreferenceManager(@NonNull Context context) {
        Context appContext = context.getApplicationContext();

        regularPrefs = appContext.getSharedPreferences(
                Constants.PREFS_NAME, Context.MODE_PRIVATE);

        SharedPreferences encrypted;
        try {
            MasterKey masterKey = new MasterKey.Builder(appContext)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build();

            encrypted = EncryptedSharedPreferences.create(
                    appContext,
                    ENCRYPTED_PREFS_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
        } catch (GeneralSecurityException | IOException e) {
            Log.e(TAG, "Failed to create EncryptedSharedPreferences, " +
                    "falling back to regular SharedPreferences for tokens", e);
            encrypted = regularPrefs;
        }
        encryptedPrefs = encrypted;
    }

    /**
     * Initialises the singleton instance. Must be called once (typically from
     * {@code Application.onCreate()}) before {@link #getInstance()} is used.
     *
     * @param context any {@link Context}
     */
    public static void init(@NonNull Context context) {
        if (sInstance == null) {
            synchronized (PreferenceManager.class) {
                if (sInstance == null) {
                    sInstance = new PreferenceManager(context);
                }
            }
        }
    }

    /**
     * Returns the singleton instance.
     *
     * @return the shared {@code PreferenceManager}
     * @throws IllegalStateException if {@link #init(Context)} has not been called
     */
    @NonNull
    public static PreferenceManager getInstance() {
        if (sInstance == null) {
            throw new IllegalStateException(
                    "PreferenceManager.init(Context) must be called before getInstance()");
        }
        return sInstance;
    }

    // -------------------------------------------------------------------------
    // Authentication Tokens (encrypted)
    // -------------------------------------------------------------------------

    @Nullable
    public String getAuthToken() {
        return encryptedPrefs.getString(KEY_AUTH_TOKEN, null);
    }

    public void setAuthToken(@Nullable String token) {
        encryptedPrefs.edit().putString(KEY_AUTH_TOKEN, token).apply();
    }

    @Nullable
    public String getRefreshToken() {
        return encryptedPrefs.getString(KEY_REFRESH_TOKEN, null);
    }

    public void setRefreshToken(@Nullable String token) {
        encryptedPrefs.edit().putString(KEY_REFRESH_TOKEN, token).apply();
    }

    // -------------------------------------------------------------------------
    // User Profile
    // -------------------------------------------------------------------------

    @Nullable
    public String getUserEmail() {
        return regularPrefs.getString(KEY_USER_EMAIL, null);
    }

    public void setUserEmail(@Nullable String email) {
        regularPrefs.edit().putString(KEY_USER_EMAIL, email).apply();
    }

    @Nullable
    public String getUserName() {
        return regularPrefs.getString(KEY_USER_NAME, null);
    }

    public void setUserName(@Nullable String name) {
        regularPrefs.edit().putString(KEY_USER_NAME, name).apply();
    }

    // -------------------------------------------------------------------------
    // Device Identity
    // -------------------------------------------------------------------------

    /**
     * Returns the unique device identifier. If one has not been assigned yet,
     * a random UUID is generated, persisted, and returned.
     *
     * @return a non-null device identifier string
     */
    @NonNull
    public String getDeviceId() {
        String id = regularPrefs.getString(KEY_DEVICE_ID, null);
        if (id == null || id.isEmpty()) {
            id = UUID.randomUUID().toString();
            regularPrefs.edit().putString(KEY_DEVICE_ID, id).apply();
        }
        return id;
    }

    public void setDeviceId(@NonNull String deviceId) {
        regularPrefs.edit().putString(KEY_DEVICE_ID, deviceId).apply();
    }

    // -------------------------------------------------------------------------
    // Server Configuration
    // -------------------------------------------------------------------------

    @NonNull
    public String getServerUrl() {
        String url = regularPrefs.getString(KEY_SERVER_URL, null);
        return (url != null && !url.isEmpty()) ? url : Constants.DEFAULT_SERVER_URL;
    }

    public void setServerUrl(@NonNull String url) {
        regularPrefs.edit().putString(KEY_SERVER_URL, url).apply();
    }

    // -------------------------------------------------------------------------
    // Monitoring Settings
    // -------------------------------------------------------------------------

    /**
     * Returns the data collection interval in milliseconds.
     *
     * @return interval in ms; defaults to {@link Constants#DEFAULT_COLLECTION_INTERVAL}
     */
    public long getCollectionInterval() {
        return regularPrefs.getLong(KEY_COLLECTION_INTERVAL,
                Constants.DEFAULT_COLLECTION_INTERVAL);
    }

    public void setCollectionInterval(long intervalMs) {
        regularPrefs.edit().putLong(KEY_COLLECTION_INTERVAL, intervalMs).apply();
    }

    public boolean isMonitoringActive() {
        return regularPrefs.getBoolean(KEY_MONITORING_ACTIVE, false);
    }

    public void setMonitoringActive(boolean active) {
        regularPrefs.edit().putBoolean(KEY_MONITORING_ACTIVE, active).apply();
    }

    // -------------------------------------------------------------------------
    // UI / Security Settings
    // -------------------------------------------------------------------------

    public boolean isBiometricEnabled() {
        return regularPrefs.getBoolean(KEY_BIOMETRIC_ENABLED, false);
    }

    public void setBiometricEnabled(boolean enabled) {
        regularPrefs.edit().putBoolean(KEY_BIOMETRIC_ENABLED, enabled).apply();
    }

    public boolean isDarkMode() {
        return "on".equals(getDarkMode());
    }

    @NonNull
    public String getDarkMode() {
        return regularPrefs.getString(KEY_DARK_MODE, "system");
    }

    public void setDarkMode(boolean darkMode) {
        setDarkMode(darkMode ? "on" : "off");
    }

    public void setDarkMode(@NonNull String mode) {
        regularPrefs.edit().putString(KEY_DARK_MODE, mode).apply();
    }

    // -------------------------------------------------------------------------
    // Alert Settings
    // -------------------------------------------------------------------------

    /**
     * Returns the signal power threshold in dBm below which an alert should
     * be triggered.
     *
     * @return threshold in dBm; defaults to {@value #DEFAULT_SIGNAL_THRESHOLD}
     */
    public int getSignalThreshold() {
        return regularPrefs.getInt(KEY_SIGNAL_THRESHOLD, DEFAULT_SIGNAL_THRESHOLD);
    }

    public void setSignalThreshold(int thresholdDbm) {
        regularPrefs.edit().putInt(KEY_SIGNAL_THRESHOLD, thresholdDbm).apply();
    }

    public boolean isAlertEnabled() {
        return regularPrefs.getBoolean(KEY_ALERT_ENABLED, true);
    }

    public void setAlertEnabled(boolean enabled) {
        regularPrefs.edit().putBoolean(KEY_ALERT_ENABLED, enabled).apply();
    }

    // -------------------------------------------------------------------------
    // Session Helpers
    // -------------------------------------------------------------------------

    /**
     * Checks whether the user is currently logged in by verifying that a
     * non-empty authentication token exists.
     *
     * @return {@code true} if an auth token is stored
     */
    public boolean isLoggedIn() {
        String token = getAuthToken();
        return token != null && !token.isEmpty();
    }

    /**
     * Clears all stored preferences -- both encrypted and regular. Typically
     * called on user logout.
     */
    public void clearAll() {
        encryptedPrefs.edit().clear().apply();
        regularPrefs.edit().clear().apply();
    }

    // -------------------------------------------------------------------------
    // Generic compatibility helpers
    // -------------------------------------------------------------------------

    public boolean getBoolean(@NonNull String key, boolean defaultValue) {
        return regularPrefs.getBoolean(key, defaultValue);
    }

    public void putBoolean(@NonNull String key, boolean value) {
        regularPrefs.edit().putBoolean(key, value).apply();
    }

    @Nullable
    public String getString(@NonNull String key, @Nullable String defaultValue) {
        return regularPrefs.getString(key, defaultValue);
    }

    public void putString(@NonNull String key, @Nullable String value) {
        regularPrefs.edit().putString(key, value).apply();
    }
}
