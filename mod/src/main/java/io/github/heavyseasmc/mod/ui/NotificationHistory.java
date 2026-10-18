package io.github.heavyseasmc.mod.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.ToLongFunction;

/**
 * 按对局身份与序号合并增量；同一帧内收到的每一包都先入历史，显示时不再追赶网络。
 *
 * <p>序号是全局的（这一局播过的每一条都有一个），而每个人只收得到给他看的那几条 —— 私信只发给本人。
 * 所以一包里的序号可以有空档：{@code [first, last]} 是这一包<b>覆盖</b>的范围，空档是别人的私信，不是丢包。
 * 「缺了几条」只按覆盖范围之间的断口算（{@link #missing}），不按条数之差算。
 */
public final class NotificationHistory<T> {
    public static final int LIMIT = 400;
    /**
     * 没有对局时的局号。服务端发的局号是随机 UUID（版本 4），不会是全 0，所以它与任何一局都不相等。
     *
     * <p>❗局号永远有值：此前清空时写成 null，客户端组件跟着取了这个值，读局号的人（{@code OverboardCue}）当场崩 ——
     * 2026-10-13 真机两次，靠岸收场回到主世界、以及服务端不重启时重连。「没有对局」是一个确定的局号，不是没有局号。
     */
    public static final UUID NO_EPOCH = new UUID(0L, 0L);
    private UUID epoch = NO_EPOCH;
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

    /** 连续的一段：第 i 条的序号就是 {@code first + i}。 */
    public void accept(UUID incomingEpoch, long first, long last, List<T> incoming) {
        if (last - first + 1 != incoming.size()) {
            throw new IllegalArgumentException("Invalid notification sequence");
        }
        List<Long> seqs = new ArrayList<>(incoming.size());
        for (int i = 0; i < incoming.size(); i++) {
            seqs.add(first + i);
        }
        accept(incomingEpoch, first, last, incoming, seqs);
    }

    /** 带空档的一段：每条自带序号，必须落在 {@code [first, last]} 里、严格递增。 */
    public void accept(UUID incomingEpoch, long first, long last, List<T> incoming, ToLongFunction<T> seqOf) {
        List<Long> seqs = new ArrayList<>(incoming.size());
        for (T entry : incoming) {
            seqs.add(seqOf.applyAsLong(entry));
        }
        accept(incomingEpoch, first, last, incoming, seqs);
    }

    private void accept(UUID incomingEpoch, long first, long last, List<T> incoming, List<Long> seqs) {
        Objects.requireNonNull(incomingEpoch, "epoch");
        if (first < 1 || last < 0 || first > last + 1 || incoming.size() > LIMIT
                || incoming.size() > last - first + 1) {
            throw new IllegalArgumentException("Invalid notification sequence");
        }
        long previous = first - 1;
        for (long seq : seqs) {
            if (seq <= previous || seq > last) {
                throw new IllegalArgumentException("Invalid notification sequence");
            }
            previous = seq;
        }
        if (!incomingEpoch.equals(epoch)) {
            clear();
            epoch = incomingEpoch;
        }
        if (last < sequence) {
            return;
        }
        missing += Math.max(0, first - sequence - 1);
        List<T> combined = new ArrayList<>(entries);
        boolean added = false;
        for (int i = 0; i < incoming.size(); i++) {
            if (seqs.get(i) > sequence) {
                combined.add(incoming.get(i));
                added = true;
            }
        }
        if (added) {
            entries = List.copyOf(combined.subList(Math.max(0, combined.size() - LIMIT), combined.size()));
        }
        sequence = last;
    }

    public void clear() {
        epoch = NO_EPOCH;
        sequence = 0;
        missing = 0;
        entries = List.of();
    }

    public List<T> entries() { return entries; }
    public UUID epoch() { return epoch; }
    public long sequence() { return sequence; }
    public long missing() { return missing; }
}
