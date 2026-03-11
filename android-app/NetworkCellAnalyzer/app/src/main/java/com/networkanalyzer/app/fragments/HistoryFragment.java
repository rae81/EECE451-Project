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
import com.networkanalyzer.app.utils.ExportHelper;
import com.networkanalyzer.app.utils.PreferenceManager;

import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.ResponseBody;
import retrofit2.Response;

public class HistoryFragment extends Fragment {

    private static final int SERVER_HISTORY_LIMIT = 500;
    private static final int SERVER_EXPORT_LIMIT = 5000;
    private static final SimpleDateFormat[] SERVER_TIMESTAMP_FORMATS = new SimpleDateFormat[]{
            new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US),
            new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
    };

    private FragmentHistoryBinding binding;
    private HistoryAdapter adapter;
    private PreferenceManager preferenceManager;
    private ApiService apiService;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

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

    private void loadHistory() {
        binding.progressBar.setVisibility(View.VISIBLE);
        executor.execute(() -> {
            List<CellDataEntity> items = fetchServerHistory();
            if (items == null) {
                items = AppDatabase.getInstance(requireContext()).cellDataDao().getAll();
            }
            List<CellDataEntity> finalItems = items;
            requireActivity().runOnUiThread(() -> updateHistoryUi(finalItems));
        });
    }

    private void exportCsv() {
        executor.execute(() -> {
            Uri result = downloadServerExport(true);
            if (result == null) {
                List<CellDataEntity> items = AppDatabase.getInstance(requireContext()).cellDataDao().getAll();
                result = ExportHelper.exportToCsv(requireContext(), items, "cell-data-history");
            }
            showExportToast(result, R.string.settings_export_csv);
        });
    }

    private void exportPdf() {
        executor.execute(() -> {
            Uri result = downloadServerExport(false);
            if (result == null) {
                List<CellDataEntity> items = AppDatabase.getInstance(requireContext()).cellDataDao().getAll();
                result = ExportHelper.exportToPdf(requireContext(), items, "cell-data-report");
            }
            showExportToast(result, R.string.settings_export_pdf);
        });
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

    private void showExportToast(Uri result, int labelRes) {
        requireActivity().runOnUiThread(() -> Toast.makeText(
                requireContext(),
                result != null ? getString(labelRes) + " complete" : "Export failed",
                Toast.LENGTH_SHORT
        ).show());
    }
}
