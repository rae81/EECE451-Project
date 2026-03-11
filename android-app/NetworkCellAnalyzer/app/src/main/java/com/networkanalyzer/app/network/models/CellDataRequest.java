package com.networkanalyzer.app.network.models;

import com.google.gson.annotations.SerializedName;

public class CellDataRequest {

    @SerializedName("device_id")
    private String deviceId;

    @SerializedName("operator")
    private String operator;

    @SerializedName("signal_power")
    private int signalPower;

    @SerializedName("snr")
    private double snr;

    @SerializedName("network_type")
    private String networkType;

    @SerializedName("frequency_band")
    private String frequencyBand;

    @SerializedName("cell_id")
    private String cellId;

    @SerializedName("latitude")
    private double latitude;

    @SerializedName("longitude")
    private double longitude;

    @SerializedName("lac")
    private String lac;

    @SerializedName("mcc")
    private String mcc;

    @SerializedName("mnc")
    private String mnc;

    @SerializedName("timestamp")
    private String timestamp;

    @SerializedName("ip_address")
    private String ipAddress;

    @SerializedName("mac_address")
    private String macAddress;

    @SerializedName("sim_slot")
    private int simSlot;

    public CellDataRequest() {
    }

    public CellDataRequest(String deviceId, String operator, int signalPower, double snr,
                           String networkType, String frequencyBand, String cellId,
                           double latitude, double longitude, String timestamp,
                           String ipAddress, String macAddress, int simSlot) {
        this.deviceId = deviceId;
        this.operator = operator;
        this.signalPower = signalPower;
        this.snr = snr;
        this.networkType = networkType;
        this.frequencyBand = frequencyBand;
        this.cellId = cellId;
        this.latitude = latitude;
        this.longitude = longitude;
        this.timestamp = timestamp;
        this.ipAddress = ipAddress;
        this.macAddress = macAddress;
        this.simSlot = simSlot;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(String deviceId) {
        this.deviceId = deviceId;
    }

    public String getOperator() {
        return operator;
    }

    public void setOperator(String operator) {
        this.operator = operator;
    }

    public int getSignalPower() {
        return signalPower;
    }

    public void setSignalPower(int signalPower) {
        this.signalPower = signalPower;
    }

    public double getSnr() {
        return snr;
    }

    public void setSnr(double snr) {
        this.snr = snr;
    }

    public String getNetworkType() {
        return networkType;
    }

    public void setNetworkType(String networkType) {
        this.networkType = networkType;
    }

    public String getFrequencyBand() {
        return frequencyBand;
    }

    public void setFrequencyBand(String frequencyBand) {
        this.frequencyBand = frequencyBand;
    }

    public String getCellId() {
        return cellId;
    }

    public void setCellId(String cellId) {
        this.cellId = cellId;
    }

    public double getLatitude() {
        return latitude;
    }

    public void setLatitude(double latitude) {
        this.latitude = latitude;
    }

    public double getLongitude() {
        return longitude;
    }

    public void setLongitude(double longitude) {
        this.longitude = longitude;
    }

    public String getLac() {
        return lac;
    }

    public void setLac(String lac) {
        this.lac = lac;
    }

    public String getMcc() {
        return mcc;
    }

    public void setMcc(String mcc) {
        this.mcc = mcc;
    }

    public String getMnc() {
        return mnc;
    }

    public void setMnc(String mnc) {
        this.mnc = mnc;
    }

    public String getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(String timestamp) {
        this.timestamp = timestamp;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public void setIpAddress(String ipAddress) {
        this.ipAddress = ipAddress;
    }

    public String getMacAddress() {
        return macAddress;
    }

    public void setMacAddress(String macAddress) {
        this.macAddress = macAddress;
    }

    public int getSimSlot() {
        return simSlot;
    }

    public void setSimSlot(int simSlot) {
        this.simSlot = simSlot;
    }
}
