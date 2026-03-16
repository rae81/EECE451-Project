package com.networkanalyzer.app.network.models;

import com.google.gson.annotations.SerializedName;

import java.util.List;

public class TowerClusterDetailResponse {

    @SerializedName("cell_id")
    private String cellId;
    @SerializedName("network_type")
    private String networkType;
    @SerializedName("operator")
    private String operator;
    @SerializedName("latitude")
    private double latitude;
    @SerializedName("longitude")
    private double longitude;
    @SerializedName("sample_count")
    private int sampleCount;
    @SerializedName("avg_signal_power")
    private double avgSignalPower;
    @SerializedName("avg_snr")
    private Double avgSnr;
    @SerializedName("estimated_radius_m")
    private double estimatedRadiusM;
    @SerializedName("first_seen")
    private String firstSeen;
    @SerializedName("last_seen")
    private String lastSeen;
    @SerializedName("channels")
    private List<CountEntry> channels;
    @SerializedName("lacs")
    private List<CountEntry> lacs;
    @SerializedName("top_hours")
    private List<CountEntry> topHours;
    @SerializedName("recent_samples")
    private List<RecentSample> recentSamples;

    public String getCellId() { return cellId; }
    public String getNetworkType() { return networkType; }
    public String getOperator() { return operator; }
    public double getLatitude() { return latitude; }
    public double getLongitude() { return longitude; }
    public int getSampleCount() { return sampleCount; }
    public double getAvgSignalPower() { return avgSignalPower; }
    public Double getAvgSnr() { return avgSnr; }
    public double getEstimatedRadiusM() { return estimatedRadiusM; }
    public String getFirstSeen() { return firstSeen; }
    public String getLastSeen() { return lastSeen; }
    public List<CountEntry> getChannels() { return channels; }
    public List<CountEntry> getLacs() { return lacs; }
    public List<CountEntry> getTopHours() { return topHours; }
    public List<RecentSample> getRecentSamples() { return recentSamples; }

    public static class CountEntry {
        @SerializedName(value = "channel", alternate = {"lac", "hour"})
        private String label;
        @SerializedName("seen_count")
        private int seenCount;

        public String getLabel() { return label; }
        public int getSeenCount() { return seenCount; }
    }

    public static class RecentSample {
        @SerializedName("timestamp")
        private String timestamp;
        @SerializedName("signal_power")
        private int signalPower;
        @SerializedName("snr")
        private Double snr;
        @SerializedName("latitude")
        private Double latitude;
        @SerializedName("longitude")
        private Double longitude;
        @SerializedName("frequency_band")
        private String frequencyBand;
        @SerializedName("lac")
        private String lac;

        public String getTimestamp() { return timestamp; }
        public int getSignalPower() { return signalPower; }
        public Double getSnr() { return snr; }
        public Double getLatitude() { return latitude; }
        public Double getLongitude() { return longitude; }
        public String getFrequencyBand() { return frequencyBand; }
        public String getLac() { return lac; }
    }
}
