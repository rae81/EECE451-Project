package com.networkanalyzer.app.fragments;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.biometric.BiometricManager;
import androidx.fragment.app.Fragment;

import com.networkanalyzer.app.R;
import com.networkanalyzer.app.activities.LoginActivity;
import com.networkanalyzer.app.databinding.FragmentSettingsBinding;
import com.networkanalyzer.app.network.RetrofitClient;
import com.networkanalyzer.app.utils.PreferenceManager;

public class SettingsFragment extends Fragment {

    private FragmentSettingsBinding binding;
    private PreferenceManager preferenceManager;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentSettingsBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        preferenceManager = new PreferenceManager(requireContext());

        binding.etServerUrl.setText(preferenceManager.getServerUrl());
        binding.etCollectionInterval.setText(String.valueOf(preferenceManager.getCollectionInterval() / 1000L));
        binding.switchBiometric.setChecked(preferenceManager.isBiometricEnabled());
        binding.switchDarkMode.setChecked(preferenceManager.isDarkMode());

        binding.btnSaveSettings.setOnClickListener(v -> saveSettings());
        binding.btnSignOut.setOnClickListener(v -> signOut());
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }

    private void saveSettings() {
        String serverUrl = binding.etServerUrl.getText() != null
                ? binding.etServerUrl.getText().toString().trim()
                : "";
        String intervalText = binding.etCollectionInterval.getText() != null
                ? binding.etCollectionInterval.getText().toString().trim()
                : "";
        boolean biometricRequested = binding.switchBiometric.isChecked();
        boolean biometricWasEnabled = preferenceManager.isBiometricEnabled();

        if (!serverUrl.isEmpty()) {
            preferenceManager.setServerUrl(serverUrl);
        }
        long seconds;
        try {
            seconds = Long.parseLong(intervalText);
        } catch (NumberFormatException e) {
            seconds = 10L;
        }
        preferenceManager.setCollectionInterval(Math.max(5L, seconds) * 1000L);
        boolean dark = binding.switchDarkMode.isChecked();
        preferenceManager.setDarkMode(dark ? "on" : "off");
        int nightMode = dark ? AppCompatDelegate.MODE_NIGHT_YES : AppCompatDelegate.MODE_NIGHT_NO;
        if (AppCompatDelegate.getDefaultNightMode() != nightMode) {
            AppCompatDelegate.setDefaultNightMode(nightMode);
        }
        RetrofitClient.getInstance(requireContext()).updateBaseUrl();

        if (biometricRequested) {
            if (!preferenceManager.isLoggedIn()) {
                binding.switchBiometric.setChecked(false);
                preferenceManager.setBiometricEnabled(false);
                Toast.makeText(requireContext(),
                        R.string.settings_biometric_requires_session,
                        Toast.LENGTH_SHORT).show();
                return;
            }
            if (!isDeviceBiometricReady()) {
                binding.switchBiometric.setChecked(false);
                preferenceManager.setBiometricEnabled(false);
                Toast.makeText(requireContext(),
                        R.string.settings_biometric_unavailable,
                        Toast.LENGTH_SHORT).show();
                return;
            }
        }

        preferenceManager.setBiometricEnabled(biometricRequested);

        int messageResId = R.string.settings_saved;
        if (biometricRequested && !biometricWasEnabled) {
            messageResId = R.string.settings_biometric_enabled;
        } else if (!biometricRequested && biometricWasEnabled) {
            messageResId = R.string.settings_biometric_disabled;
        }
        Toast.makeText(requireContext(), messageResId, Toast.LENGTH_SHORT).show();
    }

    private boolean isDeviceBiometricReady() {
        BiometricManager biometricManager = BiometricManager.from(requireContext());
        int canAuthenticate = biometricManager.canAuthenticate(
                BiometricManager.Authenticators.BIOMETRIC_STRONG
                        | BiometricManager.Authenticators.BIOMETRIC_WEAK);
        return canAuthenticate == BiometricManager.BIOMETRIC_SUCCESS;
    }

    private void signOut() {
        preferenceManager.clearAll();
        Intent intent = new Intent(requireContext(), LoginActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
    }
}
