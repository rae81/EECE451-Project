package com.networkanalyzer.app.fragments;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.networkanalyzer.app.databinding.FragmentTowerClusterDetailBinding;
import com.networkanalyzer.app.network.ApiService;
import com.networkanalyzer.app.network.RetrofitClient;
import com.networkanalyzer.app.network.models.TowerClusterDetailResponse;
import com.networkanalyzer.app.utils.PreferenceManager;

import java.util.Locale;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class TowerClusterDetailFragment extends Fragment {

    private FragmentTowerClusterDetailBinding binding;
    private ApiService apiService;
    private PreferenceManager preferenceManager;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentTowerClusterDetailBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        apiService = RetrofitClient.getInstance(requireContext()).getApiService();
        preferenceManager = new PreferenceManager(requireContext());
        Bundle args = getArguments();
        if (args == null) {
            return;
        }
        loadDetail(
                args.getString("cell_id", ""),
                args.getString("network_type", ""),
                args.getString("operator", "")
        );
    }

    private void loadDetail(String cellId, String networkType, String operator) {
        binding.progressBar.setVisibility(View.VISIBLE);
        apiService.getTowerClusterDetail(preferenceManager.getDeviceId(), cellId, networkType, operator, 5000)
                .enqueue(new Callback<TowerClusterDetailResponse>() {
                    @Override
                    public void onResponse(@NonNull Call<TowerClusterDetailResponse> call,
                                           @NonNull Response<TowerClusterDetailResponse> response) {
                        if (binding == null) return;
                        binding.progressBar.setVisibility(View.GONE);
                        if (!response.isSuccessful() || response.body() == null) {
                            return;
                        }
                        TowerClusterDetailResponse body = response.body();
                        binding.tvTowerDetailTitle.setText(String.format(Locale.US, "%s • CID %s", body.getNetworkType(), body.getCellId()));
                        binding.tvTowerDetailSubtitle.setText(String.format(Locale.US, "%s • %.0f dBm • %.0fm radius", body.getOperator(), body.getAvgSignalPower(), body.getEstimatedRadiusM()));
                        binding.tvTowerDetailStats.setText(String.format(Locale.US,
                                "Samples: %d\nAvg SNR: %s\nFirst seen: %s\nLast seen: %s",
                                body.getSampleCount(),
                                body.getAvgSnr() != null ? String.format(Locale.US, "%.1f dB", body.getAvgSnr()) : "--",
                                body.getFirstSeen(),
                                body.getLastSeen()));
                        StringBuilder builder = new StringBuilder();
                        if (body.getChannels() != null) {
                            builder.append("Channels\n");
                            for (TowerClusterDetailResponse.CountEntry item : body.getChannels()) {
                                builder.append(item.getLabel()).append(": ").append(item.getSeenCount()).append('\n');
                            }
                        }
                        if (body.getLacs() != null) {
                            builder.append("\nArea codes\n");
                            for (TowerClusterDetailResponse.CountEntry item : body.getLacs()) {
                                builder.append(item.getLabel()).append(": ").append(item.getSeenCount()).append('\n');
                            }
                        }
                        binding.tvTowerDetailBreakdown.setText(builder.toString().trim());
                    }

                    @Override
                    public void onFailure(@NonNull Call<TowerClusterDetailResponse> call, @NonNull Throwable t) {
                        if (binding != null) {
                            binding.progressBar.setVisibility(View.GONE);
                        }
                    }
                });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
