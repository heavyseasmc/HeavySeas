package io.github.heavyseasmc.mod.state;

import io.github.heavyseasmc.engine.model.CharacterId;
import io.github.heavyseasmc.engine.play.Session;
import io.github.heavyseasmc.mod.HeavySeasMod;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryWrapper;
import org.ladysnake.cca.api.v3.component.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
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
public final class GameComponent implements Component {

    private static final Logger LOGGER = LoggerFactory.getLogger(HeavySeasMod.MOD_ID);

    private static final String KEY_INTERRUPTED_TURN = "interrupted_turn";

    private Session session;

    /** 座位归谁：角色 id → 占位者。座位顺序由角色决定，与谁来占无关。 */
    private final Map<CharacterId, Occupant> occupants = new LinkedHashMap<>();

    /** 上一次存档时被打断的对局进行到第几回合；0 表示没有。仅用于提示，不用于恢复。 */
    private int interruptedTurn;

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
