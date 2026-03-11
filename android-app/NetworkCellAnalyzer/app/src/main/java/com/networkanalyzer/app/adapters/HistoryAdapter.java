package com.networkanalyzer.app.adapters;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.networkanalyzer.app.database.CellDataEntity;
import com.networkanalyzer.app.databinding.ItemHistoryBinding;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * RecyclerView adapter for displaying cell data history entries.
 * <p>
 * Each item shows a formatted timestamp, operator name, network type badge,
 * color-coded signal power, SNR value, and cell ID.
 */
public class HistoryAdapter extends RecyclerView.Adapter<HistoryAdapter.HistoryViewHolder> {

    // -- Signal color thresholds (dBm) ----------------------------------------

    private static final int THRESHOLD_EXCELLENT = -65;
    private static final int THRESHOLD_GOOD = -75;
    private static final int THRESHOLD_FAIR = -85;
    private static final double QUALITY_UNAVAILABLE = -999.0;

    private static final int COLOR_GREEN = Color.parseColor("#4CAF50");
    private static final int COLOR_YELLOW = Color.parseColor("#FFEB3B");
    private static final int COLOR_ORANGE = Color.parseColor("#FF9800");
    private static final int COLOR_RED = Color.parseColor("#F44336");

    // -- Network type badge colors --------------------------------------------

    private static final int BADGE_5G = Color.parseColor("#9C27B0");  // purple
    private static final int BADGE_4G = Color.parseColor("#2196F3");  // blue
    private static final int BADGE_3G = Color.parseColor("#4CAF50");  // green
    private static final int BADGE_2G = Color.parseColor("#FF9800");  // orange
    private static final int BADGE_UNKNOWN = Color.parseColor("#9E9E9E"); // grey

    // -- Fields ---------------------------------------------------------------

    private List<CellDataEntity> dataList;
    private OnItemClickListener listener;
    private final SimpleDateFormat dateFormat =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);

    // -- Constructor ----------------------------------------------------------

    public HistoryAdapter(List<CellDataEntity> dataList) {
        this.dataList = dataList != null ? new ArrayList<>(dataList) : new ArrayList<>();
    }

    // -- Listener interface ---------------------------------------------------

    /**
     * Callback interface for click and long-click events on history items.
     */
    public interface OnItemClickListener {
        void onItemClick(CellDataEntity item, int position);

        void onItemLongClick(CellDataEntity item, int position);
    }

    /**
     * Sets the click listener for this adapter.
     *
     * @param listener the listener to receive click events
     */
    public void setOnItemClickListener(OnItemClickListener listener) {
        this.listener = listener;
    }

    // -- Adapter overrides ----------------------------------------------------

    @NonNull
    @Override
    public HistoryViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemHistoryBinding binding = ItemHistoryBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        return new HistoryViewHolder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull HistoryViewHolder holder, int position) {
        CellDataEntity item = dataList.get(position);
        holder.bind(item, position);
    }

    @Override
    public int getItemCount() {
        return dataList.size();
    }

    // -- Public helper methods ------------------------------------------------

    /**
     * Replaces the entire data set and refreshes the list.
     *
     * @param newData the new list of entities to display
     */
    public void updateData(List<CellDataEntity> newData) {
        this.dataList = newData != null ? new ArrayList<>(newData) : new ArrayList<>();
        notifyDataSetChanged();
    }

    /**
     * Appends additional entries to the current list and notifies the adapter.
     *
     * @param additionalData entries to append
     */
    public void addData(List<CellDataEntity> additionalData) {
        if (additionalData == null || additionalData.isEmpty()) {
            return;
        }
        int startPos = dataList.size();
        dataList.addAll(additionalData);
        notifyItemRangeInserted(startPos, additionalData.size());
    }

    /**
     * Removes a single item at the given position with animation.
     *
     * @param position the adapter position to remove
     */
    public void removeItem(int position) {
        if (position >= 0 && position < dataList.size()) {
            dataList.remove(position);
            notifyItemRemoved(position);
            notifyItemRangeChanged(position, dataList.size());
        }
    }

    /**
     * Returns the entity at the given position.
     *
     * @param position the adapter position
     * @return the {@link CellDataEntity} at that position
     */
    public CellDataEntity getItem(int position) {
        if (position >= 0 && position < dataList.size()) {
            return dataList.get(position);
        }
        return null;
    }

    // =========================================================================
    //  ViewHolder
    // =========================================================================

    class HistoryViewHolder extends RecyclerView.ViewHolder {

        private final ItemHistoryBinding binding;

        HistoryViewHolder(@NonNull ItemHistoryBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        void bind(CellDataEntity item, int position) {
            // Formatted timestamp
            String formattedTime = dateFormat.format(new Date(item.getTimestamp()));
            binding.tvTimestamp.setText(formattedTime);

            // Operator
            binding.tvOperator.setText(
                    item.getOperator() != null ? item.getOperator() : "--");

            // Network type badge
            binding.tvNetworkType.setText(
                    item.getNetworkType() != null ? item.getNetworkType() : "--");
            int badgeColor = getNetworkBadgeColor(item.getNetworkType());
            GradientDrawable badgeBg = new GradientDrawable();
            badgeBg.setCornerRadius(16f);
            badgeBg.setColor(badgeColor);
            binding.tvNetworkType.setBackground(badgeBg);
            binding.tvNetworkType.setTextColor(Color.WHITE);

            // Signal power with color indicator
            int signalPower = item.getSignalPower();
            binding.tvSignalPower.setText(
                    toLatinDigits(String.format(Locale.US, "Signal Power: %d dBm", signalPower)));
            int signalColor = getSignalColor(signalPower);
            binding.tvSignalPower.setTextColor(signalColor);
            binding.viewSignalIndicator.setBackgroundColor(signalColor);

            // Radio quality
            binding.tvSnr.setText(
                    item.getSnr() <= QUALITY_UNAVAILABLE
                            ? "Radio Quality: --"
                            : toLatinDigits(String.format(Locale.US, "Radio Quality: %.1f dB", item.getSnr())));

            // Cell ID
            binding.tvCellId.setText(
                    toLatinDigits(String.format(Locale.US, "Cell ID: %s",
                            item.getCellId() != null ? item.getCellId() : "--")));

            // Click listeners
            itemView.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onItemClick(item, position);
                }
            });

            itemView.setOnLongClickListener(v -> {
                if (listener != null) {
                    listener.onItemLongClick(item, position);
                    return true;
                }
                return false;
            });
        }
    }

    // -- Utility methods ------------------------------------------------------

    /**
     * Returns the signal color based on dBm thresholds.
     * <ul>
     *   <li>Green: signal &lt;= -65 dBm (i.e. stronger or equal to -65)</li>
     *   <li>Yellow: signal &lt;= -75 dBm</li>
     *   <li>Orange: signal &lt;= -85 dBm</li>
     *   <li>Red: signal &gt; -85 dBm (i.e. weaker than -85)</li>
     * </ul>
     * Note: In dBm, a higher (less negative) value is stronger. The thresholds
     * here follow the convention that &ge; -65 is excellent, &ge; -75 is good, etc.
     */
    private int getSignalColor(int dBm) {
        if (dBm >= THRESHOLD_EXCELLENT) {
            return COLOR_GREEN;
        } else if (dBm >= THRESHOLD_GOOD) {
            return COLOR_YELLOW;
        } else if (dBm >= THRESHOLD_FAIR) {
            return COLOR_ORANGE;
        } else {
            return COLOR_RED;
        }
    }

    /**
     * Returns the badge background color for a given network type string.
     */
    private int getNetworkBadgeColor(String networkType) {
        if (networkType == null) {
            return BADGE_UNKNOWN;
        }
        switch (networkType) {
            case "5G":
                return BADGE_5G;
            case "4G":
            case "LTE":
                return BADGE_4G;
            case "3G":
            case "WCDMA":
            case "HSPA":
                return BADGE_3G;
            case "2G":
            case "GSM":
            case "EDGE":
                return BADGE_2G;
            default:
                return BADGE_UNKNOWN;
        }
    }

    private String toLatinDigits(@NonNull String input) {
        return input
                .replace('\u0660', '0')
                .replace('\u0661', '1')
                .replace('\u0662', '2')
                .replace('\u0663', '3')
                .replace('\u0664', '4')
                .replace('\u0665', '5')
                .replace('\u0666', '6')
                .replace('\u0667', '7')
                .replace('\u0668', '8')
                .replace('\u0669', '9')
                .replace('\u06F0', '0')
                .replace('\u06F1', '1')
                .replace('\u06F2', '2')
                .replace('\u06F3', '3')
                .replace('\u06F4', '4')
                .replace('\u06F5', '5')
                .replace('\u06F6', '6')
                .replace('\u06F7', '7')
                .replace('\u06F8', '8')
                .replace('\u06F9', '9');
    }
}
