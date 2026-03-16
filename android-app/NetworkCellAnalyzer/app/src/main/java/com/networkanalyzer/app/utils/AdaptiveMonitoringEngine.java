package com.networkanalyzer.app.utils;

import androidx.annotation.NonNull;

import java.util.Deque;

public final class AdaptiveMonitoringEngine {

    private AdaptiveMonitoringEngine() {
    }

    public static long chooseIntervalMs(long baseIntervalMs,
                                        @NonNull Deque<Integer> recentSignals,
                                        int recentHandovers,
                                        int neighborCount) {
        long safeBase = Math.max(5_000L, baseIntervalMs);
        if (recentSignals.isEmpty()) {
            return safeBase;
        }

        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        Integer latestValue = recentSignals.peekLast();
        int latest = latestValue != null ? latestValue : -999;
        for (int signal : recentSignals) {
            min = Math.min(min, signal);
            max = Math.max(max, signal);
        }
        int spread = max - min;

        if (latest <= -100 || spread >= 14 || recentHandovers >= 2) {
            return 5_000L;
        }
        if (latest <= -92 || spread >= 8 || neighborCount >= 5) {
            return Math.min(safeBase, 8_000L);
        }
        if (latest >= -80 && spread <= 4 && recentHandovers == 0) {
            return Math.max(safeBase, 15_000L);
        }
        return safeBase;
    }
}
