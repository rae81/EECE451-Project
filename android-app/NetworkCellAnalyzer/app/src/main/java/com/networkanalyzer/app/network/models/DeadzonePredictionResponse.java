package com.networkanalyzer.app.network.models;

import com.google.gson.annotations.SerializedName;

import java.util.List;

public class DeadzonePredictionResponse {

    @SerializedName("confidence")
    private Double confidence;

    @SerializedName("deadzone_label")
    private String deadzoneLabel;

    @SerializedName("deadzone_risk")
    private Double deadzoneRisk;

    @SerializedName("model_source")
    private String modelSource;

    @SerializedName("predicted_quality")
    private String predictedQuality;

    @SerializedName("predicted_signal_power")
    private Double predictedSignalPower;

    @SerializedName("reasons")
    private List<String> reasons;

    public Double getConfidence() {
        return confidence;
    }

    public String getDeadzoneLabel() {
        return deadzoneLabel;
    }

    public Double getDeadzoneRisk() {
        return deadzoneRisk;
    }

    public String getModelSource() {
        return modelSource;
    }

    public String getPredictedQuality() {
        return predictedQuality;
    }

    public Double getPredictedSignalPower() {
        return predictedSignalPower;
    }

    public List<String> getReasons() {
        return reasons;
    }
}
