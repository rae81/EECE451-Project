package com.networkanalyzer.app.utils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.networkanalyzer.app.database.SpeedTestEntity;

import java.util.List;
import java.util.Locale;

/**
 * Centralizes field-facing scoring for signal, reliability, and task readiness.
 * The intent is explainable telecom UX, not opaque ML.
 */
public final class NetworkInsightEngine {

    private NetworkInsightEngine() {
    }

    public static final class ExperienceSnapshot {
        public final int reliabilityScore;
        @NonNull public final String reliabilityLabel;
        @NonNull public final String streamingLabel;
        @NonNull public final String callingLabel;
        @NonNull public final String gamingLabel;
        @NonNull public final String summary;

        public ExperienceSnapshot(int reliabilityScore,
                                  @NonNull String reliabilityLabel,
                                  @NonNull String streamingLabel,
                                  @NonNull String callingLabel,
                                  @NonNull String gamingLabel,
                                  @NonNull String summary) {
            this.reliabilityScore = reliabilityScore;
            this.reliabilityLabel = reliabilityLabel;
            this.streamingLabel = streamingLabel;
            this.callingLabel = callingLabel;
            this.gamingLabel = gamingLabel;
            this.summary = summary;
        }
    }

    @NonNull
    public static ExperienceSnapshot buildExperienceSnapshot(
            int signalPower,
            @Nullable SpeedTestEntity latestSpeedTest,
            int neighborCount,
            @NonNull List<Integer> recentSignals
    ) {
        int signalScore = scoreSignal(signalPower);
        int stabilityScore = scoreStability(recentSignals);
        int congestionPenalty = Math.min(12, Math.max(0, neighborCount - 3) * 2);

        int speedScore = latestSpeedTest != null ? scoreThroughput(latestSpeedTest) : 52;
        int latencyScore = latestSpeedTest != null ? scoreLatency(latestSpeedTest.getLatency()) : 50;

        int reliability = clamp((int) Math.round(
                signalScore * 0.42
                        + speedScore * 0.23
                        + latencyScore * 0.23
                        + stabilityScore * 0.12
                        - congestionPenalty
        ));

        String reliabilityLabel = labelForScore(reliability);
        String streaming = latestSpeedTest == null
                ? classifyStreamingFromSignal(signalPower)
                : classifyStreaming(latestSpeedTest.getDownloadSpeed(), latestSpeedTest.getLatency());
        String calling = latestSpeedTest == null
                ? classifyCallingFromSignal(signalPower)
                : classifyCalling(latestSpeedTest.getLatency(), signalPower);
        String gaming = latestSpeedTest == null
                ? classifyGamingFromSignal(signalPower)
                : classifyGaming(latestSpeedTest.getLatency(), latestSpeedTest.getDownloadSpeed(), signalPower);

        String summary = latestSpeedTest == null
                ? String.format(Locale.US, "Signal-led score with %d nearby competing cells.", neighborCount)
                : String.format(
                        Locale.US,
                        "Latest test %.1f/%.1f Mbps, %d ms latency, %d nearby cells.",
                        latestSpeedTest.getDownloadSpeed(),
                        latestSpeedTest.getUploadSpeed(),
                        latestSpeedTest.getLatency(),
                        neighborCount
                );

        return new ExperienceSnapshot(reliability, reliabilityLabel, streaming, calling, gaming, summary);
    }

    public static int scoreSignal(int signalPower) {
        if (signalPower >= -70) return 96;
        if (signalPower >= -80) return 84;
        if (signalPower >= -90) return 68;
        if (signalPower >= -100) return 48;
        if (signalPower >= -110) return 28;
        return 12;
    }

    public static int scoreLatency(int latencyMs) {
        if (latencyMs <= 35) return 96;
        if (latencyMs <= 60) return 84;
        if (latencyMs <= 100) return 70;
        if (latencyMs <= 150) return 52;
        if (latencyMs <= 220) return 34;
        return 16;
    }

    public static int scoreThroughput(@NonNull SpeedTestEntity latest) {
        double combined = latest.getDownloadSpeed() * 0.75 + latest.getUploadSpeed() * 0.25;
        if (combined >= 80) return 96;
        if (combined >= 40) return 86;
        if (combined >= 20) return 72;
        if (combined >= 8) return 56;
        if (combined >= 3) return 38;
        return 18;
    }

    public static int scoreStability(@NonNull List<Integer> recentSignals) {
        if (recentSignals.size() < 4) {
            return 58;
        }
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (int value : recentSignals) {
            min = Math.min(min, value);
            max = Math.max(max, value);
        }
        int spread = Math.max(0, max - min);
        if (spread <= 4) return 94;
        if (spread <= 8) return 82;
        if (spread <= 14) return 66;
        if (spread <= 20) return 48;
        return 28;
    }

    @NonNull
    public static String labelForScore(int score) {
        if (score >= 85) return "Field-ready";
        if (score >= 70) return "Stable";
        if (score >= 55) return "Usable";
        if (score >= 40) return "Fragile";
        return "Risky";
    }

    @NonNull
    public static String classifyStreaming(double downloadMbps, int latencyMs) {
        if (downloadMbps >= 10 && latencyMs <= 80) return "HD ready";
        if (downloadMbps >= 5 && latencyMs <= 130) return "SD ready";
        if (downloadMbps >= 2) return "Basic only";
        return "Poor";
    }

    @NonNull
    public static String classifyCalling(int latencyMs, int signalPower) {
        if (latencyMs <= 80 && signalPower >= -95) return "Call ready";
        if (latencyMs <= 140 && signalPower >= -105) return "Manageable";
        return "Risky";
    }

    @NonNull
    public static String classifyGaming(int latencyMs, double downloadMbps, int signalPower) {
        if (latencyMs <= 50 && downloadMbps >= 10 && signalPower >= -90) return "Competitive";
        if (latencyMs <= 90 && downloadMbps >= 5 && signalPower >= -100) return "Casual";
        return "Poor";
    }

    @NonNull
    private static String classifyStreamingFromSignal(int signalPower) {
        if (signalPower >= -82) return "Signal-strong";
        if (signalPower >= -95) return "Likely OK";
        return "Uncertain";
    }

    @NonNull
    private static String classifyCallingFromSignal(int signalPower) {
        if (signalPower >= -90) return "Likely ready";
        if (signalPower >= -102) return "Watch quality";
        return "Risky";
    }

    @NonNull
    private static String classifyGamingFromSignal(int signalPower) {
        if (signalPower >= -85) return "Low-latency likely";
        if (signalPower >= -98) return "Unverified";
        return "Poor";
    }

    public static int clamp(int score) {
        return Math.max(0, Math.min(100, score));
    }
}
