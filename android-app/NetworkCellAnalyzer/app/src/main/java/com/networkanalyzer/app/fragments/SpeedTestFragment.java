package com.networkanalyzer.app.fragments;

import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.github.mikephil.charting.charts.BarChart;
import com.github.mikephil.charting.components.Description;
import com.github.mikephil.charting.data.BarData;
import com.github.mikephil.charting.data.BarDataSet;
import com.github.mikephil.charting.data.BarEntry;
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter;
import com.networkanalyzer.app.R;
import com.networkanalyzer.app.database.AppDatabase;
import com.networkanalyzer.app.database.CellDataEntity;
import com.networkanalyzer.app.database.SpeedTestEntity;
import com.networkanalyzer.app.databinding.FragmentSpeedTestBinding;
import com.networkanalyzer.app.network.ApiService;
import com.networkanalyzer.app.network.RetrofitClient;
import com.networkanalyzer.app.network.models.GenericResponse;
import com.networkanalyzer.app.network.models.SpeedTestRequest;
import com.networkanalyzer.app.utils.PreferenceManager;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import retrofit2.Call;

/**
 * Network speed-test fragment — measures download throughput, upload
 * throughput, and round-trip latency against our own Flask server's
 * {@code /speedtest/download} + {@code /speedtest/upload} endpoints,
 * using the well-known "fetch N MB chunk, time the byte stream, ignore
 * the first connection-setup window" pattern used by Ookla,
 * speedtest-cli, and Cloudflare's {@code speed.cloudflare.com}.
 * <p>
 * Extra (non-required) feature. References:
 * <ul>
 *   <li>speedtest-cli (Apache-2.0) — https://github.com/sivel/speedtest-cli
 *   <li>Cloudflare speed-test client — https://github.com/cloudflare/speedtest
 *   <li>OkHttp streaming body pattern — https://square.github.io/okhttp/recipes/
 * </ul>
 */
public class SpeedTestFragment extends Fragment {

    private static final String TAG = "SpeedTestFragment";
    private static final int NETWORK_TIMEOUT_SECONDS = 30;
    private static final int LATENCY_SAMPLE_COUNT = 5;
    private static final int DOWNLOAD_SAMPLE_COUNT = 2;
    private static final int UPLOAD_SAMPLE_COUNT = 2;

    private static final int UPLOAD_SIZE_BYTES = 512 * 1024;
    private static final int DOWNLOAD_SIZE_BYTES = 8 * 1024 * 1024;
    private static final int BUFFER_SIZE = 16384;

    private FragmentSpeedTestBinding binding;
    private PreferenceManager preferenceManager;
    private AppDatabase database;
    private ApiService apiService;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final List<SpeedTestResult> historyList = new ArrayList<>();
    private SpeedTestHistoryAdapter historyAdapter;

    private volatile boolean isTestRunning = false;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentSpeedTestBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        preferenceManager = new PreferenceManager(requireContext());
        database = AppDatabase.getInstance(requireContext());
        apiService = RetrofitClient.getInstance(requireContext()).getApiService();

        setupHistoryRecycler();
        setupGaugeChart();
        setupStartButton();

        resetDisplay();
        loadHistoryFromDatabase();
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

    private void setupHistoryRecycler() {
        historyAdapter = new SpeedTestHistoryAdapter(historyList);
        binding.recyclerHistory.setLayoutManager(
                new LinearLayoutManager(requireContext()));
        binding.recyclerHistory.setAdapter(historyAdapter);
        binding.recyclerHistory.setNestedScrollingEnabled(false);
    }

    private void setupGaugeChart() {
        BarChart chart = binding.chartGauge;
        chart.setDrawGridBackground(false);
        chart.setDrawBarShadow(false);
        chart.setFitBars(true);
        chart.setPinchZoom(false);
        chart.setDoubleTapToZoomEnabled(false);
        chart.getAxisRight().setEnabled(false);
        chart.getXAxis().setPosition(
                com.github.mikephil.charting.components.XAxis.XAxisPosition.BOTTOM);
        chart.getXAxis().setGranularity(1f);
        chart.getXAxis().setDrawGridLines(false);
        chart.getLegend().setEnabled(false);

        Description desc = new Description();
        desc.setText("");
        chart.setDescription(desc);
    }

    private void setupStartButton() {
        binding.btnStartTest.setOnClickListener(v -> {
            if (!isTestRunning) {
                runSpeedTest();
            }
        });
    }

    /**
     * Orchestrates the full speed test: ping, download, upload. Each phase
     * updates the UI progressively.
     */
    private void runSpeedTest() {
        isTestRunning = true;
        setTestRunningUI(true);

        executor.execute(() -> {
            String baseUrl = normalizeBaseUrl(preferenceManager.getServerUrl());
            String latencyUrl = baseUrl + "healthz";
            String downloadUrl = baseUrl + "api/speed-test/download?size_mb=8";
            String uploadUrl = baseUrl + "api/speed-test/upload";

            // --- Warm up: establish connection pool ---
            postProgress("Warming up connection...", 5);
            warmUpConnection(latencyUrl);

            // --- Phase 1: Ping ---
            postProgress("Measuring latency...", 10);
            long latencyMs = measureLatency(latencyUrl);
            postLatencyResult(latencyMs);

            // --- Phase 2: Download ---
            postProgress("Measuring download speed...", 30);
            double downloadMbps = measureDownloadSpeed(downloadUrl);
            postDownloadResult(downloadMbps);

            // --- Phase 3: Upload ---
            postProgress("Measuring upload speed...", 65);
            double uploadMbps = measureUploadSpeed(uploadUrl);
            postUploadResult(uploadMbps);

            SpeedTestResult result = new SpeedTestResult(
                    System.currentTimeMillis(),
                    downloadMbps,
                    uploadMbps,
                    latencyMs);

            mainHandler.post(() -> onTestComplete(result));
        });
    }

    /**
     * Sends a throwaway request to prime the connection pool (DNS + TLS).
     * Subsequent requests reuse the connection, giving more accurate timing.
     */
    private void warmUpConnection(@NonNull String url) {
        OkHttpClient client = buildClient();
        String requestUrl = url.contains("?") ? url + "&warmup=1" : url + "?warmup=1";
        Request request = new Request.Builder()
                .url(requestUrl)
                .get()
                .build();
        try (Response response = client.newCall(request).execute()) {
            // discard
        } catch (IOException ignored) {
        }
    }

    private OkHttpClient buildClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(NETWORK_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(NETWORK_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
                .writeTimeout(NETWORK_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
                .retryOnConnectionFailure(false)
                .build();
    }

    /**
     * Measures latency using HTTP HEAD requests. A warm-up request primes the
     * connection pool, then multiple samples are taken and the median returned.
     */
    private long measureLatency(@NonNull String baseUrl) {
        OkHttpClient client = buildClient();
        List<Long> samples = new ArrayList<>();

        for (int i = 0; i < LATENCY_SAMPLE_COUNT; i++) {
            String requestUrl = baseUrl.contains("?")
                    ? baseUrl + "&ping=" + i + "&t=" + System.nanoTime()
                    : baseUrl + "?ping=" + i + "&t=" + System.nanoTime();
            Request request = new Request.Builder()
                    .url(requestUrl)
                    .get()
                    .addHeader("Cache-Control", "no-cache, no-store")
                    .addHeader("Pragma", "no-cache")
                    .build();
            try {
                long start = System.nanoTime();
                try (Response response = client.newCall(request).execute()) {
                    long elapsed = (System.nanoTime() - start) / 1_000_000;
                    if (response.isSuccessful() || response.code() == 405) {
                        samples.add(elapsed);
                    }
                }
            } catch (IOException e) {
                Log.e(TAG, "Latency measurement failed", e);
            }
        }

        if (samples.isEmpty()) {
            return -1;
        }
        java.util.Collections.sort(samples);
        return samples.get(samples.size() / 2);
    }

    /**
     * Downloads a file and computes throughput in Mbps. Timing starts AFTER
     * the connection is established and response headers are received, so
     * DNS, TCP handshake, and TLS setup are excluded from the measurement.
     */
    private double measureDownloadSpeed(@NonNull String downloadUrl) {
        List<Double> samples = new ArrayList<>();
        for (int sampleIndex = 0; sampleIndex < DOWNLOAD_SAMPLE_COUNT; sampleIndex++) {
            try {
                String urlStr = downloadUrl.contains("?")
                        ? downloadUrl + "&s=" + sampleIndex + "&t=" + System.nanoTime()
                        : downloadUrl + "?s=" + sampleIndex + "&t=" + System.nanoTime();
                URL url = new URL(urlStr);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(NETWORK_TIMEOUT_SECONDS * 1000);
                conn.setReadTimeout(NETWORK_TIMEOUT_SECONDS * 1000);
                conn.setUseCaches(false);
                conn.setRequestProperty("Cache-Control", "no-cache, no-store");
                conn.setRequestProperty("Pragma", "no-cache");
                conn.connect();

                int responseCode = conn.getResponseCode();
                if (responseCode < 200 || responseCode >= 300) {
                    conn.disconnect();
                    continue;
                }

                long expectedBytes = conn.getContentLengthLong() > 0
                        ? conn.getContentLengthLong()
                        : DOWNLOAD_SIZE_BYTES;

                // Timing starts AFTER headers are received (connection is
                // established). This gives a pure data transfer measurement.
                long totalBytes = 0;
                long startTime = System.nanoTime();

                try (InputStream in = conn.getInputStream()) {
                    byte[] buffer = new byte[BUFFER_SIZE];
                    int bytesRead;
                    while ((bytesRead = in.read(buffer)) != -1) {
                        totalBytes += bytesRead;

                        int pct = 30 + (int) ((totalBytes * 30.0) / Math.max(1, expectedBytes));
                        postProgress("Downloading...", Math.min(pct, 60));
                    }
                }

                long elapsed = System.nanoTime() - startTime;
                conn.disconnect();
                if (elapsed > 0 && totalBytes > 0) {
                    double seconds = elapsed / 1_000_000_000.0;
                    double bits = totalBytes * 8.0;
                    samples.add(bits / seconds / 1_000_000.0);
                }
            } catch (IOException e) {
                Log.e(TAG, "Download speed test failed", e);
            }
        }
        if (samples.isEmpty()) {
            return 0.0;
        }
        return average(samples);
    }

    /**
     * Uploads random bytes and computes throughput in Mbps. Uses a reusable
     * OkHttp client so connection pooling excludes setup overhead.
     */
    private double measureUploadSpeed(@NonNull String uploadUrl) {
        OkHttpClient client = buildClient();
        List<Double> samples = new ArrayList<>();

        for (int sampleIndex = 0; sampleIndex < UPLOAD_SAMPLE_COUNT; sampleIndex++) {
            try {
                byte[] payload = new byte[UPLOAD_SIZE_BYTES];
                new Random().nextBytes(payload);

                String urlStr = uploadUrl.contains("?")
                        ? uploadUrl + "&s=" + sampleIndex
                        : uploadUrl + "?s=" + sampleIndex;

                RequestBody body = RequestBody.create(
                        MediaType.parse("application/octet-stream"), payload);

                Request.Builder requestBuilder = new Request.Builder()
                        .url(urlStr)
                        .post(body)
                        .addHeader("Cache-Control", "no-cache");

                String authToken = preferenceManager.getAuthToken();
                if (authToken != null && !authToken.trim().isEmpty()) {
                    requestBuilder.addHeader("Authorization", "Bearer " + authToken);
                }

                Request request = requestBuilder.build();
                long startTime = System.nanoTime();

                try (Response response = client.newCall(request).execute()) {
                    long elapsed = System.nanoTime() - startTime;
                    if (response.isSuccessful() || response.code() == 405) {
                        if (elapsed > 0) {
                            double seconds = elapsed / 1_000_000_000.0;
                            double bits = UPLOAD_SIZE_BYTES * 8.0;
                            samples.add(bits / seconds / 1_000_000.0);
                        }
                    }
                }
            } catch (IOException e) {
                Log.e(TAG, "Upload speed test failed", e);
            }
        }
        if (samples.isEmpty()) {
            return 0.0;
        }
        return average(samples);
    }

    // -- UI update helpers ---------------------------------------------------

    private void postProgress(@NonNull String message, int percent) {
        mainHandler.post(() -> {
            if (binding == null) return;
            binding.tvTestStatus.setText(message);
            binding.progressBar.setProgress(percent);
        });
    }

    private void postLatencyResult(long ms) {
        mainHandler.post(() -> {
            if (binding == null) return;
            binding.tvLatency.setText(
                    ms >= 0 ? String.format(Locale.US, "%d ms", ms) : "-- ms");
        });
    }

    private void postDownloadResult(double mbps) {
        mainHandler.post(() -> {
            if (binding == null) return;
            binding.tvDownloadSpeed.setText(
                    String.format(Locale.US, "%.1f", mbps));
        });
    }

    private void postUploadResult(double mbps) {
        mainHandler.post(() -> {
            if (binding == null) return;
            binding.tvUploadSpeed.setText(
                    String.format(Locale.US, "%.1f", mbps));
        });
    }

    private void onTestComplete(@NonNull SpeedTestResult result) {
        if (binding == null) return;

        isTestRunning = false;
        setTestRunningUI(false);
        if (result.latencyMs < 0 && result.downloadMbps <= 0.0 && result.uploadMbps <= 0.0) {
            binding.tvTestStatus.setText("Test failed. Check server reachability.");
        } else {
            binding.tvTestStatus.setText("Test complete");
        }
        binding.progressBar.setProgress(100);

        updateGaugeChart(result);

        historyList.add(0, result);
        historyAdapter.notifyItemInserted(0);
        binding.recyclerHistory.scrollToPosition(0);

        persistAndSyncResult(result);
    }

    private void setTestRunningUI(boolean running) {
        if (binding == null) return;
        binding.btnStartTest.setEnabled(!running);
        binding.btnStartTest.setText(running ? R.string.testing : R.string.start_test);
        binding.progressBar.setVisibility(running ? View.VISIBLE : View.INVISIBLE);
        binding.progressBar.setProgress(0);

        if (running) {
            binding.tvDownloadSpeed.setText("--");
            binding.tvUploadSpeed.setText("--");
            binding.tvLatency.setText("-- ms");
        }
    }

    private void resetDisplay() {
        binding.tvDownloadSpeed.setText("--");
        binding.tvUploadSpeed.setText("--");
        binding.tvLatency.setText("-- ms");
        binding.tvTestStatus.setText("Tap Start to begin");
        binding.progressBar.setProgress(0);
        binding.progressBar.setVisibility(View.INVISIBLE);
    }

    private void updateGaugeChart(@NonNull SpeedTestResult result) {
        List<BarEntry> entries = new ArrayList<>();
        entries.add(new BarEntry(0, (float) result.downloadMbps));
        entries.add(new BarEntry(1, (float) result.uploadMbps));
        entries.add(new BarEntry(2, (float) result.latencyMs));

        BarDataSet dataSet = new BarDataSet(entries, "Speed Test");
        List<Integer> colors = new ArrayList<>();
        colors.add(Color.parseColor("#4CAF50"));
        colors.add(Color.parseColor("#2196F3"));
        colors.add(Color.parseColor("#FF9800"));
        dataSet.setColors(colors);
        dataSet.setValueTextSize(12f);
        dataSet.setValueTextColor(Color.GRAY);

        BarData barData = new BarData(dataSet);
        barData.setBarWidth(0.5f);

        List<String> labels = Arrays.asList("Download", "Upload", "Latency");
        binding.chartGauge.getXAxis()
                .setValueFormatter(new IndexAxisValueFormatter(labels));
        binding.chartGauge.setData(barData);
        binding.chartGauge.animateY(500);
        binding.chartGauge.invalidate();
    }

    private void loadHistoryFromDatabase() {
        executor.execute(() -> {
            List<SpeedTestEntity> storedResults = database.speedTestDao().getRecent(20);
            List<SpeedTestResult> mappedResults = new ArrayList<>();
            for (SpeedTestEntity entity : storedResults) {
                mappedResults.add(new SpeedTestResult(
                        entity.getTimestamp(),
                        entity.getDownloadSpeed(),
                        entity.getUploadSpeed(),
                        entity.getLatency()));
            }
            mainHandler.post(() -> {
                historyList.clear();
                historyList.addAll(mappedResults);
                if (historyAdapter != null) {
                    historyAdapter.notifyDataSetChanged();
                }
            });
        });
    }

    private void persistAndSyncResult(@NonNull SpeedTestResult result) {
        executor.execute(() -> {
            SpeedTestEntity entity = buildSpeedTestEntity(result);
            long rowId = database.speedTestDao().insert(entity);
            sendResultToServer(entity, (int) rowId);
        });
    }

    @NonNull
    private SpeedTestEntity buildSpeedTestEntity(@NonNull SpeedTestResult result) {
        SpeedTestEntity entity = new SpeedTestEntity();
        entity.setDeviceId(preferenceManager.getDeviceId());
        entity.setDownloadSpeed(result.downloadMbps);
        entity.setUploadSpeed(result.uploadMbps);
        entity.setLatency((int) result.latencyMs);
        entity.setTimestamp(result.timestamp);
        entity.setSynced(false);

        List<CellDataEntity> latestReadings = database.cellDataDao().getLatest(1);
        if (!latestReadings.isEmpty()) {
            CellDataEntity latest = latestReadings.get(0);
            entity.setOperator(latest.getOperator());
            entity.setNetworkType(latest.getNetworkType());
        }
        return entity;
    }

    private void sendResultToServer(@NonNull SpeedTestEntity entity, int localId) {
        try {
            Integer signalPower = null;
            List<CellDataEntity> latestReadings = database.cellDataDao().getLatest(1);
            if (!latestReadings.isEmpty()) {
                signalPower = latestReadings.get(0).getSignalPower();
            }

            SpeedTestRequest request = new SpeedTestRequest(
                    entity.getDeviceId(),
                    entity.getDownloadSpeed(),
                    entity.getUploadSpeed(),
                    entity.getLatency(),
                    entity.getNetworkType(),
                    entity.getOperator(),
                    signalPower,
                    entity.getTimestamp());

            Call<GenericResponse> call = apiService.submitSpeedTest(request);
            retrofit2.Response<GenericResponse> response = call.execute();
            if (response.isSuccessful() && response.body() != null && response.body().isSuccess()) {
                database.speedTestDao().markAsSynced(localId);
                return;
            }
            Log.w(TAG, "Speed test sync failed with HTTP " + response.code());
        } catch (IOException e) {
            Log.e(TAG, "Failed to send speed test result", e);
        }
    }

    @NonNull
    private String normalizeBaseUrl(@NonNull String url) {
        return url.endsWith("/") ? url : url + "/";
    }

    private double average(@NonNull List<Double> values) {
        double total = 0.0;
        for (double value : values) {
            total += value;
        }
        return total / Math.max(1, values.size());
    }

    // =====================================================================
    //  Inner classes
    // =====================================================================

    static class SpeedTestResult {
        final long   timestamp;
        final double downloadMbps;
        final double uploadMbps;
        final long   latencyMs;

        SpeedTestResult(long timestamp, double downloadMbps,
                        double uploadMbps, long latencyMs) {
            this.timestamp    = timestamp;
            this.downloadMbps = downloadMbps;
            this.uploadMbps   = uploadMbps;
            this.latencyMs    = latencyMs;
        }
    }

    static class SpeedTestHistoryAdapter
            extends RecyclerView.Adapter<SpeedTestHistoryAdapter.VH> {

        private final List<SpeedTestResult> items;
        private final SimpleDateFormat sdf =
                new SimpleDateFormat("MMM dd, HH:mm", Locale.US);

        SpeedTestHistoryAdapter(@NonNull List<SpeedTestResult> items) {
            this.items = items;
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_speed_test_history, parent, false);
            return new VH(view);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            SpeedTestResult r = items.get(position);
            holder.tvTimestamp.setText(sdf.format(new Date(r.timestamp)));
            holder.tvDownload.setText(
                    String.format(Locale.US, "DL: %.1f Mbps", r.downloadMbps));
            holder.tvUpload.setText(
                    String.format(Locale.US, "UL: %.1f Mbps", r.uploadMbps));
            holder.tvLatency.setText(
                    String.format(Locale.US, "Ping: %d ms", r.latencyMs));
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        static class VH extends RecyclerView.ViewHolder {
            final TextView tvTimestamp;
            final TextView tvDownload;
            final TextView tvUpload;
            final TextView tvLatency;

            VH(@NonNull View itemView) {
                super(itemView);
                tvTimestamp = itemView.findViewById(R.id.tvSpeedTimestamp);
                tvDownload = itemView.findViewById(R.id.tvSpeedDownload);
                tvUpload   = itemView.findViewById(R.id.tvSpeedUpload);
                tvLatency  = itemView.findViewById(R.id.tvSpeedLatency);
            }
        }
    }
}
