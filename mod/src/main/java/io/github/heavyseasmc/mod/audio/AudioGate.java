package io.github.heavyseasmc.mod.audio;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Pure admission policy: a rejected request is forgotten, never queued or interrupted.
 * Callers supply a nondecreasing millisecond clock (the same clock as request expiry),
 * and must start playback only when {@link #admit(Request, long)} returns true.
 * Reset when changing clock origins or sessions. No wall clock or playback API is used.
 *
 * <p>Lanes have independent limits, except that nonblank dedupe keys are global.
 * Admission is first-come-first-served, not fair scheduling: busy voices are dropped,
 * and repeated urgent voices can starve ordinary voices. Priority only bypasses the
 * voice start cooldown, never an active voice, expiry, or dedupe.
 */
public final class AudioGate {
    public static final long EVENT_GLOBAL_COOLDOWN_MS = 120;
    public static final long EVENT_ID_COOLDOWN_MS = 500;
    public static final long UI_GLOBAL_COOLDOWN_MS = 75;
    public static final long UI_ID_COOLDOWN_MS = 120;
    public static final long ALERT_GLOBAL_COOLDOWN_MS = 300;
    public static final long AMBIENT_MIN_COOLDOWN_MS = 4_000;
    public static final long AMBIENT_PADDING_MS = 1_000;
    public static final long VOICE_COOLDOWN_MS = 8_000;
    public static final long VOICE_MIN_DURATION_MS = 200;
    public static final long VOICE_MAX_DURATION_MS = 30_000;
    public static final int VOICE_URGENT_PRIORITY = 90;
    public static final long DEDUPE_TTL_MS = 30_000;
    public static final int MAX_DEDUPE_KEYS = 256;

    public enum Lane { UI, ALERT, EVENT, VOICE, AMBIENT }

    /**
     * An immutable event. Expiry is exclusive: {@code expiresAtMs <= nowMs} is stale.
     * Null/blank dedupe keys disable dedupe. All durations must be positive; voice
     * busy time is clamped to 200..30,000 ms. Ambient duration must allow its 1s
     * padding to fit in a long. Other lanes do not use duration for rate limiting.
     */
    public record Request(String id, Lane lane, String dedupeKey, long expiresAtMs,
                          int priority, long durationMs) {
        public Request {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(lane, "lane");
            if (id.isBlank()) {
                throw new IllegalArgumentException("id must not be blank");
            }
            if (durationMs <= 0) {
                throw new IllegalArgumentException("durationMs must be positive");
            }
            if (lane == Lane.AMBIENT && durationMs > Long.MAX_VALUE - AMBIENT_PADDING_MS) {
                throw new IllegalArgumentException("ambient duration plus padding overflows");
            }
        }
    }

    private final Map<Lane, Long> lastStarts = new EnumMap<>(Lane.class);
    private final Map<Lane, Map<String, Window>> idWindows = new EnumMap<>(Lane.class);
    private final LinkedHashMap<String, Long> dedupe = new LinkedHashMap<>();
    private Window activeVoice;

    /** Returns true only for immediate admission; denied requests reserve no state. */
    public synchronized boolean admit(Request request, long nowMs) {
        Objects.requireNonNull(request, "request");
        if (request.expiresAtMs() <= nowMs) {
            return false;
        }

        dedupe.entrySet().removeIf(entry -> elapsed(nowMs, entry.getValue(), DEDUPE_TTL_MS));
        idWindows.values().forEach(windows ->
                windows.values().removeIf(window -> window.finished(nowMs)));
        String key = request.dedupeKey();
        boolean hasKey = key != null && !key.isBlank();
        if (hasKey && dedupe.containsKey(key)) {
            return false;
        }

        Lane lane = request.lane();
        boolean allowed = switch (lane) {
            case UI -> cooledDown(lane, nowMs, UI_GLOBAL_COOLDOWN_MS)
                    && idAvailable(request);
            case EVENT -> cooledDown(lane, nowMs, EVENT_GLOBAL_COOLDOWN_MS)
                    && idAvailable(request);
            case ALERT -> cooledDown(lane, nowMs, ALERT_GLOBAL_COOLDOWN_MS);
            case AMBIENT -> idAvailable(request);
            case VOICE -> !voiceBusy(nowMs)
                    && (request.priority() >= VOICE_URGENT_PRIORITY
                    || cooledDown(lane, nowMs, VOICE_COOLDOWN_MS));
        };
        if (!allowed) {
            return false;
        }

        lastStarts.put(lane, nowMs);
        switch (lane) {
            case UI -> rememberId(request, nowMs, UI_ID_COOLDOWN_MS);
            case EVENT -> rememberId(request, nowMs, EVENT_ID_COOLDOWN_MS);
            case AMBIENT -> rememberId(request, nowMs,
                    Math.max(AMBIENT_MIN_COOLDOWN_MS, request.durationMs() + AMBIENT_PADDING_MS));
            case VOICE -> activeVoice = new Window(nowMs,
                    Math.clamp(request.durationMs(), VOICE_MIN_DURATION_MS, VOICE_MAX_DURATION_MS));
            case ALERT -> { }
        }
        if (hasKey) {
            dedupe.put(key, nowMs);
            if (dedupe.size() > MAX_DEDUPE_KEYS) {
                // Insertion order, not access order: denied duplicates do not refresh TTL/age.
                dedupe.pollFirstEntry();
            }
        }
        return true;
    }

    /** Clears only the active voice; the last admitted start still governs cooldown. */
    public synchronized void voiceFinished() {
        activeVoice = null;
    }

    /** Busy state also expires without a playback callback, using the bounded duration. */
    public synchronized boolean voiceBusy(long nowMs) {
        if (activeVoice != null && activeVoice.finished(nowMs)) {
            activeVoice = null;
        }
        return activeVoice != null;
    }

    /** Clears every lane limit, active voice, and remembered dedupe key. */
    public synchronized void reset() {
        lastStarts.clear();
        idWindows.clear();
        dedupe.clear();
        activeVoice = null;
    }

    private boolean cooledDown(Lane lane, long nowMs, long intervalMs) {
        Long start = lastStarts.get(lane);
        return start == null || elapsed(nowMs, start, intervalMs);
    }

    private boolean idAvailable(Request request) {
        Map<String, Window> windows = idWindows.get(request.lane());
        return windows == null || !windows.containsKey(request.id());
    }

    private void rememberId(Request request, long nowMs, long intervalMs) {
        idWindows.computeIfAbsent(request.lane(), ignored -> new HashMap<>())
                .put(request.id(), new Window(nowMs, intervalMs));
    }

    private record Window(long startMs, long durationMs) {
        boolean finished(long nowMs) {
            return elapsed(nowMs, startMs, durationMs);
        }
    }

    private static boolean elapsed(long nowMs, long startMs, long intervalMs) {
        if (nowMs < startMs) {
            return false;
        }
        long delta = nowMs - startMs;
        // With now >= start, a negative subtraction means > Long.MAX_VALUE elapsed.
        return delta < 0 || delta >= intervalMs;
    }
}