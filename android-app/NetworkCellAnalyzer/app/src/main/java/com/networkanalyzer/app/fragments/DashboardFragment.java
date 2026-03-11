package com.networkanalyzer.app.fragments;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.telephony.CellInfo;
import android.telephony.CellInfoGsm;
import android.telephony.CellInfoLte;
import android.telephony.CellInfoNr;
import android.telephony.CellInfoWcdma;
import android.telephony.CellSignalStrengthGsm;
import android.telephony.CellSignalStrengthLte;
import android.telephony.CellSignalStrengthNr;
import android.telephony.CellSignalStrengthWcdma;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.networkanalyzer.app.R;
import com.networkanalyzer.app.databinding.FragmentDashboardBinding;
import com.networkanalyzer.app.services.CellMonitorService;
import com.networkanalyzer.app.utils.Constants;
import com.networkanalyzer.app.utils.PreferenceManager;

import androidx.navigation.Navigation;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;

/**
 * Real-time dashboard fragment displaying current cellular network information.
 * <p>
 * Shows operator name, network type, signal power, SINR/SNR, cell ID, frequency band,
 * and LAC/TAC. Provides a color-coded signal quality gauge, dual-SIM support,
 * neighboring cell list, a toggle for the monitoring service, and a signal-trend
 * prediction card.
 * <p>
 * Receives real-time updates via a {@link BroadcastReceiver} listening for
 * {@link Constants#ACTION_CELL_DATA_UPDATED}.
 */
public class DashboardFragment extends Fragment {

    // -- Constants -----------------------------------------------------------

    private static final int MAX_SIGNAL_SAMPLES = Constants.PREDICTION_WINDOW_SIZE;
    private static final String TREND_IMPROVING  = "Improving";
    private static final String TREND_STABLE     = "Stable";
    private static final String TREND_DEGRADING  = "Degrading";

    // -- Fields --------------------------------------------------------------

    private FragmentDashboardBinding binding;
    private PreferenceManager preferenceManager;
    private NeighborCellAdapter neighborAdapter;

    /** Rolling window of recent signal-power samples for trend prediction. */
    private final LinkedList<Integer> signalSamples = new LinkedList<>();

    /** Indicates whether the monitoring service is currently running. */
    private boolean isMonitoring = false;

    // -- BroadcastReceiver ---------------------------------------------------

    private final BroadcastReceiver cellDataReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || !Constants.ACTION_CELL_DATA_UPDATED.equals(intent.getAction())) {
                return;
            }
            updateDashboardFromBroadcast(intent);
        }
    };

    // -- Lifecycle -----------------------------------------------------------

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentDashboardBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        preferenceManager = new PreferenceManager(requireContext());

        setupNeighborRecyclerView();
        setupMonitoringToggle();
        setupDualSimInfo();
        setupSpeedTestCard();

        // Restore monitoring state from preferences.
        isMonitoring = preferenceManager.getBoolean(Constants.PREF_MONITORING_ACTIVE, false);
        updateToggleButton();
    }

    @Override
    public void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter(Constants.ACTION_CELL_DATA_UPDATED);
        LocalBroadcastManager.getInstance(requireContext())
                .registerReceiver(cellDataReceiver, filter);
    }

    @Override
    public void onPause() {
        super.onPause();
        LocalBroadcastManager.getInstance(requireContext())
                .unregisterReceiver(cellDataReceiver);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }

    // -- Setup ---------------------------------------------------------------

    /**
     * Initializes the neighboring-cells {@link RecyclerView}.
     */
    private void setupNeighborRecyclerView() {
        neighborAdapter = new NeighborCellAdapter();
        binding.recyclerNeighborCells.setLayoutManager(
                new LinearLayoutManager(requireContext()));
        binding.recyclerNeighborCells.setAdapter(neighborAdapter);
        binding.recyclerNeighborCells.setNestedScrollingEnabled(false);
    }

    /**
     * Wires the monitoring-toggle button to start/stop
     * {@link CellMonitorService}.
     */
    private void setupMonitoringToggle() {
        binding.btnToggleMonitoring.setOnClickListener(v -> {
            if (isMonitoring) {
                stopMonitoringService();
            } else {
                startMonitoringService();
            }
        });
    }

    /**
     * Wires the speed test card to navigate to the SpeedTestFragment.
     */
    private void setupSpeedTestCard() {
        binding.cardSpeedTest.setOnClickListener(v ->
                Navigation.findNavController(v).navigate(R.id.speedTestFragment));
    }

    /**
     * Populates dual-SIM card information if the device supports it.
     */
    private void setupDualSimInfo() {
        Context ctx = requireContext();
        SubscriptionManager subscriptionManager =
                (SubscriptionManager) ctx.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
        if (subscriptionManager == null) {
            binding.cardSim2.setVisibility(View.GONE);
            return;
        }

        try {
            List<SubscriptionInfo> subs = subscriptionManager.getActiveSubscriptionInfoList();
            if (subs == null || subs.size() < 2) {
                binding.cardSim2.setVisibility(View.GONE);
            } else {
                binding.cardSim2.setVisibility(View.VISIBLE);
                SubscriptionInfo sim1 = subs.get(0);
                SubscriptionInfo sim2 = subs.get(1);
                binding.tvSim1Label.setText(
                        toLatinDigits(String.format(Locale.US, "SIM 1: %s", sim1.getCarrierName())));
                binding.tvSim2Label.setText(
                        toLatinDigits(String.format(Locale.US, "SIM 2: %s", sim2.getCarrierName())));
            }
        } catch (SecurityException e) {
            // READ_PHONE_STATE permission not granted; hide SIM-2 card.
            binding.cardSim2.setVisibility(View.GONE);
        }
    }

    // -- Service control -----------------------------------------------------

    private void startMonitoringService() {
        Context ctx = requireContext();
        Intent serviceIntent = new Intent(ctx, CellMonitorService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(serviceIntent);
        } else {
            ctx.startService(serviceIntent);
        }
        isMonitoring = true;
        preferenceManager.putBoolean(Constants.PREF_MONITORING_ACTIVE, true);
        updateToggleButton();
    }

    private void stopMonitoringService() {
        Context ctx = requireContext();
        Intent stopIntent = new Intent(ctx, CellMonitorService.class);
        ctx.stopService(stopIntent);
        isMonitoring = false;
        preferenceManager.putBoolean(Constants.PREF_MONITORING_ACTIVE, false);
        updateToggleButton();
    }

    private void updateToggleButton() {
        if (binding == null) return;
        if (isMonitoring) {
            binding.btnToggleMonitoring.setText(R.string.stop_monitoring);
            binding.btnToggleMonitoring.setIconResource(R.drawable.ic_stop);
            binding.statusIndicator.setBackgroundTintList(
                    ColorStateList.valueOf(Color.parseColor("#4CAF50")));
            binding.tvMonitoringStatus.setText(R.string.monitoring_active);
        } else {
            binding.btnToggleMonitoring.setText(R.string.start_monitoring);
            binding.btnToggleMonitoring.setIconResource(R.drawable.ic_play);
            binding.statusIndicator.setBackgroundTintList(
                    ColorStateList.valueOf(Color.parseColor("#9E9E9E")));
            binding.tvMonitoringStatus.setText(R.string.monitoring_inactive);
        }
    }

    // -- Broadcast handling --------------------------------------------------

    /**
     * Extracts cell data from the broadcast intent and refreshes every UI
     * element on the dashboard.
     */
    private void updateDashboardFromBroadcast(@NonNull Intent intent) {
        if (binding == null) return;

        String operator      = intent.getStringExtra("operator");
        String networkType   = intent.getStringExtra("networkType");
        int    signalPower   = intent.getIntExtra("signalPower", -999);
        float  snr           = intent.getFloatExtra("snr", Float.NaN);
        long   cellId        = intent.getLongExtra("cellId", -1);
        String frequencyBand = intent.getStringExtra("frequencyBand");
        int    lac           = intent.getIntExtra("lac", -1);

        // -- Primary info card -----------------------------------------------
        binding.tvOperator.setText(formatDashboardValue(R.string.label_operator, operator));
        binding.tvNetworkType.setText(formatDashboardValue(R.string.label_network_type, networkType));
        binding.tvSignalPower.setText(
                signalPower != -999
                        ? toLatinDigits(String.format(
                                Locale.US,
                                getString(R.string.dashboard_signal_power_value),
                                signalPower))
                        : getString(R.string.dashboard_signal_power_na));
        binding.tvSnr.setText(
                !Float.isNaN(snr)
                        ? toLatinDigits(String.format(
                                Locale.US,
                                getString(R.string.dashboard_quality_value),
                                getQualityMetricLabel(networkType),
                                snr))
                        : toLatinDigits(String.format(
                                Locale.US,
                                getString(R.string.dashboard_quality_na),
                                getQualityMetricLabel(networkType))));
        binding.tvCellId.setText(
                cellId != -1
                        ? toLatinDigits(String.format(
                                Locale.US,
                                getString(R.string.dashboard_cell_id_value),
                                cellId))
                        : getString(R.string.dashboard_cell_id_na));
        binding.tvFrequencyBand.setText(
                frequencyBand != null
                        ? toLatinDigits(String.format(
                                Locale.US,
                                getString(R.string.dashboard_channel_value),
                                frequencyBand))
                        : getString(R.string.dashboard_channel_na));
        binding.tvLac.setText(
                lac != -1
                        ? toLatinDigits(String.format(
                                Locale.US,
                                getString(R.string.dashboard_area_code_value),
                                lac))
                        : getString(R.string.dashboard_area_code_na));

        // -- Signal quality gauge --------------------------------------------
        updateSignalGauge(signalPower);

        // -- Prediction card -------------------------------------------------
        if (signalPower != -999) {
            addSampleAndUpdatePrediction(signalPower);
        }

        // -- Neighboring cells -----------------------------------------------
        updateNeighboringCells();
    }

    // -- Signal quality gauge ------------------------------------------------

    /**
     * Maps a dBm value to a color-coded gauge level and updates the gauge
     * progress bar and label.
     * <ul>
     *   <li><b>Green</b>: Excellent (&ge; {@value Constants#SIGNAL_EXCELLENT} dBm)</li>
     *   <li><b>Yellow-green</b>: Good (&ge; {@value Constants#SIGNAL_GOOD} dBm)</li>
     *   <li><b>Orange</b>: Fair (&ge; {@value Constants#SIGNAL_FAIR} dBm)</li>
     *   <li><b>Red</b>: Poor (&lt; {@value Constants#SIGNAL_FAIR} dBm)</li>
     * </ul>
     */
    private void updateSignalGauge(int dBm) {
        int color;
        String quality;
        int progress;

        if (dBm >= Constants.SIGNAL_EXCELLENT) {
            color    = Color.parseColor("#4CAF50"); // green
            quality  = "Excellent";
            progress = 100;
        } else if (dBm >= Constants.SIGNAL_GOOD) {
            color    = Color.parseColor("#8BC34A"); // yellow-green
            quality  = "Good";
            progress = 75;
        } else if (dBm >= Constants.SIGNAL_FAIR) {
            color    = Color.parseColor("#FF9800"); // orange
            quality  = "Fair";
            progress = 50;
        } else if (dBm >= Constants.SIGNAL_POOR) {
            color    = Color.parseColor("#FF5722"); // deep orange
            quality  = "Poor";
            progress = 25;
        } else {
            color    = Color.parseColor("#F44336"); // red
            quality  = "No Signal";
            progress = 5;
        }

        binding.signalGauge.setProgress(progress);
        binding.signalGauge.setProgressTintList(ColorStateList.valueOf(color));
        binding.tvSignalQuality.setText(quality);
        binding.tvSignalQuality.setTextColor(color);
    }

    private String formatDashboardValue(int labelResId, @Nullable String value) {
        return toLatinDigits(getString(labelResId) + ": " + (value != null ? value : "--"));
    }

    private String getQualityMetricLabel(@Nullable String networkType) {
        if (Constants.NETWORK_3G.equals(networkType)) {
            return getString(R.string.label_quality_ecno);
        }
        if (Constants.NETWORK_2G.equals(networkType)) {
            return getString(R.string.label_quality_unavailable);
        }
        if (Constants.NETWORK_5G.equals(networkType)) {
            return getString(R.string.label_quality_sinr);
        }
        return getString(R.string.label_quality_snr_sinr);
    }

    // -- Signal trend prediction ---------------------------------------------

    /**
     * Adds a new sample to the rolling window and calculates a simple linear
     * trend over the last {@link #MAX_SIGNAL_SAMPLES} readings.
     */
    private void addSampleAndUpdatePrediction(int signalPower) {
        signalSamples.addLast(signalPower);
        if (signalSamples.size() > MAX_SIGNAL_SAMPLES) {
            signalSamples.removeFirst();
        }

        String trend = calculateTrend();
        int trendColor;
        int trendIcon;

        switch (trend) {
            case TREND_IMPROVING:
                trendColor = Color.parseColor("#4CAF50");
                trendIcon  = R.drawable.ic_trending_up;
                break;
            case TREND_DEGRADING:
                trendColor = Color.parseColor("#F44336");
                trendIcon  = R.drawable.ic_trending_down;
                break;
            default:
                trendColor = Color.parseColor("#FF9800");
                trendIcon  = R.drawable.ic_trending_flat;
                break;
        }

        binding.tvPredictionTrend.setText(trend);
        binding.tvPredictionTrend.setTextColor(trendColor);
        binding.ivTrendIcon.setImageResource(trendIcon);
        binding.ivTrendIcon.setColorFilter(trendColor);

        // Show the number of samples used.
        binding.tvPredictionSamples.setText(
                toLatinDigits(String.format(Locale.US, "Based on %d sample(s)", signalSamples.size())));
    }

    /**
     * Computes a simple least-squares slope over the stored samples.
     * A positive slope indicates improvement; a negative slope indicates
     * degradation; a slope within &plusmn;0.3 is considered stable.
     */
    private String calculateTrend() {
        int n = signalSamples.size();
        if (n < 3) {
            return TREND_STABLE;
        }

        double sumX  = 0, sumY  = 0;
        double sumXY = 0, sumX2 = 0;
        int index = 0;
        for (int sample : signalSamples) {
            sumX  += index;
            sumY  += sample;
            sumXY += (double) index * sample;
            sumX2 += (double) index * index;
            index++;
        }

        double slope = (n * sumXY - sumX * sumY) / (n * sumX2 - sumX * sumX);

        if (slope > 0.3) {
            return TREND_IMPROVING;
        } else if (slope < -0.3) {
            return TREND_DEGRADING;
        } else {
            return TREND_STABLE;
        }
    }

    // -- Neighboring cells ---------------------------------------------------

    /**
     * Queries the {@link TelephonyManager} for all visible cells and populates
     * the neighbor-cells RecyclerView, excluding the primary serving cell.
     */
    private void updateNeighboringCells() {
        Context ctx = getContext();
        if (ctx == null) return;

        TelephonyManager tm =
                (TelephonyManager) ctx.getSystemService(Context.TELEPHONY_SERVICE);
        if (tm == null) return;

        try {
            List<CellInfo> allCells = tm.getAllCellInfo();
            if (allCells == null) {
                neighborAdapter.submitList(new ArrayList<>());
                return;
            }

            List<NeighborCellItem> neighbors = new ArrayList<>();
            for (CellInfo cell : allCells) {
                if (cell.isRegistered()) {
                    // Skip the serving cell.
                    continue;
                }
                neighbors.add(parseCellInfo(cell));
            }
            neighborAdapter.submitList(neighbors);

            binding.tvNeighborCount.setText(
                    toLatinDigits(String.format(Locale.US, "Neighboring Cells (%d)", neighbors.size())));
        } catch (SecurityException e) {
            // Permission not granted.
            neighborAdapter.submitList(new ArrayList<>());
        }
    }

    /**
     * Converts a {@link CellInfo} instance into a lightweight
     * {@link NeighborCellItem} for display.
     */
    private NeighborCellItem parseCellInfo(@NonNull CellInfo cellInfo) {
        String type = Constants.NETWORK_UNKNOWN;
        int dbm = -999;
        long cid = -1;

        if (cellInfo instanceof CellInfoLte) {
            CellInfoLte lte = (CellInfoLte) cellInfo;
            type = Constants.NETWORK_4G;
            dbm  = lte.getCellSignalStrength().getDbm();
            cid  = lte.getCellIdentity().getCi();
        } else if (cellInfo instanceof CellInfoWcdma) {
            CellInfoWcdma wcdma = (CellInfoWcdma) cellInfo;
            type = Constants.NETWORK_3G;
            dbm  = wcdma.getCellSignalStrength().getDbm();
            cid  = wcdma.getCellIdentity().getCid();
        } else if (cellInfo instanceof CellInfoGsm) {
            CellInfoGsm gsm = (CellInfoGsm) cellInfo;
            type = Constants.NETWORK_2G;
            dbm  = gsm.getCellSignalStrength().getDbm();
            cid  = gsm.getCellIdentity().getCid();
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                && cellInfo instanceof CellInfoNr) {
            CellInfoNr nr = (CellInfoNr) cellInfo;
            type = Constants.NETWORK_5G;
            dbm  = nr.getCellSignalStrength().getDbm();
        }

        return new NeighborCellItem(type, dbm, cid);
    }

    // =====================================================================
    //  Inner classes
    // =====================================================================

    /**
     * Lightweight model for a neighboring cell displayed in the RecyclerView.
     */
    static class NeighborCellItem {
        final String networkType;
        final int    signalPower;
        final long   cellId;

        NeighborCellItem(String networkType, int signalPower, long cellId) {
            this.networkType = networkType;
            this.signalPower = signalPower;
            this.cellId      = cellId;
        }
    }

    /**
     * RecyclerView adapter for the list of neighboring cells.
     */
    static class NeighborCellAdapter extends RecyclerView.Adapter<NeighborCellAdapter.VH> {

        private List<NeighborCellItem> items = new ArrayList<>();

        void submitList(List<NeighborCellItem> newItems) {
            items = newItems != null ? newItems : new ArrayList<>();
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_neighbor_cell, parent, false);
            return new VH(view);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            NeighborCellItem item = items.get(position);
            holder.tvType.setText(item.networkType);
            holder.tvSignal.setText(
                    toLatinDigits(String.format(Locale.US, "%d dBm", item.signalPower)));
            holder.tvCellId.setText(
                    item.cellId != -1
                            ? toLatinDigits(String.format(Locale.US, "CID: %d", item.cellId))
                            : "CID: --");

            // Color-code the signal value.
            int color;
            if (item.signalPower >= Constants.SIGNAL_EXCELLENT) {
                color = Color.parseColor("#4CAF50");
            } else if (item.signalPower >= Constants.SIGNAL_GOOD) {
                color = Color.parseColor("#8BC34A");
            } else if (item.signalPower >= Constants.SIGNAL_FAIR) {
                color = Color.parseColor("#FF9800");
            } else {
                color = Color.parseColor("#F44336");
            }
            holder.tvSignal.setTextColor(color);
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        static class VH extends RecyclerView.ViewHolder {
            final TextView tvType;
            final TextView tvSignal;
            final TextView tvCellId;

            VH(@NonNull View itemView) {
                super(itemView);
                tvType   = itemView.findViewById(R.id.tvNeighborType);
                tvSignal = itemView.findViewById(R.id.tvNeighborSignal);
                tvCellId = itemView.findViewById(R.id.tvNeighborCellId);
            }
        }
    }

    private static String toLatinDigits(@NonNull String input) {
        return input
                .replace('\u0660', '0')
                .replace('\u0661', '1')
                .replace('\u0662', '2')
                .replace('\u0663', '3')
                .replace('\u0664', '4')
                .replace('\u0665', '5')
                .replace('\u0666', '6')
                .replace('\u0667', '7')
                .replace('\u0668', '8')
                .replace('\u0669', '9')
                .replace('\u06F0', '0')
                .replace('\u06F1', '1')
                .replace('\u06F2', '2')
                .replace('\u06F3', '3')
                .replace('\u06F4', '4')
                .replace('\u06F5', '5')
                .replace('\u06F6', '6')
                .replace('\u06F7', '7')
                .replace('\u06F8', '8')
                .replace('\u06F9', '9');
    }
}
