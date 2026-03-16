package com.networkanalyzer.app.network.models;

import com.google.gson.annotations.SerializedName;

import java.util.List;

public class DiagnosticsHistoryResponse {
    @SerializedName("success")
    private boolean success;

    @SerializedName("device_id")
    private String deviceId;

    @SerializedName("history")
    private List<HistoryItem> history;

    public boolean isSuccess() { return success; }
    public String getDeviceId() { return deviceId; }
    public List<HistoryItem> getHistory() { return history; }

    public static class HistoryItem {
        @SerializedName("from_timestamp")
        private String fromTimestamp;
        @SerializedName("to_timestamp")
        private String toTimestamp;
        @SerializedName("reliability_score")
        private int reliabilityScore;
        @SerializedName("reliability_label")
        private String reliabilityLabel;
        @SerializedName("primary_issue")
        private String primaryIssue;
        @SerializedName("issue_count")
        private int issueCount;

        public String getFromTimestamp() { return fromTimestamp; }
        public String getToTimestamp() { return toTimestamp; }
        public int getReliabilityScore() { return reliabilityScore; }
        public String getReliabilityLabel() { return reliabilityLabel; }
        public String getPrimaryIssue() { return primaryIssue; }
        public int getIssueCount() { return issueCount; }
    }
}
