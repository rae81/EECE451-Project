package com.networkanalyzer.app.network;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;

import com.google.gson.Gson;
import com.networkanalyzer.app.network.models.AuthResponse;
import com.networkanalyzer.app.network.models.RefreshRequest;
import com.networkanalyzer.app.utils.PreferenceManager;

import java.io.IOException;

import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * OkHttp Interceptor that handles Bearer token authentication.
 * <p>
 * - Adds "Authorization: Bearer &lt;token&gt;" header to all requests except auth endpoints.
 * - On 401 responses, attempts to refresh the token using the stored refresh token.
 * - If the refresh succeeds, retries the original request with the new token.
 * - If the refresh fails, clears all stored tokens so the user must re-login.
 * - Token refresh is synchronized to prevent concurrent refresh attempts.
 */
public class AuthInterceptor implements Interceptor {

    private static final String TAG = "AuthInterceptor";
    private static final String HEADER_AUTHORIZATION = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final Context context;
    private final PreferenceManager preferenceManager;
    private final Object tokenLock = new Object();

    public AuthInterceptor(Context context) {
        this.context = context.getApplicationContext();
        this.preferenceManager = new PreferenceManager(this.context);
    }

    @NonNull
    @Override
    public Response intercept(@NonNull Chain chain) throws IOException {
        Request originalRequest = chain.request();
        String path = originalRequest.url().encodedPath();

        // Do not attach token to auth endpoints
        if (isAuthEndpoint(path)) {
            return chain.proceed(originalRequest);
        }

        // Attach the current access token
        String accessToken = getAccessToken();
        Request authenticatedRequest = originalRequest;
        if (accessToken != null && !accessToken.isEmpty()) {
            authenticatedRequest = originalRequest.newBuilder()
                    .header(HEADER_AUTHORIZATION, BEARER_PREFIX + accessToken)
                    .build();
        }

        Response response = chain.proceed(authenticatedRequest);

        // If we get 401 Unauthorized, try to refresh the token
        if (response.code() == 401) {
            synchronized (tokenLock) {
                // Re-read the token; another thread may have already refreshed it
                String currentToken = getAccessToken();

                if (currentToken != null && currentToken.equals(accessToken)) {
                    // Token has not been refreshed by another thread; attempt refresh
                    String newToken = attemptTokenRefresh();

                    if (newToken != null) {
                        // Close the 401 response body before retrying
                        response.close();

                        // Retry the original request with the new token
                        Request retryRequest = originalRequest.newBuilder()
                                .header(HEADER_AUTHORIZATION, BEARER_PREFIX + newToken)
                                .build();
                        return chain.proceed(retryRequest);
                    } else {
                        // Refresh failed -- clear tokens, user must re-login
                        clearTokens();
                        Log.w(TAG, "Token refresh failed. User must re-authenticate.");
                    }
                } else if (currentToken != null) {
                    // Another thread already refreshed the token; retry with the new one
                    response.close();

                    Request retryRequest = originalRequest.newBuilder()
                            .header(HEADER_AUTHORIZATION, BEARER_PREFIX + currentToken)
                            .build();
                    return chain.proceed(retryRequest);
                }
            }
        }

        return response;
    }

    /**
     * Determines whether the given request path is an authentication endpoint
     * that should not carry a Bearer token.
     */
    private boolean isAuthEndpoint(String path) {
        return path.contains("auth/login")
                || path.contains("auth/register")
                || path.contains("auth/refresh");
    }

    /**
     * Attempts to refresh the access token by calling the auth/refresh endpoint
     * synchronously with a dedicated OkHttpClient (no interceptors to avoid recursion).
     *
     * @return the new access token, or null if the refresh failed.
     */
    private String attemptTokenRefresh() {
        String refreshToken = getRefreshToken();
        if (refreshToken == null || refreshToken.isEmpty()) {
            Log.w(TAG, "No refresh token available.");
            return null;
        }

        try {
            String baseUrl = getBaseUrl();
            RefreshRequest refreshRequest = new RefreshRequest(refreshToken);
            Gson gson = new Gson();
            String requestJson = gson.toJson(refreshRequest);

            // Use a plain OkHttpClient without any interceptors to avoid infinite recursion
            OkHttpClient plainClient = new OkHttpClient.Builder().build();

            RequestBody body = RequestBody.create(requestJson, JSON);
            Request request = new Request.Builder()
                    .url(baseUrl + "auth/refresh")
                    .post(body)
                    .header("Content-Type", "application/json")
                    .build();

            try (Response response = plainClient.newCall(request).execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    String responseBody = response.body().string();
                    AuthResponse authResponse = gson.fromJson(responseBody, AuthResponse.class);

                    if (authResponse != null && authResponse.isSuccess()
                            && authResponse.getToken() != null) {
                        // Persist the new tokens
                        saveTokens(authResponse.getToken(), authResponse.getRefreshToken());
                        Log.d(TAG, "Token refreshed successfully.");
                        return authResponse.getToken();
                    }
                }

                Log.w(TAG, "Token refresh endpoint returned failure. HTTP code: " + response.code());
            }
        } catch (IOException e) {
            Log.e(TAG, "IOException during token refresh", e);
        } catch (Exception e) {
            Log.e(TAG, "Unexpected error during token refresh", e);
        }

        return null;
    }

    private String getAccessToken() {
        return preferenceManager.getAuthToken();
    }

    private String getRefreshToken() {
        return preferenceManager.getRefreshToken();
    }

    private String getBaseUrl() {
        String baseUrl = preferenceManager.getServerUrl();
        return baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
    }

    private void saveTokens(String accessToken, String refreshToken) {
        preferenceManager.setAuthToken(accessToken);
        if (refreshToken != null) {
            preferenceManager.setRefreshToken(refreshToken);
        }
    }

    private void clearTokens() {
        preferenceManager.setAuthToken(null);
        preferenceManager.setRefreshToken(null);
        preferenceManager.setUserEmail(null);
        preferenceManager.setUserName(null);
    }
}
