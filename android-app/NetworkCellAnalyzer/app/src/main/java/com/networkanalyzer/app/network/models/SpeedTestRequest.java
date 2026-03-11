package com.networkanalyzer.app.network.models;

import com.google.gson.annotations.SerializedName;

public class SpeedTestRequest {

    @SerializedName("device_id")
    private String deviceId;

    @SerializedName(value = "downloadMbps", alternate = {"download_mbps", "download_speed"})
    private double downloadSpeed;

    @SerializedName(value = "uploadMbps", alternate = {"upload_mbps", "upload_speed"})
    private double uploadSpeed;

    @SerializedName(value = "latencyMs", alternate = {"latency_ms", "latency"})
    private int latency;

    @SerializedName("network_type")
    private String networkType;

    @SerializedName("operator")
    private String operator;

    @SerializedName("signal_power")
    private Integer signalPower;

    @SerializedName("timestamp")
    private long timestamp;

    public SpeedTestRequest() {
    }

    public SpeedTestRequest(String deviceId, double downloadSpeed, double uploadSpeed,
                            int latency, String networkType, String operator,
                            Integer signalPower, long timestamp) {
        this.deviceId = deviceId;
        this.downloadSpeed = downloadSpeed;
        this.uploadSpeed = uploadSpeed;
        this.latency = latency;
        this.networkType = networkType;
        this.operator = operator;
        this.signalPower = signalPower;
        this.timestamp = timestamp;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(String deviceId) {
        this.deviceId = deviceId;
    }

    public double getDownloadSpeed() {
        return downloadSpeed;
    }

    public void setDownloadSpeed(double downloadSpeed) {
        this.downloadSpeed = downloadSpeed;
    }

    public double getUploadSpeed() {
        return uploadSpeed;
    }

    public void setUploadSpeed(double uploadSpeed) {
        this.uploadSpeed = uploadSpeed;
    }

    public int getLatency() {
        return latency;
    }

    public void setLatency(int latency) {
        this.latency = latency;
    }

    public String getNetworkType() {
        return networkType;
    }

    public void setNetworkType(String networkType) {
        this.networkType = networkType;
    }

    public String getOperator() {
        return operator;
    }

    public void setOperator(String operator) {
        this.operator = operator;
    }

    public Integer getSignalPower() {
        return signalPower;
    }

    public void setSignalPower(Integer signalPower) {
        this.signalPower = signalPower;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }
}
