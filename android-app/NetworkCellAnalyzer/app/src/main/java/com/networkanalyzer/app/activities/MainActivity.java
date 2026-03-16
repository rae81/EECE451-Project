package com.networkanalyzer.app.activities;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.View;

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
import com.google.android.material.bottomnavigation.BottomNavigationView;
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
 * Hosts a bottom-navigation shell for the four primary screens
 * (Dashboard, Map, Analytics, History) and a side drawer for secondary
 * tools (Speed Test, Tower Clusters, Diagnostics, Settings).
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final int REQUEST_CODE_PERMISSIONS = 1001;

    private ActivityMainBinding binding;
    private PreferenceManager preferenceManager;
    private NavController navController;
    private AppBarConfiguration appBarConfiguration;

    private final ActivityResultLauncher<String[]> permissionLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.RequestMultiplePermissions(),
                    this::onPermissionsResult);

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
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
        BottomNavigationView bottomNav = binding.bottomNavigation;

        setSupportActionBar(topAppBar);

        // All top-level destinations (no back arrow shown)
        appBarConfiguration = new AppBarConfiguration.Builder(
                R.id.dashboardFragment,
                R.id.heatmapFragment,
                R.id.statisticsFragment,
                R.id.historyFragment,
                R.id.speedTestFragment,
                R.id.towerClustersFragment,
                R.id.diagnosticsFragment,
                R.id.settingsFragment)
                .setOpenableLayout(drawerLayout)
                .build();

        NavigationUI.setupActionBarWithNavController(this, navController, appBarConfiguration);
        NavigationUI.setupWithNavController(bottomNav, navController);
        NavigationUI.setupWithNavController(navigationView, navController);

        // Handle destination changes
        navController.addOnDestinationChangedListener((controller, destination, arguments) -> {
            // Update toolbar title
            topAppBar.setTitle(destination.getLabel());

            // Close drawer on any navigation
            drawerLayout.closeDrawers();

            // Show/hide bottom nav for detail screens
            int destId = destination.getId();
            if (destId == R.id.towerClusterDetailFragment) {
                bottomNav.setVisibility(View.GONE);
            } else {
                bottomNav.setVisibility(View.VISIBLE);
            }
        });
    }

    @Override
    public boolean onSupportNavigateUp() {
        return NavigationUI.navigateUp(navController, appBarConfiguration)
                || super.onSupportNavigateUp();
    }

    // -------------------------------------------------------------------------
    // Permissions
    // -------------------------------------------------------------------------

    private void requestRequiredPermissions() {
        List<String> permissionsNeeded = new ArrayList<>();

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE)
                != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.READ_PHONE_STATE);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.POST_NOTIFICATIONS);
            }
        }

        if (permissionsNeeded.isEmpty()) {
            onAllPermissionsGranted();
        } else {
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
                permissionLauncher.launch(permissionsNeeded.toArray(new String[0]));
            }
        }
    }

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
            showPermissionDeniedDialog();
        } else {
            startCellMonitorService();
        }
    }

    private void onAllPermissionsGranted() {
        startCellMonitorService();
    }

    private void onPermissionsDenied() {
        Snackbar.make(binding.getRoot(),
                        R.string.permission_denied_message,
                        Snackbar.LENGTH_LONG)
                .setAction(R.string.action_settings, v -> openAppSettings())
                .show();
    }

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

    private void openAppSettings() {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(Uri.fromParts("package", getPackageName(), null));
        startActivity(intent);
    }

    // -------------------------------------------------------------------------
    // Foreground Service
    // -------------------------------------------------------------------------

    private void startCellMonitorService() {
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
    // Public Accessors
    // -------------------------------------------------------------------------

    @NonNull
    public NavController getNavController() {
        return navController;
    }
}
