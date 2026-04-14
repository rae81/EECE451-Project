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
import android.telephony.TelephonyManager;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.navigation.Navigation;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.networkanalyzer.app.R;
import com.networkanalyzer.app.database.AppDatabase;
import com.networkanalyzer.app.database.SpeedTestDao;
import com.networkanalyzer.app.database.SpeedTestEntity;
import com.networkanalyzer.app.databinding.FragmentDashboardBinding;
import com.networkanalyzer.app.network.ApiService;
import com.networkanalyzer.app.network.RetrofitClient;
import com.networkanalyzer.app.network.models.DiagnosticsResponse;
import com.networkanalyzer.app.network.models.TowerClustersResponse;
import com.networkanalyzer.app.services.CellMonitorService;
import com.networkanalyzer.app.utils.Constants;
import com.networkanalyzer.app.utils.NetworkInsightEngine;
import com.networkanalyzer.app.utils.PreferenceManager;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * Real-time field dashboard inspired by telecom diagnostic tools.
 * Renders the currently-serving cell (operator, network type, signal
 * power, cell/LAC IDs, MCC/MNC, frequency band) plus neighbor-cell
 * telemetry, refreshing whenever
 * {@link com.networkanalyzer.app.services.CellMonitorService} broadcasts
 * a new reading.
 * <p>
 * Implements the "real-time service" half of the 10% graded
 * app-services requirement (EECE 451 brief). The statistical half is
 * in {@link StatisticsFragment}. Charts are drawn with MPAndroidChart
 * (Apache-2.0) — https://github.com/PhilJay/MPAndroidChart .
 */
public class DashboardFragment extends Fragment {

    private static final int MAX_SIGNAL_SAMPLES = Constants.PREDICTION_WINDOW_SIZE;
    private static final String TREND_IMPROVING = "Improving";
    private static final String TREND_STABLE = "Stable";
    private static final String TREND_DEGRADING = "Degrading";

    private FragmentDashboardBinding binding;
    private PreferenceManager preferenceManager;
    private SpeedTestDao speedTestDao;
    private ApiService apiService;
    private NeighborCellAdapter neighborAdapter;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final LinkedList<Integer> signalSamples = new LinkedList<>();

    private boolean isMonitoring = false;
    private int latestSignalPower = -999;
    private String latestOperator = "--";
    private String latestNetworkType = Constants.NETWORK_UNKNOWN;
    private String latestCellId = "--";
    private String latestFrequencyBand = "--";
    private String latestLac = "--";
    private float latestSnr = Float.NaN;
    private int lastNeighborCount = 0;

    private final BroadcastReceiver cellDataReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || !Constants.ACTION_CELL_DATA_UPDATED.equals(intent.getAction())) {
                return;
            }
            updateDashboardFromBroadcast(intent);
        }
    };

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
        speedTestDao = AppDatabase.getInstance(requireContext()).speedTestDao();
        apiService = RetrofitClient.getInstance(requireContext()).getApiService();

        setupNeighborRecyclerView();
        setupMonitoringToggle();
        setupSpeedTestCard();
        setupTowerMapButton();

        isMonitoring = preferenceManager.getBoolean(Constants.PREF_MONITORING_ACTIVE, false);
        updateToggleButton();
        applyIdleState();
        refreshExperienceCard();
    }

    @Override
    public void onResume() {
        super.onResume();
        LocalBroadcastManager.getInstance(requireContext())
                .registerReceiver(cellDataReceiver, new IntentFilter(Constants.ACTION_CELL_DATA_UPDATED));
        refreshExperienceCard();
        fetchServerDiagnostics();
        fetchTowerClusters();
        updateNeighboringCells();
    }

    @Override
    public void onPause() {
        super.onPause();
        LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(cellDataReceiver);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }

    private void setupNeighborRecyclerView() {
        neighborAdapter = new NeighborCellAdapter();
        binding.recyclerNeighborCells.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.recyclerNeighborCells.setAdapter(neighborAdapter);
        binding.recyclerNeighborCells.setNestedScrollingEnabled(false);
    }

    private void setupMonitoringToggle() {
        binding.btnToggleMonitoring.setOnClickListener(v -> {
            if (isMonitoring) {
                stopMonitoringService();
            } else {
                startMonitoringService();
            }
        });
    }

    private void setupSpeedTestCard() {
        binding.cardSpeedTest.setOnClickListener(v ->
                Navigation.findNavController(v).navigate(R.id.speedTestFragment));
    }

    private void setupTowerMapButton() {
        binding.btnOpenTowerMap.setOnClickListener(v ->
                Navigation.findNavController(v).navigate(R.id.heatmapFragment));
    }

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
        ctx.stopService(new Intent(ctx, CellMonitorService.class));
        isMonitoring = false;
        preferenceManager.putBoolean(Constants.PREF_MONITORING_ACTIVE, false);
        updateToggleButton();
    }

    private void updateToggleButton() {
        if (binding == null) {
            return;
        }
        if (isMonitoring) {
            binding.btnToggleMonitoring.setText(R.string.stop_monitoring);
            binding.btnToggleMonitoring.setIconResource(R.drawable.ic_stop);
            binding.statusIndicator.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#4CAF50")));
            binding.tvMonitoringStatus.setText(R.string.monitoring_active);
        } else {
            binding.btnToggleMonitoring.setText(R.string.start_monitoring);
            binding.btnToggleMonitoring.setIconResource(R.drawable.ic_play);
            binding.statusIndicator.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#9E9E9E")));
            binding.tvMonitoringStatus.setText(R.string.monitoring_inactive);
        }
    }

    private void applyIdleState() {
        if (binding == null) {
            return;
        }
        binding.tvNowOperator.setText(getString(R.string.placeholder_operator));
        binding.tvNowNetworkBadge.setText(getString(R.string.placeholder_network_badge));
        binding.tvOperator.setText(formatMetric("Operator", "--"));
        binding.tvNetworkType.setText(formatMetric("Network", "--"));
        binding.tvSnr.setText(formatMetric("Quality", "--"));
        binding.tvCellId.setText(getString(R.string.dashboard_cell_id_na));
        binding.tvFrequencyBand.setText(getString(R.string.dashboard_channel_na));
        binding.tvLac.setText(getString(R.string.dashboard_area_code_na));
        binding.tvTowerFingerprint.setText(getString(R.string.dashboard_tower_unknown));
        binding.tvTowerPlmn.setText(getString(R.string.dashboard_tower_plmn_unknown));
        binding.tvNeighborCount.setText(getString(R.string.placeholder_neighbor_count));
        binding.tvDiagnosticsHeadline.setText(getString(R.string.dashboard_diagnostics_placeholder));
        binding.tvAdaptiveSampling.setText(getString(R.string.dashboard_adaptive_placeholder));
        binding.tvTopTowerClusters.setText(getString(R.string.dashboard_tower_clusters_placeholder));
    }

    private void updateDashboardFromBroadcast(@NonNull Intent intent) {
        if (binding == null) {
            return;
        }

        latestOperator = fallback(intent.getStringExtra("operator"), "--");
        latestNetworkType = fallback(intent.getStringExtra("networkType"), Constants.NETWORK_UNKNOWN);
        latestSignalPower = intent.getIntExtra("signalPower", -999);
        latestSnr = intent.getFloatExtra("snr", Float.NaN);
        latestCellId = String.valueOf(intent.getLongExtra("cellId", -1));
        latestFrequencyBand = fallback(intent.getStringExtra("frequencyBand"), "--");
        latestLac = String.valueOf(intent.getIntExtra("lac", -1));
        if ("-1".equals(latestCellId)) {
            latestCellId = "--";
        }
        if ("-1".equals(latestLac)) {
            latestLac = "--";
        }

        binding.tvNowOperator.setText(latestOperator);
        binding.tvNowNetworkBadge.setText(latestNetworkType);

        binding.tvOperator.setText(formatMetric("Operator", latestOperator));
        binding.tvNetworkType.setText(formatMetric("Network", latestNetworkType));
        binding.tvSnr.setText(Float.isNaN(latestSnr)
                ? formatMetric("Quality", "--")
                : formatMetric(getQualityMetricLabel(latestNetworkType), String.format(Locale.US, "%.1f dB", latestSnr)));

        if (latestSignalPower != -999) {
            binding.tvSignalPower.setText(toLatinDigits(String.format(Locale.US, "%d dBm", latestSignalPower)));
            updateSignalGauge(latestSignalPower);
            addSampleAndUpdatePrediction(latestSignalPower);
        }

        binding.tvCellId.setText("CID " + latestCellId);
        binding.tvFrequencyBand.setText("CH " + latestFrequencyBand);
        binding.tvLac.setText("LAC/TAC " + latestLac);
        binding.tvTowerFingerprint.setText(buildTowerFingerprint());
        binding.tvTowerPlmn.setText(String.format(Locale.US, "%s • %s • %s", latestOperator, latestNetworkType, latestFrequencyBand));

        updateNeighboringCells();
        refreshExperienceCard();
        fetchServerDiagnostics();
        fetchTowerClusters();
    }

    private void refreshExperienceCard() {
        executor.execute(() -> {
            SpeedTestEntity latestSpeedTest = null;
            List<SpeedTestEntity> speedTests = speedTestDao.getRecent(1);
            if (!speedTests.isEmpty()) {
                latestSpeedTest = speedTests.get(0);
            }

            NetworkInsightEngine.ExperienceSnapshot snapshot =
                    NetworkInsightEngine.buildExperienceSnapshot(
                            latestSignalPower,
                            latestSpeedTest,
                            lastNeighborCount,
                            new ArrayList<>(signalSamples)
                    );

            if (getActivity() == null) {
                return;
            }
            getActivity().runOnUiThread(() -> {
                if (binding == null) {
                    return;
                }
                binding.tvReliabilityScore.setText(String.format(Locale.US, "%d/100", snapshot.reliabilityScore));
                binding.tvReliabilityLabel.setText(snapshot.reliabilityLabel);
                binding.tvUseCaseStreaming.setText("Streaming: " + snapshot.streamingLabel);
                binding.tvUseCaseCalling.setText("Calls: " + snapshot.callingLabel);
                binding.tvUseCaseGaming.setText("Gaming: " + snapshot.gamingLabel);
                binding.tvExperienceSummary.setText(snapshot.summary);
                int color = colorForReliability(snapshot.reliabilityScore);
                binding.tvReliabilityScore.setTextColor(color);
                binding.tvReliabilityLabel.setTextColor(color);
            });
        });
    }

    private void fetchServerDiagnostics() {
        if (apiService == null) {
            return;
        }
        apiService.getDiagnosticsSummary(preferenceManager.getDeviceId(), 1200)
                .enqueue(new Callback<DiagnosticsResponse>() {
                    @Override
                    public void onResponse(@NonNull Call<DiagnosticsResponse> call,
                                           @NonNull Response<DiagnosticsResponse> response) {
                        if (binding == null || !response.isSuccessful() || response.body() == null) {
                            return;
                        }
                        DiagnosticsResponse body = response.body();
                        binding.tvDiagnosticsHeadline.setText(buildDiagnosticsHeadline(body));
                        binding.tvAdaptiveSampling.setText(buildAdaptiveSummary(body));
                    }

                    @Override
                    public void onFailure(@NonNull Call<DiagnosticsResponse> call, @NonNull Throwable t) {
                        // Keep the local experience card if backend diagnostics are unavailable.
                    }
                });
    }

    private void fetchTowerClusters() {
        if (apiService == null) {
            return;
        }
        apiService.getTowerClusters(preferenceManager.getDeviceId(), null, 12)
                .enqueue(new Callback<TowerClustersResponse>() {
                    @Override
                    public void onResponse(@NonNull Call<TowerClustersResponse> call,
                                           @NonNull Response<TowerClustersResponse> response) {
                        if (binding == null || !response.isSuccessful() || response.body() == null) {
                            return;
                        }
                        List<TowerClustersResponse.TowerCluster> clusters = response.body().getClusters();
                        if (clusters == null || clusters.isEmpty()) {
                            binding.tvTopTowerClusters.setText(getString(R.string.dashboard_tower_clusters_placeholder));
                            return;
                        }
                        StringBuilder builder = new StringBuilder();
                        int max = Math.min(3, clusters.size());
                        for (int i = 0; i < max; i++) {
                            TowerClustersResponse.TowerCluster cluster = clusters.get(i);
                            if (i > 0) {
                                builder.append('\n');
                            }
                            builder.append(cluster.getNetworkType())
                                    .append(" • CID ")
                                    .append(cluster.getCellId())
                                    .append(" • ")
                                    .append(cluster.getSampleCount())
                                    .append(" samples • ")
                                    .append(Math.round(cluster.getAvgSignalPower()))
                                    .append(" dBm");
                        }
                        binding.tvTopTowerClusters.setText(builder.toString());
                    }

                    @Override
                    public void onFailure(@NonNull Call<TowerClustersResponse> call, @NonNull Throwable t) {
                        // Keep the local fingerprint info if tower clusters are unavailable.
                    }
                });
    }

    private void updateSignalGauge(int dBm) {
        int color;
        String quality;
        int progress;

        if (dBm >= Constants.SIGNAL_EXCELLENT) {
            color = Color.parseColor("#4CAF50");
            quality = "Excellent";
            progress = 100;
        } else if (dBm >= Constants.SIGNAL_GOOD) {
            color = Color.parseColor("#8BC34A");
            quality = "Good";
            progress = 75;
        } else if (dBm >= Constants.SIGNAL_FAIR) {
            color = Color.parseColor("#FF9800");
            quality = "Fair";
            progress = 50;
        } else if (dBm >= Constants.SIGNAL_POOR) {
            color = Color.parseColor("#FF5722");
            quality = "Poor";
            progress = 25;
        } else {
            color = Color.parseColor("#F44336");
            quality = "No Signal";
            progress = 5;
        }

        binding.signalGauge.setProgress(progress);
        binding.signalGauge.setProgressTintList(ColorStateList.valueOf(color));
        binding.tvSignalQuality.setText(quality);
        binding.tvSignalQuality.setTextColor(color);
    }

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
                trendIcon = R.drawable.ic_trending_up;
                break;
            case TREND_DEGRADING:
                trendColor = Color.parseColor("#F44336");
                trendIcon = R.drawable.ic_trending_down;
                break;
            default:
                trendColor = Color.parseColor("#FF9800");
                trendIcon = R.drawable.ic_trending_flat;
                break;
        }
        binding.tvPredictionTrend.setText(trend);
        binding.tvPredictionTrend.setTextColor(trendColor);
        binding.ivTrendIcon.setImageResource(trendIcon);
        binding.ivTrendIcon.setColorFilter(trendColor);
        binding.tvPredictionSamples.setText(
                toLatinDigits(String.format(Locale.US, "Window: %d samples", signalSamples.size())));
    }

    private String calculateTrend() {
        int n = signalSamples.size();
        if (n < 3) {
            return TREND_STABLE;
        }
        double sumX = 0;
        double sumY = 0;
        double sumXY = 0;
        double sumX2 = 0;
        int index = 0;
        for (int sample : signalSamples) {
            sumX += index;
            sumY += sample;
            sumXY += (double) index * sample;
            sumX2 += (double) index * index;
            index++;
        }
        double slope = (n * sumXY - sumX * sumY) / (n * sumX2 - sumX * sumX);
        if (slope > 0.3) {
            return TREND_IMPROVING;
        }
        if (slope < -0.3) {
            return TREND_DEGRADING;
        }
        return TREND_STABLE;
    }

    private void updateNeighboringCells() {
        Context ctx = getContext();
        if (ctx == null || binding == null) {
            return;
        }
        TelephonyManager tm = (TelephonyManager) ctx.getSystemService(Context.TELEPHONY_SERVICE);
        if (tm == null) {
            return;
        }
        try {
            List<CellInfo> allCells = tm.getAllCellInfo();
            if (allCells == null) {
                neighborAdapter.submitList(new ArrayList<>());
                return;
            }
            List<NeighborCellItem> neighbors = new ArrayList<>();
            for (CellInfo cell : allCells) {
                if (cell.isRegistered()) {
                    continue;
                }
                neighbors.add(parseCellInfo(cell));
            }
            lastNeighborCount = neighbors.size();
            neighborAdapter.submitList(neighbors);
            binding.tvNeighborCount.setText(
                    toLatinDigits(String.format(Locale.US, "Nearby cells: %d", lastNeighborCount)));
        } catch (SecurityException e) {
            neighborAdapter.submitList(new ArrayList<>());
            lastNeighborCount = 0;
        }
    }

    @NonNull
    private NeighborCellItem parseCellInfo(@NonNull CellInfo cellInfo) {
        String type = Constants.NETWORK_UNKNOWN;
        int dbm = -999;
        long cid = -1;
        if (cellInfo instanceof CellInfoLte) {
            CellInfoLte lte = (CellInfoLte) cellInfo;
            type = Constants.NETWORK_4G;
            dbm = lte.getCellSignalStrength().getDbm();
            cid = lte.getCellIdentity().getCi();
        } else if (cellInfo instanceof CellInfoWcdma) {
            CellInfoWcdma wcdma = (CellInfoWcdma) cellInfo;
            type = Constants.NETWORK_3G;
            dbm = wcdma.getCellSignalStrength().getDbm();
            cid = wcdma.getCellIdentity().getCid();
        } else if (cellInfo instanceof CellInfoGsm) {
            CellInfoGsm gsm = (CellInfoGsm) cellInfo;
            type = Constants.NETWORK_2G;
            dbm = gsm.getCellSignalStrength().getDbm();
            cid = gsm.getCellIdentity().getCid();
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && cellInfo instanceof CellInfoNr) {
            CellInfoNr nr = (CellInfoNr) cellInfo;
            type = Constants.NETWORK_5G;
            dbm = nr.getCellSignalStrength().getDbm();
        }
        return new NeighborCellItem(type, dbm, cid);
    }

    @NonNull
    private String getQualityMetricLabel(@Nullable String networkType) {
        if (Constants.NETWORK_3G.equals(networkType)) {
            return "Ec/No";
        }
        if (Constants.NETWORK_5G.equals(networkType)) {
            return "SINR";
        }
        if (Constants.NETWORK_2G.equals(networkType)) {
            return "Quality";
        }
        return "SNR";
    }

    @NonNull
    private String buildTowerFingerprint() {
        return String.format(Locale.US, "%s • %s • CID %s • TAC %s",
                latestOperator,
                latestNetworkType,
                latestCellId,
                latestLac);
    }

    private int colorForReliability(int score) {
        if (score >= 85) return Color.parseColor("#2E7D32");
        if (score >= 70) return Color.parseColor("#558B2F");
        if (score >= 55) return Color.parseColor("#EF6C00");
        if (score >= 40) return Color.parseColor("#D84315");
        return Color.parseColor("#C62828");
    }

    @NonNull
    private String formatMetric(@NonNull String label, @NonNull String value) {
        return label + ": " + value;
    }

    @NonNull
    private String buildDiagnosticsHeadline(@NonNull DiagnosticsResponse response) {
        List<DiagnosticsResponse.DiagnosticIssue> issues = response.getIssues();
        if (issues == null || issues.isEmpty()) {
            return "Diagnostics: no major radio issues flagged by the backend.";
        }
        DiagnosticsResponse.DiagnosticIssue primary = issues.get(0);
        return "Diagnostics: " + primary.getTitle() + " (" + primary.getSeverity() + ")";
    }

    @NonNull
    private String buildAdaptiveSummary(@NonNull DiagnosticsResponse response) {
        DiagnosticsResponse.Summary summary = response.getSummary();
        StringBuilder builder = new StringBuilder();
        builder.append("Adaptive cadence: ")
                .append(response.getAdaptiveLabel())
                .append(" • every ")
                .append(response.getRecommendedIntervalSeconds())
                .append("s");
        if (summary != null) {
            builder.append(" • handovers ")
                    .append(summary.getHandoverCount())
                    .append(", ping-pong ")
                    .append(summary.getPingPongCount());
        }
        return builder.toString();
    }

    @NonNull
    private String fallback(@Nullable String value, @NonNull String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value;
    }

    static class NeighborCellItem {
        final String networkType;
        final int signalPower;
        final long cellId;

        NeighborCellItem(String networkType, int signalPower, long cellId) {
            this.networkType = networkType;
            this.signalPower = signalPower;
            this.cellId = cellId;
        }
    }

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
            holder.tvCellId.setText(item.cellId != -1
                    ? toLatinDigits(String.format(Locale.US, "CID: %d", item.cellId))
                    : "CID: --");

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
                tvType = itemView.findViewById(R.id.tvNeighborType);
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
                .replace('\u0669', '9');
    }
}
