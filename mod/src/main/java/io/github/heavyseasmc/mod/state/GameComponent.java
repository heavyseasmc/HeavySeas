package io.github.heavyseasmc.mod.state;

import io.github.heavyseasmc.engine.model.CharacterId;
import io.github.heavyseasmc.engine.model.Survivor;
import io.github.heavyseasmc.engine.navigation.NavigationCard;
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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
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
     * 刚结束的那一局里坐着的真人。结束那一帧只推给他们。
     *
     * <p>❗{@link #shouldSyncWith} 原先只认「有对局且有座位」—— 于是 {@code end()} 之后那一次 sync
     * 一个人都发不到，客户端一直挂着最后一帧：2026-09-15 实拍，{@code /seas end} 之后行动一面照样开着、
     * HUD 照样说「轮到你行动」；下一局一开，行动一面的「刚轮到你」也因此没弹。
     * 不报错、不掉线，只是客户端以为那一局还在。
     */
    private final Set<UUID> endedFor = new LinkedHashSet<>();

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
        if (session == null) {
            return endedFor.contains(player.getUuid());   // 结束那一帧（「没有对局」）也得送到
        }
        return seatOf(player.getUuid()).isPresent();
    }

    /**
     * 收尾哨兵。写在每一条分支的最后，读到的第一件事就是核对它。
     *
     * <h2>它防的是一种不会报错的坏法</h2>
     * 写 5 个字段、读 4 个，缓冲区从此错位：后面每个字段都读到<b>上一个字段的字节</b>，
     * 于是回合数变成天文数字、枚举下标越界、字符串长度荒唐。表现随机，
     * 而<b>哪一条都不会指向真正的原因</b>（两边字段表不一致）。
     *
     * <p>加一个哨兵之后，这类错位一定在这里当场变成一句点名的报错。
     * 这是本仓库那条老教训的同一形态：让「漏了」与「对了」在输出上分得开。
     */
    private static final int SYNC_END = 0x53_45_41_53;

    /** ❗按收件人裁剪：每个人只拿到自己那一份。手牌、划船抽到的牌、舵手看的划船堆走的都是这条路。 */
    @Override
    public void writeSyncPacket(RegistryByteBuf buf, ServerPlayerEntity recipient) {
        writeView(buf, recipient);
        buf.writeInt(SYNC_END);           // ❗必须是最后一笔，且每条分支都经过这里
    }

    private void writeView(RegistryByteBuf buf, ServerPlayerEntity recipient) {
        buf.writeBoolean(session != null);
        if (session == null) {
            return;
        }
        GameState g = session.state();
        buf.writeVarInt(g.turn());
        buf.writeEnumConstant(g.phase());
        buf.writeVarInt(g.gulls());
        // 座位轨：谁坐哪、轮到谁。**公开信息**，每人一份照发 —— 等别人行动时全船看的就是它。
        buf.writeVarInt(g.bySeat().size());
        for (CharacterId s : g.bySeat()) {
            buf.writeString(s.value());
        }
        buf.writeString(g.phase() == Phase.ACTION ? g.nextActor().map(CharacterId::value).orElse("") : "");
        // 航海这一段的公开部分（决策 ⑭）：划船堆几张、舵手是谁、挑牌还剩多久、执行的是哪一张。
        // ❗划船堆里是什么牌不在这里 —— 那一项下面只写给舵手。
        buf.writeVarInt(session.table().rowStack().size());
        buf.writeString(g.helmsman().map(CharacterId::value).orElse(""));
        buf.writeVarLong(helmDeadline);
        Optional<NavigationCard> revealed = session.navigatedThisTurn();
        buf.writeBoolean(revealed.isPresent());
        revealed.ifPresent(card -> NavCardView.of(card).write(buf));
        // 口渴这一段是**公开**的：全船都看得见「轮到医生决定喝不喝水、还剩几秒」。
        // ❗看不见的是他手上有几张水 —— 那在他自己那一包里（手牌），不在这里。
        Optional<Session.ThirstPrompt> thirst = g.phase() == Phase.NAVIGATION
                ? session.thirstPending() : Optional.empty();
        buf.writeBoolean(thirst.isPresent());
        if (thirst.isPresent()) {
            Session.ThirstPrompt prompt = thirst.get();
            buf.writeString(prompt.who().value());
            buf.writeVarInt(prompt.effective().count());
            buf.writeVarInt(prompt.covered());
            buf.writeVarInt(prompt.shared());
            buf.writeVarInt(prompt.remaining());
            buf.writeVarLong(thirstDeadline);
        }

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
        // ❗nextActor 只看「能行动」与「本回合还没行动过」，不看阶段 —— 行动阶段以外它照样可能指向某一位。
        //   HUD 的「轮到你行动」与行动一面的自动弹出都认这一位，所以阶段要在这里一起判。
        // ❗划船抽到的牌还没定完时，nextActor 仍然是他（行动要等两张都定了才算完），但他该看的是划船一面，不是行动一面。
        buf.writeBoolean(g.phase() == Phase.ACTION && g.nextActor().map(id::equals).orElse(false)
                && session.rower().isEmpty());
        // 手牌只写这一份 —— 别人的包里没有这些字节，不是「发了再藏」。
        List<String> hand = g.stateOf(id).hand();
        buf.writeVarInt(hand.size());
        for (String card : hand) {
            buf.writeString(card);
        }
        // ❗面前那一区是**公开**的（亮出来就是给全船看的），但这一版只写收件人自己那份：
        //   别人的「面前」要等头顶信息条（决策 ⑥）才有地方显示，现在发了也没人读。
        //   记在 CURRENT_STATUS 的开放项里，不在这里偷偷发。
        List<String> front = g.stateOf(id).front();
        buf.writeVarInt(front.size());
        for (String card : front) {
            buf.writeString(card);
            buf.writeBoolean(g.stateOf(id).isOpen(card));
        }
        // 划船抽到的牌只写给划船者本人：只有他知道自己放了什么（决策 ⑭ 的三层信息）。
        List<Session.RowCard> rowing = session.rower().map(id::equals).orElse(false) ? session.rowing() : List.of();
        buf.writeVarInt(rowing.size());
        for (Session.RowCard row : rowing) {
            NavCardView.of(row.card()).write(buf);
            buf.writeEnumConstant(row.fate());
        }
        // 划船堆的牌只写给舵手，而且只在挑牌窗口里 —— 决策 ⑭：界面不能揭穿舵手，结算后只公开被执行的那一张。
        boolean picking = helmDeadline > 0 && g.phase() == Phase.NAVIGATION
                && g.helmsman().map(id::equals).orElse(false);
        List<NavigationCard> offer = picking ? session.table().rowStack() : List.of();
        buf.writeVarInt(offer.size());
        for (NavigationCard card : offer) {
            NavCardView.of(card).write(buf);
        }
    }

    @Override
    public void applySyncPacket(RegistryByteBuf buf) {
        HudView next = readView(buf);
        int mark = buf.readInt();
        if (mark != SYNC_END) {
            // ❗先核对再赋值：半截读出来的投影不许装进界面。
            throw new IllegalStateException(
                    "对局投影的收尾标记对不上（读到 0x%08X）—— 两端的字段表不一致，八成是改了一侧忘了改另一侧"
                            .formatted(mark));
        }
        view = next;
    }

    private static HudView readView(RegistryByteBuf buf) {
        if (!buf.readBoolean()) {
            return HudView.IDLE;
        }
        int turn = buf.readVarInt();
        Phase phase = buf.readEnumConstant(Phase.class);
        int gulls = buf.readVarInt();
        int seatCount = buf.readVarInt();
        List<String> seats = new ArrayList<>(seatCount);
        for (int i = 0; i < seatCount; i++) {
            seats.add(buf.readString());
        }
        String actor = buf.readString();
        int rowStack = buf.readVarInt();
        String helmsman = buf.readString();
        long helmDeadline = buf.readVarLong();
        Optional<NavCardView> revealed = buf.readBoolean() ? Optional.of(NavCardView.read(buf)) : Optional.empty();
        HudView.Thirst thirstPrompt = HudView.Thirst.NONE;
        if (buf.readBoolean()) {
            thirstPrompt = new HudView.Thirst(buf.readString(), buf.readVarInt(), buf.readVarInt(),
                    buf.readVarInt(), buf.readVarInt(), buf.readVarLong());
        }
        if (!buf.readBoolean()) {
            return new HudView(true, turn, phase, gulls, seats, actor,
                    new HudView.Sea(rowStack, helmsman, helmDeadline, revealed, List.of(), List.of()),
                    thirstPrompt, false, "", 0, 0, Condition.CONSCIOUS, 0, false, List.of(), List.of());
        }
        String character = buf.readString();
        int health = buf.readVarInt();
        int maxHealth = buf.readVarInt();
        Condition condition = buf.readEnumConstant(Condition.class);
        int thirst = buf.readVarInt();
        boolean yourTurn = buf.readBoolean();
        int cards = buf.readVarInt();
        List<String> hand = new ArrayList<>(cards);
        for (int i = 0; i < cards; i++) {
            hand.add(buf.readString());
        }
        int frontCount = buf.readVarInt();
        List<HudView.FrontCard> front = new ArrayList<>(frontCount);
        for (int i = 0; i < frontCount; i++) {
            front.add(new HudView.FrontCard(buf.readString(), buf.readBoolean()));
        }
        int rowCount = buf.readVarInt();
        List<HudView.Sea.RowCard> rowing = new ArrayList<>(rowCount);
        for (int i = 0; i < rowCount; i++) {
            NavCardView card = NavCardView.read(buf);
            rowing.add(new HudView.Sea.RowCard(card, buf.readEnumConstant(Session.RowFate.class)));
        }
        int offerCount = buf.readVarInt();
        List<NavCardView> offer = new ArrayList<>(offerCount);
        for (int i = 0; i < offerCount; i++) {
            offer.add(NavCardView.read(buf));
        }
        return new HudView(true, turn, phase, gulls, seats, actor,
                new HudView.Sea(rowStack, helmsman, helmDeadline, revealed, rowing, offer),
                thirstPrompt, true, character, health, maxHealth, condition, thirst, yourTurn,
                List.copyOf(hand), List.copyOf(front));
    }

    /**
     * 物资阶段这一轮的运行时状态：超时时刻与当前高亮。
     *
     * <p>**不持久化**，也不该持久化 —— 它是一轮传递之内的东西，跨存档没有意义。
     * 超时时刻放服务端是因为它是权威：客户端自己算超时的话，改过的客户端可以永远不超时。
     */
    private long provisionDeadline;
    private int provisionHighlight;

    public long provisionDeadline() {
        return provisionDeadline;
    }

    public void setProvisionDeadline(long millis) {
        this.provisionDeadline = millis;
    }

    /** 持有者最后一次上报的高亮下标。超时时认它（决策 ⑨：不是随机）。 */
    public int provisionHighlight() {
        return provisionHighlight;
    }

    public void setProvisionHighlight(int index) {
        this.provisionHighlight = Math.max(0, index);
    }

    public void clearProvision() {
        this.provisionDeadline = 0L;
        this.provisionHighlight = 0;
    }

    /**
     * 舵手挑牌窗口的运行时状态：超时时刻与当前高亮。理由与补给箱那两项相同，也同样不持久化。
     *
     * <p>超时时刻为 0 表示窗口没开：没人划船时不开（当场翻顶牌），替身开着自动推进时也不开（ADR-0019）。
     */
    private long helmDeadline;
    private int helmHighlight;

    public long helmDeadline() {
        return helmDeadline;
    }

    public void setHelmDeadline(long millis) {
        this.helmDeadline = millis;
    }

    /** 舵手最后一次上报的高亮下标。超时时认它（用户 2026-09-15 定，与补给箱同一条规则）。 */
    public int helmHighlight() {
        return helmHighlight;
    }

    public void setHelmHighlight(int index) {
        this.helmHighlight = Math.max(0, index);
    }

    public void clearHelm() {
        this.helmDeadline = 0L;
        this.helmHighlight = 0;
    }

    /**
     * 口渴选择窗口的运行时状态：超时时刻与当前打算喝几张。理由与另外两处相同，也同样不持久化。
     *
     * <p>❗<b>高亮在这里是个「几张」而不是「哪一张」</b>：手上三张水完全等价，没有编号可指。
     */
    private long thirstDeadline;
    private int thirstHighlight;

    public long thirstDeadline() {
        return thirstDeadline;
    }

    public void setThirstDeadline(long millis) {
        this.thirstDeadline = millis;
    }

    /** 口渴的人最后一次上报的张数。超时时认它（与另外三面同一条规则）。 */
    public int thirstHighlight() {
        return thirstHighlight;
    }

    public void setThirstHighlight(int waters) {
        this.thirstHighlight = Math.max(0, waters);
    }

    /**
     * 这一轮口渴里，别人替他打出来的水（一张一个人，可以重复）。
     *
     * <p>❗<b>攒着而不是当场结算</b>：一次只打一张的话，剩下几次会当场变成伤害，
     * 而他自己那几张水还没轮到说话。窗口关上时两边一起交给引擎。
     */
    private final List<CharacterId> thirstDonors = new ArrayList<>();

    public List<CharacterId> thirstDonors() {
        return List.copyOf(thirstDonors);
    }

    public void addThirstDonor(CharacterId donor) {
        thirstDonors.add(donor);
    }

    public void clearThirst() {
        this.thirstDeadline = 0L;
        this.thirstHighlight = 0;
        this.thirstDonors.clear();
    }

    /**
     * 替身自动推进（ADR-0019）：开着时轮到替身「什么也不做」、替身当舵手挑第一张；关着时替身等指令。
     *
     * <p><b>默认开，不持久化</b> —— 重启回到开。对局本身都不持久化，开关比对局活得久没有意义。
     * 它挂在组件上而不是对局上：{@code playthrough-check.sh} 要在同一次起服里开着、关着各打一局。
     */
    private boolean dummyAutoplay = true;

    public boolean dummyAutoplay() {
        return dummyAutoplay;
    }

    public void setDummyAutoplay(boolean on) {
        this.dummyAutoplay = on;
    }

    /**
     * 推迟到之后某个 tick 再做的一步（ADR-0019）。
     *
     * <h2>为什么要有它</h2>
     * 各阶段互相直接调用（{@code finishAction → announceTurn → …}）。替身若在轮到它的那一刻当场行动，
     * 全是替身的一局会在一次调用里递归打完，而且整局落在同一个 tick 里 —— 客户端一帧都看不到。
     * 替身那一步排到下一 tick，递归就断在这里。
     *
     * @param dueMs   什么时候到期（{@code System.currentTimeMillis()} 同一时钟）
     * @param session 排的时候是哪一局。❗那一局结束或重开了，这一步就作废 —— 不许把上一局的动作做到下一局上
     * @param what    这一步是什么，出错时进日志
     * @param action  要做的事
     */
    public record Step(long dueMs, Session session, String what, Runnable action) {
    }

    private final ArrayDeque<Step> steps = new ArrayDeque<>();

    /** 排一步。只能在有对局时排：排进来的一步都属于当前这一局。 */
    public void schedule(long dueMs, String what, Runnable action) {
        steps.add(new Step(dueMs, requireSession(), what, action));
    }

    /**
     * 到期的下一步；属于别的对局的一律丢掉。
     *
     * <p>每 tick 至多取一步：一步推进一个人，客户端的投影才一格一格地跟得上。
     * 先进先出 —— 流程上同一时刻只会有一步排着（下一个替身、或者航海结算后的停顿）。
     */
    public Optional<Step> pollDueStep(long nowMs) {
        while (!steps.isEmpty()) {
            Step head = steps.peek();
            if (head.session() != session) {
                steps.poll();
                continue;
            }
            if (head.dueMs() > nowMs) {
                return Optional.empty();
            }
            return Optional.of(steps.poll());
        }
        return Optional.empty();
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
        this.endedFor.clear();
        clearProvision();
        clearHelm();
        clearThirst();
        steps.clear();
    }

    public void end() {
        occupants.values().stream().filter(o -> !o.isDummy()).map(Occupant::player).forEach(endedFor::add);
        this.session = null;
        this.occupants.clear();
        clearProvision();
        clearHelm();
        clearThirst();
        steps.clear();
    }

    public Map<CharacterId, Occupant> occupants() {
        return Map.copyOf(occupants);
    }

    public Optional<Occupant> occupantOf(CharacterId id) {
        return Optional.ofNullable(occupants.get(id));
    }

    /** 座位上有没有真人。有人要看，节奏才需要停下来等人读（ADR-0019：航海结算后的停顿）。 */
    public boolean anyHumanSeated() {
        return occupants.values().stream().anyMatch(o -> !o.isDummy());
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
