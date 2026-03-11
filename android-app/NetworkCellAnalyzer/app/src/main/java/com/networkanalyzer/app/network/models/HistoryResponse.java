package com.networkanalyzer.app.network.models;

import com.google.gson.annotations.SerializedName;

import java.util.List;

public class HistoryResponse {

    @SerializedName("records")
    private List<HistoryRecord> records;

    @SerializedName("count")
    private int count;

    public List<HistoryRecord> getRecords() {
        return records;
    }

    public void setRecords(List<HistoryRecord> records) {
        this.records = records;
    }

    public int getCount() {
        return count;
    }

    public void setCount(int count) {
        this.count = count;
    }

    public static class HistoryRecord {
        @SerializedName("device_id")
        private String deviceId;

        @SerializedName("operator")
        private String operator;

        @SerializedName("signal_power")
        private int signalPower;

        @SerializedName("snr")
        private Double snr;

        @SerializedName("network_type")
        private String networkType;

        @SerializedName("frequency_band")
        private String frequencyBand;

        @SerializedName("cell_id")
        private String cellId;

        @SerializedName("lac")
        private String lac;

        @SerializedName("mcc")
        private String mcc;

        @SerializedName("mnc")
        private String mnc;

        @SerializedName("latitude")
        private Double latitude;

        @SerializedName("longitude")
        private Double longitude;

        @SerializedName("sim_slot")
        private Integer simSlot;

        @SerializedName("timestamp")
        private String timestamp;

        public String getDeviceId() {
            return deviceId;
        }

        public String getOperator() {
            return operator;
        }

        public int getSignalPower() {
            return signalPower;
        }

        public Double getSnr() {
            return snr;
        }

        public String getNetworkType() {
            return networkType;
        }

        public String getFrequencyBand() {
            return frequencyBand;
        }

        public String getCellId() {
            return cellId;
        }

        public String getLac() {
            return lac;
        }

        public String getMcc() {
            return mcc;
        }

        public String getMnc() {
            return mnc;
        }

        public Double getLatitude() {
            return latitude;
        }

        public Double getLongitude() {
            return longitude;
        }

        public Integer getSimSlot() {
            return simSlot;
        }

        public String getTimestamp() {
            return timestamp;
        }
    }
}
