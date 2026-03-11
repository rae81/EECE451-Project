package com.networkanalyzer.app.activities;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.util.Patterns;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;

import com.google.android.material.snackbar.Snackbar;
import com.networkanalyzer.app.R;
import com.networkanalyzer.app.databinding.ActivityLoginBinding;
import com.networkanalyzer.app.network.ApiService;
import com.networkanalyzer.app.network.RetrofitClient;
import com.networkanalyzer.app.network.models.AuthResponse;
import com.networkanalyzer.app.network.models.LoginRequest;
import com.networkanalyzer.app.network.models.RegisterRequest;
import com.networkanalyzer.app.utils.PreferenceManager;

import java.util.concurrent.Executor;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * Handles user authentication -- both login and registration.
 * <p>
 * The single screen toggles between a <b>Login</b> mode and a <b>Register</b>
 * mode. In login mode the user provides email and password; in register mode
 * additional fields for name and password confirmation are shown.
 * <p>
 * On successful authentication the JWT token returned by the server is
 * persisted via {@link PreferenceManager} and the user is forwarded to
 * {@link MainActivity}.
 * <p>
 * If biometric login was previously enabled, a fingerprint / face prompt is
 * offered as an alternative to password entry.
 */
public class LoginActivity extends AppCompatActivity {

    private static final String TAG = "LoginActivity";

    /** Minimum acceptable password length. */
    private static final int MIN_PASSWORD_LENGTH = 6;

    private ActivityLoginBinding binding;
    private PreferenceManager preferenceManager;
    private ApiService apiService;

    /** {@code true} when the UI is in registration mode. */
    private boolean isRegisterMode = false;

    /** {@code true} while an API call is in-flight; prevents duplicate submissions. */
    private boolean isLoading = false;

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = ActivityLoginBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        preferenceManager = new PreferenceManager(this);
        apiService = RetrofitClient.getInstance(this).getApiService();

        setupUI();
        setupListeners();
        checkBiometricAvailability();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        binding = null;
    }

    // -------------------------------------------------------------------------
    // UI Setup
    // -------------------------------------------------------------------------

    /**
     * Configures the initial UI state -- login mode with register-only fields
     * hidden and the progress indicator invisible.
     */
    private void setupUI() {
        binding.etServerUrl.setText(preferenceManager.getServerUrl());
        setRegisterMode(false);
        setLoadingState(false);
    }

    /**
     * Wires click and editor-action listeners to buttons and input fields.
     */
    private void setupListeners() {
        // Primary action button (Login / Register).
        binding.btnSubmit.setOnClickListener(v -> onSubmitClicked());

        // Toggle between Login and Register modes.
        binding.tvToggleMode.setOnClickListener(v -> toggleMode());

        // Biometric login shortcut.
        binding.btnBiometric.setOnClickListener(v -> showBiometricPrompt());

        binding.btnContinueOffline.setOnClickListener(v -> continueOffline());

        // Allow the user to submit directly from the keyboard.
        binding.etPassword.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE && !isRegisterMode) {
                onSubmitClicked();
                return true;
            }
            return false;
        });

        binding.etConfirmPassword.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE && isRegisterMode) {
                onSubmitClicked();
                return true;
            }
            return false;
        });
    }

    // -------------------------------------------------------------------------
    // Mode Toggle
    // -------------------------------------------------------------------------

    /**
     * Switches between Login and Register UI modes. Clears any existing input
     * errors when toggling.
     */
    private void toggleMode() {
        setRegisterMode(!isRegisterMode);
        clearErrors();
    }

    /**
     * Applies the given mode to the UI.
     *
     * @param registerMode {@code true} to show register fields; {@code false} for login.
     */
    private void setRegisterMode(boolean registerMode) {
        isRegisterMode = registerMode;

        int registerFieldsVisibility = registerMode ? View.VISIBLE : View.GONE;
        binding.tilName.setVisibility(registerFieldsVisibility);
        binding.tilConfirmPassword.setVisibility(registerFieldsVisibility);

        binding.btnSubmit.setText(registerMode
                ? getString(R.string.action_register)
                : getString(R.string.action_login));

        binding.tvToggleMode.setText(registerMode
                ? getString(R.string.auth_toggle_to_login)
                : getString(R.string.auth_toggle_to_register));

        // Biometric login is only available in login mode.
        binding.btnBiometric.setVisibility(
                !registerMode && isBiometricAvailable() ? View.VISIBLE : View.GONE);
    }

    // -------------------------------------------------------------------------
    // Input Validation
    // -------------------------------------------------------------------------

    /**
     * Validates all visible input fields. Sets inline errors via
     * {@link com.google.android.material.textfield.TextInputLayout#setError(CharSequence)}.
     *
     * @return {@code true} if every field passes validation.
     */
    private boolean validateInputs() {
        clearErrors();
        boolean isValid = true;

        String email = getInputText(binding.etEmail);
        String password = getInputText(binding.etPassword);

        // -- Email --
        if (TextUtils.isEmpty(email)) {
            binding.tilEmail.setError(getString(R.string.error_email_required));
            isValid = false;
        } else if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            binding.tilEmail.setError(getString(R.string.error_email_invalid));
            isValid = false;
        }

        // -- Password --
        if (TextUtils.isEmpty(password)) {
            binding.tilPassword.setError(getString(R.string.error_password_required));
            isValid = false;
        } else if (password.length() < MIN_PASSWORD_LENGTH) {
            binding.tilPassword.setError(getString(R.string.error_password_too_short));
            isValid = false;
        }

        // -- Register-only fields --
        if (isRegisterMode) {
            String name = getInputText(binding.etName);
            String confirmPassword = getInputText(binding.etConfirmPassword);

            if (TextUtils.isEmpty(name)) {
                binding.tilName.setError(getString(R.string.error_name_required));
                isValid = false;
            }

            if (TextUtils.isEmpty(confirmPassword)) {
                binding.tilConfirmPassword.setError(getString(R.string.error_confirm_password_required));
                isValid = false;
            } else if (!password.equals(confirmPassword)) {
                binding.tilConfirmPassword.setError(getString(R.string.error_passwords_do_not_match));
                isValid = false;
            }
        }

        return isValid;
    }

    /** Clears error states on all {@code TextInputLayout} fields. */
    private void clearErrors() {
        binding.tilEmail.setError(null);
        binding.tilPassword.setError(null);
        binding.tilName.setError(null);
        binding.tilConfirmPassword.setError(null);

        binding.tilEmail.setErrorEnabled(false);
        binding.tilPassword.setErrorEnabled(false);
        binding.tilName.setErrorEnabled(false);
        binding.tilConfirmPassword.setErrorEnabled(false);
    }

    // -------------------------------------------------------------------------
    // Submit (Login / Register)
    // -------------------------------------------------------------------------

    /**
     * Called when the primary action button is tapped. Validates inputs and
     * delegates to the appropriate network call.
     */
    private void onSubmitClicked() {
        hideKeyboard();

        if (isLoading) {
            return;
        }

        if (!persistServerUrlInput()) {
            return;
        }

        if (!validateInputs()) {
            return;
        }

        if (isRegisterMode) {
            performRegister();
        } else {
            performLogin();
        }
    }

    /**
     * Executes the login API call. On success the returned token is saved and
     * the user is navigated to {@link MainActivity}.
     */
    private void performLogin() {
        String email = getInputText(binding.etEmail);
        String password = getInputText(binding.etPassword);

        setLoadingState(true);
        apiService = rebuildApiService();

        LoginRequest request = new LoginRequest(
                email, password, preferenceManager.getDeviceId());

        apiService.login(request).enqueue(new Callback<AuthResponse>() {
            @Override
            public void onResponse(@NonNull Call<AuthResponse> call,
                                   @NonNull Response<AuthResponse> response) {
                setLoadingState(false);

                if (response.isSuccessful() && response.body() != null) {
                    handleAuthSuccess(response.body());
                } else {
                    int code = response.code();
                    String message;
                    switch (code) {
                        case 401:
                            message = getString(R.string.error_invalid_credentials);
                            break;
                        case 404:
                            message = getString(R.string.error_user_not_found);
                            break;
                        case 429:
                            message = getString(R.string.error_too_many_attempts);
                            break;
                        default:
                            message = getString(R.string.error_login_failed, code);
                            break;
                    }
                    showError(message);
                }
            }

            @Override
            public void onFailure(@NonNull Call<AuthResponse> call,
                                  @NonNull Throwable t) {
                setLoadingState(false);
                Log.e(TAG, "Login failed", t);
                showError(getString(R.string.error_network));
            }
        });
    }

    /**
     * Executes the registration API call. On success the returned token is
     * saved and the user is navigated to {@link MainActivity}.
     */
    private void performRegister() {
        String name = getInputText(binding.etName);
        String email = getInputText(binding.etEmail);
        String password = getInputText(binding.etPassword);

        setLoadingState(true);
        apiService = rebuildApiService();

        RegisterRequest request = new RegisterRequest(
                name, email, password, preferenceManager.getDeviceId());

        apiService.register(request).enqueue(new Callback<AuthResponse>() {
            @Override
            public void onResponse(@NonNull Call<AuthResponse> call,
                                   @NonNull Response<AuthResponse> response) {
                setLoadingState(false);

                if (response.isSuccessful() && response.body() != null) {
                    handleAuthSuccess(response.body());
                } else {
                    int code = response.code();
                    String message;
                    switch (code) {
                        case 409:
                            message = getString(R.string.error_email_already_registered);
                            break;
                        case 422:
                            message = getString(R.string.error_validation_failed);
                            break;
                        default:
                            message = getString(R.string.error_registration_failed, code);
                            break;
                    }
                    showError(message);
                }
            }

            @Override
            public void onFailure(@NonNull Call<AuthResponse> call,
                                  @NonNull Throwable t) {
                setLoadingState(false);
                Log.e(TAG, "Registration failed", t);
                showError(getString(R.string.error_network));
            }
        });
    }

    // -------------------------------------------------------------------------
    // Auth Success Handling
    // -------------------------------------------------------------------------

    /**
     * Persists the authentication tokens and user data returned by the server,
     * then navigates to {@link MainActivity}.
     *
     * @param authResponse the successful auth response from the server.
     */
    private void handleAuthSuccess(@NonNull AuthResponse authResponse) {
        preferenceManager.setAuthToken(authResponse.getToken());

        if (authResponse.getRefreshToken() != null) {
            preferenceManager.setRefreshToken(authResponse.getRefreshToken());
        }
        if (authResponse.getEmail() != null) {
            preferenceManager.setUserEmail(authResponse.getEmail());
        }
        if (authResponse.getName() != null) {
            preferenceManager.setUserName(authResponse.getName());
        }
        preferenceManager.putBoolean(com.networkanalyzer.app.utils.Constants.PREF_GUEST_MODE, false);

        navigateToMain();
    }

    /**
     * Opens {@link MainActivity} and clears the back-stack so the user cannot
     * navigate back to the login screen by pressing back.
     */
    private void navigateToMain() {
        Intent intent = new Intent(LoginActivity.this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }

    // -------------------------------------------------------------------------
    // Biometric Authentication
    // -------------------------------------------------------------------------

    /**
     * Checks whether biometric authentication is both available on the device
     * and previously enabled by the user in settings.
     *
     * @return {@code true} if biometric login can be offered.
     */
    private boolean isBiometricAvailable() {
        if (!preferenceManager.isBiometricEnabled()) {
            return false;
        }

        BiometricManager biometricManager = BiometricManager.from(this);
        int canAuthenticate = biometricManager.canAuthenticate(
                BiometricManager.Authenticators.BIOMETRIC_STRONG
                        | BiometricManager.Authenticators.BIOMETRIC_WEAK);

        return canAuthenticate == BiometricManager.BIOMETRIC_SUCCESS;
    }

    /**
     * Shows or hides the biometric button based on availability. Called once
     * during {@link #setupUI()}.
     */
    private void checkBiometricAvailability() {
        binding.btnBiometric.setVisibility(
                isBiometricAvailable() ? View.VISIBLE : View.GONE);
    }

    /**
     * Displays the system biometric prompt. On successful authentication the
     * stored token is used to skip password entry entirely.
     */
    private void showBiometricPrompt() {
        Executor executor = ContextCompat.getMainExecutor(this);

        BiometricPrompt biometricPrompt = new BiometricPrompt(this, executor,
                new BiometricPrompt.AuthenticationCallback() {

                    @Override
                    public void onAuthenticationError(int errorCode,
                                                      @NonNull CharSequence errString) {
                        super.onAuthenticationError(errorCode, errString);
                        // User cancelled or a non-recoverable error occurred.
                        if (errorCode != BiometricPrompt.ERROR_USER_CANCELED
                                && errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON
                                && errorCode != BiometricPrompt.ERROR_CANCELED) {
                            showError(getString(R.string.error_biometric_failed, errString));
                        }
                    }

                    @Override
                    public void onAuthenticationSucceeded(
                            @NonNull BiometricPrompt.AuthenticationResult result) {
                        super.onAuthenticationSucceeded(result);
                        handleBiometricSuccess();
                    }

                    @Override
                    public void onAuthenticationFailed() {
                        super.onAuthenticationFailed();
                        // Individual attempt failed; the system prompt handles retries.
                    }
                });

        BiometricPrompt.PromptInfo promptInfo = new BiometricPrompt.PromptInfo.Builder()
                .setTitle(getString(R.string.biometric_prompt_title))
                .setSubtitle(getString(R.string.biometric_prompt_subtitle))
                .setNegativeButtonText(getString(R.string.biometric_prompt_cancel))
                .build();

        biometricPrompt.authenticate(promptInfo);
    }

    /**
     * Called after a successful biometric authentication. Because biometric
     * login is only offered when a token already exists, this simply verifies
     * the token is still present and navigates forward.
     */
    private void handleBiometricSuccess() {
        String token = preferenceManager.getAuthToken();
        if (token != null && !token.isEmpty()) {
            navigateToMain();
        } else {
            showError(getString(R.string.error_biometric_no_session));
        }
    }

    // -------------------------------------------------------------------------
    // Loading State
    // -------------------------------------------------------------------------

    /**
     * Toggles the loading UI -- shows / hides a progress indicator and
     * enables / disables interactive elements to prevent duplicate submissions.
     *
     * @param loading {@code true} to show loading state.
     */
    private void setLoadingState(boolean loading) {
        isLoading = loading;

        binding.progressBar.setVisibility(loading ? View.VISIBLE : View.GONE);
        binding.btnSubmit.setEnabled(!loading);
        binding.tvToggleMode.setEnabled(!loading);
        binding.btnBiometric.setEnabled(!loading);
        binding.btnContinueOffline.setEnabled(!loading);

        binding.etEmail.setEnabled(!loading);
        binding.etPassword.setEnabled(!loading);
        binding.etServerUrl.setEnabled(!loading);
        binding.etName.setEnabled(!loading);
        binding.etConfirmPassword.setEnabled(!loading);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Extracts the trimmed text from an {@code EditText} accessed via the
     * binding.
     */
    @NonNull
    private String getInputText(@NonNull android.widget.EditText editText) {
        CharSequence text = editText.getText();
        return text != null ? text.toString().trim() : "";
    }

    /** Hides the soft keyboard. */
    private void hideKeyboard() {
        View currentFocus = getCurrentFocus();
        if (currentFocus != null) {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.hideSoftInputFromWindow(currentFocus.getWindowToken(), 0);
            }
        }
    }

    /**
     * Displays an error message to the user using a {@link Snackbar}.
     *
     * @param message the error message to display.
     */
    private void showError(@NonNull String message) {
        if (binding != null) {
            Snackbar.make(binding.getRoot(), message, Snackbar.LENGTH_LONG).show();
        }
    }

    private boolean persistServerUrlInput() {
        String serverUrl = getInputText(binding.etServerUrl);
        binding.tilServerUrl.setError(null);
        binding.tilServerUrl.setErrorEnabled(false);

        if (serverUrl.isEmpty()) {
            binding.tilServerUrl.setError(getString(R.string.error_server_url_required));
            return false;
        }

        String normalizedUrl = serverUrl.endsWith("/") ? serverUrl : serverUrl + "/";
        if (!(normalizedUrl.startsWith("http://") || normalizedUrl.startsWith("https://"))) {
            binding.tilServerUrl.setError(getString(R.string.error_server_url_invalid));
            return false;
        }

        preferenceManager.setServerUrl(normalizedUrl);
        binding.etServerUrl.setText(normalizedUrl);
        return true;
    }

    @NonNull
    private ApiService rebuildApiService() {
        RetrofitClient retrofitClient = RetrofitClient.getInstance(this);
        retrofitClient.updateBaseUrl();
        return retrofitClient.getApiService();
    }

    private void continueOffline() {
        preferenceManager.putBoolean(com.networkanalyzer.app.utils.Constants.PREF_GUEST_MODE, true);
        navigateToMain();
    }
}
