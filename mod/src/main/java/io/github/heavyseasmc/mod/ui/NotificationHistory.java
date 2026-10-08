package io.github.heavyseasmc.mod.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** 按对局身份与连续序号合并增量；同一帧内收到的每一包都先入历史，显示时不再追赶网络。 */
public final class NotificationHistory<T> {
    public static final int LIMIT = 400;
    private UUID epoch;
    private long sequence;
    private long missing;
    private List<T> entries = List.of();

    public NotificationHistory<T> copy() {
        NotificationHistory<T> copy = new NotificationHistory<>();
        copy.epoch = epoch;
        copy.sequence = sequence;
        copy.missing = missing;
        copy.entries = entries;
        return copy;
    }

    public void accept(UUID incomingEpoch, long first, long last, List<T> incoming) {
        Objects.requireNonNull(incomingEpoch, "epoch");
        if (first < 1 || last < 0 || first > last + 1 || last - first + 1 != incoming.size()
                || incoming.size() > LIMIT) {
            throw new IllegalArgumentException("Invalid notification sequence");
        }
        if (!incomingEpoch.equals(epoch)) {
            clear();
            epoch = incomingEpoch;
        }
        if (last < sequence) {
            return;
        }
        missing += Math.max(0, first - sequence - 1);
        int skip = (int) Math.min(incoming.size(), Math.max(0, sequence - first + 1));
        if (skip < incoming.size()) {
            List<T> combined = new ArrayList<>(entries);
            combined.addAll(incoming.subList(skip, incoming.size()));
            entries = List.copyOf(combined.subList(Math.max(0, combined.size() - LIMIT), combined.size()));
        }
        sequence = last;
    }

    public void clear() {
        epoch = null;
        sequence = 0;
        missing = 0;
        entries = List.of();
    }

    public List<T> entries() { return entries; }
    public UUID epoch() { return epoch; }
    public long sequence() { return sequence; }
    public long missing() { return missing; }
}
