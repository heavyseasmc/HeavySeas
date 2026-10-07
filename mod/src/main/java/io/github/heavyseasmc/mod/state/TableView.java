package io.github.heavyseasmc.mod.state;

import io.github.heavyseasmc.engine.model.CharacterId;
import io.github.heavyseasmc.engine.play.Session;
import io.github.heavyseasmc.engine.state.Condition;
import net.minecraft.network.PacketByteBuf;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Public cards plus interventions available only to the packet recipient.
 *
 * <p>{@code window}：落海这一窗本来有多长（ADR-0099 D8）。时限可配之后，客户端不能再拿编译进去的常量画满格。
 */
public record TableView(List<Seat> seats, int token, long deadline, long window, List<String> swimmers,
                        List<Play> plays) {
    public static final TableView EMPTY = new TableView(List.of(), 0, 0, 0, List.of(), List.of());

    public TableView {
        seats = List.copyOf(seats);
        swimmers = List.copyOf(swimmers);
        plays = List.copyOf(plays);
    }

    /**
     * 一个座位。{@code occupant} 是坐在这一座的真人的 UUID（字符串），替身是空串 ——
     * 指人时客户端要知道准星前面那个玩家是哪个角色（ADR-0095 F2：看着谁就选谁），谁坐哪本来就是公开的。
     */
    public record Seat(String id, int health, int size, Condition condition, boolean offline,
                       boolean removed, List<String> front, String occupant) {
        public Seat {
            front = List.copyOf(front);
            occupant = occupant == null ? "" : occupant;
        }
    }

    public record Play(String card, String target) {
    }

    public static TableView of(Session session, Optional<CharacterId> recipient, long deadline) {
        return of(session, recipient, deadline, 0L, id -> "");
    }

    /** @param occupant 角色 → 坐在那一座的真人 UUID（字符串；替身返回空串） */
    public static TableView of(Session session, Optional<CharacterId> recipient, long deadline, long window,
                               java.util.function.Function<CharacterId, String> occupant) {
        if (session == null) {
            return EMPTY;
        }
        var g = session.state();
        List<Seat> seats = g.bySeat().stream().map(id -> new Seat(id.value(),
                g.roster().get(id).size() - g.stateOf(id).damage(), g.roster().get(id).size(),
                g.conditionOf(id), g.isOffline(id), g.isRemoved(id), g.stateOf(id).front(), occupant.apply(id))).toList();
        var prompt = session.overboardPending();
        List<Play> plays = deadline <= 0 ? List.of() : recipient.map(session::overboardPlays)
                .orElse(List.of()).stream().map(p -> new Play(p.card(), p.target().value())).toList();
        return new TableView(seats, prompt.map(Session.OverboardPrompt::token).orElse(0), deadline, window,
                prompt.map(p -> p.swimmers().stream().map(CharacterId::value).toList()).orElse(List.of()), plays);
    }

    public boolean overboardOpen() {
        return token > 0 && deadline > 0;
    }

    public void write(PacketByteBuf buf) {
        buf.writeVarInt(seats.size());
        for (Seat seat : seats) {
            buf.writeString(seat.id());
            buf.writeVarInt(seat.health());
            buf.writeVarInt(seat.size());
            buf.writeEnumConstant(seat.condition());
            buf.writeBoolean(seat.offline());
            buf.writeBoolean(seat.removed());
            buf.writeCollection(seat.front(), PacketByteBuf::writeString);
            buf.writeString(seat.occupant());
        }
        buf.writeVarInt(token);
        buf.writeVarLong(deadline);
        buf.writeVarLong(window);
        buf.writeCollection(swimmers, PacketByteBuf::writeString);
        buf.writeVarInt(plays.size());
        for (Play play : plays) {
            buf.writeString(play.card());
            buf.writeString(play.target());
        }
    }

    public static TableView read(PacketByteBuf buf) {
        int count = buf.readVarInt();
        List<Seat> seats = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            seats.add(new Seat(buf.readString(), buf.readVarInt(), buf.readVarInt(),
                    buf.readEnumConstant(Condition.class), buf.readBoolean(), buf.readBoolean(),
                    buf.readList(PacketByteBuf::readString), buf.readString()));
        }
        int token = buf.readVarInt();
        long deadline = buf.readVarLong();
        long window = buf.readVarLong();
        List<String> swimmers = buf.readList(PacketByteBuf::readString);
        int options = buf.readVarInt();
        List<Play> plays = new ArrayList<>();
        for (int i = 0; i < options; i++) {
            plays.add(new Play(buf.readString(), buf.readString()));
        }
        return new TableView(seats, token, deadline, window, swimmers, plays);
    }
}
