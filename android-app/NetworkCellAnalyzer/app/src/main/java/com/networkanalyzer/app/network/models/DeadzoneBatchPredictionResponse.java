package com.networkanalyzer.app.network.models;

import com.google.gson.annotations.SerializedName;

import java.util.List;

public class DeadzoneBatchPredictionResponse {

    @SerializedName("count")
    private int count;

    @SerializedName("predictions")
    private List<PredictionPoint> predictions;

    public int getCount() {
        return count;
    }

    public List<PredictionPoint> getPredictions() {
        return predictions;
    }

    public static class PredictionPoint {
        @SerializedName("latitude")
        private Double latitude;

        @SerializedName("longitude")
        private Double longitude;

        @SerializedName("deadzone_risk")
        private Double deadzoneRisk;

        @SerializedName("confidence")
        private Double confidence;

        @SerializedName("error")
        private String error;

        public Double getLatitude() {
            return latitude;
        }

        public Double getLongitude() {
            return longitude;
        }

        public Double getDeadzoneRisk() {
            return deadzoneRisk;
        }

        public Double getConfidence() {
            return confidence;
        }

        public String getError() {
            return error;
        }
    }
}
