package com.networkanalyzer.app.activities;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;
import androidx.navigation.ui.AppBarConfiguration;
import androidx.navigation.ui.NavigationUI;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.navigation.NavigationView;
import com.google.android.material.snackbar.Snackbar;
import com.networkanalyzer.app.R;
import com.networkanalyzer.app.databinding.ActivityMainBinding;
import com.networkanalyzer.app.services.CellMonitorService;
import com.networkanalyzer.app.utils.PreferenceManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Primary container activity for the application.
 * <p>
 * Hosts a drawer-organized navigation shell for the six main screens
 * (Dashboard, Statistics, Heatmap, Speed Test, History, Settings) and a
 * {@code NavHostFragment} driven by the Navigation Component.
 * <p>
 * On creation the activity:
 * <ol>
 *   <li>Applies the user's dark-mode preference.</li>
 *   <li>Requests all required runtime permissions.</li>
 *   <li>Starts {@link CellMonitorService} as a foreground service.</li>
 * </ol>
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    /** Request code used with the legacy {@link #onRequestPermissionsResult} path. */
    private static final int REQUEST_CODE_PERMISSIONS = 1001;

    private ActivityMainBinding binding;
    private PreferenceManager preferenceManager;
    private NavController navController;
    private AppBarConfiguration appBarConfiguration;

    // -------------------------------------------------------------------------
    // Permission launcher (Activity Result API)
    // -------------------------------------------------------------------------

    /**
     * Handles the result of a multi-permission request issued via the
     * Activity Result API.
     */
    private final ActivityResultLauncher<String[]> permissionLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.RequestMultiplePermissions(),
                    this::onPermissionsResult);

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Apply dark-mode preference before inflation.
        preferenceManager = new PreferenceManager(this);
        applyDarkModePreference();

        super.onCreate(savedInstanceState);

        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        setupNavigation();
        requestRequiredPermissions();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Re-apply in case the user toggled dark mode from settings and came back.
        applyDarkModePreference();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        binding = null;
    }

    // -------------------------------------------------------------------------
    // Navigation
    // -------------------------------------------------------------------------

    /**
     * Initialises the Navigation Component. The {@code NavHostFragment} is
     * looked up from the layout and its {@link NavController} is wired to the
     * top app bar and side drawer for a clearer screen hierarchy.
     */
    private void setupNavigation() {
        NavHostFragment navHostFragment = (NavHostFragment)
                getSupportFragmentManager().findFragmentById(R.id.nav_host_fragment);

        if (navHostFragment == null) {
            throw new IllegalStateException(
                    "NavHostFragment not found. Ensure activity_main.xml contains a "
                            + "FragmentContainerView with id nav_host_fragment.");
        }

        navController = navHostFragment.getNavController();
        DrawerLayout drawerLayout = binding.drawerLayout;
        NavigationView navigationView = binding.navigationView;
        MaterialToolbar topAppBar = binding.topAppBar;

        setSupportActionBar(topAppBar);

        appBarConfiguration = new AppBarConfiguration.Builder(
                R.id.dashboardFragment,
                R.id.statisticsFragment,
                R.id.heatmapFragment,
                R.id.speedTestFragment,
                R.id.historyFragment,
                R.id.settingsFragment)
                .setOpenableLayout(drawerLayout)
                .build();

        NavigationUI.setupActionBarWithNavController(this, navController, appBarConfiguration);
        NavigationUI.setupWithNavController(navigationView, navController);

        navController.addOnDestinationChangedListener((controller, destination, arguments) -> {
            topAppBar.setTitle(destination.getLabel());
            topAppBar.setSubtitle(getToolbarSubtitle(destination.getId()));
        });
    }

    @Override
    public boolean onSupportNavigateUp() {
        return NavigationUI.navigateUp(navController, appBarConfiguration)
                || super.onSupportNavigateUp();
    }

    private int getToolbarSubtitle(int destinationId) {
        if (destinationId == R.id.dashboardFragment) {
            return R.string.dashboard_subtitle;
        }
        if (destinationId == R.id.statisticsFragment) {
            return R.string.statistics_subtitle;
        }
        if (destinationId == R.id.heatmapFragment) {
            return R.string.heatmap_subtitle;
        }
        if (destinationId == R.id.speedTestFragment) {
            return R.string.speed_test_subtitle;
        }
        if (destinationId == R.id.historyFragment) {
            return R.string.history_subtitle;
        }
        if (destinationId == R.id.settingsFragment) {
            return R.string.settings_subtitle;
        }
        return R.string.app_summary;
    }

    // -------------------------------------------------------------------------
    // Permissions
    // -------------------------------------------------------------------------

    /**
     * Builds the list of permissions the app requires at runtime and requests
     * any that have not yet been granted.
     * <p>
     * The set of permissions varies by API level:
     * <ul>
     *   <li>{@code ACCESS_FINE_LOCATION} and {@code ACCESS_COARSE_LOCATION} -- always.</li>
     *   <li>{@code READ_PHONE_STATE} -- always.</li>
     *   <li>{@code POST_NOTIFICATIONS} -- Android 13+ (API 33).</li>
     * </ul>
     */
    private void requestRequiredPermissions() {
        List<String> permissionsNeeded = new ArrayList<>();

        // Location permissions -- critical for cell info.
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        }

        // Phone state -- required to read cell info.
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE)
                != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.READ_PHONE_STATE);
        }

        // Notification permission -- Android 13+ (API 33).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.POST_NOTIFICATIONS);
            }
        }

        if (permissionsNeeded.isEmpty()) {
            // All permissions already granted -- start monitoring.
            onAllPermissionsGranted();
        } else {
            // Check if we need to show a rationale for any of the requested permissions.
            boolean shouldShowRationale = false;
            for (String perm : permissionsNeeded) {
                if (ActivityCompat.shouldShowRequestPermissionRationale(this, perm)) {
                    shouldShowRationale = true;
                    break;
                }
            }

            if (shouldShowRationale) {
                showPermissionRationale(permissionsNeeded);
            } else {
                permissionLauncher.launch(
                        permissionsNeeded.toArray(new String[0]));
            }
        }
    }

    /**
     * Displays an explanatory dialog before requesting permissions, giving the
     * user context for why the app needs them.
     *
     * @param permissions the list of permissions about to be requested.
     */
    private void showPermissionRationale(@NonNull List<String> permissions) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.permission_rationale_title)
                .setMessage(R.string.permission_rationale_message)
                .setPositiveButton(R.string.permission_rationale_grant,
                        (dialog, which) -> permissionLauncher.launch(
                                permissions.toArray(new String[0])))
                .setNegativeButton(R.string.permission_rationale_deny,
                        (dialog, which) -> {
                            dialog.dismiss();
                            onPermissionsDenied();
                        })
                .setCancelable(false)
                .show();
    }

    /**
     * Processes the results of a multi-permission request.
     *
     * @param results map of permission name to granted status.
     */
    private void onPermissionsResult(@NonNull Map<String, Boolean> results) {
        boolean allGranted = true;
        boolean locationGranted = true;

        for (Map.Entry<String, Boolean> entry : results.entrySet()) {
            if (Boolean.FALSE.equals(entry.getValue())) {
                allGranted = false;
                if (Manifest.permission.ACCESS_FINE_LOCATION.equals(entry.getKey())
                        || Manifest.permission.ACCESS_COARSE_LOCATION.equals(entry.getKey())) {
                    locationGranted = false;
                }
            }
        }

        if (allGranted) {
            onAllPermissionsGranted();
        } else if (!locationGranted) {
            // Location is critical -- explain and offer to open app settings.
            showPermissionDeniedDialog();
        } else {
            // Non-critical permissions were denied (e.g. notifications).
            // Start the service anyway; it can still run without notifications
            // on pre-13 devices but the UX will be degraded on 13+.
            startCellMonitorService();
        }
    }

    /**
     * Called when all required runtime permissions have been granted.
     * Starts the foreground cell monitoring service.
     */
    private void onAllPermissionsGranted() {
        startCellMonitorService();
    }

    /**
     * Called when one or more permissions were denied by the user without
     * selecting "Don't ask again". The app can still be used in a limited
     * capacity.
     */
    private void onPermissionsDenied() {
        Snackbar.make(binding.getRoot(),
                        R.string.permission_denied_message,
                        Snackbar.LENGTH_LONG)
                .setAction(R.string.action_settings, v -> openAppSettings())
                .show();
    }

    /**
     * Shows a dialog explaining that a critical permission (location) was
     * denied, and offers to open the system app settings screen.
     */
    private void showPermissionDeniedDialog() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.permission_denied_title)
                .setMessage(R.string.permission_denied_location_message)
                .setPositiveButton(R.string.action_settings,
                        (dialog, which) -> openAppSettings())
                .setNegativeButton(R.string.action_cancel,
                        (dialog, which) -> dialog.dismiss())
                .setCancelable(true)
                .show();
    }

    /**
     * Opens the system "App Info" settings screen for this application, where
     * the user can manually grant permissions.
     */
    private void openAppSettings() {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(Uri.fromParts("package", getPackageName(), null));
        startActivity(intent);
    }

    // -------------------------------------------------------------------------
    // Foreground Service
    // -------------------------------------------------------------------------

    /**
     * Starts {@link CellMonitorService} as a foreground service. On API 26+
     * the service must be started via
     * {@link ContextCompat#startForegroundService(android.content.Context, Intent)}.
     */
    private void startCellMonitorService() {
        // Only start the service if we have the minimum required permission.
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Cannot start CellMonitorService -- location permission not granted.");
            return;
        }

        try {
            Intent serviceIntent = new Intent(this, CellMonitorService.class);
            ContextCompat.startForegroundService(this, serviceIntent);
            Log.i(TAG, "CellMonitorService started.");
        } catch (Exception e) {
            Log.e(TAG, "Failed to start CellMonitorService", e);
            Snackbar.make(binding.getRoot(),
                    R.string.error_service_start_failed,
                    Snackbar.LENGTH_LONG).show();
        }
    }

    // -------------------------------------------------------------------------
    // Dark Mode
    // -------------------------------------------------------------------------

    /**
     * Reads the user's dark-mode preference from {@link PreferenceManager} and
     * applies it via {@link AppCompatDelegate#setDefaultNightMode(int)}.
     * <p>
     * Supported modes:
     * <ul>
     *   <li>{@code "on"}  -- force dark mode.</li>
     *   <li>{@code "off"} -- force light mode.</li>
     *   <li>{@code "system"} (default) -- follow the system setting.</li>
     * </ul>
     */
    private void applyDarkModePreference() {
        String mode = preferenceManager.getDarkMode();
        if (mode == null) {
            mode = "system";
        }

        switch (mode) {
            case "on":
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
                break;
            case "off":
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
                break;
            default:
                AppCompatDelegate.setDefaultNightMode(
                        AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
                break;
        }
    }

    // -------------------------------------------------------------------------
    // Public Accessors (used by fragments)
    // -------------------------------------------------------------------------

    /**
     * Returns the {@link NavController} managing fragment navigation within
     * this activity's {@code NavHostFragment}.
     *
     * @return the active {@link NavController}.
     */
    @NonNull
    public NavController getNavController() {
        return navController;
    }
}
