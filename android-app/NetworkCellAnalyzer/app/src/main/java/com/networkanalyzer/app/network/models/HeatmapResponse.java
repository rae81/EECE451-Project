package com.networkanalyzer.app.network.models;

import com.google.gson.annotations.SerializedName;

import java.util.List;

public class HeatmapResponse {

    @SerializedName("count")
    private int count;

    @SerializedName("points")
    private List<HeatmapPoint> points;

    public int getCount() {
        return count;
    }

    public void setCount(int count) {
        this.count = count;
    }

    public List<HeatmapPoint> getPoints() {
        return points;
    }

    public void setPoints(List<HeatmapPoint> points) {
        this.points = points;
    }

    public static class HeatmapPoint {
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

        @SerializedName("operators")
        private List<String> operators;

        @SerializedName("network_types")
        private List<String> networkTypes;

        @SerializedName("latest_timestamp")
        private String latestTimestamp;

        @SerializedName("heat_intensity")
        private double heatIntensity;

        @SerializedName("deadzone_risk")
        private Double deadzoneRisk;

        @SerializedName("deadzone_label")
        private String deadzoneLabel;

        @SerializedName("prediction_confidence")
        private Double predictionConfidence;

        @SerializedName("predicted_signal_power")
        private Double predictedSignalPower;

        @SerializedName("risk_reasons")
        private List<String> riskReasons;

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

        public List<String> getOperators() {
            return operators;
        }

        public List<String> getNetworkTypes() {
            return networkTypes;
        }

        public String getLatestTimestamp() {
            return latestTimestamp;
        }

        public double getHeatIntensity() {
            return heatIntensity;
        }

        public Double getDeadzoneRisk() {
            return deadzoneRisk;
        }

        public String getDeadzoneLabel() {
            return deadzoneLabel;
        }

        public Double getPredictionConfidence() {
            return predictionConfidence;
        }

        public Double getPredictedSignalPower() {
            return predictedSignalPower;
        }

        public List<String> getRiskReasons() {
            return riskReasons;
        }
    }
}
