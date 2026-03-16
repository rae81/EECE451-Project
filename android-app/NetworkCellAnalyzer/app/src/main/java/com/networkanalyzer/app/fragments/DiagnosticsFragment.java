package com.networkanalyzer.app.fragments;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.networkanalyzer.app.R;
import com.networkanalyzer.app.databinding.FragmentDiagnosticsBinding;
import com.networkanalyzer.app.network.ApiService;
import com.networkanalyzer.app.network.RetrofitClient;
import com.networkanalyzer.app.network.models.DiagnosticsHistoryResponse;
import com.networkanalyzer.app.network.models.DiagnosticsResponse;
import com.networkanalyzer.app.utils.PreferenceManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class DiagnosticsFragment extends Fragment {

    private FragmentDiagnosticsBinding binding;
    private ApiService apiService;
    private PreferenceManager preferenceManager;
    private final IssueAdapter issueAdapter = new IssueAdapter();
    private final HistoryAdapter historyAdapter = new HistoryAdapter();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentDiagnosticsBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        apiService = RetrofitClient.getInstance(requireContext()).getApiService();
        preferenceManager = new PreferenceManager(requireContext());
        binding.recyclerDiagnosticIssues.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.recyclerDiagnosticIssues.setAdapter(issueAdapter);
        binding.recyclerDiagnosticsHistory.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.recyclerDiagnosticsHistory.setAdapter(historyAdapter);
        loadDiagnostics();
    }

    private void loadDiagnostics() {
        apiService.getDiagnosticsSummary(preferenceManager.getDeviceId(), 1200)
                .enqueue(new Callback<DiagnosticsResponse>() {
                    @Override
                    public void onResponse(@NonNull Call<DiagnosticsResponse> call,
                                           @NonNull Response<DiagnosticsResponse> response) {
                        if (binding == null || !response.isSuccessful() || response.body() == null) {
                            return;
                        }
                        bindSummary(response.body());
                    }

                    @Override
                    public void onFailure(@NonNull Call<DiagnosticsResponse> call, @NonNull Throwable t) {
                    }
                });

        apiService.getDiagnosticsHistory(preferenceManager.getDeviceId(), 2000)
                .enqueue(new Callback<DiagnosticsHistoryResponse>() {
                    @Override
                    public void onResponse(@NonNull Call<DiagnosticsHistoryResponse> call,
                                           @NonNull Response<DiagnosticsHistoryResponse> response) {
                        if (binding == null || !response.isSuccessful() || response.body() == null) {
                            return;
                        }
                        historyAdapter.submit(response.body().getHistory());
                    }

                    @Override
                    public void onFailure(@NonNull Call<DiagnosticsHistoryResponse> call, @NonNull Throwable t) {
                    }
                });
    }

    private void bindSummary(DiagnosticsResponse body) {
        binding.tvDiagnosticsScore.setText(String.format(Locale.US, "%d/100", body.getReliabilityScore()));
        binding.progressDiagnosticsScore.setProgressCompat(body.getReliabilityScore(), true);
        binding.tvDiagnosticsLabel.setText(body.getReliabilityLabel());
        binding.tvDiagnosticsPrimaryCause.setText(buildPrimaryCause(body));
        binding.tvDiagnosticsHeadline.setText(buildHeadline(body));
        binding.tvDiagnosticsAdaptive.setText(String.format(Locale.US,
                "Adaptive guidance: %s mode, sample every %ds",
                body.getAdaptiveLabel(),
                body.getRecommendedIntervalSeconds()));
        binding.tvDiagnosticsActionPlan.setText(buildActionPlan(body));

        DiagnosticsResponse.Summary summary = body.getSummary();
        if (summary != null) {
            binding.tvDiagnosticsRadioGrade.setText(String.format(Locale.US,
                    "Radio grade\n%s",
                    classifyRadioGrade(summary.getAvgSignalPower(), summary.getAvgSnr())));
            binding.tvDiagnosticsMobilityGrade.setText(String.format(Locale.US,
                    "Mobility risk\n%s",
                    classifyMobility(summary.getHandoverRatePer100(), summary.getPingPongCount())));
            binding.tvSignalMetric.setText(String.format(Locale.US,
                    "Radio snapshot\nAvg signal %.0f dBm\nAvg SNR %.1f dB",
                    valueOrZero(summary.getAvgSignalPower()),
                    valueOrZero(summary.getAvgSnr())));
            binding.tvLatencyMetric.setText(String.format(Locale.US,
                    "Latency path\nAvg latency %.0f ms\nNeighbor clusters %d",
                    valueOrZero(summary.getAvgLatencyMs()),
                    summary.getNeighborClusterCount()));
            binding.tvThroughputMetric.setText(String.format(Locale.US,
                    "Backhaul health\nDown %.1f Mbps\nUp %.1f Mbps",
                    valueOrZero(summary.getAvgDownloadMbps()),
                    valueOrZero(summary.getAvgUploadMbps())));
            binding.tvHandoverMetric.setText(String.format(Locale.US,
                    "Mobility behavior\nHandovers %d\nRate %.1f /100 samples\nPing-pong %d",
                    summary.getHandoverCount(),
                    summary.getHandoverRatePer100(),
                    summary.getPingPongCount()));
            binding.tvNeighborMetric.setText(String.format(Locale.US,
                    "Interpretation aid\nNeighbor landscape: %d clusters visible in the current analysis window.",
                    summary.getNeighborClusterCount()));
        }

        List<DiagnosticsResponse.DiagnosticIssue> issues = body.getIssues() != null
                ? body.getIssues() : new ArrayList<>();
        issueAdapter.submit(issues);
        binding.tvDiagnosticsIssuesEmpty.setVisibility(issues.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private String buildPrimaryCause(DiagnosticsResponse body) {
        List<DiagnosticsResponse.DiagnosticIssue> issues = body.getIssues();
        if (issues == null || issues.isEmpty()) {
            return "Primary cause: no dominant issue detected";
        }
        DiagnosticsResponse.DiagnosticIssue primary = issues.get(0);
        return String.format(Locale.US, "Primary cause: %s (%s)",
                primary.getTitle(),
                primary.getSeverity().toUpperCase(Locale.US));
    }

    private String buildHeadline(DiagnosticsResponse body) {
        List<DiagnosticsResponse.DiagnosticIssue> issues = body.getIssues();
        if (issues == null || issues.isEmpty()) {
            return "The latest window looks stable. No clear radio or backhaul issue is dominating the session.";
        }
        DiagnosticsResponse.DiagnosticIssue primary = issues.get(0);
        return String.format(Locale.US,
                "Primary concern: %s. Inspect the evidence cards below to see whether this looks like weak coverage, interference, congestion, or mobility instability.",
                primary.getTitle());
    }

    private String buildActionPlan(DiagnosticsResponse body) {
        List<DiagnosticsResponse.DiagnosticIssue> issues = body.getIssues();
        if (issues == null || issues.isEmpty()) {
            return "Next action: keep monitoring. The current window does not need aggressive resampling or immediate troubleshooting.";
        }
        DiagnosticsResponse.DiagnosticIssue primary = issues.get(0);
        return "Next action: " + primary.getSuggestion();
    }

    private String classifyRadioGrade(Double signal, Double snr) {
        double sig = valueOrZero(signal);
        double quality = valueOrZero(snr);
        if (sig >= -90 && quality >= 15) {
            return "Strong and clean";
        }
        if (sig >= -105 && quality >= 8) {
            return "Usable but not clean";
        }
        if (sig >= -115) {
            return "Weak or noisy";
        }
        return "Very weak";
    }

    private String classifyMobility(double handoverRatePer100, int pingPongCount) {
        if (handoverRatePer100 < 3 && pingPongCount == 0) {
            return "Stable";
        }
        if (handoverRatePer100 < 8 && pingPongCount <= 1) {
            return "Moderate";
        }
        return "High instability";
    }

    private double valueOrZero(Double value) {
        return value != null ? value : 0d;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }

    private class IssueAdapter extends RecyclerView.Adapter<IssueAdapter.VH> {
        private List<DiagnosticsResponse.DiagnosticIssue> items = new ArrayList<>();

        void submit(List<DiagnosticsResponse.DiagnosticIssue> history) {
            items = history != null ? history : new ArrayList<>();
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_diagnostic_issue, parent, false);
            return new VH(view);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            DiagnosticsResponse.DiagnosticIssue item = items.get(position);
            holder.severity.setText(item.getSeverity().toUpperCase(Locale.US));
            holder.title.setText(item.getTitle());
            holder.evidence.setText("Evidence: " + item.getEvidence());
            holder.suggestion.setText("Suggested action: " + item.getSuggestion());
            holder.itemView.setOnClickListener(v -> new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(item.getTitle())
                    .setMessage(String.format(Locale.US,
                            "Severity: %s\n\nEvidence:\n%s\n\nSuggested action:\n%s",
                            item.getSeverity(),
                            item.getEvidence(),
                            item.getSuggestion()))
                    .setPositiveButton(android.R.string.ok, null)
                    .show());
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        class VH extends RecyclerView.ViewHolder {
            final TextView severity;
            final TextView title;
            final TextView evidence;
            final TextView suggestion;

            VH(@NonNull View itemView) {
                super(itemView);
                severity = itemView.findViewById(R.id.tvIssueSeverity);
                title = itemView.findViewById(R.id.tvIssueTitle);
                evidence = itemView.findViewById(R.id.tvIssueEvidence);
                suggestion = itemView.findViewById(R.id.tvIssueSuggestion);
            }
        }
    }

    private class HistoryAdapter extends RecyclerView.Adapter<HistoryAdapter.VH> {
        private List<DiagnosticsHistoryResponse.HistoryItem> items = new ArrayList<>();

        void submit(List<DiagnosticsHistoryResponse.HistoryItem> history) {
            items = history != null ? history : new ArrayList<>();
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_diagnostics_history, parent, false);
            return new VH(view);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            DiagnosticsHistoryResponse.HistoryItem item = items.get(position);
            holder.score.setText(String.format(Locale.US, "%s • %d/100",
                    item.getReliabilityLabel(),
                    item.getReliabilityScore()));
            holder.window.setText(String.format(Locale.US, "Window: %s -> %s",
                    item.getFromTimestamp(),
                    item.getToTimestamp()));
            holder.meta.setText(String.format(Locale.US, "Primary issue: %s • %d findings",
                    item.getPrimaryIssue(),
                    item.getIssueCount()));
            holder.itemView.setOnClickListener(v -> new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(item.getReliabilityLabel())
                    .setMessage(String.format(Locale.US,
                            "Score: %d/100\nWindow: %s -> %s\nPrimary issue: %s\nIssue count: %d",
                            item.getReliabilityScore(),
                            item.getFromTimestamp(),
                            item.getToTimestamp(),
                            item.getPrimaryIssue(),
                            item.getIssueCount()))
                    .setPositiveButton(android.R.string.ok, null)
                    .show());
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        class VH extends RecyclerView.ViewHolder {
            final TextView score;
            final TextView window;
            final TextView meta;

            VH(@NonNull View itemView) {
                super(itemView);
                score = itemView.findViewById(R.id.tvHistoryScore);
                window = itemView.findViewById(R.id.tvHistoryWindow);
                meta = itemView.findViewById(R.id.tvHistoryIssueMeta);
            }
        }
    }
}
