package io.github.heavyseasmc.mod.ui;

/** Maps a server wall clock onto a local monotonic clock; local wall-clock changes do not move a deadline. */
public final class ServerClockEstimate {
    private static final long MAX_RTT = 5_000_000_000L;
    private static final long SAMPLE_LIFETIME = 120_000_000_000L;
    private boolean ready;
    private long serverAnchor;
    private long localAnchor;
    private long sequence = -1;
    private long bestRtt = Long.MAX_VALUE;

    public synchronized void reset() {
        ready = false;
        sequence = -1;
        bestRtt = Long.MAX_VALUE;
    }

    /** Initial one-way sample arrives before the first game projection. Later probes account for latency. */
    public synchronized void seed(long serverMillis, long receivedNanos) {
        if (!ready) {
            ready = true;
            serverAnchor = serverMillis;
            localAnchor = receivedNanos;
        }
    }

    public synchronized boolean sample(long seq, long sentNanos, long serverMillis, long receivedNanos) {
        long rtt = receivedNanos - sentNanos;
        if (seq <= sequence || rtt < 0 || rtt > MAX_RTT) {
            return false;
        }
        sequence = seq;
        if (ready && receivedNanos - localAnchor < SAMPLE_LIFETIME && bestRtt != Long.MAX_VALUE
                && rtt > bestRtt * 2 + 10_000_000L) {
            return false;
        }
        ready = true;
        serverAnchor = serverMillis + rtt / 2_000_000L;
        localAnchor = receivedNanos;
        bestRtt = rtt;
        return true;
    }

    public synchronized long now(long localNanos, long fallbackMillis) {
        return ready ? serverAnchor + Math.max(0, localNanos - localAnchor) / 1_000_000L : fallbackMillis;
    }

    public synchronized boolean ready() { return ready; }
}
