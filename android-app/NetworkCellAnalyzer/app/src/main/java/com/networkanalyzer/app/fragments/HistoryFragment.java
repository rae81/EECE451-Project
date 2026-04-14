package com.networkanalyzer.app.fragments;

import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.networkanalyzer.app.R;
import com.networkanalyzer.app.adapters.HistoryAdapter;
import com.networkanalyzer.app.database.AppDatabase;
import com.networkanalyzer.app.database.CellDataEntity;
import com.networkanalyzer.app.databinding.FragmentHistoryBinding;
import com.networkanalyzer.app.network.ApiService;
import com.networkanalyzer.app.network.RetrofitClient;
import com.networkanalyzer.app.network.models.HistoryResponse;
import com.networkanalyzer.app.utils.Constants;
import com.networkanalyzer.app.utils.ExportHelper;
import com.networkanalyzer.app.utils.PreferenceManager;

import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.ResponseBody;
import retrofit2.Response;

/**
 * Paginated list of stored cell-data readings backed by the server's
 * {@code /api/history} endpoint with a Room cache for offline viewing.
 * Also hosts the CSV export button that calls {@code /api/export.csv}.
 * Part of the 10% "statistical service" graded feature.
 */
public class HistoryFragment extends Fragment {

    private static final int SERVER_HISTORY_LIMIT = 500;
    private static final int SERVER_EXPORT_LIMIT = 5000;
    private static final SimpleDateFormat[] SERVER_TIMESTAMP_FORMATS = new SimpleDateFormat[]{
            new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US),
            new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
    };

    private enum FilterMode {
        ALL,
        UNSYNCED,
        G2,
        G3,
        G4,
        G5
    }

    private FragmentHistoryBinding binding;
    private HistoryAdapter adapter;
    private PreferenceManager preferenceManager;
    private ApiService apiService;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private List<CellDataEntity> allItems = new ArrayList<>();
    private FilterMode filterMode = FilterMode.ALL;

    static {
        for (SimpleDateFormat format : SERVER_TIMESTAMP_FORMATS) {
            format.setTimeZone(TimeZone.getTimeZone("UTC"));
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentHistoryBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        preferenceManager = new PreferenceManager(requireContext());
        apiService = RetrofitClient.getInstance(requireContext()).getApiService();
        adapter = new HistoryAdapter(null);
        binding.recyclerHistoryEntries.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.recyclerHistoryEntries.setAdapter(adapter);

        setupFilters();
        binding.btnExportCsv.setOnClickListener(v -> exportCsv());
        binding.btnExportPdf.setOnClickListener(v -> exportPdf());

        loadHistory();
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

    private void setupFilters() {
        binding.chipHistoryAll.setOnClickListener(v -> {
            filterMode = FilterMode.ALL;
            applyFilter();
        });
        binding.chipHistoryUnsynced.setOnClickListener(v -> {
            filterMode = FilterMode.UNSYNCED;
            applyFilter();
        });
        binding.chipHistory2g.setOnClickListener(v -> {
            filterMode = FilterMode.G2;
            applyFilter();
        });
        binding.chipHistory3g.setOnClickListener(v -> {
            filterMode = FilterMode.G3;
            applyFilter();
        });
        binding.chipHistory4g.setOnClickListener(v -> {
            filterMode = FilterMode.G4;
            applyFilter();
        });
        binding.chipHistory5g.setOnClickListener(v -> {
            filterMode = FilterMode.G5;
            applyFilter();
        });
    }

    private void loadHistory() {
        binding.progressBar.setVisibility(View.VISIBLE);
        executor.execute(() -> {
            List<CellDataEntity> items = fetchServerHistory();
            boolean usedServer = items != null;
            if (items == null) {
                items = AppDatabase.getInstance(requireContext()).cellDataDao().getAll();
            }
            allItems = items;
            boolean finalUsedServer = usedServer;
            requireActivity().runOnUiThread(() -> {
                if (binding == null) {
                    return;
                }
                binding.tvExportStatus.setText(finalUsedServer
                        ? getString(R.string.history_server_export_ready)
                        : "Offline/local field log active. Exports will use local storage.");
                applyFilter();
            });
        });
    }

    private void applyFilter() {
        if (binding == null) {
            return;
        }
        List<CellDataEntity> filtered = new ArrayList<>();
        for (CellDataEntity item : allItems) {
            if (matchesFilter(item)) {
                filtered.add(item);
            }
        }
        updateHistoryUi(filtered);
        updateSummary(filtered);
    }

    private boolean matchesFilter(@NonNull CellDataEntity item) {
        switch (filterMode) {
            case UNSYNCED:
                return !item.isSynced();
            case G2:
                return Constants.NETWORK_2G.equals(item.getNetworkType());
            case G3:
                return Constants.NETWORK_3G.equals(item.getNetworkType());
            case G4:
                return Constants.NETWORK_4G.equals(item.getNetworkType());
            case G5:
                return Constants.NETWORK_5G.equals(item.getNetworkType());
            default:
                return true;
        }
    }

    private void updateSummary(@NonNull List<CellDataEntity> items) {
        LinkedHashSet<String> cellIds = new LinkedHashSet<>();
        LinkedHashSet<String> operators = new LinkedHashSet<>();
        int syncedCount = 0;
        for (CellDataEntity item : items) {
            if (item.getCellId() != null && !item.getCellId().isEmpty()) {
                cellIds.add(item.getCellId());
            }
            if (item.getOperator() != null && !item.getOperator().isEmpty()) {
                operators.add(item.getOperator());
            }
            if (item.isSynced()) {
                syncedCount++;
            }
        }
        int syncPercent = items.isEmpty() ? 0 : Math.round((syncedCount * 100f) / items.size());
        binding.tvSummaryRecords.setText(String.valueOf(items.size()));
        binding.tvSummaryCells.setText(String.valueOf(cellIds.size()));
        binding.tvSummaryOperators.setText(String.valueOf(operators.size()));
        binding.tvSummarySync.setText(syncPercent + "%");
    }

    private void exportCsv() {
        updateExportUi(true, getString(R.string.history_exporting_csv));
        executor.execute(() -> {
            Uri result = downloadServerExport(true);
            if (result == null) {
                result = ExportHelper.exportToCsv(requireContext(), applyFilterCopy(), "cell-data-history");
            }
            handleExportResult(result, "text/csv", getString(R.string.history_export_format_csv));
        });
    }

    private void exportPdf() {
        updateExportUi(true, getString(R.string.history_exporting_pdf));
        executor.execute(() -> {
            Uri result = downloadServerExport(false);
            if (result == null) {
                result = ExportHelper.exportToPdf(requireContext(), applyFilterCopy(), "cell-data-report");
            }
            handleExportResult(result, "application/pdf", getString(R.string.history_export_format_pdf));
        });
    }

    @NonNull
    private List<CellDataEntity> applyFilterCopy() {
        List<CellDataEntity> filtered = new ArrayList<>();
        for (CellDataEntity item : allItems) {
            if (matchesFilter(item)) {
                filtered.add(item);
            }
        }
        return filtered;
    }

    private void updateHistoryUi(List<CellDataEntity> items) {
        if (binding == null) {
            return;
        }
        binding.progressBar.setVisibility(View.GONE);
        adapter.updateData(items);
        int emptyVisibility = items.isEmpty() ? View.VISIBLE : View.GONE;
        binding.tvEmptyState.setVisibility(emptyVisibility);
        binding.tvEmptyStateCaption.setVisibility(emptyVisibility);
    }

    private List<CellDataEntity> fetchServerHistory() {
        try {
            Response<HistoryResponse> response = apiService.getHistory(
                    preferenceManager.getDeviceId(),
                    SERVER_HISTORY_LIMIT
            ).execute();
            if (!response.isSuccessful() || response.body() == null || response.body().getRecords() == null) {
                return null;
            }

            List<CellDataEntity> items = new ArrayList<>();
            for (HistoryResponse.HistoryRecord record : response.body().getRecords()) {
                CellDataEntity entity = new CellDataEntity();
                entity.setDeviceId(record.getDeviceId());
                entity.setOperator(record.getOperator());
                entity.setSignalPower(record.getSignalPower());
                entity.setSnr(record.getSnr() != null ? record.getSnr() : 0.0);
                entity.setNetworkType(record.getNetworkType());
                entity.setFrequencyBand(record.getFrequencyBand());
                entity.setCellId(record.getCellId());
                entity.setLac(record.getLac());
                entity.setMcc(record.getMcc());
                entity.setMnc(record.getMnc());
                entity.setLatitude(record.getLatitude() != null ? record.getLatitude() : 0.0);
                entity.setLongitude(record.getLongitude() != null ? record.getLongitude() : 0.0);
                entity.setSimSlot(record.getSimSlot() != null ? record.getSimSlot() : 0);
                entity.setTimestamp(parseServerTimestamp(record.getTimestamp()));
                entity.setSynced(true);
                items.add(entity);
            }
            return items;
        } catch (IOException e) {
            return null;
        }
    }

    private Uri downloadServerExport(boolean csv) {
        try {
            Response<ResponseBody> response = (csv
                    ? apiService.exportCsv(preferenceManager.getDeviceId(), SERVER_EXPORT_LIMIT)
                    : apiService.exportPdf(preferenceManager.getDeviceId(), SERVER_EXPORT_LIMIT))
                    .execute();
            if (!response.isSuccessful() || response.body() == null) {
                return null;
            }
            String filename = csv ? "cell-data-history-server.csv" : "cell-data-report-server.pdf";
            String mimeType = csv ? "text/csv" : "application/pdf";
            return ExportHelper.saveDownloadedFile(
                    requireContext(),
                    response.body().byteStream(),
                    filename,
                    mimeType
            );
        } catch (IOException e) {
            return null;
        }
    }

    private long parseServerTimestamp(String rawTimestamp) {
        if (rawTimestamp == null || rawTimestamp.trim().isEmpty()) {
            return System.currentTimeMillis();
        }
        for (SimpleDateFormat format : SERVER_TIMESTAMP_FORMATS) {
            try {
                Date parsed = format.parse(rawTimestamp);
                if (parsed != null) {
                    return parsed.getTime();
                }
            } catch (ParseException ignored) {
            }
        }
        return System.currentTimeMillis();
    }

    private void updateExportUi(boolean busy, @NonNull String statusText) {
        if (getActivity() == null) {
            return;
        }
        requireActivity().runOnUiThread(() -> {
            if (binding == null) {
                return;
            }
            binding.btnExportCsv.setEnabled(!busy);
            binding.btnExportPdf.setEnabled(!busy);
            binding.tvExportStatus.setText(statusText);
        });
    }

    private void handleExportResult(@Nullable Uri result, @NonNull String mimeType, @NonNull String formatLabel) {
        if (getActivity() == null) {
            return;
        }
        requireActivity().runOnUiThread(() -> {
            if (binding == null) {
                return;
            }
            binding.btnExportCsv.setEnabled(true);
            binding.btnExportPdf.setEnabled(true);
            if (result == null) {
                binding.tvExportStatus.setText(R.string.history_export_failed);
                Toast.makeText(requireContext(), R.string.history_export_failed, Toast.LENGTH_SHORT).show();
                return;
            }

            boolean surfaced = ExportHelper.openFile(requireContext(), result, mimeType);
            binding.tvExportStatus.setText(
                    surfaced
                            ? getString(R.string.history_export_opened, formatLabel)
                            : getString(R.string.history_export_saved, formatLabel)
            );
            Toast.makeText(
                    requireContext(),
                    surfaced
                            ? getString(R.string.history_export_opened, formatLabel)
                            : getString(R.string.history_export_saved, formatLabel),
                    Toast.LENGTH_SHORT
            ).show();
        });
    }
}
