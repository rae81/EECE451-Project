package com.networkanalyzer.app.adapters;

import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.networkanalyzer.app.databinding.ItemNeighborCellBinding;
import com.networkanalyzer.app.models.CellDataEntry;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Compact RecyclerView adapter for displaying neighboring cell information.
 * <p>
 * Each item shows the cell ID, network type, signal power with a small bar
 * indicator, and frequency band. Designed for embedding inside the dashboard
 * fragment.
 */
public class NeighborCellAdapter extends RecyclerView.Adapter<NeighborCellAdapter.NeighborViewHolder> {

    // -- Signal thresholds (dBm) for bar indicator ----------------------------

    private static final int THRESHOLD_EXCELLENT = -65;
    private static final int THRESHOLD_GOOD = -75;
    private static final int THRESHOLD_FAIR = -85;
    private static final int THRESHOLD_POOR = -95;

    /** Minimum expected dBm value used for bar width calculation. */
    private static final int MIN_DBM = -120;
    /** Maximum expected dBm value used for bar width calculation. */
    private static final int MAX_DBM = -40;

    // -- Fields ---------------------------------------------------------------

    private List<CellDataEntry> cellList;

    // -- Constructors ---------------------------------------------------------

    /**
     * Creates an adapter with the provided list of neighboring cells.
     *
     * @param cellList initial list of neighboring cell entries
     */
    public NeighborCellAdapter(List<CellDataEntry> cellList) {
        this.cellList = cellList != null ? new ArrayList<>(cellList) : new ArrayList<>();
    }

    /**
     * Creates an adapter with an empty list.
     */
    public NeighborCellAdapter() {
        this.cellList = new ArrayList<>();
    }

    // -- Public methods -------------------------------------------------------

    /**
     * Replaces the data set and refreshes the RecyclerView.
     *
     * @param newList the new list of neighboring cells
     */
    public void updateData(List<CellDataEntry> newList) {
        this.cellList = newList != null ? new ArrayList<>(newList) : new ArrayList<>();
        notifyDataSetChanged();
    }

    // -- Adapter overrides ----------------------------------------------------

    @NonNull
    @Override
    public NeighborViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemNeighborCellBinding binding = ItemNeighborCellBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        return new NeighborViewHolder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull NeighborViewHolder holder, int position) {
        CellDataEntry entry = cellList.get(position);
        holder.bind(entry);
    }

    @Override
    public int getItemCount() {
        return cellList.size();
    }

    // =========================================================================
    //  ViewHolder
    // =========================================================================

    class NeighborViewHolder extends RecyclerView.ViewHolder {

        private final ItemNeighborCellBinding binding;

        NeighborViewHolder(@NonNull ItemNeighborCellBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        void bind(CellDataEntry entry) {
            // Cell ID
            String cellId = entry.getCellId();
            binding.tvNeighborCellId.setText(
                    cellId != null && !cellId.isEmpty()
                            ? String.format(Locale.US, "CID: %s", cellId)
                            : "CID: --");

            // Network type
            binding.tvNeighborType.setText(
                    entry.getNetworkType() != null ? entry.getNetworkType() : "--");

            // Signal power
            int signalPower = entry.getSignalPower();
            binding.tvNeighborSignal.setText(
                    String.format(Locale.US, "%d dBm", signalPower));

            // Signal bar indicator color and width
            int signalColor = getSignalColor(signalPower);
            binding.tvNeighborSignal.setTextColor(signalColor);
            binding.viewSignalBar.setBackgroundColor(signalColor);

            // Calculate bar width as a fraction of its maximum width
            float fraction = calculateBarFraction(signalPower);
            binding.viewSignalBar.post(() -> {
                ViewGroup.LayoutParams params = binding.viewSignalBar.getLayoutParams();
                int maxWidth = ((ViewGroup) binding.viewSignalBar.getParent()).getWidth();
                if (maxWidth > 0) {
                    params.width = Math.max(4, (int) (maxWidth * fraction));
                    binding.viewSignalBar.setLayoutParams(params);
                }
            });

            // Frequency band (appended to cell ID if available)
            String freq = entry.getFrequencyBand();
            if (freq != null && !freq.isEmpty() && !"--".equals(freq)) {
                String cidText = binding.tvNeighborCellId.getText().toString();
                binding.tvNeighborCellId.setText(cidText + " | " + freq);
            }
        }
    }

    // -- Utility methods ------------------------------------------------------

    /**
     * Maps a dBm value to a color for the signal bar indicator.
     */
    private int getSignalColor(int dBm) {
        if (dBm >= THRESHOLD_EXCELLENT) {
            return Color.parseColor("#4CAF50"); // green
        } else if (dBm >= THRESHOLD_GOOD) {
            return Color.parseColor("#8BC34A"); // yellow-green
        } else if (dBm >= THRESHOLD_FAIR) {
            return Color.parseColor("#FF9800"); // orange
        } else if (dBm >= THRESHOLD_POOR) {
            return Color.parseColor("#FF5722"); // deep orange
        } else {
            return Color.parseColor("#F44336"); // red
        }
    }

    /**
     * Calculates a 0.0 -- 1.0 fraction representing how strong the signal is
     * relative to the expected MIN/MAX dBm range.
     */
    private float calculateBarFraction(int dBm) {
        int clamped = Math.max(MIN_DBM, Math.min(MAX_DBM, dBm));
        return (float) (clamped - MIN_DBM) / (float) (MAX_DBM - MIN_DBM);
    }
}
