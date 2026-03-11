package com.networkanalyzer.app.fragments;

import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;

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
import com.networkanalyzer.app.network.models.HeatmapResponse;
import com.networkanalyzer.app.utils.Constants;
import com.networkanalyzer.app.utils.PreferenceManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * Displays a free OpenStreetMap-based signal map using a WebView.
 * This avoids Google Maps billing and API-key requirements.
 */
public class HeatmapFragment extends Fragment {

    private static final String TAG = "HeatmapFragment";
    private static final int SERVER_POINT_LIMIT = 500;

    private FragmentHeatmapBinding binding;
    private CellDataDao cellDataDao;
    private PreferenceManager preferenceManager;
    private ApiService apiService;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    /** Currently selected network type filter; {@code null} means "All". */
    private String selectedNetworkType = null;

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
        loadHeatmapData();
    }

    @Override
    public void onDestroyView() {
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
        binding.webMap.setBackgroundColor(Color.TRANSPARENT);
    }

    private void setupChipGroup() {
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

    private void setupLegend() {
        binding.tvLegendStrong.setText("Strong");
        binding.tvLegendStrong.setTextColor(Color.rgb(76, 175, 80));
        binding.tvLegendWeak.setText("Weak");
        binding.tvLegendWeak.setTextColor(Color.rgb(244, 67, 54));
    }

    private void loadHeatmapData() {
        if (binding == null) {
            return;
        }
        binding.progressBar.setVisibility(View.VISIBLE);
        binding.tvEmptyHeatmap.setVisibility(View.GONE);
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

    private void loadLocalHeatmapData() {
        executor.execute(() -> {
            List<CellDataEntity> entities = selectedNetworkType != null
                    ? cellDataDao.getWithLocationByType(selectedNetworkType)
                    : cellDataDao.getWithLocation();

            java.util.LinkedHashMap<String, AggregatedPoint> groupedPoints = new java.util.LinkedHashMap<>();
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
                            entity.getNetworkType() != null ? entity.getNetworkType() : Constants.NETWORK_UNKNOWN,
                            entity.getOperator() != null ? entity.getOperator() : "Unknown");
                    groupedPoints.put(key, point);
                }
                point.addReading(
                        entity.getSignalPower(),
                        entity.getTimestamp(),
                        entity.getCellId() != null ? entity.getCellId() : "--",
                        entity.getFrequencyBand() != null ? entity.getFrequencyBand() : "--",
                        entity.getMcc() != null ? entity.getMcc() : "--",
                        entity.getMnc() != null ? entity.getMnc() : "--");
            }

            List<MapPoint> points = new ArrayList<>();
            for (AggregatedPoint point : groupedPoints.values()) {
                points.add(point.toMapPoint());
            }

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
            String snrSummary = point.getAvgSnr() != null
                    ? String.format(Locale.US, "%.1f dB", point.getAvgSnr())
                    : "--";
            points.add(new MapPoint(
                    point.getLatitude(),
                    point.getLongitude(),
                    signalPower,
                    networkType,
                    operator,
                    simplifyTimestamp(point.getLatestTimestamp()),
                    "Aggregated",
                    CellInfoHelper.getSignalQuality(signalPower),
                    point.getSampleCount(),
                    "Server aggregation",
                    "Operators: " + operator,
                    "Avg SNR: " + snrSummary));
        }
        return points;
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

    private void renderMap(@NonNull List<MapPoint> points) {
        if (binding == null) {
            return;
        }

        binding.progressBar.setVisibility(View.GONE);

        if (points.isEmpty()) {
            binding.tvEmptyHeatmap.setText(R.string.heatmap_no_data);
            binding.tvEmptyHeatmap.setVisibility(View.VISIBLE);
            binding.webMap.loadData("<html><body style='background:#f5f5f5;'></body></html>",
                    "text/html", "UTF-8");
            return;
        }

        binding.tvEmptyHeatmap.setVisibility(View.GONE);
        binding.webMap.loadDataWithBaseURL(
                "https://appassets.androidplatform.net/",
                buildLeafletHtml(points),
                "text/html",
                "UTF-8",
                null
        );
    }

    private String buildLeafletHtml(@NonNull List<MapPoint> points) {
        MapPoint center = points.get(points.size() - 1);
        StringBuilder markers = new StringBuilder();

        for (MapPoint point : points) {
            String borderColor = borderColorForSignal(point.signalPower);
            String fillColor = colorForSignal(point.signalPower);
            int radius = radiusForSignal(point.signalPower);

            markers.append("L.circleMarker([")
                    .append(point.latitude).append(',').append(point.longitude).append("],{")
                    .append("radius:").append(radius).append(',')
                    .append("color:'").append(borderColor).append("',")
                    .append("fillColor:'").append(fillColor).append("',")
                    .append("fillOpacity:0.65,weight:2,opacity:0.9")
                    .append("}).addTo(map)")
                    .append(".bindPopup('")
                    .append("<div class=&quot;popup-content&quot;>")
                    .append("<div class=&quot;popup-header&quot;>")
                    .append("<span class=&quot;popup-badge&quot; style=&quot;background:").append(fillColor).append("&quot;>")
                    .append(escapeJs(point.signalQuality)).append("</span>")
                    .append("<span class=&quot;popup-count&quot;>").append(point.readingCount).append(" reading(s)</span>")
                    .append("</div>")
                    .append("<div class=&quot;popup-operator&quot;>").append(escapeJs(point.operator)).append("</div>")
                    .append("<table class=&quot;popup-table&quot;>")
                    .append("<tr><td>Network</td><td><b>").append(escapeJs(point.networkType)).append("</b></td></tr>")
                    .append("<tr><td>Avg Signal</td><td><b>").append(point.signalPower).append(" dBm</b></td></tr>")
                    .append("<tr><td>Cell IDs</td><td>").append(escapeJs(point.cellIdsSummary)).append("</td></tr>")
                    .append("<tr><td>PLMN</td><td>").append(escapeJs(point.plmnSummary)).append("</td></tr>")
                    .append("<tr><td>Channels</td><td>").append(escapeJs(point.channelsSummary)).append("</td></tr>")
                    .append("<tr><td>Serving Cell</td><td>").append(escapeJs(point.cellId)).append("</td></tr>")
                    .append("<tr><td>Latest Time</td><td>").append(escapeJs(point.displayTimestamp)).append("</td></tr>")
                    .append("<tr><td>Map Point</td><td>Measurement location</td></tr>")
                    .append("</table></div>');");
        }

        return "<!doctype html><html><head>"
                + "<meta name='viewport' content='width=device-width, initial-scale=1.0'>"
                + "<link rel='stylesheet' href='https://unpkg.com/leaflet@1.9.4/dist/leaflet.css'/>"
                + "<style>"
                + "html,body,#map{height:100%;margin:0;}"
                + "body{background:#eef3f7;}"
                + ".leaflet-container{font-family:-apple-system,sans-serif;background:#dbe7f0;}"
                + ".popup-content{min-width:200px;font-size:13px;line-height:1.5;}"
                + ".popup-header{display:flex;align-items:center;gap:8px;margin-bottom:4px;}"
                + ".popup-badge{color:#fff;padding:2px 8px;border-radius:10px;font-size:11px;font-weight:700;}"
                + ".popup-count{color:#666;font-size:11px;}"
                + ".popup-operator{font-size:15px;font-weight:700;margin-bottom:6px;}"
                + ".popup-table{width:100%;border-collapse:collapse;}"
                + ".popup-table td{padding:2px 0;font-size:12px;border-bottom:1px solid #f0f0f0;}"
                + ".popup-table td:first-child{color:#888;padding-right:10px;white-space:nowrap;}"
                + ".map-legend{position:absolute;right:10px;bottom:10px;z-index:999;"
                + "background:rgba(255,255,255,.94);padding:10px 14px;border-radius:12px;"
                + "box-shadow:0 2px 12px rgba(0,0,0,.15);font-size:11px;line-height:1.6;}"
                + ".dot{display:inline-block;width:10px;height:10px;border-radius:50%;margin-right:6px;vertical-align:middle;}"
                + "</style>"
                + "</head><body><div id='map'></div>"
                + "<div class='map-legend'>"
                + "<div><span class='dot' style='background:#1B5E20'></span>Excellent</div>"
                + "<div><span class='dot' style='background:#558B2F'></span>Good</div>"
                + "<div><span class='dot' style='background:#E65100'></span>Fair</div>"
                + "<div><span class='dot' style='background:#BF360C'></span>Poor</div>"
                + "<div><span class='dot' style='background:#B71C1C'></span>Very weak</div>"
                + "</div>"
                + "<script src='https://unpkg.com/leaflet@1.9.4/dist/leaflet.js'></script>"
                + "<script>"
                + "var map=L.map('map',{zoomControl:true}).setView(["
                + center.latitude + "," + center.longitude + "],15);"
                + "L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png',"
                + "{maxZoom:19,attribution:'&copy; OSM'}).addTo(map);"
                + markers
                + "</script></body></html>";
    }

    private String borderColorForSignal(int signalPower) {
        if (signalPower >= Constants.SIGNAL_EXCELLENT) {
            return "#1B5E20";
        }
        if (signalPower >= Constants.SIGNAL_GOOD) {
            return "#33691E";
        }
        if (signalPower >= Constants.SIGNAL_FAIR) {
            return "#E65100";
        }
        if (signalPower >= Constants.SIGNAL_POOR) {
            return "#BF360C";
        }
        return "#B71C1C";
    }

    private int radiusForSignal(int signalPower) {
        if (signalPower >= Constants.SIGNAL_EXCELLENT) {
            return 11;
        }
        if (signalPower >= Constants.SIGNAL_GOOD) {
            return 9;
        }
        if (signalPower >= Constants.SIGNAL_FAIR) {
            return 7;
        }
        if (signalPower >= Constants.SIGNAL_POOR) {
            return 6;
        }
        return 5;
    }

    private String colorForSignal(int signalPower) {
        if (signalPower >= Constants.SIGNAL_EXCELLENT) {
            return "#2E7D32";
        }
        if (signalPower >= Constants.SIGNAL_GOOD) {
            return "#7CB342";
        }
        if (signalPower >= Constants.SIGNAL_FAIR) {
            return "#FB8C00";
        }
        if (signalPower >= Constants.SIGNAL_POOR) {
            return "#F4511E";
        }
        return "#D32F2F";
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

        MapPoint(double latitude, double longitude, int signalPower, @NonNull String networkType,
                 @NonNull String operator, @NonNull String displayTimestamp, @NonNull String cellId,
                 @NonNull String signalQuality, int readingCount,
                 @NonNull String cellIdsSummary, @NonNull String plmnSummary,
                 @NonNull String channelsSummary) {
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
        }
    }

    private static final class AggregatedPoint {
        final double latitude;
        final double longitude;
        final String networkType;
        final String operator;
        int readingCount;
        int signalTotal;
        long latestTimestamp;
        String latestCellId = "--";
        final java.util.LinkedHashSet<String> cellIds = new java.util.LinkedHashSet<>();
        final java.util.LinkedHashSet<String> plmns = new java.util.LinkedHashSet<>();
        final java.util.LinkedHashSet<String> channels = new java.util.LinkedHashSet<>();

        AggregatedPoint(double latitude, double longitude, @NonNull String networkType,
                        @NonNull String operator) {
            this.latitude = latitude;
            this.longitude = longitude;
            this.networkType = networkType;
            this.operator = operator;
        }

        void addReading(int signalPower, long timestamp, @NonNull String cellId, @NonNull String channel,
                        @NonNull String mcc, @NonNull String mnc) {
            readingCount++;
            signalTotal += signalPower;
            if (!"--".equals(cellId)) {
                cellIds.add(cellId);
            }
            if (!"--".equals(mcc) && !"--".equals(mnc) && !"N/A".equals(mcc) && !"N/A".equals(mnc)) {
                plmns.add(mcc + "-" + mnc);
            }
            if (!"--".equals(channel)) {
                channels.add(channel);
            }
            if (timestamp >= latestTimestamp) {
                latestTimestamp = timestamp;
                latestCellId = cellId;
            }
        }

        MapPoint toMapPoint() {
            int averageSignal = Math.round(signalTotal / (float) Math.max(1, readingCount));
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
                    summarize(channels));
        }

        private String summarize(@NonNull java.util.LinkedHashSet<String> values) {
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
}
