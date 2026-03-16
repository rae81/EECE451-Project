package com.networkanalyzer.app.activities;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;

import androidx.appcompat.app.AppCompatActivity;
import androidx.biometric.BiometricManager;

import com.networkanalyzer.app.databinding.ActivitySplashBinding;
import com.networkanalyzer.app.utils.PreferenceManager;

/**
 * Splash screen displayed on app launch.
 * <p>
 * Shows the application logo with a fade-in animation for 2 seconds, then
 * routes the user to either {@link MainActivity} (if an auth token is stored)
 * or {@link LoginActivity} (if the user has not logged in yet).
 * <p>
 * The activity uses {@code Theme.NetworkCellAnalyzer.Splash} declared in the
 * manifest so that the system window has no action bar.
 */
@SuppressLint("CustomSplashScreen")
public class SplashActivity extends AppCompatActivity {

    /** Duration the splash screen remains visible before navigation (ms). */
    private static final long SPLASH_DISPLAY_DURATION = 2000L;

    /** Duration of the logo fade-in animation (ms). */
    private static final long FADE_IN_DURATION = 1000L;

    private ActivitySplashBinding binding;
    private PreferenceManager preferenceManager;

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = ActivitySplashBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        preferenceManager = new PreferenceManager(this);

        startFadeInAnimation();
        scheduleNavigation();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        binding = null;
    }

    // -------------------------------------------------------------------------
    // Animation
    // -------------------------------------------------------------------------

    /**
     * Applies a simple alpha fade-in animation to the root splash container.
     * The container starts fully transparent and fades to fully opaque over
     * {@link #FADE_IN_DURATION} milliseconds.
     */
    private void startFadeInAnimation() {
        View rootContainer = binding.getRoot();
        rootContainer.setAlpha(0f);
        rootContainer.animate()
                .alpha(1f)
                .setDuration(FADE_IN_DURATION)
                .setListener(null)
                .start();
    }

    // -------------------------------------------------------------------------
    // Navigation
    // -------------------------------------------------------------------------

    /**
     * Posts a delayed navigation action. After {@link #SPLASH_DISPLAY_DURATION}
     * elapses the activity checks for a stored authentication token and opens
     * the appropriate destination.
     */
    private void scheduleNavigation() {
        new Handler(Looper.getMainLooper()).postDelayed(this::navigateToNextScreen,
                SPLASH_DISPLAY_DURATION);
    }

    /**
     * Determines the next screen based on authentication state and starts it.
     * <ul>
     *   <li>If an auth token exists in {@link PreferenceManager}, the user is
     *       considered logged in and is sent to {@link MainActivity}.</li>
     *   <li>Otherwise the user is sent to {@link LoginActivity}.</li>
     * </ul>
     * This activity is finished so the user cannot navigate back to it.
     */
    private void navigateToNextScreen() {
        // Guard against the activity being destroyed before the handler fires.
        if (isFinishing() || isDestroyed()) {
            return;
        }

        String authToken = preferenceManager.getAuthToken();
        boolean isLoggedIn = authToken != null && !authToken.isEmpty();
        boolean isGuestMode = preferenceManager.getBoolean(
                com.networkanalyzer.app.utils.Constants.PREF_GUEST_MODE, false);

        Intent intent;
        if (shouldRequireBiometricGate(isLoggedIn)) {
            intent = new Intent(SplashActivity.this, LoginActivity.class);
            intent.putExtra(LoginActivity.EXTRA_AUTO_PROMPT_BIOMETRIC, true);
        } else if (isLoggedIn || isGuestMode) {
            intent = new Intent(SplashActivity.this, MainActivity.class);
        } else {
            intent = new Intent(SplashActivity.this, LoginActivity.class);
        }

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();

        // Apply a cross-fade transition between activities.
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }

    private boolean shouldRequireBiometricGate(boolean isLoggedIn) {
        if (!isLoggedIn || !preferenceManager.isBiometricEnabled()) {
            return false;
        }

        BiometricManager biometricManager = BiometricManager.from(this);
        int canAuthenticate = biometricManager.canAuthenticate(
                BiometricManager.Authenticators.BIOMETRIC_STRONG
                        | BiometricManager.Authenticators.BIOMETRIC_WEAK);
        return canAuthenticate == BiometricManager.BIOMETRIC_SUCCESS;
    }
}
