package io.github.heavyseasmc.mod.state;

import io.github.heavyseasmc.engine.model.Affinities;
import io.github.heavyseasmc.engine.model.CharacterId;
import io.github.heavyseasmc.engine.model.Survivor;
import io.github.heavyseasmc.engine.navigation.NavigationCard;
import io.github.heavyseasmc.engine.play.Contest;
import io.github.heavyseasmc.engine.play.Session;
import io.github.heavyseasmc.engine.scoring.ScoreSheet;
import io.github.heavyseasmc.engine.state.Condition;
import io.github.heavyseasmc.engine.state.Fight;
import io.github.heavyseasmc.engine.state.GameState;
import io.github.heavyseasmc.engine.state.Phase;
import io.github.heavyseasmc.engine.state.SurvivorState;
import io.github.heavyseasmc.mod.HeavySeasMod;
import io.github.heavyseasmc.mod.data.FogTable;
import io.github.heavyseasmc.mod.data.SceneDataLoader;
import io.github.heavyseasmc.mod.game.GameTiming;
import io.github.heavyseasmc.mod.ui.NotificationHistory;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.ladysnake.cca.api.v3.component.Component;
import org.ladysnake.cca.api.v3.component.sync.AutoSyncedComponent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.PriorityQueue;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
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
    private static final String KEY_ESCROWS = "voyage_escrows";
    private static final String KEY_SCENE = "scene";

    /** Owning world, used to include same-dimension spectators in the public projection. */
    private final World owner;

    private Session session;

    /**
     * 系统事件画在 HUD 侧边栏。两端保留最新 400 条，正常同步按收件人只发新条目。
     *
     * <p>客户端在每包应用时合并，不等下一帧绘制；重连发保留的历史，对局 UUID 区分重新从 0 开始的序号。
     * 超出保留范围有缺口计数与日志，不把截断误报为完整历史。
     */
    private static final int MAX_NOTIFICATIONS = NotificationHistory.LIMIT;
    private final ArrayDeque<Text> notifications = new ArrayDeque<>();
    /** 这一局一共播了几条（开局从 0 数，只增不减）：客户端拿两次之差数新到了几条（{@code NotificationArrivals}）。 */
    private long notificationSeq;
    private final Map<Long, String> notificationJsonCache = new LinkedHashMap<>();
    private final Map<UUID, Long> notificationSent = new LinkedHashMap<>();
    private UUID notificationEpoch = UUID.randomUUID();
    private NotificationHistory<Text> receivedNotifications = new NotificationHistory<>();
    private boolean extraProvisionPending;

    public GameComponent(World owner) {
        this.owner = owner;
    }

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
    private TableView tableView = TableView.EMPTY;
    private long overboardDeadline;
    /** 落海这一窗本来有多长（ADR-0099 D8）：客户端按它画满格，不再用编译进去的常量。 */
    private long overboardWindow;

    public TableView tableView() {
        return tableView;
    }

    public long overboardDeadline() {
        return overboardDeadline;
    }

    /** 只改到点时刻（提前收窗 · 收尾清零）；开窗走 {@link #openOverboardWindow}。 */
    public void setOverboardDeadline(long deadline) {
        overboardDeadline = deadline;
    }

    /** 开落海这一窗：到点时刻与总长一起设（与 {@link #openContestWindow} 同一个理由）。 */
    public void openOverboardWindow(long millis) {
        overboardDeadline = System.currentTimeMillis() + millis;
        overboardWindow = millis;
        standInsOverboard = false;
    }

    /**
     * 落海这一窗里，动脑 / 大模型的替身还在一个个想要不要出牌（审查 2026-10-07 R3）。
     *
     * <p>❗原先真人先说完「不用」就当场收窗 —— 替身那一手还在路上，落地时这一窗已经结了，记成「作废」。
     * 真人那一侧收窗前要先问它：它还亮着就不收，等替身的接力走完由那一侧收（接力有看门狗兜底，不会一直亮着）。
     */
    private boolean standInsOverboard;

    public boolean standInsOverboard() {
        return standInsOverboard;
    }

    public void setStandInsOverboard(boolean thinking) {
        this.standInsOverboard = thinking;
    }

    /**
     * 这一局的时限（ADR-0099 D8）：开局时由 {@code GameFlow} 从服务端设置里取一份，一局之内不变。
     * 没开过局时是改之前写死的那一套。
     */
    private GameTiming timing = GameTiming.DEFAULTS;

    public GameTiming timing() {
        return timing;
    }

    public void setTiming(GameTiming timing) {
        this.timing = Objects.requireNonNull(timing, "timing");
    }

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
        // 只发给这一局的人（入座的 + 开局时带进来的观众，ADR-0083）。此前发给同一维度里的每一个人（M4：终局是一场演出）——
        // 北辰号与对局同在雾海之后，船上闲逛的人会看到对局的 HUD、雾、终局界面弹出来、按键被接管。观众照旧只拿到公开的那几栏。
        return activeVoyagePlayers.contains(player.getUuid());
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
        writeView(buf, recipient.getUuid(), notificationSent.getOrDefault(recipient.getUuid(), 0L));
        TableView.of(session, seatOf(recipient.getUuid()), overboardDeadline, overboardWindow,
                id -> occupantOf(id).filter(o -> !o.isDummy()).map(o -> o.player().toString()).orElse("")).write(buf);
        buf.writeInt(SYNC_END);           // ❗必须是最后一笔，且每条分支都经过这里
        notificationSent.put(recipient.getUuid(), notificationSeq);
    }

    /** 单测用：按收件人写出 HUD 那一半投影（不含公共牌桌与收尾哨兵），不必造一个 {@code ServerPlayerEntity}。 */
    void writeViewFor(RegistryByteBuf buf, UUID recipient) {
        writeView(buf, recipient, 0L);
    }

    void writeViewSince(RegistryByteBuf buf, UUID recipient, long lastReceived) {
        writeView(buf, recipient, lastReceived);
    }

    /** 单测用：读回 {@link #writeViewFor} 写出的那一半。 */
    static HudView readViewFrom(RegistryByteBuf buf) {
        return readView(buf, new NotificationHistory<>());
    }

    static HudView readViewFrom(RegistryByteBuf buf, NotificationHistory<Text> history) {
        return readView(buf, history);
    }

    private void writeView(RegistryByteBuf buf, UUID recipient, long lastReceived) {
        buf.writeBoolean(session != null);
        if (session == null) {
            return;
        }
        GameState g = session.state();
        buf.writeVarInt(g.turn());
        buf.writeEnumConstant(g.phase());
        buf.writeVarInt(g.gulls());
        buf.writeString(session.currentWeather().map(card -> card.id()).orElse(""));
        // 今天有没有航海阶段。❗按**效果**判、不按天候 id（数据包可以给任何 id 配这个效果，ADR-0032 #2）。
        // 客户端拿它把「划船」画成按不动的那一档 —— 否则界面照样给出这件事，按下去才被服务端拒：
        // 2026-09-23 实拍，navigation_test 因此干等 6 秒后报「划船一面该自己弹」，病根却在天候。
        buf.writeBoolean(!io.github.heavyseasmc.mod.command.SeasCommand.skipsNavigation(session.currentWeather()));
        // 今天的雾（ADR-0034 §5.1.2）：服务端查表算好再发，雾散之后是 0/0。公开，全船同一片。
        HudView.Fog fog = currentFog();
        buf.writeVarInt(fog.start());
        buf.writeVarInt(fog.end());
        long firstNotification = Math.max(notificationSeq - notifications.size() + 1,
                Math.min(notificationSeq, Math.max(0, lastReceived)) + 1);
        buf.writeUuid(notificationEpoch);
        buf.writeVarLong(firstNotification);
        buf.writeVarLong(notificationSeq);
        List<String> newNotifications = notificationJson(buf.getRegistryManager(), firstNotification);
        buf.writeVarInt(newNotifications.size());
        for (String notification : newNotifications) {
            buf.writeString(notification);
        }
        // 座位轨：谁坐哪、轮到谁。**公开信息**，每人一份照发 —— 等别人行动时全船看的就是它。
        buf.writeVarInt(g.bySeat().size());
        for (CharacterId s : g.bySeat()) {
            buf.writeString(s.value());
        }
        // 被移出游戏的人（ADR-0022）：公开 —— 全船都看见他被海水带走了，座位条上他那一格要显示「没了」。
        List<CharacterId> removed = g.bySeat().stream().filter(g::isRemoved).toList();
        buf.writeVarInt(removed.size());
        for (CharacterId r : removed) {
            buf.writeString(r.value());
        }
        buf.writeString(g.phase() == Phase.ACTION ? g.nextActor().map(CharacterId::value).orElse("") : "");
        // 航海这一段的公开部分（决策 ⑭）：划船堆几张、舵手是谁、挑牌还剩多久、执行的是哪一张。
        // ❗划船堆里是什么牌不在这里 —— 那一项下面只写给舵手。
        buf.writeVarInt(session.table().rowStack().size());
        buf.writeString(helmSeat(g).map(CharacterId::value).orElse(""));
        buf.writeVarLong(helmDeadline);
        // 这一窗本来有多长（ADR-0099 D8）：时限可配之后，客户端不能再拿编译进去的常量画满格
        buf.writeVarLong(helmWindow);
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
            buf.writeVarInt(thirstDonors.size());
            buf.writeVarInt(prompt.waterPerSource());
            buf.writeVarLong(thirstDeadline);
            buf.writeVarLong(thirstWindow);   // 这一窗本来有多长（ADR-0099 D8）
        }
        // 终局：这一轮已经翻开的目标对全船公开；计分阶段只发合计。
        // ❗四项明细不在这里，下面只写给本人。
        buf.writeBoolean(endgame != null);
        if (endgame != null) {
            buf.writeEnumConstant(endgame.outcome());
            buf.writeVarInt(endgame.alive());
            buf.writeEnumConstant(endgame.stage());
            buf.writeVarInt(endgame.flipped());
            Affinities aff = session.affinities().orElseThrow();
            boolean scoring = endgame.stage() == EndgameProgress.Stage.SCORES;
            boolean revealing = endgame.stage() == EndgameProgress.Stage.HATE
                    || endgame.stage() == EndgameProgress.Stage.LOVE;
            // ❗审查 2026-10-07 L4：翻牌次序就是总分从低到高，胜者旗标就是名次 —— 原先从靠岸那一幕（ARRIVAL）起整张表连同旗标
            //   就发给所有人，改过的客户端在船靠岸时就知道结果。现在：靠岸那一幕一行都不发（客户端那一幕不开翻牌面，用不着）；
            //   翻牌只公开已翻与当前点名的身份；后续位置用空身份占位，不把完整名次藏在列表顺序里；
            //   胜者旗标只给已翻开的牌，保留那一下较慢的翻牌节奏；
            //   计分阶段才全给。
            List<CharacterId> order = endgame.stage() == EndgameProgress.Stage.ARRIVAL ? List.of() : endgame.order();
            buf.writeVarInt(order.size());
            for (int i = 0; i < order.size(); i++) {
                CharacterId who = order.get(i);
                boolean named = scoring || (revealing && i <= endgame.flipped());
                buf.writeString(named ? who.value() : "");
                boolean open = revealing && i < endgame.flipped();
                CharacterId target = endgame.stage() == EndgameProgress.Stage.HATE ? aff.hateOf(who) : aff.loveOf(who);
                buf.writeString(open ? target.value() : "");
                buf.writeVarInt(scoring ? endgame.scores().get(who).total() : -1);
                buf.writeBoolean((scoring || open) && endgame.isWinner(who));
            }
            // 计分面板上那枚章：这一局有几处调试改动（ADR-0060 D3）。公开 —— 用了调试，全船都该知道。
            buf.writeVarInt(debugChanges());
        }

        // 进行中的换座位 / 抢夺（ADR-0023）：谁对谁 · 哪一段 · 还剩多久 · 两边站了谁 · 两边的体型和，全都**公开**。
        // 理由与划船堆张数、口渴轮到谁同一条（决策 ⑭）：等待要看得见。
        // ❗战力和里**不含已押的武器** —— 武器是全场唯一的暗牌（决策 ④），含进去的话减一减就知道对面押了几点。
        Optional<Contest> contest = session.contest();
        buf.writeBoolean(contest.isPresent());
        if (contest.isPresent()) {
            Contest c = contest.get();
            buf.writeEnumConstant(c.kind());
            buf.writeString(c.attacker().value());
            buf.writeString(c.target().value());
            buf.writeEnumConstant(c.stage());
            buf.writeVarLong(contestDeadline);
            buf.writeVarLong(contestWindow);
            Optional<Fight> fight = c.fight();
            writeSide(buf, session, fight.map(Fight::attackSide).orElse(Set.of()));
            writeSide(buf, session, fight.map(Fight::defendSide).orElse(Set.of()));
        }

        Optional<CharacterId> seat = seatOf(recipient);
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
        // 自己的爱恨只写给自己（规则：爱恨卡全程保密，不能亮出来证明自己）。别人的包里没有这两个字节。
        Optional<Affinities> own = session.affinities();
        buf.writeString(own.map(a -> a.loveOf(id).value()).orElse(""));
        buf.writeString(own.map(a -> a.hateOf(id).value()).orElse(""));
        // 计分阶段：自己的四项明细（照交互稿：舞台上列的是「你」的四行）。
        boolean scoring = endgame != null && endgame.stage() == EndgameProgress.Stage.SCORES;
        buf.writeBoolean(scoring);
        if (scoring) {
            ScoreSheet sheet = endgame.scores().get(id);
            buf.writeVarInt(sheet.selfSurvival());
            buf.writeVarInt(sheet.treasure());
            buf.writeVarInt(sheet.loved());
            buf.writeVarInt(sheet.hated());
        }
        // ❗nextActor 只看「能行动」与「本回合还没行动过」，不看阶段 —— 行动阶段以外它照样可能指向某一位。
        //   HUD 的「轮到你行动」与行动一面的自动弹出都认这一位，所以阶段要在这里一起判。
        // ❗划船抽到的牌还没选完时，nextActor 仍然是他（行动要等选择落定才算完），但他该看的是划船一面，不是行动一面。
        // ❗这一场进行中时也不算「轮到你」：进攻方在收场之前一直还是 nextActor（行动是收场那一刻才记的），
        //   不排除的话，他的行动一面会在这一场当中弹出来，而按下去的那一下会撞上引擎的 requireNoContest ——
        //   那是一句堆栈，不是一次被拒绝的操作。
        // ❗举着拳头时也不算「轮到你选一件事」：不排掉的话，按下「换座位」之后行动一面会当场弹回来，
        //   而它发出的包会被服务端当作「已经在指定模式里了」静默丢掉 —— 屏幕上只是「按了没反应」。
        boolean yourTurn = g.phase() == Phase.ACTION && g.nextActor().map(id::equals).orElse(false)
                && session.rower().isEmpty() && session.contest().isEmpty()
                && designating().isEmpty() && provisionTargeter().isEmpty();
        buf.writeBoolean(yourTurn);
        boolean ownsActionWindow = g.phase() == Phase.ACTION && g.nextActor().map(id::equals).orElse(false)
                && actionDeadline > 0L;
        buf.writeVarLong(ownsActionWindow ? actionDeadline : 0L);
        buf.writeVarLong(ownsActionWindow ? actionWindow : 0L);
        // 我正举着拳头找人吗（ADR-0025）· 还剩多久。只写给他自己 —— 别人看的是世界里那个发光的人。
        buf.writeBoolean(designating().map(id::equals).orElse(false));
        buf.writeVarLong(designating().map(id::equals).orElse(false) ? designationDeadline : 0L);
        // 医疗箱挑目标：待用的牌与候选人只写给发起者。别人不需要知道他在菜单里指着谁。
        boolean pickingProvisionTarget = provisionTargeter().map(id::equals).orElse(false);
        buf.writeString(pickingProvisionTarget ? provisionTargetCard : "");
        List<HudView.MedicalTarget> medicalTargets = pickingProvisionTarget
                ? medicalTargets(session) : List.of();
        buf.writeVarInt(medicalTargets.size());
        for (HudView.MedicalTarget target : medicalTargets) {
            buf.writeString(target.id());
            buf.writeVarInt(target.health());
            buf.writeVarInt(target.maxHealth());
            buf.writeEnumConstant(target.condition());
        }
        // 这位玩家在当前口渴窗口里已经承诺了几张。手里的牌到结算时才一起扣，所以必须单独告诉客户端。
        buf.writeVarInt((int) thirstDonors.stream().filter(id::equals).count());
        // 手牌只写这一份 —— 别人的包里没有这些字节，不是「发了再藏」。
        List<String> hand = g.stateOf(id).hand();
        buf.writeVarInt(hand.size());
        for (String card : hand) {
            buf.writeString(card);
        }
        // 本人的公开区保留撑伞状态；全船公开牌另由 TableView 投影。
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
                && helmSeat(g).map(id::equals).orElse(false);
        List<NavigationCard> offer = picking ? session.table().rowStack() : List.of();
        buf.writeVarInt(offer.size());
        for (NavigationCard card : offer) {
            NavCardView.of(card).write(buf);
        }
        // 这一场里只进他自己那一包的几样（ADR-0023）。
        // ❗押武器是暗牌，所以**连他自己那一包里也没有「谁押了什么」**：只有「我还押得出哪几张」与「我押了几张」。
        if (contest.isPresent()) {
            Contest c = contest.get();
            List<String> weapons = committableWeapons(session, c, id);
            buf.writeVarInt(weapons.size());
            for (String card : weapons) {
                buf.writeString(card);
            }
            buf.writeVarInt(c.committedBy(id).size());
            // 挑牌那一段：被抢方**面前**那一区只写给抢夺方（那一区规则上本来就是公开的），
            // 手牌只给张数 —— 手牌是暗的，抢夺方也不能看着牌挑（规则 §5）。
            boolean contestPick = c.stage() == Contest.Stage.PICK && c.attacker().equals(id);
            List<String> victimFront = contestPick && !c.handOnly()
                    ? g.stateOf(c.target()).front() : List.of();
            buf.writeVarInt(victimFront.size());
            for (String card : victimFront) {
                buf.writeString(card);
            }
            buf.writeVarInt(contestPick ? g.stateOf(c.target()).hand().size() : 0);
        }
    }

    /**
     * 今天的雾：按当日天候查这一局布局指的雾表；雾散之后（{@link #fogCleared}）与晴空那一天都是 {@code 0/0}。
     *
     * <p>❗算在服务端而不是把天候 id 交给客户端查表：客户端没有雾表，也不该有第二份「雾还在不在」的规则。
     */
    private HudView.Fog currentFog() {
        if (session == null || fogCleared) {
            return HudView.Fog.NONE;
        }
        String weather = session.currentWeather().map(card -> card.id()).orElse("");
        FogTable.Entry entry = fogFor(weather);
        return entry.vanilla() ? HudView.Fog.NONE : new HudView.Fog(entry.start(), entry.end());
    }

    /**
     * 这一局某种天候那一天的雾表条目：有开局快照（{@link #setLayout}）就查快照，没有（没开过局）才去问当前的场景数据。
     *
     * <p>❗审查 2026-10-07 C6：原先每次都按布局 id 去 {@code SceneDataLoader} 里现取 —— 对局中 {@code /reload}
     * 把这一局用的布局拿掉，好几处每 tick 的计时就各抛一次，冒到服务端主循环，崩服。一局之内布局不变，与时限同一条（ADR-0099 D8）。
     */
    public FogTable.Entry fogFor(String weather) {
        if (fogTable != null) {
            return fogTable.entryFor(weather);
        }
        return SceneDataLoader.fogFor(layoutId == null ? SceneDataLoader.DEFAULT : layoutId, weather);
    }

    /**
     * 一边：站了谁（公开），加上他们的<b>打架体型和</b>（满体型 + 今天喝的酒，与引擎结算同一个函数 {@code Session#fightingSize}）。
     *
     * <p>❗不含押下的武器：那是暗牌，加进来就等于提前把它亮了 —— 而且是以最难发现的方式：
     * 界面上只是一个数变大了，没有任何人会报错。
     * <p>❗酒要算（ADR-0095 A4）：喝酒是公开的，原先只加体型，有人喝了酒时界面上的数与结算对不上。
     */
    private static void writeSide(RegistryByteBuf buf, Session session, Set<CharacterId> side) {
        buf.writeVarInt(side.size());
        int power = 0;
        for (CharacterId who : side) {
            buf.writeString(who.value());
            power += session.fightingSize(who);
        }
        buf.writeVarInt(power);
    }

    /**
     * 他此刻还押得出的武器：手上与面前的武器牌，减去已经押下的那几张（两支船桨押了一支，还剩一支）。
     *
     * <p>判据与引擎 {@code Session#commitWeapon} 同源 —— 同一句「手上 + 面前 − 已押」，
     * 只是一个用来拦、一个用来画。<b>不许在这里另立一套</b>：两套一旦分家，界面上摆着的牌会押不出去，
     * 而那时看到的只是「按了没反应」。
     */
    private static List<String> committableWeapons(Session session, Contest contest, CharacterId who) {
        if (contest.stage() != Contest.Stage.WEAPONS
                || !contest.fight().map(f -> f.combatants().contains(who)).orElse(false)) {
            return List.of();
        }
        SurvivorState s = session.state().stateOf(who);
        List<String> owned = new ArrayList<>(s.hand());
        owned.addAll(s.front());
        List<String> committed = new ArrayList<>(contest.committedBy(who));
        List<String> out = new ArrayList<>();
        for (String card : owned) {
            if (session.provisions().get(card).weaponPower() <= 0) {
                continue;
            }
            if (!committed.remove(card)) {         // 已经押下的先一张一张扣掉，扣不掉的才是还押得出的
                out.add(card);
            }
        }
        return out;
    }

    /**
     * 医疗箱此刻能指向谁：仍在局里、没有死亡、而且真有伤害。顺序沿用座位顺序，界面不会另造一套排序。
     */
    private static List<HudView.MedicalTarget> medicalTargets(Session session) {
        GameState g = session.state();
        List<HudView.MedicalTarget> out = new ArrayList<>();
        for (CharacterId id : g.bySeat()) {
            Survivor survivor = g.roster().get(id);
            SurvivorState state = g.stateOf(id);
            Condition condition = g.conditionOf(id);
            if (g.isRemoved(id) || condition == Condition.DEAD || state.damage() == 0) {
                continue;
            }
            out.add(new HudView.MedicalTarget(id.value(), survivor.size() - state.damage(),
                    survivor.size(), condition));
        }
        return List.copyOf(out);
    }

    @Override
    public void applySyncPacket(RegistryByteBuf buf) {
        NotificationHistory<Text> nextNotifications = receivedNotifications.copy();
        HudView next = readView(buf, nextNotifications);
        TableView nextTable = TableView.read(buf);
        int mark = buf.readInt();
        if (mark != SYNC_END) {
            // ❗先核对再赋值：半截读出来的投影不许装进界面。
            throw new IllegalStateException(
                    "对局投影的收尾标记对不上（读到 0x%08X）—— 两端的字段表不一致，八成是改了一侧忘了改另一侧"
                            .formatted(mark));
        }
        if (nextNotifications.missing() > receivedNotifications.missing()) {
            LOGGER.warn("通知历史：{} 条较早的播报已超出保留范围", nextNotifications.missing());
        }
        receivedNotifications = nextNotifications;
        notificationEpoch = nextNotifications.epoch();
        view = next;
        tableView = nextTable;
    }

    private static HudView readView(RegistryByteBuf buf, NotificationHistory<Text> history) {
        if (!buf.readBoolean()) {
            history.clear();
            return HudView.IDLE;
        }
        int turn = buf.readVarInt();
        Phase phase = buf.readEnumConstant(Phase.class);
        int gulls = buf.readVarInt();
        String weather = buf.readString();
        boolean canRow = buf.readBoolean();
        HudView.Fog fog = new HudView.Fog(buf.readVarInt(), buf.readVarInt());
        UUID notificationEpoch = buf.readUuid();
        long firstNotification = buf.readVarLong();
        long notificationSeq = buf.readVarLong();
        int notificationCount = buf.readVarInt();
        if (notificationCount < 0 || notificationCount > MAX_NOTIFICATIONS) {
            throw new IllegalArgumentException("Invalid notification count: " + notificationCount);
        }
        List<Text> incomingNotifications = new ArrayList<>(notificationCount);
        for (int i = 0; i < notificationCount; i++) {
            Text message = Text.Serialization.fromJson(buf.readString(), buf.getRegistryManager());
            incomingNotifications.add(Objects.requireNonNull(message, "通知文本不能是 null"));
        }
        history.accept(notificationEpoch, firstNotification, notificationSeq, incomingNotifications);
        List<Text> notifications = history.entries();
        int seatCount = buf.readVarInt();
        List<String> seats = new ArrayList<>(seatCount);
        for (int i = 0; i < seatCount; i++) {
            seats.add(buf.readString());
        }
        int removedCount = buf.readVarInt();
        List<String> removed = new ArrayList<>(removedCount);
        for (int i = 0; i < removedCount; i++) {
            removed.add(buf.readString());
        }
        String actor = buf.readString();
        int rowStack = buf.readVarInt();
        String helmsman = buf.readString();
        long helmDeadline = buf.readVarLong();
        long helmWindow = buf.readVarLong();
        Optional<NavCardView> revealed = buf.readBoolean() ? Optional.of(NavCardView.read(buf)) : Optional.empty();
        HudView.Thirst thirstPrompt = HudView.Thirst.NONE;
        if (buf.readBoolean()) {
            thirstPrompt = new HudView.Thirst(buf.readString(), buf.readVarInt(), buf.readVarInt(),
                    buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarLong(),
                    buf.readVarLong());
        }
        HudView.Endgame endgame = HudView.Endgame.NONE;
        if (buf.readBoolean()) {
            GameState.Outcome outcome = buf.readEnumConstant(GameState.Outcome.class);
            int alive = buf.readVarInt();
            EndgameProgress.Stage stage = buf.readEnumConstant(EndgameProgress.Stage.class);
            int flipped = buf.readVarInt();
            int entryCount = buf.readVarInt();
            List<HudView.Endgame.Entry> entries = new ArrayList<>(entryCount);
            for (int i = 0; i < entryCount; i++) {
                entries.add(new HudView.Endgame.Entry(buf.readString(), buf.readString(), buf.readVarInt(), buf.readBoolean()));
            }
            int debugChanges = buf.readVarInt();
            endgame = new HudView.Endgame(outcome, alive, stage, flipped, entries, debugChanges);
        }
        boolean hasContest = buf.readBoolean();
        Contest.Kind contestKind = Contest.Kind.SWAP;
        String attacker = "";
        String target = "";
        Contest.Stage contestStage = null;
        long contestDeadline = 0L;
        long contestWindow = 0L;
        List<String> attackSide = List.of();
        List<String> defendSide = List.of();
        int attackPower = 0;
        int defendPower = 0;
        if (hasContest) {
            contestKind = buf.readEnumConstant(Contest.Kind.class);
            attacker = buf.readString();
            target = buf.readString();
            contestStage = buf.readEnumConstant(Contest.Stage.class);
            contestDeadline = buf.readVarLong();
            contestWindow = buf.readVarLong();
            attackSide = readNames(buf);
            attackPower = buf.readVarInt();
            defendSide = readNames(buf);
            defendPower = buf.readVarInt();
        }
        ContestView publicContest = hasContest
                ? new ContestView(contestKind, attacker, target, contestStage, contestDeadline, contestWindow,
                        attackSide, defendSide, attackPower, defendPower, List.of(), 0, List.of(), 0)
                : ContestView.NONE;
        if (!buf.readBoolean()) {
            return new HudView(true, turn, phase, gulls, weather, canRow, fog, notifications, notificationSeq, seats, removed, actor,
                    new HudView.Sea(rowStack, helmsman, helmDeadline, helmWindow, revealed, List.of(), List.of()),
                    thirstPrompt, endgame, publicContest, false, "", 0, 0, Condition.CONSCIOUS, 0, "", "",
                    false, 0L, 0L, false, 0L, "", List.of(), 0, List.of(), List.of(), HudView.Score.NONE);
        }
        String character = buf.readString();
        int health = buf.readVarInt();
        int maxHealth = buf.readVarInt();
        Condition condition = buf.readEnumConstant(Condition.class);
        int thirst = buf.readVarInt();
        String love = buf.readString();
        String hate = buf.readString();
        HudView.Score myScore = HudView.Score.NONE;
        if (buf.readBoolean()) {
            myScore = new HudView.Score(buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt());
        }
        boolean yourTurn = buf.readBoolean();
        long actionDeadline = buf.readVarLong();
        long actionWindow = buf.readVarLong();
        boolean designating = buf.readBoolean();
        long designateUntil = buf.readVarLong();
        String provisionTargetCard = buf.readString();
        int targetCount = buf.readVarInt();
        List<HudView.MedicalTarget> provisionTargets = new ArrayList<>(targetCount);
        for (int i = 0; i < targetCount; i++) {
            provisionTargets.add(new HudView.MedicalTarget(buf.readString(), buf.readVarInt(), buf.readVarInt(),
                    buf.readEnumConstant(Condition.class)));
        }
        int myDonatedWater = buf.readVarInt();
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
        ContestView contest = publicContest;
        if (hasContest) {
            List<String> myWeapons = readNames(buf);
            int myCommitted = buf.readVarInt();
            List<String> victimFront = readNames(buf);
            int victimHand = buf.readVarInt();
            contest = new ContestView(contestKind, attacker, target, contestStage, contestDeadline, contestWindow,
                    attackSide, defendSide, attackPower, defendPower, myWeapons, myCommitted,
                    victimFront, victimHand);
        }
        return new HudView(true, turn, phase, gulls, weather, canRow, fog, notifications, notificationSeq, seats, removed, actor,
                new HudView.Sea(rowStack, helmsman, helmDeadline, helmWindow, revealed, rowing, offer),
                thirstPrompt, endgame, contest, true, character, health, maxHealth, condition, thirst,
                love, hate, yourTurn, actionDeadline, actionWindow, designating, designateUntil,
                provisionTargetCard, provisionTargets, myDonatedWater,
                List.copyOf(hand), List.copyOf(front), myScore);
    }

    /** 一串角色 id：写的那一侧都是「先张数再逐个」，读的这一侧就只此一份。 */
    private static List<String> readNames(RegistryByteBuf buf) {
        int count = buf.readVarInt();
        List<String> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            out.add(buf.readString());
        }
        return out;
    }

    /**
     * 行动选择与划船决定共用的运行时窗口。两者不会同时存在：按下划船后直接用较短窗口替换。
     * 服务端持有截止时间，客户端只负责显示；掉线不会让整局永久停住。
     */
    private long actionDeadline;
    private long actionWindow;

    public long actionDeadline() {
        return actionDeadline;
    }

    public long actionWindow() {
        return actionWindow;
    }

    public void openActionWindow(long millis) {
        this.actionDeadline = System.currentTimeMillis() + millis;
        this.actionWindow = millis;
    }

    public void clearActionWindow() {
        this.actionDeadline = 0L;
        this.actionWindow = 0L;
    }

    /**
     * 物资阶段这一轮的运行时状态：超时时刻与当前高亮。
     *
     * <p>**不持久化**，也不该持久化 —— 它是一轮传递之内的东西，跨存档没有意义。
     * 超时时刻放服务端是因为它是权威：客户端自己算超时的话，改过的客户端可以永远不超时。
     */
    private long provisionDeadline;
    /** 这一手本来有多长（ADR-0099 D8）：随补给箱的包发给客户端画满格。 */
    private long provisionWindow;
    private int provisionHighlight;

    public long provisionDeadline() {
        return provisionDeadline;
    }

    public long provisionWindow() {
        return provisionWindow;
    }

    public void setProvisionDeadline(long millis) {
        this.provisionDeadline = millis;
    }

    /** 开补给箱这一手：到点时刻与总长一起设。 */
    public void openProvisionWindow(long millis) {
        this.provisionDeadline = System.currentTimeMillis() + millis;
        this.provisionWindow = millis;
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
        this.provisionWindow = 0L;
        this.provisionHighlight = 0;
    }

    /**
     * 舵手挑牌窗口的运行时状态：超时时刻与当前高亮。理由与补给箱那两项相同，也同样不持久化。
     *
     * <p>超时时刻为 0 表示窗口没开：没人划船时不开（当场翻顶牌），替身开着自动推进时也不开（ADR-0019）。
     */
    private long helmDeadline;
    /** 这一窗本来有多长（ADR-0099 D8）。 */
    private long helmWindow;
    private int helmHighlight;
    /**
     * 开窗那一刻的舵手（ADR-0051 B5，用户 2026-10-01 拍板）：窗口开了就不换人。
     *
     * <p>舵手按「最靠船尾的清醒在线者」现算，协作者 2adbbe4 那一版在窗口里有人掉线 / 重连时换人并重开 12 秒 ——
     * 划船堆的暗牌于是给到两个人看（规则 §12 舵手全知），反复断连还能让计时一直重开，指南针多抽的那张也算错人。
     * 窗口里认这一位：谁能看到划船堆、谁的挑牌包算数、超时替谁挑。他掉线了就等超时按默认挑。
     */
    private CharacterId helmOwner;

    public long helmDeadline() {
        return helmDeadline;
    }

    public void setHelmDeadline(long millis) {
        this.helmDeadline = millis;
    }

    /** 开舵手挑牌这一窗：到点时刻与总长一起设。 */
    public void openHelmWindow(long millis) {
        this.helmDeadline = System.currentTimeMillis() + millis;
        this.helmWindow = millis;
    }

    /** 开窗时记下舵手；窗口开着时 {@link #helmSeat} 认它。 */
    public void setHelmOwner(CharacterId owner) {
        this.helmOwner = owner;
    }

    /** 窗口开着时是开窗那一刻的舵手；没开窗时按现在的局面算（公开信息：谁是舵手）。 */
    public Optional<CharacterId> helmSeat(io.github.heavyseasmc.engine.state.GameState g) {
        return helmDeadline > 0 && helmOwner != null ? Optional.of(helmOwner) : g.helmsman();
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
        this.helmWindow = 0L;
        this.helmHighlight = 0;
        this.helmOwner = null;
    }

    /**
     * 口渴选择窗口的运行时状态：超时时刻与当前打算喝几张。理由与另外两处相同，也同样不持久化。
     *
     * <p>❗<b>高亮在这里是个「几张」而不是「哪一张」</b>：手上三张水完全等价，没有编号可指。
     */
    private long thirstDeadline;
    /** 这一窗本来有多长（ADR-0099 D8）。 */
    private long thirstWindow;
    private int thirstHighlight;

    public long thirstDeadline() {
        return thirstDeadline;
    }

    public void setThirstDeadline(long millis) {
        this.thirstDeadline = millis;
    }

    /** 开口渴这一窗：到点时刻与总长一起设。 */
    public void openThirstWindow(long millis) {
        this.thirstDeadline = System.currentTimeMillis() + millis;
        this.thirstWindow = millis;
    }

    /** 口渴的人最后一次上报的张数。超时时认它（与另外三面同一条规则）。 */
    public int thirstHighlight() {
        return thirstHighlight;
    }

    public void setThirstHighlight(int waters) {
        this.thirstHighlight = Math.max(0, waters);
    }

    /**
     * 进行中的那一场换座位 / 抢夺，当前这一段的超时时刻（ADR-0023）。0 = 没开窗口。
     *
     * <h2>为什么只有一个数</h2>
     * 走到哪一段、谁在打、押了什么，全在引擎的 {@code Session#contest} 里 —— 这里只存「什么时候到点」。
     * 两段软倒计时（有人加入 / 有人押武器就重置）也只是把这个数往后推一次。
     *
     * <p>❗<b>没开窗口不等于没有这一场</b>：全是替身的那几段不开窗口，由排程一步一步推。
     */
    private long contestDeadline;

    /** 这一段<b>本来有多长</b>。见 {@link #openContestWindow}。 */
    private long contestWindow;

    public long contestDeadline() {
        return contestDeadline;
    }

    public long contestWindow() {
        return contestWindow;
    }

    /**
     * 开一段窗口：记下什么时候到点，也记下这一段本来有多长。
     *
     * <p>❗<b>两个数一起设，不给分开设的入口</b>。倒计时那条横杠按「这一段有多长」画 ——
     * 只记到点时刻的话，有人加入把 15 秒重置成 8 秒之后，杠会从一半开始走，
     * 而它看起来<b>完全正常</b>：没有报错，只是每个看它的人都以为还剩得更多。
     */
    public void openContestWindow(long millis) {
        this.contestDeadline = System.currentTimeMillis() + millis;
        this.contestWindow = millis;
    }

    public void clearContest() {
        this.contestDeadline = 0L;
        this.contestWindow = 0L;
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

    public void removeThirstDonor(CharacterId donor) {
        thirstDonors.removeIf(donor::equals);
    }

    public void clearThirst() {
        this.thirstDeadline = 0L;
        this.thirstWindow = 0L;
        this.thirstHighlight = 0;
        this.thirstDonors.clear();
        this.standInsThirst = false;
    }

    /**
     * 真人的口渴窗口开着时，动脑 / 大模型的替身还在一个个想递不递水（审查 2026-10-07 R4）。
     * 与落海那一窗的 {@link #standInsOverboard} 同一个用法：「能打水的真人都表过态了」那一下先问它，亮着就不提前收。
     */
    private boolean standInsThirst;

    public boolean standInsThirst() {
        return standInsThirst;
    }

    public void setStandInsThirst(boolean thinking) {
        this.standInsThirst = thinking;
    }

    /**
     * 终局序列走到哪了（ADR-0022）；{@code null} = 不在终局。运行时状态，不持久化。
     *
     * <p>❗它在的时候会话还活着 —— 翻牌与计分要靠投影推给客户端，会话收起就什么都推不出去了。
     * 由 {@code EndgamePhase} 在序列走完时调 {@link #end()}。
     */
    private EndgameProgress endgame;

    public Optional<EndgameProgress> endgame() {
        return Optional.ofNullable(endgame);
    }

    public void setEndgame(EndgameProgress progress) {
        this.endgame = progress;
    }

    /**
     * 替身自动推进（ADR-0019）：开着时轮到替身「什么也不做」、替身当舵手挑第一张；关着时替身等指令。
     *
     * <p><b>默认开，不持久化</b> —— 重启回到开。对局本身都不持久化，开关比对局活得久没有意义。
     * 它挂在组件上而不是对局上：{@code playthrough-check.sh} 要在同一次起服里开着、关着各打一局。
     * 起服时按服务端设置 {@code stand_ins.autoplay} 定初值（ADR-0099 D7，默认仍是开）；指令照旧能在这一次运行里改（ADR-0019）。
     */
    private boolean dummyAutoplay = true;

    public boolean dummyAutoplay() {
        return dummyAutoplay;
    }

    public void setDummyAutoplay(boolean on) {
        this.dummyAutoplay = on;
    }

    /**
     * 替身怎么拿主意（{@code /seas dummy random|smart|llm}，用户 2026-10-07）：自动推进开着时，替身不再一律「什么也不做 · 同意 ·
     * 不加入 · 不押」—— {@link StandInMind#RANDOM} 照模拟器的分布随机动（{@code StandInPlay}），
     * {@link StandInMind#SMART} / {@link StandInMind#LLM} 按这一座看得见的局面想（{@code StandInMinds}）。
     *
     * <p><b>默认 {@link StandInMind#IDLE}，不持久化</b>：回归脚本都按「替身什么也不做」写，开着就全红了；与 {@link #dummyAutoplay} 同一个理由挂在组件上。
     * 起服时按服务端设置 {@code stand_ins.mind} 定初值（默认「什么也不做」，经 {@link #setDummyMind}）；这一次运行里由指令说了算。
     */
    private StandInMind dummyMind = StandInMind.IDLE;

    public StandInMind dummyMind() {
        return dummyMind;
    }

    public void setDummyMind(StandInMind mind) {
        this.dummyMind = Objects.requireNonNull(mind, "mind");
    }

    /**
     * 替身是不是在<b>随机</b>地动（{@link StandInMind#RANDOM}）—— 只给那几条「随机数怎么摇」的路问。
     * 问「替身会不会动」（拍子、尾巴、演示局不限时）一律用 {@link #standInsAct()}。
     */
    public boolean dummyRandom() {
        return dummyMind == StandInMind.RANDOM;
    }

    /**
     * 随机开关（{@code /seas dummy random on|off}）。关掉时只关「随机」：别的脑子（动脑 · 大模型）不受这一下影响。
     */
    public void setDummyRandom(boolean on) {
        if (on) {
            this.dummyMind = StandInMind.RANDOM;
        } else if (dummyMind == StandInMind.RANDOM) {
            this.dummyMind = StandInMind.IDLE;
        }
    }

    /** 替身会自己动（脑子不是 {@link StandInMind#IDLE}）。原先问 {@code dummyRandom()} 而意思是「替身会动」的地方都问它。 */
    public boolean standInsAct() {
        return dummyMind.acts();
    }

    /**
     * 「动脑」与「大模型」两种替身用的随机种子（每局一个）：开局时由开局那一串种子派生（{@code GameFlow#start}），
     * 指定了种子的一局，动脑的替身也照样可复现。❗不从 {@link #gameRandom} 里取 —— 那条流是「天意」（抢到哪一张手牌）用的，
     * 多取一次就改了天意，同一个种子打出的局就跟着变了。
     */
    private long standInSeed = new java.util.Random().nextLong();

    public long standInSeed() {
        return standInSeed;
    }

    public void setStandInSeed(long seed) {
        this.standInSeed = seed;
    }

    /**
     * 大模型那一种脑子只给哪几座（{@code /seas dummy llm seats}）；空 = 每一座替身都问大模型。不在里面的替身照动脑那一层走。
     * 与 {@link #dummyMind} 一样不持久化、跨局留着（开发期的开关，不是对局的一部分）。
     */
    private final java.util.Set<CharacterId> llmSeats = new java.util.LinkedHashSet<>();

    public java.util.Set<CharacterId> llmSeats() {
        return java.util.Set.copyOf(llmSeats);
    }

    public void setLlmSeats(java.util.Collection<CharacterId> seats) {
        llmSeats.clear();
        llmSeats.addAll(seats);
    }

    /** 随机行动的快档（{@code /seas dummy random on fast}）：调试用，替身几乎不停顿。默认慢档（用户 2026-10-07「demo 玩家不要出牌太快」）。 */
    private boolean dummyFast = false;

    public boolean dummyFast() {
        return dummyFast;
    }

    public void setDummyFast(boolean on) {
        this.dummyFast = on;
    }

    /** 「不限时」的窗口有多长：一年。客户端见到窗口长过一天就画「∞」、倒计时条不动（用户 2026-10-07）。 */
    public static final long UNLIMITED_MS = 365L * 24 * 3600 * 1000;

    /**
     * 演示局里真人不限时（用户 2026-10-07：「demo 局里真人不限时」）：这一局是演示局（{@link #isDemo}）、替身会自己动、有真人坐着。
     * 只认随机开关开着的局 —— 回归脚本都关着它跑，那几条「超时替你选」的路照旧测得到。
     *
     * <p>再加一道显式的开关（ADR-0099 D7 · 服务端设置 {@code demo.untimed_humans}，开局快照进 {@link #timing}）：
     * 默认开，与原先「四条都成立就不限时」一模一样；关掉之后随机替身的局照样限时。
     * ❗原先正式局不会变成不限时，只靠「随机默认关、不持久化」（ADR-0097）—— 随机的默认值进了设置之后，那道闸要靠这一项。
     */
    public boolean demoNoTimeout() {
        return timing.untimedDemo() && demo && dummyAutoplay && standInsAct() && anyHumanSeated();
    }

    /**
     * 这一局是不是演示局：开局时定死（{@code GameFlow#start}）—— {@code /seas start} 开的、名单里有替身的局。
     *
     * <p>❗审查 2026-10-07 R5：原先有两份定义 —— {@code Seats} 认「名单里有替身就算演示局（不钉人）」，
     * 不限时那一侧认上面那五项。服务端设置 {@code stand_ins.fill_empty_seats} 加进来以后，演习艇开的正式局也会补替身，
     * 于是被 {@code Seats} 当成演示局、人不再钉在座位上。两处现在都问这一个标志。按倾向定（用户可推翻）：演习艇开航的局算正式局。
     */
    private boolean demo;

    public boolean isDemo() {
        return demo;
    }

    public void setDemo(boolean demo) {
        this.demo = demo;
    }

    /** 等真人的那一扇窗口开多长：演示局不限时，否则照给的毫秒数。只给「在等真人」的窗口用。 */
    public long humanWindow(long millis) {
        return demoNoTimeout() ? UNLIMITED_MS : millis;
    }

    /**
     * 这一扇窗口里已经表过态、不必再等的真人（站队选了旁观或加入 · 押完武器 · 不给水 · 落海时不用牌）。
     * 每扇窗口开时清空（{@link #clearDecided}）。所有该答的人都答了，窗口就提前收（ADR-0095 D1）——
     * 不限时的演示局里没有这一条就会一直等下去。
     */
    private final java.util.Set<CharacterId> decided = new java.util.HashSet<>();

    public void markDecided(CharacterId who) {
        decided.add(who);
    }

    public boolean hasDecided(CharacterId who) {
        return decided.contains(who);
    }

    public void clearDecided() {
        decided.clear();
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
    public record Step(long dueMs, Session session, String what, Runnable action, long seq) {
    }

    /**
     * 按到期时刻排、同一时刻按排进来的先后（{@code seq}）。
     *
     * <p>❗原先是先进先出、只看队头到没到期（「流程上同一时刻只会有一步排着」）。替身随机行动之后不再成立：
     * 站队、押武器一次排好几步、各带一个随机延迟（StandInPlay），队头那一步要 6 秒，后面 1.5 秒的就被它堵住，
     * 等它一到，后面几步一 tick 一步地连着做完 —— 屏幕上就是「替身加入防守 / 进攻没有延迟」（用户 2026-10-07），
     * 下一个替身的回合也跟着被拖后。按到期时刻排，谁先到谁先做（ADR-0095 F1）。
     */
    private final PriorityQueue<Step> steps = new PriorityQueue<>(
            Comparator.comparingLong(Step::dueMs).thenComparingLong(Step::seq));
    private long stepSeq;

    /** 排一步。只能在有对局时排：排进来的一步都属于当前这一局。 */
    public void schedule(long dueMs, String what, Runnable action) {
        steps.add(new Step(dueMs, requireSession(), what, action, stepSeq++));
    }

    /**
     * 丢掉当前阶段尚未执行的动作。终局接管流程时调用，避免同一 tick 里已经排好的替身动作
     * 在终局第一幕期间继续改变已经结算的对局。
     */
    public void clearScheduledSteps() {
        steps.clear();
    }

    /**
     * 到期的下一步；属于别的对局的一律丢掉。
     *
     * <p>每 tick 至多取一步：一步推进一个人，客户端的投影才一格一格地跟得上。
     * 取的是<b>最早到期</b>的那一步（见 {@link #steps}）；同一时刻到期的按排进来的先后。
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
        begin(started, seats, Set.of());
    }

    public void begin(Session started, Map<CharacterId, Occupant> seats, Set<UUID> audience) {
        overboardDeadline = 0;
        overboardWindow = 0;
        standInsOverboard = false;
        standInsThirst = false;
        demo = false;                         // 开局方随后按这一局怎么开的定（GameFlow.start，审查 R5）
        timing = GameTiming.DEFAULTS;         // 开局方随后按服务端设置换成这一局的快照（GameFlow.start）
        setWaterBodies(List.of(), 0);
        clearDesignation();
        clearProvisionTarget();
        this.session = started;
        this.occupants.clear();
        this.occupants.putAll(seats);
        activeVoyagePlayers.clear();
        activeVoyagePlayers.addAll(audience);
        seats.values().stream().filter(o -> !o.isDummy()).map(Occupant::player)
                .forEach(activeVoyagePlayers::add);
        this.interruptedTurn = 0;
        this.pendingDummies.clear();
        this.endedFor.clear();
        clearProvision();
        clearHelm();
        clearThirst();
        notifications.clear();
        notificationSeq = 0;
        notificationJsonCache.clear();
        notificationSent.clear();
        notificationEpoch = UUID.randomUUID();
        extraProvisionPending = false;
        endgame = null;
        fogCleared = false;
        steps.clear();
        debugEntries.clear();
        gameRandom = new java.util.Random();
        // 开局方随后按开局种子换成可复现的那一个（GameFlow.start）。❗不从 gameRandom 里取：那条流一个数都不能多摇
        standInSeed = new java.util.Random().nextLong();
    }

    // ------------------------------------------------------------------ 调试留痕（ADR-0060）

    /**
     * 这一局里用过的一条调试指令。
     *
     * @param who     谁下的（玩家名；控制台是 Server，RCON 是 Rcon）
     * @param turn    第几天
     * @param command 指令原文
     * @param changed 改了局面没有；只读的（查看 · 导出）为假
     */
    public record DebugEntry(String who, int turn, String command, boolean changed) {

        public DebugEntry {
            Objects.requireNonNull(who, "who");
            Objects.requireNonNull(command, "command");
        }
    }

    /**
     * 这一局的调试记录。❗{@link #end()} <b>不清</b>：一局打完还要能 {@code /seas debug log} 看它；下一局开局时清。
     * 不持久化 —— 对局本身就不持久化。
     */
    private final List<DebugEntry> debugEntries = new ArrayList<>();

    public void recordDebug(DebugEntry entry) {
        debugEntries.add(Objects.requireNonNull(entry, "entry"));
    }

    public List<DebugEntry> debugEntries() {
        return List.copyOf(debugEntries);
    }

    /** 这一局有几处调试改动（计分面板上那枚章的数）。 */
    public int debugChanges() {
        return (int) debugEntries.stream().filter(DebugEntry::changed).count();
    }

    /** D8（ADR-0060）：调试改过局面的这一局，不计入以后的长线进度。 */
    public boolean debugged() {
        return debugChanges() > 0;
    }

    /**
     * 这一局对局中途要用的随机源（抢夺时从手牌里随机挑一张）。开局时由开局那一串种子派生 ——
     * 指定了种子（{@code /seas debug next seed}）的一局因此整局可复现，而不只是开局那一刻。
     */
    private java.util.Random gameRandom = new java.util.Random();

    public java.util.Random gameRandom() {
        return gameRandom;
    }

    public void setGameRandom(java.util.Random random) {
        this.gameRandom = Objects.requireNonNull(random, "random");
    }

    /**
     * {@code /seas debug timer now}（ADR-0060）：此刻开着的倒计时一律<b>立刻到点</b>。
     *
     * <p>只改「什么时候到点」，超时那一步仍由各阶段自己的 tick 照本来的规矩走 —— 不在这里替谁做决定。
     *
     * @return 到点了哪几面（与语言无关的名字：action · provision · helm · thirst · contest · designation · overboard）
     */
    public List<String> expireOpenWindows(long now) {
        List<String> expired = new ArrayList<>();
        if (actionDeadline > 0) {
            actionDeadline = now;
            expired.add("action");
        }
        if (provisionDeadline > 0) {
            provisionDeadline = now;
            expired.add("provision");
        }
        if (helmDeadline > 0) {
            helmDeadline = now;
            expired.add("helm");
        }
        if (thirstDeadline > 0) {
            thirstDeadline = now;
            expired.add("thirst");
        }
        if (contestDeadline > 0) {
            contestDeadline = now;
            expired.add("contest");
        }
        if (designationDeadline > 0) {
            designationDeadline = now;
            expired.add("designation");
        }
        if (overboardDeadline > 0) {
            overboardDeadline = now;
            expired.add("overboard");
        }
        return List.copyOf(expired);
    }

    public boolean belongsToActiveVoyage(UUID player) {
        return session != null && activeVoyagePlayers.contains(player);
    }

    private final Set<UUID> activeVoyagePlayers = new java.util.HashSet<>();
    /** 刚结束那一局的人（入座的与观众）：{@link #end()} 记下，散局时 {@code MistSea.restoreAll} 按它把过了魔镜的人送回北辰号。 */
    private final Set<UUID> endedVoyagePlayers = new java.util.HashSet<>();

    public Set<UUID> endedVoyagePlayers() {
        return Set.copyOf(endedVoyagePlayers);
    }

    /**
     * 这一局摆在世界里的那几个座位（船头到船尾）· ADR-0024。
     *
     * <p>❗<b>不写进 NBT，也不该写</b>：对局本身就不持久化（存档时服务端会说「这一局不会被保存」），
     * 而实体是要存盘的 —— 存了它，重启之后就会有一份指着一局并不存在的对局的座位名单。
     * 起服时那些座位会在实体加载后当作孤儿清掉（{@code Seats#onSeatLoaded} → {@code Seats#tick}）。
     */
    private List<UUID> seatIds = List.of();

    /** Visible hull pieces and gulls are runtime projections, just like seats; they are not persisted. */
    private List<UUID> boatDisplayIds = List.of();
    private List<UUID> gullIds = List.of();

    /** 靠岸时的布景实体（ADR-0034 §5.3），与布局里 {@code backdrops} 同序。运行时投影，不持久化；孤儿靠标签清。 */
    private List<UUID> backdropIds = List.of();

    public List<UUID> backdropIds() {
        return backdropIds;
    }

    public void setBackdropIds(List<UUID> ids) {
        this.backdropIds = List.copyOf(ids);
    }

    /** 补给箱实物（ADR-0034 §5.4）与它现在停在第几位（−1 = 没有箱子）。运行时投影，不持久化。 */
    private UUID crateId;
    private int crateSeat = -1;

    public Optional<UUID> crateId() {
        return Optional.ofNullable(crateId);
    }

    public void setCrateId(UUID id) {
        this.crateId = id;
    }

    public int crateSeat() {
        return crateSeat;
    }

    public void setCrateSeat(int seat) {
        this.crateSeat = seat;
    }

    /** Dense Mist Sea fog clears when the fourth gull starts the shore approach. */
    private boolean fogCleared;

    /**
     * 这一局用的哪份航程布局（ADR-0034 §5.5）。运行时状态，不持久化。
     *
     * <p>❗{@link #end()} <b>不清它</b>：收场景（船体清回海水 · 解除强加载）要靠它，而那一步在
     * {@code MistSea.restoreAll} 里、排在 {@code end()} 之后。由 {@code MistSea.cleanupScene} 清。
     */
    private Identifier layoutId;

    /**
     * 场景在世界里留下的东西：船体的脚印与强加载的 chunk。
     *
     * <p>❗<b>持久化</b>，与座位相反：船体是真方块、强加载票也进存档，崩在对局中时下次起服要靠它清
     * （ADR-0024 那一课：凡是写进存档的东西都要在退出路径与起服两处收）。
     */
    private SceneLeftover sceneLeftover;

    /** 这一局用的那份布局与它的雾表：开局时整份快照进来（审查 2026-10-07 C6），{@code /reload} 换掉数据也不影响这一局。 */
    private io.github.heavyseasmc.mod.data.VoyageLayout layout;
    private FogTable fogTable;

    public Optional<Identifier> layoutId() {
        return Optional.ofNullable(layoutId);
    }

    /** 这一局的布局快照；没开过局（或场景已收）时为空。 */
    public Optional<io.github.heavyseasmc.mod.data.VoyageLayout> layout() {
        return Optional.ofNullable(layout);
    }

    /** 开局时记下这一局的布局与雾表（整份快照，不是 id）。 */
    public void setLayout(io.github.heavyseasmc.mod.data.VoyageLayout layout, FogTable fog) {
        this.layout = Objects.requireNonNull(layout, "layout");
        this.fogTable = Objects.requireNonNull(fog, "fog");
        this.layoutId = layout.id();
    }

    public void clearLayoutId() {
        this.layoutId = null;
        this.layout = null;
        this.fogTable = null;
    }

    public Optional<SceneLeftover> sceneLeftover() {
        return Optional.ofNullable(sceneLeftover);
    }

    public void setSceneLeftover(SceneLeftover leftover) {
        this.sceneLeftover = Objects.requireNonNull(leftover, "leftover");
    }

    public void clearSceneLeftover() {
        this.sceneLeftover = null;
    }

    /**
     * 船体在世界里占的那一块。
     *
     * @param box          放置后的包围盒
     * @param waterTop     放置前锚点脚下最上一层水的 y；清的时候这一层及以下填水、以上填空气
     * @param restoreWater 要不要清；地图自带船体的布局为假
     */
    public record HullFootprint(BlockBox box, int waterTop, boolean restoreWater) {

        public HullFootprint {
            Objects.requireNonNull(box, "box");
        }
    }

    /** @param forcedChunks 强加载过的 chunk（{@code ChunkPos#toLong}） */
    public record SceneLeftover(Optional<HullFootprint> hull, List<Long> forcedChunks) {

        public SceneLeftover {
            Objects.requireNonNull(hull, "hull");
            forcedChunks = List.copyOf(forcedChunks);
        }
    }

    public List<UUID> seatIds() {
        return seatIds;
    }

    public void setSeatIds(List<UUID> ids) {
        this.seatIds = List.copyOf(ids);
    }

    public List<UUID> boatDisplayIds() {
        return boatDisplayIds;
    }

    public void setBoatDisplayIds(List<UUID> ids) {
        this.boatDisplayIds = List.copyOf(ids);
    }

    public List<UUID> gullIds() {
        return gullIds;
    }

    public void setGullIds(List<UUID> ids) {
        this.gullIds = List.copyOf(ids);
    }

    public boolean fogCleared() {
        return fogCleared;
    }

    public void clearFog() {
        fogCleared = true;
    }

    /**
     * 指定模式（ADR-0025）：谁在举着拳头找人、要做什么、到点时刻。
     *
     * <p>❗与另外几个窗口同一个写法：**服务端超时，客户端不参与判定**。
     * 和座位一样不写进 NBT —— 对局本身就不持久化。
     */
    private CharacterId designating;
    private Contest.Kind designationKind;
    private long designationDeadline;
    private long designationSerial;

    public Optional<CharacterId> designating() {
        return Optional.ofNullable(designating);
    }

    public Optional<Contest.Kind> designationKind() {
        return Optional.ofNullable(designationKind);
    }

    public long designationDeadline() {
        return designationDeadline;
    }

    public long designationSerial() {
        return designationSerial;
    }

    public void beginDesignation(CharacterId who, Contest.Kind kind, long millis) {
        designationSerial++;
        this.designating = who;
        this.designationKind = kind;
        this.designationDeadline = System.currentTimeMillis() + millis;
    }

    public void clearDesignation() {
        designationSerial++;
        this.designating = null;
        this.designationKind = null;
        this.designationDeadline = 0L;
    }

    /**
     * 特殊物资的两步选择：谁正在替哪张牌挑目标。现在只有医疗箱需要这一步。
     * 与指定模式分开存：医疗箱是私有菜单，没有预告、发光与超时。
     */
    private CharacterId provisionTargeter;
    private String provisionTargetCard = "";

    public Optional<CharacterId> provisionTargeter() {
        return Optional.ofNullable(provisionTargeter);
    }

    public String provisionTargetCard() {
        return provisionTargetCard;
    }

    public void beginProvisionTarget(CharacterId who, String card) {
        this.provisionTargeter = who;
        this.provisionTargetCard = card;
    }

    public void clearProvisionTarget() {
        this.provisionTargeter = null;
        this.provisionTargetCard = "";
    }

    public void end() {
        overboardDeadline = 0;
        overboardWindow = 0;
        standInsOverboard = false;
        standInsThirst = false;
        clearContest();                       // 原先不清（审查 2026-10-07 原稿第 2 块 #8）：每扇窗开时都会重设，目前无害，收场时一并归零
        clearDecided();
        setWaterBodies(List.of(), 0);
        occupants.values().stream().filter(o -> !o.isDummy()).map(Occupant::player).forEach(endedFor::add);
        endedFor.addAll(activeVoyagePlayers);             // 结束那一帧只发给这一局的人（同 shouldSyncWith）
        endedVoyagePlayers.clear();
        endedVoyagePlayers.addAll(endedFor);              // 散局之后谁回北辰号（MistSea.restoreAll 在 end() 之后读）
        clearDesignation();
        clearProvisionTarget();
        clearActionWindow();
        this.session = null;
        this.occupants.clear();
        this.activeVoyagePlayers.clear();
        clearProvision();
        clearHelm();
        clearThirst();
        notifications.clear();
        notificationSeq = 0;
        notificationJsonCache.clear();
        notificationSent.clear();
        extraProvisionPending = false;
        endgame = null;
        seatIds = List.of();
        boatDisplayIds = List.of();
        gullIds = List.of();
        backdropIds = List.of();
        crateId = null;
        crateSeat = -1;
        fogCleared = false;
        steps.clear();
    }

    public void notify(Text message) {
        notifications.addLast(Objects.requireNonNull(message, "message").copy());
        notificationSeq++;
        while (notifications.size() > MAX_NOTIFICATIONS) {
            notificationJsonCache.remove(notificationSeq - notifications.size() + 1);
            notifications.removeFirst();
        }
    }

    /** 这一局一共播了几条（见 {@link #notificationSeq} 字段）。 */
    public long notificationSeq() {
        return notificationSeq;
    }

    /** 右栏此刻的那几条（最多 {@value #MAX_NOTIFICATIONS} 条，旧的在前）。只读的一份拷贝：调试导出用（ADR-0060）。 */
    public List<Text> notifications() {
        return List.copyOf(notifications);
    }

    private List<String> notificationJson(RegistryWrapper.WrapperLookup registry, long first) {
        List<String> result = new ArrayList<>();
        long sequence = notificationSeq - notifications.size() + 1;
        for (Text notification : notifications) {
            if (sequence >= first) {
                result.add(notificationJsonCache.computeIfAbsent(sequence,
                        ignored -> Text.Serialization.toJsonString(notification, registry)));
            }
            sequence++;
        }
        return result;
    }

    public UUID notificationEpoch() {
        return notificationEpoch;
    }

    /** 重连需要完整的保留历史；也不让旁观者 UUID 留在上一条连接的投递表里。 */
    public void forgetNotificationRecipient(UUID player) {
        notificationSent.remove(player);
    }

    public void setExtraProvisionPending(boolean pending) {
        extraProvisionPending = pending;
    }

    public boolean takeExtraProvisionPending() {
        boolean pending = extraProvisionPending;
        extraProvisionPending = false;
        return pending;
    }

    public Map<CharacterId, Occupant> occupants() {
        return Map.copyOf(occupants);
    }

    public Optional<Occupant> occupantOf(CharacterId id) {
        return Optional.ofNullable(occupants.get(id));
    }

    /** 座位上有没有真人。有人要看，节奏才需要停下来等人读（ADR-0019：航海结算后的停顿）。 */
    public boolean anyHumanSeated() {
        return session != null && occupants.entrySet().stream()
                .anyMatch(e -> !e.getValue().isDummy() && !session.state().isOffline(e.getKey()));
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

    /**
     * A player's real inventory and return point while their game body is isolated in the Mist Sea.
     * Unlike the rule session this record is persisted: it is the recovery contract after a crash/restart.
     */
    public record VoyageEscrow(UUID player, String dimension, double x, double y, double z,
                               float yaw, float pitch, NbtList inventory, Optional<BodySnapshot> body,
                               Optional<MirrorAt> mirror) {
        public VoyageEscrow {
            inventory = inventory.copy();
            Objects.requireNonNull(body, "body");
            Objects.requireNonNull(mirror, "mirror");
        }

        public VoyageEscrow(UUID player, String dimension, double x, double y, double z,
                            float yaw, float pitch, NbtList inventory, Optional<BodySnapshot> body) {
            this(player, dimension, x, y, z, yaw, pitch, inventory, body, Optional.empty());
        }

        public VoyageEscrow(UUID player, String dimension, double x, double y, double z,
                            float yaw, float pitch, NbtList inventory) {
            this(player, dimension, x, y, z, yaw, pitch, inventory, Optional.empty(), Optional.empty());
        }

        /** 过魔镜托管的（人在北辰号上或这一局里，回程走镜子）；空 = 开局时托管的老路（{@code /seas start}，散局就还）。 */
        public boolean viaMirror() {
            return mirror.isPresent();
        }
    }

    /**
     * 玩家穿过来的那面镜子：维度 + 镜子下面一层正中那一格（镜面正中的正下方）。回程先看它还在不在（先把那一格的区块载进来再查，ADR-0065 样张页第 9 步），
     * 在就回到镜子前（托管里记的那个位置），不在就送重生点。
     */
    public record MirrorAt(String dimension, BlockPos pos) {
        public MirrorAt {
            Objects.requireNonNull(dimension, "dimension");
            pos = pos.toImmutable();
        }
    }

    public record BodySnapshot(double maxHealth, float health, float absorption, String gameMode,
                               NbtCompound hunger) {
        public BodySnapshot {
            hunger = hunger.copy();
        }

        public NbtCompound write() {
            NbtCompound tag = new NbtCompound();
            tag.putDouble("max_health", maxHealth);
            tag.putFloat("health", health);
            tag.putFloat("absorption", absorption);
            tag.putString("game_mode", gameMode);
            tag.put("hunger", hunger.copy());
            return tag;
        }

        public static BodySnapshot read(NbtCompound tag) {
            return new BodySnapshot(tag.getDouble("max_health"), tag.getFloat("health"),
                    tag.getFloat("absorption"), tag.getString("game_mode"), tag.getCompound("hunger"));
        }
    }

    private Set<CharacterId> waterBodies = Set.of();
    private long waterBodiesUntil;

    public boolean bodyInWater(CharacterId id) {
        return System.currentTimeMillis() < waterBodiesUntil && waterBodies.contains(id);
    }

    public void setWaterBodies(List<CharacterId> ids, long until) {
        waterBodies = Set.copyOf(ids);
        waterBodiesUntil = until;
    }

    private final Map<UUID, VoyageEscrow> voyageEscrows = new LinkedHashMap<>();

    public boolean hasVoyageEscrow(UUID player) {
        return voyageEscrows.containsKey(player);
    }

    public void putVoyageEscrow(VoyageEscrow escrow) {
        voyageEscrows.put(escrow.player(), escrow);
    }

    public Optional<VoyageEscrow> voyageEscrow(UUID player) {
        return Optional.ofNullable(voyageEscrows.get(player));
    }

    public Optional<VoyageEscrow> removeVoyageEscrow(UUID player) {
        return Optional.ofNullable(voyageEscrows.remove(player));
    }

    public Set<UUID> voyageEscrowPlayers() {
        return Set.copyOf(voyageEscrows.keySet());
    }

    @Override
    public void readFromNbt(NbtCompound tag, RegistryWrapper.WrapperLookup registryLookup) {
        interruptedTurn = tag.getInt(KEY_INTERRUPTED_TURN);
        voyageEscrows.clear();
        NbtList escrows = tag.getList(KEY_ESCROWS, NbtElement.COMPOUND_TYPE);
        for (int i = 0; i < escrows.size(); i++) {
            NbtCompound saved = escrows.getCompound(i);
            if (!saved.containsUuid("player")) {
                continue;
            }
            VoyageEscrow escrow = new VoyageEscrow(saved.getUuid("player"), saved.getString("dimension"),
                    saved.getDouble("x"), saved.getDouble("y"), saved.getDouble("z"),
                    saved.getFloat("yaw"), saved.getFloat("pitch"),
                    saved.getList("inventory", NbtElement.COMPOUND_TYPE),
                    saved.contains("body", NbtElement.COMPOUND_TYPE)
                            ? Optional.of(BodySnapshot.read(saved.getCompound("body"))) : Optional.empty(),
                    saved.contains("mirror", NbtElement.COMPOUND_TYPE)
                            ? Optional.of(new MirrorAt(saved.getCompound("mirror").getString("dimension"),
                            BlockPos.fromLong(saved.getCompound("mirror").getLong("pos")))) : Optional.empty());
            voyageEscrows.put(escrow.player(), escrow);
        }
        sceneLeftover = null;
        if (tag.contains(KEY_SCENE, NbtElement.COMPOUND_TYPE)) {
            NbtCompound scene = tag.getCompound(KEY_SCENE);
            Optional<HullFootprint> hull = Optional.empty();
            if (scene.contains("hull", NbtElement.COMPOUND_TYPE)) {
                NbtCompound h = scene.getCompound("hull");
                hull = Optional.of(new HullFootprint(new BlockBox(
                        h.getInt("min_x"), h.getInt("min_y"), h.getInt("min_z"),
                        h.getInt("max_x"), h.getInt("max_y"), h.getInt("max_z")),
                        h.getInt("water_top"), h.getBoolean("restore_water")));
            }
            List<Long> forced = new ArrayList<>();
            for (long packed : scene.getLongArray("forced")) {
                forced.add(packed);
            }
            sceneLeftover = new SceneLeftover(hull, forced);
            LOGGER.warn("存档里留着上一次的场景脚印（船体 {} · 强加载 {} 个 chunk）：起服后清。",
                    hull.isPresent() ? "有" : "无", forced.size());
        }
        if (interruptedTurn > 0) {
            LOGGER.warn("上一局没有保存（存档时进行到第 {} 回合）—— M1 不持久化对局，"
                    + "用 /seas start 重开一局。", interruptedTurn);
        }
        if (!voyageEscrows.isEmpty()) {
            LOGGER.warn("发现 {} 份未完成的雾海托管：玩家上线时将恢复物品与返回位置。", voyageEscrows.size());
        }
    }

    @Override
    public void writeToNbt(NbtCompound tag, RegistryWrapper.WrapperLookup registryLookup) {
        // 存的是墓碑不是状态：让「重启丢了一局」与「本来就没开局」在下次加载时分得开。
        int turn = session == null ? 0 : session.state().turn();
        tag.putInt(KEY_INTERRUPTED_TURN, turn);
        NbtList escrows = new NbtList();
        for (VoyageEscrow escrow : voyageEscrows.values()) {
            NbtCompound saved = new NbtCompound();
            saved.putUuid("player", escrow.player());
            saved.putString("dimension", escrow.dimension());
            saved.putDouble("x", escrow.x());
            saved.putDouble("y", escrow.y());
            saved.putDouble("z", escrow.z());
            saved.putFloat("yaw", escrow.yaw());
            saved.putFloat("pitch", escrow.pitch());
            saved.put("inventory", escrow.inventory().copy());
            escrow.body().ifPresent(body -> saved.put("body", body.write()));
            escrow.mirror().ifPresent(mirror -> {
                NbtCompound m = new NbtCompound();
                m.putString("dimension", mirror.dimension());
                m.putLong("pos", mirror.pos().asLong());
                saved.put("mirror", m);
            });
            escrows.add(saved);
        }
        tag.put(KEY_ESCROWS, escrows);
        if (sceneLeftover != null) {
            NbtCompound scene = new NbtCompound();
            sceneLeftover.hull().ifPresent(footprint -> {
                NbtCompound h = new NbtCompound();
                h.putInt("min_x", footprint.box().getMinX());
                h.putInt("min_y", footprint.box().getMinY());
                h.putInt("min_z", footprint.box().getMinZ());
                h.putInt("max_x", footprint.box().getMaxX());
                h.putInt("max_y", footprint.box().getMaxY());
                h.putInt("max_z", footprint.box().getMaxZ());
                h.putInt("water_top", footprint.waterTop());
                h.putBoolean("restore_water", footprint.restoreWater());
                scene.put("hull", h);
            });
            long[] forced = new long[sceneLeftover.forcedChunks().size()];
            for (int i = 0; i < forced.length; i++) {
                forced[i] = sceneLeftover.forcedChunks().get(i);
            }
            scene.putLongArray("forced", forced);
            tag.put(KEY_SCENE, scene);
        }
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
