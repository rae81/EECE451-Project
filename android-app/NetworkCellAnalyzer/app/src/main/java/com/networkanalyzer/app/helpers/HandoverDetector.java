package com.networkanalyzer.app.helpers;

import com.networkanalyzer.app.utils.Constants;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * Tracks cell tower handover events and detects anomalous patterns such as
 * "ping-pong" handovers where the device oscillates rapidly between two cells.
 * <p>
 * The detector maintains a bounded history of {@link HandoverEvent} objects.
 * Callers record new handovers via {@link #addHandover(String, String, String, long)}
 * and can query for recent events or ask whether a ping-pong pattern is occurring.
 * <p>
 * Thread-safe via {@code synchronized} methods -- the expected call frequency
 * (once every few seconds from the monitoring service) does not warrant a
 * lock-free structure.
 * <p>
 * Uses the singleton pattern so that the handover history survives activity
 * recreation and remains available to both the foreground service and UI
 * fragments.
 */
public final class HandoverDetector {

    private static final String TAG = "HandoverDetector";

    /** Maximum number of events retained in memory. */
    private static final int MAX_HISTORY_SIZE = 500;

    private static volatile HandoverDetector instance;

    private final List<HandoverEvent> handoverHistory;

    // -------------------------------------------------------------------------
    // Singleton
    // -------------------------------------------------------------------------

    private HandoverDetector() {
        handoverHistory = new ArrayList<>();
    }

    /**
     * Returns the singleton instance, creating it on first access.
     *
     * @return the shared {@code HandoverDetector} instance
     */
    public static HandoverDetector getInstance() {
        if (instance == null) {
            synchronized (HandoverDetector.class) {
                if (instance == null) {
                    instance = new HandoverDetector();
                }
            }
        }
        return instance;
    }

    // -------------------------------------------------------------------------
    // Recording
    // -------------------------------------------------------------------------

    /**
     * Records a handover event. If the history exceeds {@link #MAX_HISTORY_SIZE},
     * the oldest entries are removed.
     *
     * @param fromCellId  cell identity the device was connected to before the handover
     * @param toCellId    cell identity the device connected to after the handover
     * @param networkType network generation at the time of the handover ("2G" / "3G" / "4G" / "5G")
     * @param timestamp   epoch millisecond timestamp of the handover
     */
    public synchronized void addHandover(String fromCellId, String toCellId,
                                          String networkType, long timestamp) {
        HandoverEvent event = new HandoverEvent(fromCellId, toCellId, networkType, timestamp);
        handoverHistory.add(event);

        // Trim oldest entries if necessary.
        while (handoverHistory.size() > MAX_HISTORY_SIZE) {
            handoverHistory.remove(0);
        }
    }

    // -------------------------------------------------------------------------
    // Analysis
    // -------------------------------------------------------------------------

    /**
     * Detects a "ping-pong" handover pattern -- the device switching back and
     * forth between the same pair of cells within the configured time window.
     * <p>
     * The method examines handovers that occurred within the last
     * {@link Constants#HANDOVER_WINDOW_MS} milliseconds and counts how many
     * times the same ordered or reversed cell-pair appears. If the count meets
     * or exceeds {@link Constants#PING_PONG_THRESHOLD}, a ping-pong condition
     * is flagged.
     *
     * @return {@code true} if a ping-pong pattern is detected
     */
    public synchronized boolean detectPingPong() {
        long now = System.currentTimeMillis();
        long windowStart = now - Constants.HANDOVER_WINDOW_MS;

        // Collect events within the detection window.
        List<HandoverEvent> recentEvents = new ArrayList<>();
        for (HandoverEvent event : handoverHistory) {
            if (event.timestamp >= windowStart) {
                recentEvents.add(event);
            }
        }

        if (recentEvents.size() < Constants.PING_PONG_THRESHOLD) {
            return false;
        }

        // Count occurrences of each cell pair (order-insensitive).
        // A simple O(n^2) approach is fine given the small window sizes involved.
        for (int i = 0; i < recentEvents.size(); i++) {
            HandoverEvent base = recentEvents.get(i);
            int count = 0;

            for (int j = 0; j < recentEvents.size(); j++) {
                HandoverEvent other = recentEvents.get(j);
                if (isSamePair(base, other)) {
                    count++;
                }
            }

            if (count >= Constants.PING_PONG_THRESHOLD) {
                return true;
            }
        }

        return false;
    }

    /**
     * Checks whether two handover events involve the same cell pair, regardless
     * of direction (A-to-B is considered the same pair as B-to-A).
     */
    private boolean isSamePair(HandoverEvent a, HandoverEvent b) {
        if (a.fromCellId == null || a.toCellId == null
                || b.fromCellId == null || b.toCellId == null) {
            return false;
        }

        boolean forwardMatch = a.fromCellId.equals(b.fromCellId)
                && a.toCellId.equals(b.toCellId);
        boolean reverseMatch = a.fromCellId.equals(b.toCellId)
                && a.toCellId.equals(b.fromCellId);

        return forwardMatch || reverseMatch;
    }

    /**
     * Returns the number of handover events recorded since the given timestamp.
     *
     * @param sinceTimestamp epoch millisecond lower bound (inclusive)
     * @return count of handover events at or after {@code sinceTimestamp}
     */
    public synchronized int getHandoverCount(long sinceTimestamp) {
        int count = 0;
        for (HandoverEvent event : handoverHistory) {
            if (event.timestamp >= sinceTimestamp) {
                count++;
            }
        }
        return count;
    }

    /**
     * Returns the most recent handover events, ordered newest-first.
     *
     * @param limit maximum number of events to return
     * @return an unmodifiable list of the most recent events (may be smaller
     *         than {@code limit} if fewer events have been recorded)
     */
    public synchronized List<HandoverEvent> getRecentHandovers(int limit) {
        int size = handoverHistory.size();
        int fromIndex = Math.max(0, size - limit);
        List<HandoverEvent> recent = new ArrayList<>(
                handoverHistory.subList(fromIndex, size));
        Collections.reverse(recent);
        return Collections.unmodifiableList(recent);
    }

    /**
     * Clears all recorded handover history.
     */
    public synchronized void clearHistory() {
        handoverHistory.clear();
    }

    // -------------------------------------------------------------------------
    // Inner class
    // -------------------------------------------------------------------------

    /**
     * Immutable record of a single cell handover event.
     */
    public static class HandoverEvent {

        public final String fromCellId;
        public final String toCellId;
        public final String networkType;
        public final long timestamp;

        public HandoverEvent(String fromCellId, String toCellId,
                             String networkType, long timestamp) {
            this.fromCellId = fromCellId;
            this.toCellId = toCellId;
            this.networkType = networkType;
            this.timestamp = timestamp;
        }

        @Override
        public String toString() {
            return "HandoverEvent{" +
                    "from='" + fromCellId + '\'' +
                    ", to='" + toCellId + '\'' +
                    ", networkType='" + networkType + '\'' +
                    ", timestamp=" + timestamp +
                    '}';
        }
    }
}
