package com.networkanalyzer.app.network.models;

import com.google.gson.annotations.SerializedName;

import java.util.List;

public class BatchCellDataRequest {

    @SerializedName("device_id")
    private String deviceId;

    @SerializedName("data")
    private List<CellDataRequest> data;

    public BatchCellDataRequest() {
    }

    public BatchCellDataRequest(String deviceId, List<CellDataRequest> data) {
        this.deviceId = deviceId;
        this.data = data;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(String deviceId) {
        this.deviceId = deviceId;
    }

    public List<CellDataRequest> getData() {
        return data;
    }

    public void setData(List<CellDataRequest> data) {
        this.data = data;
    }
}
