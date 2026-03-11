package com.networkanalyzer.app.network.models;

import com.google.gson.annotations.SerializedName;

import java.util.LinkedHashMap;
import java.util.Map;

public class StatsResponse {

    @SerializedName("success")
    private boolean success;

    @SerializedName("operator_time")
    private Map<String, Double> operatorTime;

    @SerializedName("network_type_time")
    private Map<String, Double> networkTypeTime;

    @SerializedName("avg_signal_per_type")
    private Map<String, Double> avgSignalPerType;

    @SerializedName("avg_signal_per_device")
    private Map<String, Double> avgSignalPerDevice;

    @SerializedName("avg_snr_per_type")
    private Map<String, Double> avgSnrPerType;

    @SerializedName("total_records")
    private int totalRecords;

    @SerializedName("from_date")
    private String fromDate;

    @SerializedName("to_date")
    private String toDate;

    public StatsResponse() {
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public Map<String, Double> getOperatorTime() {
        return operatorTime;
    }

    public void setOperatorTime(Map<String, Double> operatorTime) {
        this.operatorTime = operatorTime;
    }

    public Map<String, Double> getNetworkTypeTime() {
        return networkTypeTime;
    }

    public void setNetworkTypeTime(Map<String, Double> networkTypeTime) {
        this.networkTypeTime = networkTypeTime;
    }

    public Map<String, Double> getAvgSignalPerType() {
        return avgSignalPerType;
    }

    public void setAvgSignalPerType(Map<String, Double> avgSignalPerType) {
        this.avgSignalPerType = avgSignalPerType;
    }

    public Map<String, Double> getAvgSignalPerDevice() {
        return avgSignalPerDevice;
    }

    public void setAvgSignalPerDevice(Map<String, Double> avgSignalPerDevice) {
        this.avgSignalPerDevice = avgSignalPerDevice;
    }

    public Map<String, Double> getAvgSnrPerType() {
        return avgSnrPerType;
    }

    public void setAvgSnrPerType(Map<String, Double> avgSnrPerType) {
        this.avgSnrPerType = avgSnrPerType;
    }

    public int getTotalRecords() {
        return totalRecords;
    }

    public void setTotalRecords(int totalRecords) {
        this.totalRecords = totalRecords;
    }

    public String getFromDate() {
        return fromDate;
    }

    public void setFromDate(String fromDate) {
        this.fromDate = fromDate;
    }

    public String getToDate() {
        return toDate;
    }

    public void setToDate(String toDate) {
        this.toDate = toDate;
    }

    public Map<String, Float> getConnectivityByOperator() {
        return toFloatMap(operatorTime);
    }

    public Map<String, Float> getConnectivityByNetworkType() {
        return toFloatMap(networkTypeTime);
    }

    public Map<String, Float> getAvgSignalPowerByNetworkType() {
        return toFloatMap(avgSignalPerType);
    }

    public Map<String, Float> getAvgSnrByNetworkType() {
        return toFloatMap(avgSnrPerType);
    }

    private Map<String, Float> toFloatMap(Map<String, Double> source) {
        if (source == null) {
            return null;
        }
        Map<String, Float> converted = new LinkedHashMap<>();
        for (Map.Entry<String, Double> entry : source.entrySet()) {
            Double value = entry.getValue();
            converted.put(entry.getKey(), value != null ? value.floatValue() : 0f);
        }
        return converted;
    }
}
