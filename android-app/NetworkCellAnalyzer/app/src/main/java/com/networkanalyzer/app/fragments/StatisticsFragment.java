package com.networkanalyzer.app.fragments;

import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.util.Pair;
import androidx.fragment.app.Fragment;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.github.mikephil.charting.charts.BarChart;
import com.github.mikephil.charting.charts.PieChart;
import com.github.mikephil.charting.components.Description;
import com.github.mikephil.charting.components.Legend;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.BarData;
import com.github.mikephil.charting.data.BarDataSet;
import com.github.mikephil.charting.data.BarEntry;
import com.github.mikephil.charting.data.PieData;
import com.github.mikephil.charting.data.PieDataSet;
import com.github.mikephil.charting.data.PieEntry;
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter;
import com.github.mikephil.charting.formatter.PercentFormatter;
import com.github.mikephil.charting.utils.ColorTemplate;
import com.google.android.material.datepicker.MaterialDatePicker;
import com.networkanalyzer.app.R;
import com.networkanalyzer.app.database.AppDatabase;
import com.networkanalyzer.app.database.CellDataEntity;
import com.networkanalyzer.app.databinding.FragmentStatisticsBinding;
import com.networkanalyzer.app.network.ApiService;
import com.networkanalyzer.app.network.RetrofitClient;
import com.networkanalyzer.app.network.models.StatsResponse;
import com.networkanalyzer.app.utils.PreferenceManager;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * Fragment displaying network statistics with interactive charts.
 * <p>
 * Features:
 * <ul>
 *   <li>Date-range picker powered by {@link MaterialDatePicker}.</li>
 *   <li>Four charts via <em>MPAndroidChart</em>:
 *       <ol>
 *         <li>PieChart &ndash; average connectivity time per operator.</li>
 *         <li>PieChart &ndash; average connectivity time per network type.</li>
 *         <li>BarChart &ndash; average signal power per network type.</li>
 *         <li>BarChart &ndash; average SNR per network type.</li>
 *       </ol>
 *   </li>
 *   <li>Pull-to-refresh via {@link SwipeRefreshLayout}.</li>
 *   <li>Loading progress indicator and empty-state view.</li>
 * </ul>
 */
public class StatisticsFragment extends Fragment {

    private static final String TAG = "StatisticsFragment";
    private static final double QUALITY_UNAVAILABLE_THRESHOLD = -900.0;

    // -- Fields --------------------------------------------------------------

    private FragmentStatisticsBinding binding;
    private PreferenceManager preferenceManager;
    private ApiService apiService;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    /** Currently selected date range in epoch milliseconds. */
    private long dateFrom = 0L;
    private long dateTo   = System.currentTimeMillis();

    private final SimpleDateFormat dateFormat =
            new SimpleDateFormat("MMM dd, yyyy", Locale.US);

    // -- Lifecycle -----------------------------------------------------------

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentStatisticsBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        preferenceManager = new PreferenceManager(requireContext());
        apiService = RetrofitClient.getInstance(requireContext()).getApiService();

        setupDateRangePicker();
        setupSwipeRefresh();
        setupChartDefaults();

        // Initial fetch with default range (last 7 days).
        dateFrom = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000;
        updateDateLabels();
        fetchStats();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }

    @Override
    public void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    // -- Setup ---------------------------------------------------------------

    /**
     * Configures the date-range picker button. Opens a
     * {@link MaterialDatePicker} for selecting a from/to date pair.
     */
    private void setupDateRangePicker() {
        binding.btnDateRange.setOnClickListener(v -> {
            MaterialDatePicker<Pair<Long, Long>> picker =
                    MaterialDatePicker.Builder.dateRangePicker()
                            .setTitleText("Select date range")
                            .setSelection(new Pair<>(dateFrom, dateTo))
                            .build();

            picker.addOnPositiveButtonClickListener(selection -> {
                dateFrom = selection.first;
                dateTo   = selection.second;
                updateDateLabels();
                fetchStats();
            });

            picker.show(getChildFragmentManager(), "DATE_RANGE_PICKER");
        });
    }

    private void setupSwipeRefresh() {
        binding.swipeRefresh.setColorSchemeColors(
                Color.parseColor("#1976D2"),
                Color.parseColor("#388E3C"),
                Color.parseColor("#FFA000"));
        binding.swipeRefresh.setOnRefreshListener(this::fetchStats);
    }

    /**
     * Applies common visual defaults to all four charts.
     */
    private void setupChartDefaults() {
        // Pie charts
        configurePieChart(binding.pieChartOperator, "Connectivity by Operator");
        configurePieChart(binding.pieChartNetworkType, "Connectivity by Network Type");

        // Bar charts
        configureBarChart(binding.barChartSignalPower, "Avg Signal Power (dBm)");
        configureBarChart(binding.barChartSnr, "Avg SNR (dB)");
    }

    private void configurePieChart(@NonNull PieChart chart, String label) {
        chart.setUsePercentValues(true);
        chart.setDrawHoleEnabled(true);
        chart.setHoleColor(Color.TRANSPARENT);
        chart.setHoleRadius(45f);
        chart.setTransparentCircleRadius(50f);
        chart.setDrawEntryLabels(true);
        chart.setEntryLabelColor(Color.BLACK);
        chart.setEntryLabelTextSize(11f);
        chart.getDescription().setEnabled(false);
        chart.setCenterText(label);
        chart.setCenterTextSize(12f);
        chart.setRotationEnabled(true);
        chart.setHighlightPerTapEnabled(true);
        chart.animateY(800);

        Legend legend = chart.getLegend();
        legend.setVerticalAlignment(Legend.LegendVerticalAlignment.BOTTOM);
        legend.setHorizontalAlignment(Legend.LegendHorizontalAlignment.CENTER);
        legend.setOrientation(Legend.LegendOrientation.HORIZONTAL);
        legend.setDrawInside(false);
        legend.setTextSize(11f);
    }

    private void configureBarChart(@NonNull BarChart chart, String label) {
        Description desc = new Description();
        desc.setText(label);
        desc.setTextSize(12f);
        chart.setDescription(desc);
        chart.setDrawGridBackground(false);
        chart.setDrawBarShadow(false);
        chart.setFitBars(true);
        chart.setPinchZoom(false);
        chart.setDoubleTapToZoomEnabled(false);
        chart.animateY(800);

        XAxis xAxis = chart.getXAxis();
        xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);
        xAxis.setGranularity(1f);
        xAxis.setDrawGridLines(false);
        xAxis.setTextSize(11f);

        YAxis leftAxis = chart.getAxisLeft();
        leftAxis.setDrawGridLines(true);
        leftAxis.setTextSize(11f);
        chart.getAxisRight().setEnabled(false);

        Legend legend = chart.getLegend();
        legend.setEnabled(false);
    }

    // -- Data fetching -------------------------------------------------------

    /**
     * Fetches statistics from the server for the selected date range.
     */
    private void fetchStats() {
        showLoading(true);
        showEmptyState(false);

        String deviceId = preferenceManager.getDeviceId();
        String from  = String.valueOf(dateFrom);
        String to    = String.valueOf(dateTo);

        apiService.getStats(deviceId, from, to).enqueue(new Callback<StatsResponse>() {
            @Override
            public void onResponse(@NonNull Call<StatsResponse> call,
                                   @NonNull Response<StatsResponse> response) {
                if (binding == null) return;
                showLoading(false);

                if (response.isSuccessful() && response.body() != null) {
                    populateCharts(response.body());
                } else {
                    Log.e(TAG, "Stats request failed: " + response.code());
                    fetchLocalStats();
                }
            }

            @Override
            public void onFailure(@NonNull Call<StatsResponse> call,
                                  @NonNull Throwable t) {
                if (binding == null) return;
                Log.e(TAG, "Stats request error", t);
                fetchLocalStats();
            }
        });
    }

    private void fetchLocalStats() {
        executor.execute(() -> {
            List<CellDataEntity> entries = AppDatabase.getInstance(requireContext())
                    .cellDataDao()
                    .getByDateRange(dateFrom, dateTo);

            StatsResponse local = new StatsResponse();
            local.setSuccess(true);
            local.setOperatorTime(avgPercentByOperator(entries));
            local.setNetworkTypeTime(avgPercentByNetwork(entries));
            local.setAvgSignalPerType(avgSignalByNetwork(entries));
            local.setAvgSnrPerType(avgSnrByNetwork(entries));
            local.setTotalRecords(entries.size());

            requireActivity().runOnUiThread(() -> {
                if (binding == null) {
                    return;
                }
                showLoading(false);
                populateCharts(local);
            });
        });
    }

    private Map<String, Double> avgPercentByOperator(List<CellDataEntity> entries) {
        return percentageMap(entries, true);
    }

    private Map<String, Double> avgPercentByNetwork(List<CellDataEntity> entries) {
        return percentageMap(entries, false);
    }

    private Map<String, Double> percentageMap(List<CellDataEntity> entries, boolean operator) {
        java.util.LinkedHashMap<String, Integer> counts = new java.util.LinkedHashMap<>();
        for (CellDataEntity entity : entries) {
            String key = operator ? entity.getOperator() : entity.getNetworkType();
            if (key == null || key.trim().isEmpty()) {
                key = "Unknown";
            }
            counts.put(key, counts.getOrDefault(key, 0) + 1);
        }
        java.util.LinkedHashMap<String, Double> result = new java.util.LinkedHashMap<>();
        int total = Math.max(1, entries.size());
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            result.put(entry.getKey(), (entry.getValue() * 100.0) / total);
        }
        return result;
    }

    private Map<String, Double> avgSignalByNetwork(List<CellDataEntity> entries) {
        return averageByNetwork(entries, false);
    }

    private Map<String, Double> avgSnrByNetwork(List<CellDataEntity> entries) {
        return averageByNetwork(entries, true);
    }

    private Map<String, Double> averageByNetwork(List<CellDataEntity> entries, boolean snr) {
        java.util.LinkedHashMap<String, Double> totals = new java.util.LinkedHashMap<>();
        java.util.LinkedHashMap<String, Integer> counts = new java.util.LinkedHashMap<>();
        for (CellDataEntity entity : entries) {
            String key = entity.getNetworkType() == null ? "Unknown" : entity.getNetworkType();
            double value = snr ? entity.getSnr() : entity.getSignalPower();
            if (snr && value <= QUALITY_UNAVAILABLE_THRESHOLD) {
                continue;
            }
            totals.put(key, totals.getOrDefault(key, 0.0) + value);
            counts.put(key, counts.getOrDefault(key, 0) + 1);
        }
        java.util.LinkedHashMap<String, Double> result = new java.util.LinkedHashMap<>();
        for (String key : totals.keySet()) {
            result.put(key, totals.get(key) / Math.max(1, counts.get(key)));
        }
        return result;
    }

    // -- Chart population ----------------------------------------------------

    /**
     * Distributes the {@link StatsResponse} data across all four charts.
     */
    private void populateCharts(@NonNull StatsResponse stats) {
        boolean hasData = false;
        updateSummaryCards(stats);

        // 1. Operator pie chart
        Map<String, Float> operatorMap = stats.getConnectivityByOperator();
        if (operatorMap != null && !operatorMap.isEmpty()) {
            hasData = true;
            populateOperatorPieChart(operatorMap);
        }

        // 2. Network-type pie chart
        Map<String, Float> networkTypeMap = stats.getConnectivityByNetworkType();
        if (networkTypeMap != null && !networkTypeMap.isEmpty()) {
            hasData = true;
            populateNetworkTypePieChart(networkTypeMap);
        }

        // 3. Average signal power bar chart
        Map<String, Float> signalPowerMap = stats.getAvgSignalPowerByNetworkType();
        if (signalPowerMap != null && !signalPowerMap.isEmpty()) {
            hasData = true;
            populateSignalPowerBarChart(signalPowerMap);
        }

        // 4. Average SNR bar chart
        Map<String, Float> snrMap = stats.getAvgSnrByNetworkType();
        if (snrMap != null && !snrMap.isEmpty()) {
            hasData = true;
            populateSnrBarChart(snrMap);
        }

        showEmptyState(!hasData);
    }

    private void updateSummaryCards(@NonNull StatsResponse stats) {
        if (binding == null) {
            return;
        }

        Map<String, Float> operatorMap = stats.getConnectivityByOperator();
        Map<String, Float> networkTypeMap = stats.getConnectivityByNetworkType();
        Map<String, Float> signalPowerMap = stats.getAvgSignalPowerByNetworkType();

        binding.tvSummaryRecords.setText(String.valueOf(stats.getTotalRecords()));
        binding.tvSummaryOperators.setText(String.valueOf(operatorMap != null ? operatorMap.size() : 0));
        binding.tvSummaryNetworks.setText(String.valueOf(networkTypeMap != null ? networkTypeMap.size() : 0));

        if (signalPowerMap != null && !signalPowerMap.isEmpty()) {
            float total = 0f;
            for (Float value : signalPowerMap.values()) {
                total += value;
            }
            float average = total / signalPowerMap.size();
            binding.tvSummarySignal.setText(String.format(Locale.US, "%.0f dBm", average));
        } else {
            binding.tvSummarySignal.setText("--");
        }
    }

    /**
     * PieChart #1: Average connectivity time per operator.
     */
    private void populateOperatorPieChart(@NonNull Map<String, Float> data) {
        List<PieEntry> entries = new ArrayList<>();
        for (Map.Entry<String, Float> entry : data.entrySet()) {
            entries.add(new PieEntry(entry.getValue(), entry.getKey()));
        }

        PieDataSet dataSet = new PieDataSet(entries, "Operators");
        dataSet.setColors(getChartColors());
        dataSet.setSliceSpace(2f);
        dataSet.setValueTextSize(12f);
        dataSet.setValueTextColor(Color.WHITE);
        dataSet.setValueFormatter(new PercentFormatter(binding.pieChartOperator));

        PieData pieData = new PieData(dataSet);
        binding.pieChartOperator.setData(pieData);
        binding.pieChartOperator.invalidate();
    }

    /**
     * PieChart #2: Average connectivity time per network type.
     */
    private void populateNetworkTypePieChart(@NonNull Map<String, Float> data) {
        List<PieEntry> entries = new ArrayList<>();
        for (Map.Entry<String, Float> entry : data.entrySet()) {
            entries.add(new PieEntry(entry.getValue(), entry.getKey()));
        }

        PieDataSet dataSet = new PieDataSet(entries, "Network Types");
        dataSet.setColors(getNetworkTypeColors(data));
        dataSet.setSliceSpace(2f);
        dataSet.setValueTextSize(12f);
        dataSet.setValueTextColor(Color.WHITE);
        dataSet.setValueFormatter(new PercentFormatter(binding.pieChartNetworkType));

        PieData pieData = new PieData(dataSet);
        binding.pieChartNetworkType.setData(pieData);
        binding.pieChartNetworkType.invalidate();
    }

    /**
     * BarChart #1: Average signal power per network type.
     */
    private void populateSignalPowerBarChart(@NonNull Map<String, Float> data) {
        List<BarEntry> entries = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        int index = 0;

        for (Map.Entry<String, Float> entry : data.entrySet()) {
            // Signal power is negative; use absolute value for display, label shows actual.
            entries.add(new BarEntry(index, entry.getValue()));
            labels.add(entry.getKey());
            index++;
        }

        BarDataSet dataSet = new BarDataSet(entries, "Avg Signal Power");
        dataSet.setColors(getChartColors());
        dataSet.setValueTextSize(11f);
        dataSet.setValueTextColor(Color.BLACK);

        BarData barData = new BarData(dataSet);
        barData.setBarWidth(0.6f);

        binding.barChartSignalPower.getXAxis()
                .setValueFormatter(new IndexAxisValueFormatter(labels));
        binding.barChartSignalPower.setData(barData);
        binding.barChartSignalPower.invalidate();
    }

    /**
     * BarChart #2: Average SNR per network type.
     */
    private void populateSnrBarChart(@NonNull Map<String, Float> data) {
        List<BarEntry> entries = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        int index = 0;

        for (Map.Entry<String, Float> entry : data.entrySet()) {
            entries.add(new BarEntry(index, entry.getValue()));
            labels.add(entry.getKey());
            index++;
        }

        BarDataSet dataSet = new BarDataSet(entries, "Avg SNR");
        dataSet.setColors(getChartColors());
        dataSet.setValueTextSize(11f);
        dataSet.setValueTextColor(Color.BLACK);

        BarData barData = new BarData(dataSet);
        barData.setBarWidth(0.6f);

        binding.barChartSnr.getXAxis()
                .setValueFormatter(new IndexAxisValueFormatter(labels));
        binding.barChartSnr.setData(barData);
        binding.barChartSnr.invalidate();
    }

    // -- Color helpers -------------------------------------------------------

    /**
     * Returns a list of distinct colors for chart data sets.
     */
    private List<Integer> getChartColors() {
        List<Integer> colors = new ArrayList<>();
        colors.add(Color.parseColor("#1976D2")); // Blue
        colors.add(Color.parseColor("#388E3C")); // Green
        colors.add(Color.parseColor("#FFA000")); // Amber
        colors.add(Color.parseColor("#D32F2F")); // Red
        colors.add(Color.parseColor("#7B1FA2")); // Purple
        colors.add(Color.parseColor("#00796B")); // Teal
        return colors;
    }

    /**
     * Returns colors mapped semantically to network generation names.
     */
    private List<Integer> getNetworkTypeColors(@NonNull Map<String, Float> data) {
        List<Integer> colors = new ArrayList<>();
        for (String key : data.keySet()) {
            switch (key) {
                case "2G":
                    colors.add(Color.parseColor("#F44336")); // Red
                    break;
                case "3G":
                    colors.add(Color.parseColor("#FF9800")); // Orange
                    break;
                case "4G":
                    colors.add(Color.parseColor("#4CAF50")); // Green
                    break;
                case "5G":
                    colors.add(Color.parseColor("#2196F3")); // Blue
                    break;
                default:
                    colors.add(Color.parseColor("#9E9E9E")); // Grey
                    break;
            }
        }
        return colors;
    }

    // -- UI state helpers ----------------------------------------------------

    private void updateDateLabels() {
        if (binding == null) return;
        String label = dateFormat.format(new Date(dateFrom))
                + " - " + dateFormat.format(new Date(dateTo));
        binding.tvDateRange.setText(label);
    }

    private void showLoading(boolean loading) {
        if (binding == null) return;
        binding.swipeRefresh.setRefreshing(loading);
        binding.progressBar.setVisibility(loading ? View.VISIBLE : View.GONE);
        binding.scrollViewContent.setVisibility(loading ? View.GONE : View.VISIBLE);
    }

    private void showEmptyState(boolean empty) {
        if (binding == null) return;
        binding.layoutEmptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
        binding.scrollViewContent.setVisibility(empty ? View.GONE : View.VISIBLE);
    }
}
