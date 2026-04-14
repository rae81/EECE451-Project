package com.networkanalyzer.app.fragments;

import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.networkanalyzer.app.R;
import com.networkanalyzer.app.database.AppDatabase;
import com.networkanalyzer.app.database.CellDataDao;
import com.networkanalyzer.app.database.CellDataEntity;
import com.networkanalyzer.app.databinding.FragmentHeatmapBinding;
import com.networkanalyzer.app.helpers.CellInfoHelper;
import com.networkanalyzer.app.network.ApiService;
import com.networkanalyzer.app.network.RetrofitClient;
import com.networkanalyzer.app.network.models.DeadzoneBatchPredictionResponse;
import com.networkanalyzer.app.network.models.DeadzonePredictionResponse;
import com.networkanalyzer.app.network.models.TowerClustersResponse;
import com.networkanalyzer.app.network.models.HeatmapResponse;
import com.networkanalyzer.app.utils.Constants;
import com.networkanalyzer.app.utils.NetworkInsightEngine;
import com.networkanalyzer.app.utils.PreferenceManager;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * Geospatial signal-coverage visualisation. Pulls aggregated buckets
 * from {@code /api/heatmap-data} and renders them through an embedded
 * {@link android.webkit.WebView} running a Leaflet + Leaflet.heat
 * template; the fragment also overlays dead-zone predictions fetched
 * from {@code /api/deadzone/predict/batch}.
 * <p>
 * Extra (non-required) feature. References:
 * <ul>
 *   <li>Leaflet — https://leafletjs.com/ (BSD-2-Clause)
 *   <li>Leaflet.heat — https://github.com/Leaflet/Leaflet.heat (BSD-2-Clause)
 *   <li>OpenStreetMap tile policy — https://operations.osmfoundation.org/policies/tiles/
 * </ul>
 * The server aggregation uses H3-style lat/lon bucketing; see
 * {@code _aggregate_heatmap_rows} in {@code server/app.py}.
 */
public class HeatmapFragment extends Fragment {

    private static final String TAG = "HeatmapFragment";
    private static final int SERVER_POINT_LIMIT = 500;
    private static final double LEBANON_MIN_LAT = 33.05;
    private static final double LEBANON_MIN_LNG = 35.09;
    private static final double LEBANON_MAX_LAT = 34.72;
    private static final double LEBANON_MAX_LNG = 36.68;
    private static final int PREDICTIVE_GRID_ROWS = 11;
    private static final int PREDICTIVE_GRID_COLS = 13;

    private enum MapMode {
        SIGNAL,
        TOWERS,
        OPERATOR,
        RELIABILITY
    }

    private FragmentHeatmapBinding binding;
    private CellDataDao cellDataDao;
    private PreferenceManager preferenceManager;
    private ApiService apiService;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    @Nullable private Call<DeadzonePredictionResponse> predictionCall;
    private boolean predictiveOverlayEnabled = false;
    private int predictiveOverlayVersion = 0;

    private String selectedNetworkType = null;
    private MapMode currentMode = MapMode.SIGNAL;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentHeatmapBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        cellDataDao = AppDatabase.getInstance(requireContext()).cellDataDao();
        preferenceManager = new PreferenceManager(requireContext());
        apiService = RetrofitClient.getInstance(requireContext()).getApiService();

        setupMapWebView();
        setupChipGroup();
        setupLegend();
        setupPredictiveOverlayToggle();
        showPredictionState(getString(R.string.heatmap_prediction_loading), null);
        loadHeatmapData();
    }

    @Override
    public void onDestroyView() {
        if (predictionCall != null) {
            predictionCall.cancel();
            predictionCall = null;
        }
        if (binding != null) {
            binding.webMap.destroy();
        }
        super.onDestroyView();
        binding = null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }

    private void setupMapWebView() {
        WebSettings settings = binding.webMap.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        binding.webMap.setWebChromeClient(new WebChromeClient());
        binding.webMap.setBackgroundColor(Color.parseColor("#F8FAFC"));
    }

    private void setupChipGroup() {
        binding.chipSignalMode.setOnClickListener(v -> {
            currentMode = MapMode.SIGNAL;
            updatePredictiveOverlayUi(null);
            loadHeatmapData();
        });
        binding.chipTowerMode.setOnClickListener(v -> {
            currentMode = MapMode.TOWERS;
            updatePredictiveOverlayUi(null);
            loadHeatmapData();
        });
        binding.chipOperatorMode.setOnClickListener(v -> {
            currentMode = MapMode.OPERATOR;
            updatePredictiveOverlayUi(null);
            loadHeatmapData();
        });
        binding.chipReliabilityMode.setOnClickListener(v -> {
            currentMode = MapMode.RELIABILITY;
            updatePredictiveOverlayUi(null);
            loadHeatmapData();
        });

        binding.chipAll.setOnClickListener(v -> {
            selectedNetworkType = null;
            loadHeatmapData();
        });
        binding.chip2g.setOnClickListener(v -> {
            selectedNetworkType = Constants.NETWORK_2G;
            loadHeatmapData();
        });
        binding.chip3g.setOnClickListener(v -> {
            selectedNetworkType = Constants.NETWORK_3G;
            loadHeatmapData();
        });
        binding.chip4g.setOnClickListener(v -> {
            selectedNetworkType = Constants.NETWORK_4G;
            loadHeatmapData();
        });
        binding.chip5g.setOnClickListener(v -> {
            selectedNetworkType = Constants.NETWORK_5G;
            loadHeatmapData();
        });
    }

    private void setupPredictiveOverlayToggle() {
        predictiveOverlayEnabled = binding.switchPredictiveOverlay.isChecked();
        binding.switchPredictiveOverlay.setOnCheckedChangeListener((buttonView, isChecked) -> {
            predictiveOverlayEnabled = isChecked;
            updatePredictiveOverlayUi(null);
            loadHeatmapData();
        });
        updatePredictiveOverlayUi(null);
    }

    private void setupLegend() {
        updateLegendText();
    }

    private void updateLegendText() {
        if (binding == null) {
            return;
        }
        switch (currentMode) {
            case TOWERS:
                binding.tvLegendStrong.setText("Dense");
                binding.tvLegendStrong.setTextColor(Color.rgb(15, 118, 110));
                binding.tvLegendWeak.setText("Sparse");
                binding.tvLegendWeak.setTextColor(Color.rgb(148, 163, 184));
                break;
            case OPERATOR:
                binding.tvLegendStrong.setText("Dominant");
                binding.tvLegendStrong.setTextColor(Color.rgb(5, 150, 105));
                binding.tvLegendWeak.setText("Mixed");
                binding.tvLegendWeak.setTextColor(Color.rgb(124, 58, 237));
                break;
            case RELIABILITY:
                binding.tvLegendStrong.setText("Low risk");
                binding.tvLegendStrong.setTextColor(Color.rgb(5, 150, 105));
                binding.tvLegendWeak.setText("Dead zone");
                binding.tvLegendWeak.setTextColor(Color.rgb(220, 38, 38));
                break;
            default:
                binding.tvLegendStrong.setText("Strong");
                binding.tvLegendStrong.setTextColor(Color.rgb(5, 150, 105));
                binding.tvLegendWeak.setText("Weak");
                binding.tvLegendWeak.setTextColor(Color.rgb(220, 38, 38));
                break;
        }
    }

    private void loadHeatmapData() {
        if (binding == null) {
            return;
        }
        updateLegendText();
        updatePredictiveOverlayUi(null);
        loadCurrentPrediction();
        binding.progressBar.setVisibility(View.VISIBLE);
        binding.tvEmptyHeatmap.setVisibility(View.GONE);

        if (currentMode == MapMode.TOWERS) {
            loadTowerClusterData();
            return;
        }

        apiService.getHeatmapData(
                preferenceManager.getDeviceId(),
                selectedNetworkType,
                SERVER_POINT_LIMIT
        ).enqueue(new Callback<HeatmapResponse>() {
            @Override
            public void onResponse(@NonNull Call<HeatmapResponse> call,
                                   @NonNull Response<HeatmapResponse> response) {
                if (binding == null) {
                    return;
                }
                if (response.isSuccessful()
                        && response.body() != null
                        && response.body().getPoints() != null
                        && !response.body().getPoints().isEmpty()) {
                    renderMap(mapServerPoints(response.body().getPoints()));
                    return;
                }
                loadLocalHeatmapData();
            }

            @Override
            public void onFailure(@NonNull Call<HeatmapResponse> call, @NonNull Throwable t) {
                Log.w(TAG, "Server heatmap unavailable, using local data", t);
                loadLocalHeatmapData();
            }
        });
    }

    private void loadCurrentPrediction() {
        showPredictionState(getString(R.string.heatmap_prediction_loading), null);
        executor.execute(() -> {
            List<CellDataEntity> latestReadings = cellDataDao.getLatest(20);
            CellDataEntity candidate = null;
            for (CellDataEntity reading : latestReadings) {
                if (reading.getLatitude() == 0.0 || reading.getLongitude() == 0.0) {
                    continue;
                }
                if (reading.getOperator() == null || reading.getOperator().trim().isEmpty()) {
                    continue;
                }
                if (reading.getNetworkType() == null || reading.getNetworkType().trim().isEmpty()) {
                    continue;
                }
                candidate = reading;
                break;
            }

            if (candidate == null) {
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() ->
                            showPredictionState(getString(R.string.heatmap_prediction_waiting), null));
                }
                return;
            }

            if (predictionCall != null) {
                predictionCall.cancel();
            }
            predictionCall = apiService.getDeadzonePrediction(
                    candidate.getLatitude(),
                    candidate.getLongitude(),
                    candidate.getOperator().trim(),
                    candidate.getNetworkType().trim()
            );
            predictionCall.enqueue(new Callback<DeadzonePredictionResponse>() {
                @Override
                public void onResponse(@NonNull Call<DeadzonePredictionResponse> call,
                                       @NonNull Response<DeadzonePredictionResponse> response) {
                    if (binding == null) {
                        return;
                    }
                    if (response.isSuccessful() && response.body() != null) {
                        showPredictionState(
                                formatPredictionSummary(response.body()),
                                formatPredictionDetail(response.body())
                        );
                        return;
                    }
                    showPredictionState(getString(R.string.heatmap_prediction_error), null);
                }

                @Override
                public void onFailure(@NonNull Call<DeadzonePredictionResponse> call, @NonNull Throwable t) {
                    if (call.isCanceled() || binding == null) {
                        return;
                    }
                    Log.w(TAG, "Current-area prediction unavailable", t);
                    showPredictionState(getString(R.string.heatmap_prediction_error), null);
                }
            });
        });
    }

    private void loadTowerClusterData() {
        apiService.getTowerClusters(
                preferenceManager.getDeviceId(),
                selectedNetworkType,
                SERVER_POINT_LIMIT
        ).enqueue(new Callback<TowerClustersResponse>() {
            @Override
            public void onResponse(@NonNull Call<TowerClustersResponse> call,
                                   @NonNull Response<TowerClustersResponse> response) {
                if (binding == null) {
                    return;
                }
                if (response.isSuccessful()
                        && response.body() != null
                        && response.body().getClusters() != null
                        && !response.body().getClusters().isEmpty()) {
                    renderMap(mapTowerClusters(response.body().getClusters()));
                    return;
                }
                loadLocalHeatmapData();
            }

            @Override
            public void onFailure(@NonNull Call<TowerClustersResponse> call, @NonNull Throwable t) {
                loadLocalHeatmapData();
            }
        });
    }

    private void loadLocalHeatmapData() {
        executor.execute(() -> {
            List<CellDataEntity> entities = selectedNetworkType != null
                    ? cellDataDao.getWithLocationByType(selectedNetworkType)
                    : cellDataDao.getWithLocation();

            List<MapPoint> points = currentMode == MapMode.TOWERS
                    ? buildTowerPoints(entities)
                    : buildReadingPoints(entities);

            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> renderMap(points));
            }
        });
    }

    @NonNull
    private List<MapPoint> mapServerPoints(@NonNull List<HeatmapResponse.HeatmapPoint> serverPoints) {
        List<MapPoint> points = new ArrayList<>();
        for (HeatmapResponse.HeatmapPoint point : serverPoints) {
            String operator = joinOrFallback(point.getOperators(), "Server aggregate");
            String networkType = joinOrFallback(point.getNetworkTypes(), Constants.NETWORK_UNKNOWN);
            int signalPower = (int) Math.round(point.getAvgSignalPower());
            Double deadzoneRisk = point.getDeadzoneRisk();
            int reliabilityScore = deadzoneRisk != null
                    ? Math.max(0, 100 - (int) Math.round(deadzoneRisk * 100.0))
                    : reliabilityFromServerPoint(signalPower, point.getSampleCount(), point.getAvgSnr());
            String descriptor;
            String cellId;
            String channels;
            String carrierSummary;
            String deadzoneSummary = buildDeadzoneSummary(point);
            if (currentMode == MapMode.TOWERS) {
                descriptor = "Inferred tower area";
                cellId = "Coverage cluster";
                channels = "Server aggregate";
                carrierSummary = "Carriers: " + operator;
            } else {
                descriptor = currentMode == MapMode.RELIABILITY
                        ? (deadzoneRisk != null ? deadzoneLabel(deadzoneRisk) : NetworkInsightEngine.labelForScore(reliabilityScore))
                        : operator;
                cellId = "Aggregated";
                channels = point.getAvgSnr() != null
                        ? String.format(Locale.US, "Avg SNR %.1f dB", point.getAvgSnr())
                        : "Avg SNR --";
                carrierSummary = "Carriers: " + operator;
            }

            points.add(new MapPoint(
                    point.getLatitude(),
                    point.getLongitude(),
                    signalPower,
                    networkType,
                    operator,
                    simplifyTimestamp(point.getLatestTimestamp()),
                    cellId,
                    CellInfoHelper.getSignalQuality(signalPower),
                    point.getSampleCount(),
                    descriptor,
                    carrierSummary,
                    channels,
                    reliabilityScore,
                    deadzoneRisk,
                    point.getPredictionConfidence(),
                    deadzoneSummary
            ));
        }
        return points;
    }

    @NonNull
    private List<MapPoint> mapTowerClusters(@NonNull List<TowerClustersResponse.TowerCluster> clusters) {
        List<MapPoint> points = new ArrayList<>();
        int offsetIndex = 0;
        for (TowerClustersResponse.TowerCluster cluster : clusters) {
            MapPoint point = new MapPoint(
                    cluster.getLatitude(),
                    cluster.getLongitude(),
                    (int) Math.round(cluster.getAvgSignalPower()),
                    fallback(cluster.getNetworkType(), Constants.NETWORK_UNKNOWN),
                    fallback(cluster.getOperator(), "Unknown"),
                    simplifyTimestamp(cluster.getLastSeen()),
                    fallback(cluster.getCellId(), "Unknown"),
                    CellInfoHelper.getSignalQuality((int) Math.round(cluster.getAvgSignalPower())),
                    cluster.getSampleCount(),
                    "Inferred tower cluster",
                    "Carrier " + fallback(cluster.getOperator(), "Unknown"),
                    cluster.getChannels() != null && !cluster.getChannels().isEmpty()
                            ? "Channels " + android.text.TextUtils.join(", ", cluster.getChannels())
                            : "Channels --",
                    reliabilityFromServerPoint((int) Math.round(cluster.getAvgSignalPower()), cluster.getSampleCount(), cluster.getAvgSnr()),
                    null,
                    null,
                    "Model unavailable for tower clusters"
            );
            points.add(offsetTowerPoint(point, offsetIndex++));
        }
        return points;
    }

    @NonNull
    private List<MapPoint> buildReadingPoints(@NonNull List<CellDataEntity> entities) {
        LinkedHashMap<String, AggregatedPoint> groupedPoints = new LinkedHashMap<>();
        for (CellDataEntity entity : entities) {
            double lat = entity.getLatitude();
            double lng = entity.getLongitude();
            if (lat == 0.0 && lng == 0.0) {
                continue;
            }
            String key = String.format(Locale.US, "%.5f,%.5f", lat, lng);
            AggregatedPoint point = groupedPoints.get(key);
            if (point == null) {
                point = new AggregatedPoint(
                        lat,
                        lng,
                        fallback(entity.getNetworkType(), Constants.NETWORK_UNKNOWN),
                        fallback(entity.getOperator(), "Unknown"));
                groupedPoints.put(key, point);
            }
            point.addReading(entity);
        }

        List<MapPoint> points = new ArrayList<>();
        for (AggregatedPoint point : groupedPoints.values()) {
            points.add(point.toReadingPoint());
        }
        return points;
    }

    @NonNull
    private List<MapPoint> buildTowerPoints(@NonNull List<CellDataEntity> entities) {
        LinkedHashMap<String, TowerAggregate> towers = new LinkedHashMap<>();
        for (CellDataEntity entity : entities) {
            double lat = entity.getLatitude();
            double lng = entity.getLongitude();
            if (lat == 0.0 && lng == 0.0) {
                continue;
            }
            String cellId = fallback(entity.getCellId(), "--");
            String networkType = fallback(entity.getNetworkType(), Constants.NETWORK_UNKNOWN);
            String key = networkType + "|" + cellId;
            TowerAggregate tower = towers.get(key);
            if (tower == null) {
                tower = new TowerAggregate(cellId, networkType, fallback(entity.getOperator(), "Unknown"));
                towers.put(key, tower);
            }
            tower.add(entity);
        }

        List<MapPoint> points = new ArrayList<>();
        LinkedHashMap<String, Integer> coordinateUse = new LinkedHashMap<>();
        for (TowerAggregate tower : towers.values()) {
            MapPoint point = tower.toMapPoint();
            String coordKey = String.format(Locale.US, "%.5f,%.5f", point.latitude, point.longitude);
            int offsetIndex = coordinateUse.getOrDefault(coordKey, 0);
            coordinateUse.put(coordKey, offsetIndex + 1);
            points.add(offsetTowerPoint(point, offsetIndex));
        }
        return points;
    }

    private void renderMap(@NonNull List<MapPoint> points) {
        int requestVersion = ++predictiveOverlayVersion;
        if (currentMode == MapMode.RELIABILITY && predictiveOverlayEnabled) {
            applyMapRender(points, null);
            requestPredictiveOverlay(new ArrayList<>(points), requestVersion);
            return;
        }
        applyMapRender(points, null);
    }

    private void requestPredictiveOverlay(@NonNull List<MapPoint> basePoints, int requestVersion) {
        executor.execute(() -> {
            PredictionCandidate candidate = findPredictionCandidate();
            if (getActivity() == null || binding == null || requestVersion != predictiveOverlayVersion) {
                return;
            }
            if (candidate == null) {
                requireActivity().runOnUiThread(() -> {
                    if (binding == null || requestVersion != predictiveOverlayVersion) {
                        return;
                    }
                    updatePredictiveOverlayUi(getString(R.string.heatmap_predictive_overlay_unavailable_hint));
                });
                return;
            }

            String networkLabel = normalizeNetworkType(candidate.networkType);
            requireActivity().runOnUiThread(() -> {
                if (binding == null || requestVersion != predictiveOverlayVersion) {
                    return;
                }
                updatePredictiveOverlayUi(
                        getString(
                                R.string.heatmap_predictive_overlay_loading_hint,
                                candidate.operator,
                                networkLabel
                        )
                );
            });

            JsonObject requestBody = buildPredictiveOverlayRequest(candidate);
            try {
                Response<DeadzoneBatchPredictionResponse> response =
                        apiService.getDeadzoneBatchPrediction(requestBody).execute();
                if (requestVersion != predictiveOverlayVersion) {
                    return;
                }
                if (!response.isSuccessful() || response.body() == null || response.body().getPredictions() == null) {
                    postPredictiveOverlayFailure(requestVersion);
                    return;
                }
                List<PredictionCell> overlayCells = buildPredictiveOverlayCells(response.body().getPredictions());
                if (overlayCells.isEmpty()) {
                    postPredictiveOverlayFailure(requestVersion);
                    return;
                }
                requireActivity().runOnUiThread(() -> {
                    if (binding == null || requestVersion != predictiveOverlayVersion) {
                        return;
                    }
                    updatePredictiveOverlayUi(
                            getString(
                                    R.string.heatmap_predictive_overlay_active_hint,
                                    candidate.operator,
                                    networkLabel
                            )
                    );
                    applyMapRender(basePoints, overlayCells);
                });
            } catch (IOException error) {
                Log.w(TAG, "Lebanon predictive overlay unavailable", error);
                postPredictiveOverlayFailure(requestVersion);
            }
        });
    }

    private void postPredictiveOverlayFailure(int requestVersion) {
        if (getActivity() == null) {
            return;
        }
        requireActivity().runOnUiThread(() -> {
            if (binding == null || requestVersion != predictiveOverlayVersion) {
                return;
            }
            updatePredictiveOverlayUi(getString(R.string.heatmap_predictive_overlay_failed_hint));
        });
    }

    @Nullable
    private PredictionCandidate findPredictionCandidate() {
        List<CellDataEntity> latestReadings = cellDataDao.getLatest(80);
        for (CellDataEntity reading : latestReadings) {
            if (reading.getLatitude() == 0.0 || reading.getLongitude() == 0.0) {
                continue;
            }
            String operator = reading.getOperator();
            String networkType = reading.getNetworkType();
            if (operator == null || operator.trim().isEmpty()) {
                continue;
            }
            if (networkType == null || networkType.trim().isEmpty()) {
                continue;
            }
            if (selectedNetworkType != null && !selectedNetworkType.equalsIgnoreCase(networkType.trim())) {
                continue;
            }
            return new PredictionCandidate(operator.trim(), selectedNetworkType != null
                    ? selectedNetworkType
                    : networkType.trim());
        }
        return null;
    }

    @NonNull
    private JsonObject buildPredictiveOverlayRequest(@NonNull PredictionCandidate candidate) {
        JsonObject request = new JsonObject();
        JsonArray points = new JsonArray();
        double cellHeight = (LEBANON_MAX_LAT - LEBANON_MIN_LAT) / PREDICTIVE_GRID_ROWS;
        double cellWidth = (LEBANON_MAX_LNG - LEBANON_MIN_LNG) / PREDICTIVE_GRID_COLS;

        for (int row = 0; row < PREDICTIVE_GRID_ROWS; row++) {
            for (int col = 0; col < PREDICTIVE_GRID_COLS; col++) {
                JsonObject point = new JsonObject();
                point.addProperty("latitude", LEBANON_MIN_LAT + (row + 0.5) * cellHeight);
                point.addProperty("longitude", LEBANON_MIN_LNG + (col + 0.5) * cellWidth);
                point.addProperty("operator", candidate.operator);
                point.addProperty("network_type", candidate.networkType);
                points.add(point);
            }
        }

        request.add("points", points);
        return request;
    }

    @NonNull
    private List<PredictionCell> buildPredictiveOverlayCells(
            @NonNull List<DeadzoneBatchPredictionResponse.PredictionPoint> predictions
    ) {
        List<PredictionCell> cells = new ArrayList<>();
        double cellHeight = (LEBANON_MAX_LAT - LEBANON_MIN_LAT) / PREDICTIVE_GRID_ROWS;
        double cellWidth = (LEBANON_MAX_LNG - LEBANON_MIN_LNG) / PREDICTIVE_GRID_COLS;

        for (DeadzoneBatchPredictionResponse.PredictionPoint prediction : predictions) {
            if (prediction.getError() != null
                    || prediction.getLatitude() == null
                    || prediction.getLongitude() == null
                    || prediction.getDeadzoneRisk() == null) {
                continue;
            }
            double lat = prediction.getLatitude();
            double lng = prediction.getLongitude();
            cells.add(new PredictionCell(
                    Math.max(LEBANON_MIN_LAT, lat - cellHeight / 2.0),
                    Math.min(LEBANON_MAX_LAT, lat + cellHeight / 2.0),
                    Math.max(LEBANON_MIN_LNG, lng - cellWidth / 2.0),
                    Math.min(LEBANON_MAX_LNG, lng + cellWidth / 2.0),
                    prediction.getDeadzoneRisk(),
                    prediction.getConfidence()
            ));
        }
        return cells;
    }

    private void applyMapRender(@NonNull List<MapPoint> points, @Nullable List<PredictionCell> overlayCells) {
        if (binding == null) {
            return;
        }
        binding.progressBar.setVisibility(View.GONE);
        boolean hasOverlay = overlayCells != null && !overlayCells.isEmpty();
        if (points.isEmpty() && !hasOverlay) {
            binding.tvEmptyHeatmap.setText(R.string.heatmap_no_data);
            binding.tvEmptyHeatmap.setVisibility(View.VISIBLE);
            binding.webMap.loadData("<html><body style='background:#F8FAFC;'></body></html>",
                    "text/html", "UTF-8");
            updateSummary(points);
            return;
        }
        binding.tvEmptyHeatmap.setVisibility(View.GONE);
        updateSummary(points);
        binding.webMap.loadDataWithBaseURL(
                "https://appassets.androidplatform.net/",
                buildLeafletHtml(points, overlayCells),
                "text/html",
                "UTF-8",
                null
        );
    }

    private void updateSummary(@NonNull List<MapPoint> points) {
        if (binding == null) {
            return;
        }
        LinkedHashSet<String> towerIds = new LinkedHashSet<>();
        LinkedHashSet<String> operators = new LinkedHashSet<>();
        int confidenceSum = 0;
        for (MapPoint point : points) {
            towerIds.add(point.cellId + "|" + point.networkType);
            operators.add(point.operator);
            confidenceSum += point.reliabilityScore;
        }
        int avgConfidence = points.isEmpty() ? 0 : Math.round(confidenceSum / (float) points.size());
        binding.tvSummaryTowers.setText(String.valueOf(towerIds.size()));
        binding.tvSummaryOperators.setText(String.valueOf(operators.size()));
        binding.tvSummaryConfidence.setText(String.format(Locale.US, "%d/100", avgConfidence));
    }

    private String buildLeafletHtml(@NonNull List<MapPoint> points, @Nullable List<PredictionCell> overlayCells) {
        double centerLat = !points.isEmpty() ? points.get(points.size() - 1).latitude : 33.89;
        double centerLng = !points.isEmpty() ? points.get(points.size() - 1).longitude : 35.50;
        int zoom = !points.isEmpty() ? 15 : 8;
        StringBuilder markers = new StringBuilder();

        for (MapPoint point : points) {
            markers.append(buildMarkerJs(point));
        }

        StringBuilder overlayJs = new StringBuilder();
        StringBuilder deadzoneGridJs = new StringBuilder();
        String fitBoundsJs = "";
        if (overlayCells != null && !overlayCells.isEmpty()) {
            overlayJs.append(buildPredictiveOverlayJs(overlayCells));
            centerLat = (LEBANON_MIN_LAT + LEBANON_MAX_LAT) / 2.0;
            centerLng = (LEBANON_MIN_LNG + LEBANON_MAX_LNG) / 2.0;
            zoom = 8;
            fitBoundsJs = String.format(
                    Locale.US,
                    "map.fitBounds([[%f,%f],[%f,%f]],{padding:[18,18]});",
                    LEBANON_MIN_LAT, LEBANON_MIN_LNG, LEBANON_MAX_LAT, LEBANON_MAX_LNG
            );
        } else if (currentMode == MapMode.RELIABILITY) {
            deadzoneGridJs.append(buildDeadzoneGridJs(points));
        }

        return "<!doctype html><html><head>"
                + "<meta name='viewport' content='width=device-width, initial-scale=1.0'>"
                + "<link rel='stylesheet' href='https://unpkg.com/leaflet@1.9.4/dist/leaflet.css'/>"
                + "<style>"
                + "*{box-sizing:border-box;}"
                + "html,body,#map{height:100%;margin:0;}"
                + "body{background:#ECF2F7;}"
                + ".leaflet-container{font-family:'Inter',-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;"
                + "background:#EAF1F6;border-radius:0;}"
                + ".leaflet-control-container .leaflet-top,.leaflet-control-container .leaflet-bottom{z-index:700;}"
                + ".leaflet-popup-content-wrapper{border-radius:18px;background:rgba(255,255,255,0.96);"
                + "box-shadow:0 18px 42px rgba(15,23,42,0.16);border:1px solid rgba(226,232,240,0.96);"
                + "font-family:'Inter',-apple-system,sans-serif;padding:0;}"
                + ".leaflet-popup-content{margin:0;}"
                + ".leaflet-popup-tip{border-top-color:rgba(255,255,255,0.96);}"
                + ".leaflet-control-zoom a{border-radius:12px!important;border:1px solid rgba(226,232,240,0.9)!important;"
                + "background:rgba(255,255,255,0.92)!important;color:#0F172A!important;font-weight:700;"
                + "box-shadow:0 10px 24px rgba(15,23,42,0.12)!important;}"
                + ".leaflet-control-zoom{border:none!important;border-radius:14px!important;"
                + "overflow:hidden;box-shadow:none!important;}"
                + ".popup-content{min-width:220px;font-size:12px;line-height:1.45;color:#0F172A;padding:14px;}"
                + ".popup-header{display:flex;align-items:center;gap:6px;margin-bottom:8px;flex-wrap:wrap;}"
                + ".popup-badge{color:#fff;padding:4px 9px;border-radius:999px;font-size:10px;font-weight:700;"
                + "letter-spacing:0.04em;text-transform:uppercase;box-shadow:0 8px 18px rgba(15,23,42,0.12);}"
                + ".popup-count{color:#64748B;font-size:10px;letter-spacing:0.04em;text-transform:uppercase;}"
                + ".popup-operator{font-size:15px;font-weight:700;margin-bottom:8px;color:#0F172A;}"
                + ".popup-table{width:100%;border-collapse:collapse;}"
                + ".popup-table td{padding:5px 0;font-size:12px;border-bottom:1px solid rgba(241,245,249,0.9);}"
                + ".popup-table tr:last-child td{border-bottom:none;}"
                + ".popup-table td:first-child{color:#64748B;padding-right:12px;white-space:nowrap;font-weight:600;}"
                + ".popup-table td:last-child{font-weight:700;color:#0F172A;text-align:right;}"
                + ".tower-marker{width:28px;height:28px;display:flex;align-items:center;justify-content:center;"
                + "background:#0F766E;border:2px solid #fff;border-radius:999px;"
                + "box-shadow:0 2px 8px rgba(15,23,42,0.18);}"
                + ".tower-marker svg{width:15px;height:15px;display:block;}"
                + ".deadzone-overlay,.predictive-overlay,.predictive-glow,.predictive-core,.signal-halo,.signal-core{pointer-events:none;}"
                + ".predictive-glow,.predictive-core,.signal-halo,.signal-core{transition:fill-opacity 240ms ease,stroke-opacity 240ms ease;}"
                + ".predictive-glow,.signal-halo{mix-blend-mode:multiply;}"
                + "</style>"
                + "</head><body><div id='map'></div>"
                + "<script src='https://unpkg.com/leaflet@1.9.4/dist/leaflet.js'></script>"
                + "<script>"
                + "var map=L.map('map',{zoomControl:true}).setView(["
                + centerLat + "," + centerLng + "]," + zoom + ");"
                + "map.createPane('predictivePane');"
                + "map.getPane('predictivePane').style.zIndex=280;"
                + "map.getPane('predictivePane').style.pointerEvents='none';"
                + "map.createPane('localHaloPane');"
                + "map.getPane('localHaloPane').style.zIndex=290;"
                + "map.getPane('localHaloPane').style.pointerEvents='none';"
                + "L.tileLayer('https://{s}.basemaps.cartocdn.com/light_all/{z}/{x}/{y}{r}.png',"
                + "{maxZoom:19,attribution:'&copy; OSM &copy; CARTO',subdomains:'abcd'}).addTo(map);"
                + overlayJs
                + markers
                + deadzoneGridJs
                + fitBoundsJs
                + "</script></body></html>";
    }

    @NonNull
    private String buildPredictiveOverlayJs(@NonNull List<PredictionCell> cells) {
        StringBuilder js = new StringBuilder();
        js.append("var predictiveLayer=L.layerGroup().addTo(map);");
        for (PredictionCell cell : cells) {
            String color = predictiveColor(cell.deadzoneRisk);
            double conf = cell.confidence == null ? 0.52 : cell.confidence;
            double glowOpacity = (cell.deadzoneRisk >= 0.7 ? 0.13 : cell.deadzoneRisk >= 0.45 ? 0.10 : 0.07)
                    * (0.55 + 0.45 * conf);
            double coreOpacity = (cell.deadzoneRisk >= 0.7 ? 0.20 : cell.deadzoneRisk >= 0.45 ? 0.14 : 0.10)
                    * (0.55 + 0.45 * conf);
            double centerLat = (cell.southLat + cell.northLat) / 2.0;
            double centerLng = (cell.westLng + cell.eastLng) / 2.0;
            double radiusM = 3200.0 + (cell.deadzoneRisk * 1900.0) + ((conf - 0.5) * 700.0);
            double glowRadiusM = radiusM * 1.35;
            js.append(String.format(
                    Locale.US,
                    "L.circle([%f,%f],{pane:'predictivePane',radius:%.0f,color:'%s',weight:0,fillColor:'%s',fillOpacity:%.3f,className:'deadzone-overlay predictive-overlay predictive-glow'}).addTo(predictiveLayer);",
                    centerLat, centerLng, glowRadiusM, color, color, glowOpacity
            ));
            js.append(String.format(
                    Locale.US,
                    "L.circle([%f,%f],{pane:'predictivePane',radius:%.0f,color:'%s',weight:1,opacity:%.3f,fillColor:'%s',fillOpacity:%.3f,className:'deadzone-overlay predictive-overlay predictive-core'}).addTo(predictiveLayer);",
                    centerLat, centerLng, radiusM, color, Math.min(0.34, glowOpacity + 0.08), color, coreOpacity
            ));
        }
        return js.toString();
    }

    @NonNull
    private String buildDeadzoneGridJs(@NonNull List<MapPoint> points) {
        if (points.isEmpty()) return "";

        StringBuilder js = new StringBuilder();
        js.append("var dzLayer=L.layerGroup().addTo(map);");

        for (MapPoint p : points) {
            String color;
            double haloOpacity;
            double coreOpacity;
            double radius;
            if (p.deadzoneRisk != null) {
                if (p.deadzoneRisk >= 0.7) {
                    color = "#DC2626";
                    haloOpacity = 0.18;
                    coreOpacity = 0.12;
                    radius = 220;
                } else if (p.deadzoneRisk >= 0.45) {
                    color = "#F97316";
                    haloOpacity = 0.14;
                    coreOpacity = 0.10;
                    radius = 176;
                } else {
                    color = "#059669";
                    haloOpacity = 0.10;
                    coreOpacity = 0.07;
                    radius = 138;
                }
            } else {
                if (p.reliabilityScore < 40) {
                    color = "#DC2626";
                    haloOpacity = 0.17;
                    coreOpacity = 0.11;
                    radius = 210;
                } else if (p.reliabilityScore < 60) {
                    color = "#F97316";
                    haloOpacity = 0.13;
                    coreOpacity = 0.09;
                    radius = 170;
                } else if (p.reliabilityScore < 75) {
                    color = "#EAB308";
                    haloOpacity = 0.10;
                    coreOpacity = 0.07;
                    radius = 144;
                } else {
                    color = "#059669";
                    haloOpacity = 0.07;
                    coreOpacity = 0.05;
                    radius = 120;
                }
            }

            js.append(String.format(Locale.US,
                    "L.circle([%f,%f],{pane:'localHaloPane',radius:%d,color:'%s',weight:0,fillColor:'%s',fillOpacity:%.3f,className:'deadzone-overlay signal-halo'}).addTo(dzLayer);",
                    p.latitude, p.longitude, (int) Math.round(radius * 1.18),
                    color, color, haloOpacity));
            js.append(String.format(Locale.US,
                    "L.circle([%f,%f],{pane:'localHaloPane',radius:%d,color:'%s',weight:0,fillColor:'%s',fillOpacity:%.3f,className:'deadzone-overlay signal-core'}).addTo(dzLayer);",
                    p.latitude, p.longitude, (int) Math.round(radius * 0.68),
                    color, color, coreOpacity));
        }

        return js.toString();
    }

    @NonNull
    private String buildMarkerJs(@NonNull MapPoint point) {
        String fillColor = colorForPoint(point);
        String borderColor = borderColorForPoint(point);
        String popup = buildPopupHtml(point, fillColor);
        if (currentMode == MapMode.TOWERS) {
            return "L.marker(["
                    + point.latitude + "," + point.longitude + "],{icon:L.divIcon({className:'',html:'"
                    + buildTowerIconHtml(fillColor)
                    + "',iconSize:[26,26],iconAnchor:[13,13],popupAnchor:[0,-12]})})"
                    + ".addTo(map).bindPopup('" + popup + "');";
        }
        int radius = radiusForPoint(point);
        return "L.circleMarker(["
                + point.latitude + "," + point.longitude + "],{"
                + "radius:" + radius + ","
                + "color:'" + borderColor + "',"
                + "fillColor:'" + fillColor + "',"
                + "fillOpacity:0.72,weight:2,opacity:0.95"
                + "}).addTo(map).bindPopup('" + popup + "');";
    }

    @NonNull
    private MapPoint offsetTowerPoint(@NonNull MapPoint point, int offsetIndex) {
        if (offsetIndex == 0) {
            return point;
        }
        double angle = Math.toRadians(offsetIndex * 45.0);
        double distance = 0.00010 * ((offsetIndex + 1) / 2.0);
        double lat = point.latitude + Math.sin(angle) * distance;
        double lng = point.longitude + Math.cos(angle) * distance;
        return new MapPoint(
                lat,
                lng,
                point.signalPower,
                point.networkType,
                point.operator,
                point.displayTimestamp,
                point.cellId,
                point.signalQuality,
                point.readingCount,
                point.cellIdsSummary,
                point.plmnSummary,
                point.channelsSummary,
                point.reliabilityScore,
                point.deadzoneRisk,
                point.predictionConfidence,
                point.deadzoneSummary
        );
    }

    @NonNull
    private String buildPopupHtml(@NonNull MapPoint point, @NonNull String fillColor) {
        return "<div class=&quot;popup-content&quot;>"
                + "<div class=&quot;popup-header&quot;>"
                + "<span class=&quot;popup-badge&quot; style=&quot;background:" + fillColor + "&quot;>"
                + escapeJs(point.signalQuality) + "</span>"
                + "<span class=&quot;popup-badge&quot; style=&quot;background:#334155&quot;>"
                + escapeJs(point.networkType) + "</span>"
                + "<span class=&quot;popup-count&quot;>" + point.readingCount + " samples</span>"
                + "</div>"
                + "<div class=&quot;popup-operator&quot;>" + escapeJs(point.operator) + "</div>"
                + "<table class=&quot;popup-table&quot;>"
                + "<tr><td>Signal</td><td>" + point.signalPower + " dBm</td></tr>"
                + "<tr><td>Reliability</td><td>" + point.reliabilityScore + "/100</td></tr>"
                + "<tr><td>DZ Risk</td><td>" + escapeJs(point.deadzoneDisplay) + "</td></tr>"
                + "<tr><td>Tower</td><td>" + escapeJs(point.cellId) + "</td></tr>"
                + "<tr><td>Time</td><td>" + escapeJs(point.displayTimestamp) + "</td></tr>"
                + "</table></div>";
    }

    @NonNull
    private String buildTowerIconHtml(@NonNull String fillColor) {
        String svg = "<div class=&quot;tower-marker&quot; style=&quot;background:" + fillColor + "&quot;>"
                + "<svg viewBox=&quot;0 0 24 24&quot; fill=&quot;none&quot; xmlns=&quot;http://www.w3.org/2000/svg&quot;>"
                + "<path d=&quot;M12 3L8.5 21H10.9L12 15.6L13.1 21H15.5L12 3Z&quot; fill=&quot;white&quot;/>"
                + "<path d=&quot;M7 9L12 12L17 9&quot; stroke=&quot;white&quot; stroke-width=&quot;1.7&quot; stroke-linecap=&quot;round&quot;/>"
                + "<path d=&quot;M5.5 6.5L12 10.2L18.5 6.5&quot; stroke=&quot;white&quot; stroke-width=&quot;1.5&quot; stroke-linecap=&quot;round&quot;/>"
                + "<circle cx=&quot;12&quot; cy=&quot;5&quot; r=&quot;1.4&quot; fill=&quot;white&quot;/>"
                + "</svg></div>";
        return escapeJs(svg);
    }

    private int reliabilityFromServerPoint(int signalPower, int sampleCount, @Nullable Double avgSnr) {
        int base = NetworkInsightEngine.scoreSignal(signalPower);
        int densityBoost = Math.min(14, sampleCount * 3);
        int snrBoost = avgSnr == null ? 0 : (int) Math.round(Math.max(-5, Math.min(12, avgSnr)));
        return NetworkInsightEngine.clamp(base + densityBoost + snrBoost);
    }

    private String colorForPoint(@NonNull MapPoint point) {
        switch (currentMode) {
            case TOWERS:
                return point.readingCount >= 8 ? "#0F766E" : point.readingCount >= 4 ? "#14B8A6" : "#99F6E4";
            case OPERATOR:
                return operatorColor(point.operator);
            case RELIABILITY:
                return reliabilityColor(point.reliabilityScore);
            default:
                return signalColor(point.signalPower);
        }
    }

    private String borderColorForPoint(@NonNull MapPoint point) {
        switch (currentMode) {
            case RELIABILITY:
                return point.reliabilityScore >= 70 ? "#059669" : "#DC2626";
            case OPERATOR:
                return "#334155";
            case TOWERS:
                return "#0D5D57";
            default:
                return signalBorder(point.signalPower);
        }
    }

    private int radiusForPoint(@NonNull MapPoint point) {
        if (currentMode == MapMode.TOWERS) {
            return Math.min(14, 5 + point.readingCount);
        }
        if (currentMode == MapMode.RELIABILITY) {
            return point.reliabilityScore >= 80 ? 11 : point.reliabilityScore >= 60 ? 8 : 6;
        }
        return point.signalPower >= Constants.SIGNAL_EXCELLENT ? 11
                : point.signalPower >= Constants.SIGNAL_GOOD ? 9
                : point.signalPower >= Constants.SIGNAL_FAIR ? 7
                : point.signalPower >= Constants.SIGNAL_POOR ? 6 : 5;
    }

    @NonNull
    private String signalColor(int signalPower) {
        if (signalPower >= Constants.SIGNAL_EXCELLENT) return "#059669";
        if (signalPower >= Constants.SIGNAL_GOOD) return "#65A30D";
        if (signalPower >= Constants.SIGNAL_FAIR) return "#D97706";
        if (signalPower >= Constants.SIGNAL_POOR) return "#EA580C";
        return "#DC2626";
    }

    @NonNull
    private String signalBorder(int signalPower) {
        if (signalPower >= Constants.SIGNAL_EXCELLENT) return "#047857";
        if (signalPower >= Constants.SIGNAL_GOOD) return "#4D7C0F";
        if (signalPower >= Constants.SIGNAL_FAIR) return "#B45309";
        if (signalPower >= Constants.SIGNAL_POOR) return "#C2410C";
        return "#B91C1C";
    }

    @NonNull
    private String reliabilityColor(int score) {
        if (score >= 85) return "#059669";
        if (score >= 70) return "#65A30D";
        if (score >= 55) return "#D97706";
        if (score >= 40) return "#EA580C";
        return "#DC2626";
    }

    @NonNull
    private String predictiveColor(double deadzoneRisk) {
        if (deadzoneRisk >= 0.7) return "#B91C1C";
        if (deadzoneRisk >= 0.45) return "#EA580C";
        return "#059669";
    }

    @NonNull
    private String operatorColor(@NonNull String operator) {
        String normalized = operator.toLowerCase(Locale.US);
        if (normalized.contains("alfa")) return "#059669";
        if (normalized.contains("touch")) return "#1E40AF";
        if (normalized.contains("server")) return "#78716C";
        return "#7C3AED";
    }

    @NonNull
    private String joinOrFallback(List<String> values, @NonNull String fallback) {
        if (values == null || values.isEmpty()) {
            return fallback;
        }
        return android.text.TextUtils.join(", ", values);
    }

    @NonNull
    private String simplifyTimestamp(String timestamp) {
        if (timestamp == null || timestamp.trim().isEmpty()) {
            return "--";
        }
        return timestamp.replace('T', ' ').replace("Z", " UTC");
    }

    @NonNull
    private String fallback(@Nullable String value, @NonNull String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value;
    }

    @NonNull
    private String deadzoneLabel(double deadzoneRisk) {
        if (deadzoneRisk >= 0.7) {
            return "High dead-zone risk";
        }
        if (deadzoneRisk >= 0.45) {
            return "Moderate dead-zone risk";
        }
        return "Low dead-zone risk";
    }

    @NonNull
    private String buildDeadzoneSummary(@NonNull HeatmapResponse.HeatmapPoint point) {
        Double risk = point.getDeadzoneRisk();
        if (risk == null) {
            return "No trained dead-zone score";
        }
        StringBuilder builder = new StringBuilder();
        builder.append(String.format(Locale.US, "%.0f%% risk", risk * 100.0));
        if (point.getPredictionConfidence() != null) {
            builder.append(String.format(Locale.US, " at %.0f%% confidence", point.getPredictionConfidence() * 100.0));
        }
        if (point.getRiskReasons() != null && !point.getRiskReasons().isEmpty()) {
            builder.append(" | ").append(point.getRiskReasons().get(0));
        }
        return builder.toString();
    }

    private void showPredictionState(@NonNull String summary, @Nullable String detail) {
        if (binding == null) {
            return;
        }
        binding.tvPredictionSummary.setVisibility(View.VISIBLE);
        binding.tvPredictionSummary.setText(summary);
        if (detail == null || detail.trim().isEmpty()) {
            binding.tvPredictionDetail.setVisibility(View.GONE);
            binding.tvPredictionDetail.setText("");
            return;
        }
        binding.tvPredictionDetail.setVisibility(View.VISIBLE);
        binding.tvPredictionDetail.setText(detail);
    }

    @NonNull
    private String formatPredictionSummary(@NonNull DeadzonePredictionResponse prediction) {
        String label = prediction.getDeadzoneLabel();
        if (label == null || label.trim().isEmpty()) {
            if (prediction.getDeadzoneRisk() != null) {
                label = deadzoneLabel(prediction.getDeadzoneRisk());
            } else {
                label = "Prediction available";
            }
        }
        return getString(R.string.heatmap_prediction_summary, prettifyPredictionLabel(label));
    }

    @NonNull
    private String formatPredictionDetail(@NonNull DeadzonePredictionResponse prediction) {
        String risk = prediction.getDeadzoneRisk() == null
                ? "--"
                : String.format(Locale.US, "%.0f%%", prediction.getDeadzoneRisk() * 100.0);
        String confidence = prediction.getConfidence() == null
                ? "--"
                : String.format(Locale.US, "%.0f%%", prediction.getConfidence() * 100.0);
        String source = "deadzone-model".equalsIgnoreCase(prediction.getModelSource())
                ? getString(R.string.heatmap_prediction_source_model)
                : getString(R.string.heatmap_prediction_source_fallback);
        if (prediction.getReasons() != null && !prediction.getReasons().isEmpty()) {
            return getString(
                    R.string.heatmap_prediction_detail_with_reason,
                    risk,
                    confidence,
                    prediction.getReasons().get(0)
            );
        }
        return getString(R.string.heatmap_prediction_detail_with_reason, risk, confidence, source);
    }

    @NonNull
    private String prettifyPredictionLabel(@NonNull String label) {
        String normalized = label.replace('_', ' ').trim();
        if (normalized.isEmpty()) {
            return "Prediction available";
        }
        if ("high".equalsIgnoreCase(normalized)) {
            return "High dead-zone risk";
        }
        if ("moderate".equalsIgnoreCase(normalized)) {
            return "Moderate dead-zone risk";
        }
        if ("low".equalsIgnoreCase(normalized)) {
            return "Low dead-zone risk";
        }
        return Character.toUpperCase(normalized.charAt(0)) + normalized.substring(1);
    }

    @NonNull
    private String normalizeNetworkType(@NonNull String networkType) {
        return networkType.trim().toUpperCase(Locale.US);
    }

    private void updatePredictiveOverlayUi(@Nullable String overrideHint) {
        if (binding == null) {
            return;
        }
        boolean reliabilityMode = currentMode == MapMode.RELIABILITY;
        binding.switchPredictiveOverlay.setEnabled(reliabilityMode);
        binding.switchPredictiveOverlay.setAlpha(reliabilityMode ? 1f : 0.55f);
        if (overrideHint != null && !overrideHint.trim().isEmpty()) {
            binding.tvPredictiveOverlayHint.setText(overrideHint);
            return;
        }
        binding.tvPredictiveOverlayHint.setText(
                reliabilityMode
                        ? getString(R.string.heatmap_predictive_overlay_hint)
                        : getString(R.string.heatmap_predictive_overlay_disabled_hint)
        );
    }

    private String escapeJs(@NonNull String text) {
        return text.replace("\\", "\\\\").replace("'", "\\'");
    }

    private static final class MapPoint {
        final double latitude;
        final double longitude;
        final int signalPower;
        final String networkType;
        final String operator;
        final String displayTimestamp;
        final String cellId;
        final String signalQuality;
        final int readingCount;
        final String cellIdsSummary;
        final String plmnSummary;
        final String channelsSummary;
        final int reliabilityScore;
        final Double deadzoneRisk;
        final Double predictionConfidence;
        final String deadzoneSummary;
        final String deadzoneDisplay;

        MapPoint(double latitude, double longitude, int signalPower, @NonNull String networkType,
                 @NonNull String operator, @NonNull String displayTimestamp, @NonNull String cellId,
                 @NonNull String signalQuality, int readingCount, @NonNull String cellIdsSummary,
                 @NonNull String plmnSummary, @NonNull String channelsSummary, int reliabilityScore,
                 @Nullable Double deadzoneRisk, @Nullable Double predictionConfidence,
                 @NonNull String deadzoneSummary) {
            this.latitude = latitude;
            this.longitude = longitude;
            this.signalPower = signalPower;
            this.networkType = networkType;
            this.operator = operator;
            this.displayTimestamp = displayTimestamp;
            this.cellId = cellId;
            this.signalQuality = signalQuality;
            this.readingCount = readingCount;
            this.cellIdsSummary = cellIdsSummary;
            this.plmnSummary = plmnSummary;
            this.channelsSummary = channelsSummary;
            this.reliabilityScore = reliabilityScore;
            this.deadzoneRisk = deadzoneRisk;
            this.predictionConfidence = predictionConfidence;
            this.deadzoneSummary = deadzoneSummary;
            this.deadzoneDisplay = deadzoneRisk == null
                    ? "Model unavailable"
                    : String.format(Locale.US, "%.0f%%", deadzoneRisk * 100.0);
        }
    }

    private static final class PredictionCandidate {
        final String operator;
        final String networkType;

        PredictionCandidate(@NonNull String operator, @NonNull String networkType) {
            this.operator = operator;
            this.networkType = networkType;
        }
    }

    private static final class PredictionCell {
        final double southLat;
        final double northLat;
        final double westLng;
        final double eastLng;
        final double deadzoneRisk;
        @Nullable final Double confidence;

        PredictionCell(double southLat, double northLat, double westLng, double eastLng,
                       double deadzoneRisk, @Nullable Double confidence) {
            this.southLat = southLat;
            this.northLat = northLat;
            this.westLng = westLng;
            this.eastLng = eastLng;
            this.deadzoneRisk = deadzoneRisk;
            this.confidence = confidence;
        }
    }

    private static final class AggregatedPoint {
        final double latitude;
        final double longitude;
        final String networkType;
        final String operator;
        int readingCount;
        int signalTotal;
        double snrTotal;
        int snrSamples;
        long latestTimestamp;
        String latestCellId = "--";
        final LinkedHashSet<String> cellIds = new LinkedHashSet<>();
        final LinkedHashSet<String> plmns = new LinkedHashSet<>();
        final LinkedHashSet<String> channels = new LinkedHashSet<>();

        AggregatedPoint(double latitude, double longitude, @NonNull String networkType,
                        @NonNull String operator) {
            this.latitude = latitude;
            this.longitude = longitude;
            this.networkType = networkType;
            this.operator = operator;
        }

        void addReading(@NonNull CellDataEntity entity) {
            readingCount++;
            signalTotal += entity.getSignalPower();
            if (entity.getSnr() > -500) {
                snrTotal += entity.getSnr();
                snrSamples++;
            }
            String cellId = entity.getCellId() != null ? entity.getCellId() : "--";
            if (!"--".equals(cellId)) {
                cellIds.add(cellId);
            }
            if (entity.getMcc() != null && entity.getMnc() != null) {
                plmns.add(entity.getMcc() + "-" + entity.getMnc());
            }
            if (entity.getFrequencyBand() != null) {
                channels.add(entity.getFrequencyBand());
            }
            if (entity.getTimestamp() >= latestTimestamp) {
                latestTimestamp = entity.getTimestamp();
                latestCellId = cellId;
            }
        }

        MapPoint toReadingPoint() {
            int averageSignal = Math.round(signalTotal / (float) Math.max(1, readingCount));
            int reliability = NetworkInsightEngine.clamp(
                    NetworkInsightEngine.scoreSignal(averageSignal) + Math.min(15, readingCount * 3));
            return new MapPoint(
                    latitude,
                    longitude,
                    averageSignal,
                    networkType,
                    operator,
                    android.text.format.DateFormat.format("yyyy-MM-dd HH:mm:ss", latestTimestamp).toString(),
                    latestCellId,
                    CellInfoHelper.getSignalQuality(averageSignal),
                    readingCount,
                    summarize(cellIds),
                    summarize(plmns),
                    summarize(channels),
                    reliability,
                    null,
                    null,
                    "Model unavailable for local-only heatmap points"
            );
        }

        private String summarize(@NonNull LinkedHashSet<String> values) {
            if (values.isEmpty()) {
                return "--";
            }
            StringBuilder sb = new StringBuilder();
            int index = 0;
            for (String value : values) {
                if (index > 0) {
                    sb.append(", ");
                }
                sb.append(value);
                index++;
                if (index == 4 && values.size() > index) {
                    sb.append(" +").append(values.size() - index).append(" more");
                    break;
                }
            }
            return sb.toString();
        }
    }

    private static final class TowerAggregate {
        final String cellId;
        final String networkType;
        final String operator;
        int readingCount;
        int signalTotal;
        double latSum;
        double lonSum;
        long latestTimestamp;
        final LinkedHashSet<String> channels = new LinkedHashSet<>();

        TowerAggregate(@NonNull String cellId, @NonNull String networkType, @NonNull String operator) {
            this.cellId = cellId;
            this.networkType = networkType;
            this.operator = operator;
        }

        void add(@NonNull CellDataEntity entity) {
            readingCount++;
            signalTotal += entity.getSignalPower();
            latSum += entity.getLatitude();
            lonSum += entity.getLongitude();
            latestTimestamp = Math.max(latestTimestamp, entity.getTimestamp());
            if (entity.getFrequencyBand() != null) {
                channels.add(entity.getFrequencyBand());
            }
        }

        MapPoint toMapPoint() {
            double lat = latSum / Math.max(1, readingCount);
            double lon = lonSum / Math.max(1, readingCount);
            int signal = Math.round(signalTotal / (float) Math.max(1, readingCount));
            int reliability = NetworkInsightEngine.clamp(
                    NetworkInsightEngine.scoreSignal(signal) + Math.min(20, readingCount * 2));
            return new MapPoint(
                    lat,
                    lon,
                    signal,
                    networkType,
                    operator,
                    android.text.format.DateFormat.format("yyyy-MM-dd HH:mm:ss", latestTimestamp).toString(),
                    cellId,
                    "Tower fingerprint",
                    readingCount,
                    "Community-style recurring cell trace",
                    "--",
                    channels.isEmpty() ? "--" : android.text.TextUtils.join(", ", channels),
                    reliability,
                    null,
                    null,
                    "Model unavailable for local-only tower points"
            );
        }
    }
}
