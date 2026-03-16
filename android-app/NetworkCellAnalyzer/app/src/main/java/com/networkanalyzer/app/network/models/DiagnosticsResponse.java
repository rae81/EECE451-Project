package com.networkanalyzer.app.network.models;

import com.google.gson.annotations.SerializedName;

import java.util.List;

public class DiagnosticsResponse {

    @SerializedName("success")
    private boolean success;

    @SerializedName("device_id")
    private String deviceId;

    @SerializedName("reliability_score")
    private int reliabilityScore;

    @SerializedName("reliability_label")
    private String reliabilityLabel;

    @SerializedName("recommended_interval_seconds")
    private int recommendedIntervalSeconds;

    @SerializedName("adaptive_mode")
    private String adaptiveMode;

    @SerializedName("adaptive_label")
    private String adaptiveLabel;

    @SerializedName("issues")
    private List<DiagnosticIssue> issues;

    @SerializedName("summary")
    private Summary summary;

    public boolean isSuccess() {
        return success;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public int getReliabilityScore() {
        return reliabilityScore;
    }

    public String getReliabilityLabel() {
        return reliabilityLabel;
    }

    public int getRecommendedIntervalSeconds() {
        return recommendedIntervalSeconds;
    }

    public String getAdaptiveMode() {
        return adaptiveMode;
    }

    public String getAdaptiveLabel() {
        return adaptiveLabel;
    }

    public List<DiagnosticIssue> getIssues() {
        return issues;
    }

    public Summary getSummary() {
        return summary;
    }

    public static class DiagnosticIssue {
        @SerializedName("code")
        private String code;

        @SerializedName("title")
        private String title;

        @SerializedName("severity")
        private String severity;

        @SerializedName("evidence")
        private String evidence;

        @SerializedName("suggestion")
        private String suggestion;

        public String getCode() {
            return code;
        }

        public String getTitle() {
            return title;
        }

        public String getSeverity() {
            return severity;
        }

        public String getEvidence() {
            return evidence;
        }

        public String getSuggestion() {
            return suggestion;
        }
    }

    public static class Summary {
        @SerializedName("avg_signal_power")
        private Double avgSignalPower;

        @SerializedName("avg_snr")
        private Double avgSnr;

        @SerializedName("avg_download_mbps")
        private Double avgDownloadMbps;

        @SerializedName("avg_upload_mbps")
        private Double avgUploadMbps;

        @SerializedName("avg_latency_ms")
        private Double avgLatencyMs;

        @SerializedName("handover_count")
        private int handoverCount;

        @SerializedName("handover_rate_per_100")
        private double handoverRatePer100;

        @SerializedName("ping_pong_count")
        private int pingPongCount;

        @SerializedName("neighbor_cluster_count")
        private int neighborClusterCount;

        public Double getAvgSignalPower() {
            return avgSignalPower;
        }

        public Double getAvgSnr() {
            return avgSnr;
        }

        public Double getAvgDownloadMbps() {
            return avgDownloadMbps;
        }

        public Double getAvgUploadMbps() {
            return avgUploadMbps;
        }

        public Double getAvgLatencyMs() {
            return avgLatencyMs;
        }

        public int getHandoverCount() {
            return handoverCount;
        }

        public double getHandoverRatePer100() {
            return handoverRatePer100;
        }

        public int getPingPongCount() {
            return pingPongCount;
        }

        public int getNeighborClusterCount() {
            return neighborClusterCount;
        }
    }
}
