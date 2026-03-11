package com.networkanalyzer.app.database;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/**
 * Room entity representing a speed test result stored in the local database.
 * Maps to the "speed_tests" table.
 */
@Entity(tableName = "speed_tests")
public class SpeedTestEntity {

    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    private int id;

    @ColumnInfo(name = "device_id")
    private String deviceId;

    @ColumnInfo(name = "download_speed")
    private double downloadSpeed;

    @ColumnInfo(name = "upload_speed")
    private double uploadSpeed;

    @ColumnInfo(name = "latency")
    private int latency;

    @ColumnInfo(name = "network_type")
    private String networkType;

    @ColumnInfo(name = "operator")
    private String operator;

    @ColumnInfo(name = "timestamp")
    private long timestamp;

    @ColumnInfo(name = "synced")
    private boolean synced;

    /**
     * Default no-arg constructor required by Room.
     */
    public SpeedTestEntity() {
    }

    // -------------------------------------------------------------------------
    // Getters & Setters
    // -------------------------------------------------------------------------

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
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

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    public boolean isSynced() {
        return synced;
    }

    public void setSynced(boolean synced) {
        this.synced = synced;
    }
}
