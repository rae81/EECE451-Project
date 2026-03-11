package com.networkanalyzer.app.models;

/**
 * Data model representing a single cell tower reading collected by the monitoring service.
 * <p>
 * Each instance captures the operator name, signal power (dBm), signal-to-noise ratio,
 * network generation, frequency band, cell identity, location area / tracking area code,
 * GPS coordinates, and the SIM slot from which the reading was taken.
 */
public class CellDataEntry {

    private String operator;
    private int signalPower;
    private double snr;
    private String networkType;
    private String frequencyBand;
    private String cellId;
    private String lac;
    private String mcc;
    private String mnc;
    private double latitude;
    private double longitude;
    private long timestamp;
    private int simSlot;
    private String signalQuality;

    /**
     * Default no-arg constructor.
     */
    public CellDataEntry() {
    }

    /**
     * Full constructor with every field.
     *
     * @param operator      network operator name (e.g. "T-Mobile")
     * @param signalPower   received signal power in dBm
     * @param snr           signal-to-noise ratio (SNR / SINR) in dB
     * @param networkType   network generation string ("2G", "3G", "4G", "5G", or "Unknown")
     * @param frequencyBand frequency channel number as a string (ARFCN / UARFCN / EARFCN / NRARFCN)
     * @param cellId        cell identity (CID / CI / NCI) as a string
     * @param lac           location area code or tracking area code as a string
     * @param latitude      GPS latitude at the time of the reading
     * @param longitude     GPS longitude at the time of the reading
     * @param timestamp     epoch millisecond timestamp of the reading
     * @param simSlot       SIM slot index (0-based)
     * @param signalQuality human-readable signal quality label
     */
    public CellDataEntry(String operator, int signalPower, double snr, String networkType,
                         String frequencyBand, String cellId, String lac,
                         double latitude, double longitude, long timestamp,
                         int simSlot, String signalQuality) {
        this.operator = operator;
        this.signalPower = signalPower;
        this.snr = snr;
        this.networkType = networkType;
        this.frequencyBand = frequencyBand;
        this.cellId = cellId;
        this.lac = lac;
        this.latitude = latitude;
        this.longitude = longitude;
        this.timestamp = timestamp;
        this.simSlot = simSlot;
        this.signalQuality = signalQuality;
    }

    // -------------------------------------------------------------------------
    // Getters & Setters
    // -------------------------------------------------------------------------

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

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    public int getSimSlot() {
        return simSlot;
    }

    public void setSimSlot(int simSlot) {
        this.simSlot = simSlot;
    }

    public String getSignalQuality() {
        return signalQuality;
    }

    public void setSignalQuality(String signalQuality) {
        this.signalQuality = signalQuality;
    }

    // -------------------------------------------------------------------------
    // Object overrides
    // -------------------------------------------------------------------------

    @Override
    public String toString() {
        return "CellDataEntry{" +
                "operator='" + operator + '\'' +
                ", signalPower=" + signalPower +
                ", snr=" + snr +
                ", networkType='" + networkType + '\'' +
                ", frequencyBand='" + frequencyBand + '\'' +
                ", cellId='" + cellId + '\'' +
                ", lac='" + lac + '\'' +
                ", mcc='" + mcc + '\'' +
                ", mnc='" + mnc + '\'' +
                ", latitude=" + latitude +
                ", longitude=" + longitude +
                ", timestamp=" + timestamp +
                ", simSlot=" + simSlot +
                ", signalQuality='" + signalQuality + '\'' +
                '}';
    }
}
