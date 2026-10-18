package io.github.heavyseasmc.mod.audio;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * Thread-confined, short-lived rendezvous between live events and their projection.
 * This is not a playback queue: draining consumes an event even if playback rejects it.
 * No snapshot produces an event and no consumed event is retried.
 *
 * <p>Supply a nonnegative, nondecreasing millisecond clock to both operations. Call
 * {@link #clear()} on disconnect or resource reload, and before changing clock origins.
 * Epochs are opaque: an unknown epoch may be a future projection, whereas the last
 * 16 ended/replaced dimension-epoch pairs are explicitly rejected. Older retired
 * epochs cannot be recognized indefinitely with bounded history.
 */
public final class AudioInbox {
    public static final int MAX_EVENTS = 16;
    public static final long TTL_MS = 500;
    public static final int MAX_RETIRED_EPOCHS = 16;

    public record Event(String dimension, String epoch, long sequence, AudioCue cue, String speaker) {
        public Event {
            requireText(dimension, "dimension");
            requireText(epoch, "epoch");
            Objects.requireNonNull(cue, "cue");
            Objects.requireNonNull(speaker, "speaker");
            if (sequence < 0) {
                throw new IllegalArgumentException("sequence must be nonnegative");
            }
        }
    }

    private record Session(String dimension, String epoch) { }
    private record Pending(Event event, long offeredAtMs) { }

    private final ArrayDeque<Pending> pending = new ArrayDeque<>();
    private final LinkedHashSet<Session> retired = new LinkedHashSet<>();
    private Session current;
    private long lastSequence = -1;
    private long lastNowMs = -1;

    /**
     * Holds a live event for at most 500 ms, evicting the oldest pending event at capacity.
     * Retired epochs, already-consumed sequences, and pending duplicates are rejected
     * before eviction. Repeated offers never extend an event's original lifetime.
     */
    public void offer(Event event, long nowMs) {
        Objects.requireNonNull(event, "event");
        checkClock(nowMs);
        trim(nowMs);
        Session session = sessionOf(event);
        if (retired.contains(session) || (session.equals(current) && event.sequence() <= lastSequence)) {
            return;
        }
        for (Pending item : pending) {
            Event previous = item.event();
            if (sessionOf(previous).equals(session) && previous.sequence() == event.sequence()) {
                return;
            }
        }
        if (pending.size() == MAX_EVENTS) {
            pending.removeFirst();
        }
        pending.addLast(new Pending(event, nowMs));
    }

    /**
     * Returns matching live events in arrival order, once each, with strictly increasing
     * sequence numbers. Wrong dimensions and expired events are discarded; unknown
     * epochs in this dimension wait for their projection. The first sequence may be zero.
     *
     * <p>An inactive projection retires the last active epoch, but an initial inactive
     * projection does not discard unknown future epochs. {@code epoch} may be null when
     * inactive. A projection for a known retired epoch never resurrects its events.
     */
    public List<Event> drain(String dimension, String epoch, boolean active, long nowMs) {
        requireText(dimension, "dimension");
        if (active) {
            requireText(epoch, "epoch");
        }
        checkClock(nowMs);
        trim(nowMs);
        pending.removeIf(item -> !item.event().dimension().equals(dimension));

        if (!active) {
            retireCurrent();
            return List.of();
        }
        Session projected = new Session(dimension, epoch);
        if (retired.contains(projected)) {
            return List.of();
        }
        if (!projected.equals(current)) {
            retireCurrent();
            current = projected;
        }

        List<Event> events = new ArrayList<>();
        var iterator = pending.iterator();
        while (iterator.hasNext()) {
            Event event = iterator.next().event();
            if (sessionOf(event).equals(current)) {
                iterator.remove();
                if (event.sequence() > lastSequence) {
                    lastSequence = event.sequence();
                    events.add(event);
                }
            }
        }
        return List.copyOf(events);
    }

    /** Clears pending events, projection/sequence history, retired epochs, and clock state. */
    public void clear() {
        pending.clear();
        retired.clear();
        current = null;
        lastSequence = -1;
        lastNowMs = -1;
    }

    private void retireCurrent() {
        if (current != null) {
            Session ending = current;
            retired.add(ending);
            if (retired.size() > MAX_RETIRED_EPOCHS) {
                retired.removeFirst();
            }
            pending.removeIf(item -> sessionOf(item.event()).equals(ending));
        }
        current = null;
        lastSequence = -1;
    }

    private void trim(long nowMs) {
        // Nonnegative monotonic timestamps make subtraction safe even near Long.MAX_VALUE.
        pending.removeIf(item -> nowMs - item.offeredAtMs() >= TTL_MS);
    }

    private void checkClock(long nowMs) {
        if (nowMs < 0 || nowMs < lastNowMs) {
            throw new IllegalArgumentException("nowMs must be nonnegative and nondecreasing");
        }
        lastNowMs = nowMs;
    }

    private static Session sessionOf(Event event) {
        return new Session(event.dimension(), event.epoch());
    }

    private static void requireText(String text, String name) {
        Objects.requireNonNull(text, name);
        if (text.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}