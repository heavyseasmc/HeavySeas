package io.github.heavyseasmc.mod.state;

import io.github.heavyseasmc.engine.model.CharacterId;
import io.github.heavyseasmc.engine.model.ProvisionEffect;
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
                        List<Play> plays, List<String> passDrowns) {
    public static final TableView EMPTY = new TableView(List.of(), 0, 0, 0, List.of(), List.of(), List.of());

    /** 不带「不出牌时谁会淹死」的老写法（夹具用）。 */
    public TableView(List<Seat> seats, int token, long deadline, long window, List<String> swimmers, List<Play> plays) {
        this(seats, token, deadline, window, swimmers, plays, List.of());
    }

    public TableView {
        seats = List.copyOf(seats);
        swimmers = List.copyOf(swimmers);
        plays = List.copyOf(plays);
        passDrowns = List.copyOf(passDrowns);
    }

    /**
     * 一个座位。{@code occupant} 是坐在这一座的真人的 UUID（字符串），替身是空串 ——
     * 指人时客户端要知道准星前面那个玩家是哪个角色（ADR-0095 F2：看着谁就选谁），谁坐哪本来就是公开的。
     */
    /**
     * @param strength 打起架来的力气：体型 + 今天喝的酒（含陪酒女蹭到的），与打架结算同一个函数（{@code Session#fightingSize}）· 公开
     * @param survival 生存分（印在角色卡上）· 公开 —— 座位签上「你爱的这个人活到最后能得几分」要用
     * @param today    今天背上的记号，按 {@link Today} 的次序（划过船 · 打过架 · 喝过酒）· 公开（规则书第五章：别人看得见你做过这些）
     * @param stealsUncontested 这个人抢东西只拿手牌、对方不能拒绝（小孩的本事，{@code Session#stealsUncontested}）· 公开 ——
     *                 行动一面「抢」那张签按它换说法，客户端不写死是哪个角色
     */
    public record Seat(String id, int health, int size, Condition condition, boolean offline,
                       boolean removed, List<String> front, String occupant, int strength, int survival,
                       List<Today> today, boolean stealsUncontested) {
        public Seat {
            front = List.copyOf(front);
            occupant = occupant == null ? "" : occupant;
            today = List.copyOf(today);
        }

        public Seat(String id, int health, int size, Condition condition, boolean offline,
                    boolean removed, List<String> front, String occupant) {
            this(id, health, size, condition, offline, removed, front, occupant, size, 0, List.of(), false);
        }
    }

    /** 今天做过、会让航海牌点到口渴的三件事（引擎 {@code ThirstSource} 里公开的那三种；天候与点名不是人做的事）。 */
    public enum Today {
        ROWED, FOUGHT, DRANK;

        static List<Today> of(java.util.Set<io.github.heavyseasmc.engine.thirst.ThirstSource> sources) {
            List<Today> out = new ArrayList<>();
            if (sources.contains(io.github.heavyseasmc.engine.thirst.ThirstSource.ROWED)) out.add(ROWED);
            if (sources.contains(io.github.heavyseasmc.engine.thirst.ThirstSource.FOUGHT)) out.add(FOUGHT);
            if (sources.contains(io.github.heavyseasmc.engine.thirst.ThirstSource.DRANK_RUM)) out.add(DRANK);
            return out;
        }
    }

    /** What the player affects; {@link Play#target()} remains the engine's intervention target. */
    public enum Scope {
        SINGLE,
        ALL_SWIMMERS
    }

    /**
     * @param extraDamage the batch's total extra water damage after this play, including earlier interventions
     * @param drowns      这样出的话这一批里谁会淹死（{@code Session#overboardDrownsIf}，ADR-0043 §11 第五组）· 只进出牌的人那一包
     */
    public record Play(String card, String target, Scope scope, int extraDamage, List<String> drowns) {
        public Play {
            drowns = List.copyOf(drowns);
        }

        public Play(String card, String target, Scope scope, int extraDamage) {
            this(card, target, scope, extraDamage, List.of());
        }

        public Play(String card, String target) {
            this(card, target, Scope.SINGLE, 0);
        }
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
                g.conditionOf(id), g.isOffline(id), g.isRemoved(id), g.stateOf(id).front(), occupant.apply(id),
                session.fightingSize(id), g.roster().get(id).survival(), Today.of(g.stateOf(id).thirst().sources()),
                session.stealsUncontested(id))).toList();
        var prompt = session.overboardPending();
        List<Play> plays = deadline <= 0 ? List.of() : recipient.map(session::overboardPlays)
                .orElse(List.of()).stream().map(p -> {
                    ProvisionEffect effect = session.provisions().get(p.card()).effect();
                    List<String> drowns = names(session.overboardDrownsIf(recipient.orElseThrow(), Optional.of(p)));
                    if (effect instanceof ProvisionEffect.DamageInWater) {
                        int after = session.overboardExtraDamageAfter(recipient.orElseThrow(), p);
                        return new Play(p.card(), p.target().value(), Scope.ALL_SWIMMERS, after, drowns);
                    }
                    return new Play(p.card(), p.target().value(), Scope.SINGLE, 0, drowns);
                }).toList();
        // 不出牌时谁会淹死：只给这一窗里有牌可出的人（别人那一面上没有这个选择）
        List<String> pass = plays.isEmpty() ? List.of() : names(session.overboardDrownsIf(recipient.orElseThrow(), Optional.empty()));
        return new TableView(seats, prompt.map(Session.OverboardPrompt::token).orElse(0), deadline, window,
                prompt.map(p -> p.swimmers().stream().map(CharacterId::value).toList()).orElse(List.of()), plays, pass);
    }

    private static List<String> names(List<CharacterId> ids) {
        return ids.stream().map(CharacterId::value).toList();
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
            buf.writeVarInt(seat.strength());
            buf.writeVarInt(seat.survival());
            buf.writeCollection(seat.today(), PacketByteBuf::writeEnumConstant);
            buf.writeBoolean(seat.stealsUncontested());
        }
        buf.writeVarInt(token);
        buf.writeVarLong(deadline);
        buf.writeVarLong(window);
        buf.writeCollection(swimmers, PacketByteBuf::writeString);
        buf.writeVarInt(plays.size());
        for (Play play : plays) {
            buf.writeString(play.card());
            buf.writeString(play.target());
            buf.writeEnumConstant(play.scope());
            buf.writeVarInt(play.extraDamage());
            buf.writeCollection(play.drowns(), PacketByteBuf::writeString);
        }
        buf.writeCollection(passDrowns, PacketByteBuf::writeString);
    }

    public static TableView read(PacketByteBuf buf) {
        int count = buf.readVarInt();
        List<Seat> seats = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            seats.add(new Seat(buf.readString(), buf.readVarInt(), buf.readVarInt(),
                    buf.readEnumConstant(Condition.class), buf.readBoolean(), buf.readBoolean(),
                    buf.readList(PacketByteBuf::readString), buf.readString(), buf.readVarInt(), buf.readVarInt(),
                    buf.readList(b -> b.readEnumConstant(Today.class)), buf.readBoolean()));
        }
        int token = buf.readVarInt();
        long deadline = buf.readVarLong();
        long window = buf.readVarLong();
        List<String> swimmers = buf.readList(PacketByteBuf::readString);
        int options = buf.readVarInt();
        List<Play> plays = new ArrayList<>();
        for (int i = 0; i < options; i++) {
            plays.add(new Play(buf.readString(), buf.readString(), buf.readEnumConstant(Scope.class), buf.readVarInt(),
                    buf.readList(PacketByteBuf::readString)));
        }
        return new TableView(seats, token, deadline, window, swimmers, plays, buf.readList(PacketByteBuf::readString));
    }
}
