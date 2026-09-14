package io.github.heavyseasmc.mod.state;

import io.github.heavyseasmc.engine.model.CharacterId;
import io.github.heavyseasmc.engine.model.Survivor;
import io.github.heavyseasmc.engine.play.Session;
import io.github.heavyseasmc.engine.state.Condition;
import io.github.heavyseasmc.engine.state.GameState;
import io.github.heavyseasmc.engine.state.Phase;
import io.github.heavyseasmc.mod.HeavySeasMod;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.network.ServerPlayerEntity;
import org.ladysnake.cca.api.v3.component.Component;
import org.ladysnake.cca.api.v3.component.sync.AutoSyncedComponent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 一个世界上进行中的对局。
 *
 * <h2>为什么挂在 World 而不是 Player（决策 ⑫ 的硬性约束）</h2>
 * 不是因为重生 —— 重生一行配置就能解决。真正的原因是<b>离线可达性</b>：决策 ⑧ 要求
 * 离线玩家的座位继续完整运转（照常口渴、可被抢、可被喂水、舵手权让位）。
 * Player 级组件依附 {@code ServerPlayerEntity}，玩家一离线实例就不存在，引擎取不到他的状态，
 * 游戏推不下去。这跟选哪个库无关。
 *
 * <h2>❗M1 不持久化，而且说出来</h2>
 * {@link Session} 里有牌堆顺序与全部角色状态，而 {@code GameState} 的构造函数是私有的 ——
 * 引擎目前<b>没有</b>从存档重建状态的入口，硬加一个就等于绕开「状态只能由合法转移产生」这条。
 * 那是决策 ⑧ 的活，不是 M1 的。
 *
 * <p>所以这里只存一个<b>墓碑</b>：存档时若有对局在进行，记下它进行到第几回合。
 * 下次加载时对局是空的，但日志会明说「上一局没有保存」——
 * <b>让「重启丢了一局」与「本来就没开局」在输出上分得开</b>，这是本仓库反复付过学费的那一条。
 */
public final class GameComponent implements Component, AutoSyncedComponent {

    private static final Logger LOGGER = LoggerFactory.getLogger(HeavySeasMod.MOD_ID);

    private static final String KEY_INTERRUPTED_TURN = "interrupted_turn";

    private Session session;

    /** 座位归谁：角色 id → 占位者。座位顺序由角色决定，与谁来占无关。 */
    private final Map<CharacterId, Occupant> occupants = new LinkedHashMap<>();

    /** 上一次存档时被打断的对局进行到第几回合；0 表示没有。仅用于提示，不用于恢复。 */
    private int interruptedTurn;

    /**
     * 开局前预定给 dummy 的角色（{@code /seas dummy add}）。
     *
     * <p>决策 ② 把它定为**硬性前置**而不是「强烈建议」：人数下限提到 6 之后，
     * 开发期不可能每改一行就凑 6 个真人开 6 个客户端。这不是 AI，是测试夹具。
     */
    private final Set<CharacterId> pendingDummies = new LinkedHashSet<>();

    public Set<CharacterId> pendingDummies() {
        return Set.copyOf(pendingDummies);
    }

    public boolean reserveForDummy(CharacterId id) {
        return pendingDummies.add(id);
    }

    public void clearPendingDummies() {
        pendingDummies.clear();
    }

    /** 客户端侧的投影。服务端不读它。 */
    private HudView view = HudView.IDLE;

    /** 客户端 HUD 的数据源。服务端上它永远是 {@link HudView#IDLE}。 */
    public HudView hudView() {
        return view;
    }

    /**
     * 只发给局内玩家。
     *
     * <p>决策 ⑫ 记着这一条「白送的好处」：它一次解决三件事 —— 防作弊、省带宽、
     * 不会把没装模组的旁人踢下线（CCA 6.0+ 默认会踢，对我们自动消失）。
     */
    @Override
    public boolean shouldSyncWith(ServerPlayerEntity player) {
        return session != null && seatOf(player.getUuid()).isPresent();
    }

    /** ❗按收件人裁剪：每个人只拿到自己那一份。手牌与爱恨将来走的是同一条路。 */
    @Override
    public void writeSyncPacket(RegistryByteBuf buf, ServerPlayerEntity recipient) {
        buf.writeBoolean(session != null);
        if (session == null) {
            return;
        }
        GameState g = session.state();
        buf.writeVarInt(g.turn());
        buf.writeEnumConstant(g.phase());
        buf.writeVarInt(g.gulls());
        Optional<CharacterId> seat = seatOf(recipient.getUuid());
        buf.writeBoolean(seat.isPresent());
        if (seat.isEmpty()) {
            return;
        }
        CharacterId id = seat.get();
        Survivor survivor = g.roster().get(id);
        buf.writeString(id.value());
        buf.writeVarInt(survivor.size() - g.stateOf(id).damage());
        buf.writeVarInt(survivor.size());
        buf.writeEnumConstant(g.conditionOf(id));
        buf.writeVarInt(g.stateOf(id).thirst().count());
        buf.writeBoolean(g.nextActor().map(id::equals).orElse(false));
    }

    @Override
    public void applySyncPacket(RegistryByteBuf buf) {
        if (!buf.readBoolean()) {
            view = HudView.IDLE;
            return;
        }
        int turn = buf.readVarInt();
        Phase phase = buf.readEnumConstant(Phase.class);
        int gulls = buf.readVarInt();
        if (!buf.readBoolean()) {
            view = new HudView(true, turn, phase, gulls, false, "", 0, 0, Condition.CONSCIOUS, 0, false);
            return;
        }
        String character = buf.readString();
        int health = buf.readVarInt();
        int maxHealth = buf.readVarInt();
        Condition condition = buf.readEnumConstant(Condition.class);
        int thirst = buf.readVarInt();
        boolean yourTurn = buf.readBoolean();
        view = new HudView(true, turn, phase, gulls, true, character, health, maxHealth,
                condition, thirst, yourTurn);
    }

    public Optional<Session> session() {
        return Optional.ofNullable(session);
    }

    /** 取进行中的对局，没有就抛 —— 指令层负责先问 {@link #session()}，别让空值漂进规则里。 */
    public Session requireSession() {
        if (session == null) {
            throw new IllegalStateException("当前世界没有进行中的对局");
        }
        return session;
    }

    public void begin(Session started, Map<CharacterId, Occupant> seats) {
        this.session = started;
        this.occupants.clear();
        this.occupants.putAll(seats);
        this.interruptedTurn = 0;
        this.pendingDummies.clear();
    }

    public void end() {
        this.session = null;
        this.occupants.clear();
    }

    public Map<CharacterId, Occupant> occupants() {
        return Map.copyOf(occupants);
    }

    public Optional<Occupant> occupantOf(CharacterId id) {
        return Optional.ofNullable(occupants.get(id));
    }

    /** 这个玩家占着哪个角色。一个玩家最多占一个座位。 */
    public Optional<CharacterId> seatOf(UUID player) {
        return occupants.entrySet().stream()
                .filter(e -> player.equals(e.getValue().player()))
                .map(Map.Entry::getKey)
                .findFirst();
    }

    /** 上一次存档打断在第几回合；0 表示上次关服时没有对局。 */
    public int interruptedTurn() {
        return interruptedTurn;
    }

    @Override
    public void readFromNbt(NbtCompound tag, RegistryWrapper.WrapperLookup registryLookup) {
        interruptedTurn = tag.getInt(KEY_INTERRUPTED_TURN);
        if (interruptedTurn > 0) {
            LOGGER.warn("上一局没有保存（存档时进行到第 {} 回合）—— M1 不持久化对局，"
                    + "用 /seas start 重开一局。", interruptedTurn);
        }
    }

    @Override
    public void writeToNbt(NbtCompound tag, RegistryWrapper.WrapperLookup registryLookup) {
        // 存的是墓碑不是状态：让「重启丢了一局」与「本来就没开局」在下次加载时分得开。
        int turn = session == null ? 0 : session.state().turn();
        tag.putInt(KEY_INTERRUPTED_TURN, turn);
        if (turn > 0) {
            LOGGER.warn("存档时有一局进行到第 {} 回合，**不会被保存** —— M1 不持久化对局。", turn);
        }
    }

    /**
     * 座位上坐着谁。
     *
     * @param player 真人的 UUID；{@code null} 表示这是个 dummy
     * @param label  显示用的名字
     */
    public record Occupant(UUID player, String label) {

        public boolean isDummy() {
            return player == null;
        }
    }
}
