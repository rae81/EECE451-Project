package com.networkanalyzer.app.network.models;

import com.google.gson.annotations.SerializedName;

import java.util.List;

public class TowerClustersResponse {

    @SerializedName("count")
    private int count;

    @SerializedName("clusters")
    private List<TowerCluster> clusters;

    public int getCount() {
        return count;
    }

    public List<TowerCluster> getClusters() {
        return clusters;
    }

    public static class TowerCluster {
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

        @SerializedName("channels")
        private List<String> channels;

        @SerializedName("lacs")
        private List<String> lacs;

        @SerializedName("first_seen")
        private String firstSeen;

        @SerializedName("last_seen")
        private String lastSeen;

        @SerializedName("estimated_radius_m")
        private double estimatedRadiusM;

        public String getCellId() {
            return cellId;
        }

        public String getNetworkType() {
            return networkType;
        }

        public String getOperator() {
            return operator;
        }

        public double getLatitude() {
            return latitude;
        }

        public double getLongitude() {
            return longitude;
        }

        public int getSampleCount() {
            return sampleCount;
        }

        public double getAvgSignalPower() {
            return avgSignalPower;
        }

        public Double getAvgSnr() {
            return avgSnr;
        }

        public List<String> getChannels() {
            return channels;
        }

        public List<String> getLacs() {
            return lacs;
        }

        public String getFirstSeen() {
            return firstSeen;
        }

        public String getLastSeen() {
            return lastSeen;
        }

        public double getEstimatedRadiusM() {
            return estimatedRadiusM;
        }
    }
}
