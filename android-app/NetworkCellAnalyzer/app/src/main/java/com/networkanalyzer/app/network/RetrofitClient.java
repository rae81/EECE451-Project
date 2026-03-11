package com.networkanalyzer.app.network;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.networkanalyzer.app.utils.Constants;

import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import okhttp3.OkHttpClient;
import okhttp3.logging.HttpLoggingInterceptor;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

/**
 * Singleton Retrofit client for the NetworkCellAnalyzer app.
 * <p>
 * Provides a configured {@link ApiService} instance with:
 * <ul>
 *   <li>OkHttpClient with logging interceptor (DEBUG builds)</li>
 *   <li>{@link AuthInterceptor} for automatic Bearer token management</li>
 *   <li>30-second connect / read / write timeouts</li>
 *   <li>TLS/SSL configuration</li>
 *   <li>Dynamic base URL support (reads from SharedPreferences)</li>
 * </ul>
 */
public class RetrofitClient {

    private static final String TAG = "RetrofitClient";
    private static final long TIMEOUT_SECONDS = 30;

    private static volatile RetrofitClient instance;

    private final Context applicationContext;
    private Retrofit retrofit;
    private ApiService apiService;
    private String currentBaseUrl;

    private RetrofitClient(Context context) {
        this.applicationContext = context.getApplicationContext();
        buildClient();
    }

    /**
     * Returns the singleton instance, creating it if necessary.
     *
     * @param context any Context (application context is extracted internally)
     * @return the singleton {@link RetrofitClient}
     */
    public static RetrofitClient getInstance(Context context) {
        if (instance == null) {
            synchronized (RetrofitClient.class) {
                if (instance == null) {
                    instance = new RetrofitClient(context);
                }
            }
        }
        return instance;
    }

    /**
     * Returns the {@link ApiService} for making API calls.
     * <p>
     * Automatically rebuilds the client if the base URL has changed in preferences.
     */
    public ApiService getApiService() {
        String preferredUrl = getBaseUrlFromPreferences();
        if (!preferredUrl.equals(currentBaseUrl)) {
            rebuildClient();
        }
        return apiService;
    }

    /**
     * Forces the Retrofit client to rebuild with the current base URL from preferences.
     * Call this after the user changes the server URL in settings.
     */
    public synchronized void updateBaseUrl() {
        rebuildClient();
    }

    // ---- Internal setup ----

    private synchronized void buildClient() {
        currentBaseUrl = getBaseUrlFromPreferences();

        OkHttpClient okHttpClient = createOkHttpClient();

        retrofit = new Retrofit.Builder()
                .baseUrl(currentBaseUrl)
                .client(okHttpClient)
                .addConverterFactory(GsonConverterFactory.create())
                .build();

        apiService = retrofit.create(ApiService.class);
        Log.d(TAG, "Retrofit client built with base URL: " + currentBaseUrl);
    }

    private synchronized void rebuildClient() {
        buildClient();
    }

    /**
     * Creates the OkHttpClient with logging, auth interceptor, timeouts, and SSL config.
     */
    private OkHttpClient createOkHttpClient() {
        // Logging interceptor
        HttpLoggingInterceptor loggingInterceptor = new HttpLoggingInterceptor(
                message -> Log.d(TAG, message)
        );
        loggingInterceptor.setLevel(HttpLoggingInterceptor.Level.BODY);

        // Auth interceptor
        AuthInterceptor authInterceptor = new AuthInterceptor(applicationContext);

        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .addInterceptor(authInterceptor)
                .addInterceptor(loggingInterceptor);

        // Configure SSL/TLS
        configureSsl(builder);

        return builder.build();
    }

    /**
     * Configures TLS 1.2+ on the OkHttpClient. For production use, this uses the
     * system default trust managers which validate server certificates normally.
     * <p>
     * On devices running API 16-19, TLS 1.2 may not be enabled by default, so we
     * explicitly create an SSLContext with TLSv1.2.
     */
    private void configureSsl(OkHttpClient.Builder builder) {
        try {
            SSLContext sslContext = SSLContext.getInstance("TLSv1.2");
            sslContext.init(null, null, new SecureRandom());
            SSLSocketFactory sslSocketFactory = sslContext.getSocketFactory();

            // Use the default X509TrustManager from the platform
            X509TrustManager trustManager = getDefaultTrustManager();
            if (trustManager != null) {
                builder.sslSocketFactory(sslSocketFactory, trustManager);
            }
        } catch (NoSuchAlgorithmException | KeyManagementException e) {
            Log.e(TAG, "Error configuring SSL/TLS", e);
        }
    }

    /**
     * Retrieves the platform default X509TrustManager.
     */
    private X509TrustManager getDefaultTrustManager() {
        try {
            javax.net.ssl.TrustManagerFactory tmf =
                    javax.net.ssl.TrustManagerFactory.getInstance(
                            javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
            tmf.init((java.security.KeyStore) null);

            for (TrustManager tm : tmf.getTrustManagers()) {
                if (tm instanceof X509TrustManager) {
                    return (X509TrustManager) tm;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting default TrustManager", e);
        }
        return null;
    }

    /**
     * Reads the base URL from SharedPreferences. Falls back to {@link Constants#DEFAULT_SERVER_URL}.
     * Ensures the URL ends with a trailing slash as required by Retrofit.
     */
    private String getBaseUrlFromPreferences() {
        SharedPreferences prefs = applicationContext.getSharedPreferences(
                Constants.PREFS_NAME, Context.MODE_PRIVATE);
        String url = prefs.getString(Constants.PREF_SERVER_URL, Constants.DEFAULT_SERVER_URL);

        if (url == null || url.trim().isEmpty()) {
            url = Constants.DEFAULT_SERVER_URL;
        }

        // Retrofit requires a trailing slash on the base URL
        if (!url.endsWith("/")) {
            url = url + "/";
        }

        return url;
    }

    /**
     * Returns the underlying OkHttpClient for internal reuse.
     */
    public OkHttpClient getOkHttpClient() {
        return retrofit != null
                ? retrofit.callFactory() instanceof OkHttpClient
                    ? (OkHttpClient) retrofit.callFactory()
                    : createOkHttpClient()
                : createOkHttpClient();
    }
}
