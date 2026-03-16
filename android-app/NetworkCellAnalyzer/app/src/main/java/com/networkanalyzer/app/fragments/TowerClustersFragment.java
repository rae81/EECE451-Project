package com.networkanalyzer.app.fragments;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.navigation.Navigation;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.networkanalyzer.app.R;
import com.networkanalyzer.app.databinding.FragmentTowerClustersBinding;
import com.networkanalyzer.app.network.ApiService;
import com.networkanalyzer.app.network.RetrofitClient;
import com.networkanalyzer.app.network.models.TowerClustersResponse;
import com.networkanalyzer.app.utils.PreferenceManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class TowerClustersFragment extends Fragment {

    private FragmentTowerClustersBinding binding;
    private PreferenceManager preferenceManager;
    private ApiService apiService;
    private final TowerClusterAdapter adapter = new TowerClusterAdapter();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentTowerClustersBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        preferenceManager = new PreferenceManager(requireContext());
        apiService = RetrofitClient.getInstance(requireContext()).getApiService();
        binding.recyclerTowerClusters.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.recyclerTowerClusters.setAdapter(adapter);
        loadClusters();
    }

    private void loadClusters() {
        binding.progressBar.setVisibility(View.VISIBLE);
        apiService.getTowerClusters(preferenceManager.getDeviceId(), null, 100)
                .enqueue(new Callback<TowerClustersResponse>() {
                    @Override
                    public void onResponse(@NonNull Call<TowerClustersResponse> call,
                                           @NonNull Response<TowerClustersResponse> response) {
                        if (binding == null) return;
                        binding.progressBar.setVisibility(View.GONE);
                        if (!response.isSuccessful() || response.body() == null || response.body().getClusters() == null) {
                            binding.tvTowerClustersEmpty.setVisibility(View.VISIBLE);
                            return;
                        }
                        List<TowerClustersResponse.TowerCluster> clusters = response.body().getClusters();
                        binding.tvTowerClustersEmpty.setVisibility(clusters.isEmpty() ? View.VISIBLE : View.GONE);
                        adapter.submit(clusters);
                    }

                    @Override
                    public void onFailure(@NonNull Call<TowerClustersResponse> call, @NonNull Throwable t) {
                        if (binding == null) return;
                        binding.progressBar.setVisibility(View.GONE);
                        binding.tvTowerClustersEmpty.setVisibility(View.VISIBLE);
                    }
                });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }

    private class TowerClusterAdapter extends RecyclerView.Adapter<TowerClusterAdapter.VH> {
        private List<TowerClustersResponse.TowerCluster> items = new ArrayList<>();

        void submit(List<TowerClustersResponse.TowerCluster> clusters) {
            items = clusters != null ? clusters : new ArrayList<>();
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_tower_cluster, parent, false);
            return new VH(view);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            TowerClustersResponse.TowerCluster item = items.get(position);
            holder.title.setText(String.format(Locale.US, "%s • CID %s", item.getNetworkType(), item.getCellId()));
            holder.subtitle.setText(String.format(Locale.US, "%s • %d samples • %.0f dBm", item.getOperator(), item.getSampleCount(), item.getAvgSignalPower()));
            holder.meta.setText(String.format(Locale.US, "Radius %.0fm • %s", item.getEstimatedRadiusM(), item.getLastSeen()));
            holder.itemView.setOnClickListener(v -> {
                Bundle args = new Bundle();
                args.putString("cell_id", item.getCellId());
                args.putString("network_type", item.getNetworkType());
                args.putString("operator", item.getOperator());
                Navigation.findNavController(v).navigate(R.id.towerClusterDetailFragment, args);
            });
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        class VH extends RecyclerView.ViewHolder {
            final TextView title;
            final TextView subtitle;
            final TextView meta;

            VH(@NonNull View itemView) {
                super(itemView);
                title = itemView.findViewById(R.id.tvTowerClusterTitle);
                subtitle = itemView.findViewById(R.id.tvTowerClusterSubtitle);
                meta = itemView.findViewById(R.id.tvTowerClusterMeta);
            }
        }
    }
}
