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
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.world.World;
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

    /** Owning world, used to include same-dimension spectators in the public projection. */
    private final World owner;

    private Session session;

    /** 系统事件改画在 HUD 侧边栏，不再写入玩家聊天记录。 */
    private static final int MAX_NOTIFICATIONS = 8;
    private final ArrayDeque<Text> notifications = new ArrayDeque<>();
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
        // M4: the finale is a world performance. Same-dimension spectators receive only the
        // public fields below; their per-player secrets remain empty because they have no seat.
        return player.getWorld().getRegistryKey().equals(owner.getRegistryKey());
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
        buf.writeString(session.currentWeather().map(card -> card.id()).orElse(""));
        buf.writeVarInt(notifications.size());
        for (Text notification : notifications) {
            buf.writeString(Text.Serialization.toJsonString(notification, buf.getRegistryManager()));
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
            buf.writeVarInt(thirstDonors.size());
            buf.writeVarInt(prompt.waterPerSource());
            buf.writeVarLong(thirstDeadline);
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
            buf.writeVarInt(endgame.order().size());
            for (int i = 0; i < endgame.order().size(); i++) {
                CharacterId who = endgame.order().get(i);
                buf.writeString(who.value());
                boolean open = revealing && i < endgame.flipped();
                CharacterId target = endgame.stage() == EndgameProgress.Stage.HATE ? aff.hateOf(who) : aff.loveOf(who);
                buf.writeString(open ? target.value() : "");
                buf.writeVarInt(scoring ? endgame.scores().get(who).total() : -1);
                buf.writeBoolean(endgame.isWinner(who));
            }
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
            writeSide(buf, g, fight.map(Fight::attackSide).orElse(Set.of()));
            writeSide(buf, g, fight.map(Fight::defendSide).orElse(Set.of()));
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
        // ❗划船抽到的牌还没定完时，nextActor 仍然是他（行动要等两张都定了才算完），但他该看的是划船一面，不是行动一面。
        // ❗这一场进行中时也不算「轮到你」：进攻方在收场之前一直还是 nextActor（行动是收场那一刻才记的），
        //   不排除的话，他的行动一面会在这一场当中弹出来，而按下去的那一下会撞上引擎的 requireNoContest ——
        //   那是一句堆栈，不是一次被拒绝的操作。
        // ❗举着拳头时也不算「轮到你选一件事」：不排掉的话，按下「换座位」之后行动一面会当场弹回来，
        //   而它发出的包会被服务端当作「已经在指定模式里了」静默丢掉 —— 屏幕上只是「按了没反应」。
        buf.writeBoolean(g.phase() == Phase.ACTION && g.nextActor().map(id::equals).orElse(false)
                && session.rower().isEmpty() && session.contest().isEmpty()
                && designating().isEmpty() && provisionTargeter().isEmpty());
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
     * 一边：站了谁（公开），加上他们的<b>体型和</b>。
     *
     * <p>❗只有体型。押下的武器是暗牌，加进来就等于提前把它亮了 —— 而且是以最难发现的方式：
     * 界面上只是一个数变大了，没有任何人会报错。
     */
    private static void writeSide(RegistryByteBuf buf, GameState g, Set<CharacterId> side) {
        buf.writeVarInt(side.size());
        int power = 0;
        for (CharacterId who : side) {
            buf.writeString(who.value());
            power += g.roster().get(who).size();
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
        String weather = buf.readString();
        int notificationCount = buf.readVarInt();
        List<Text> notifications = new ArrayList<>(notificationCount);
        for (int i = 0; i < notificationCount; i++) {
            Text message = Text.Serialization.fromJson(buf.readString(), buf.getRegistryManager());
            notifications.add(Objects.requireNonNull(message, "通知文本不能是 null"));
        }
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
        Optional<NavCardView> revealed = buf.readBoolean() ? Optional.of(NavCardView.read(buf)) : Optional.empty();
        HudView.Thirst thirstPrompt = HudView.Thirst.NONE;
        if (buf.readBoolean()) {
            thirstPrompt = new HudView.Thirst(buf.readString(), buf.readVarInt(), buf.readVarInt(),
                    buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarLong());
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
            endgame = new HudView.Endgame(outcome, alive, stage, flipped, entries);
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
            return new HudView(true, turn, phase, gulls, weather, notifications, seats, removed, actor,
                    new HudView.Sea(rowStack, helmsman, helmDeadline, revealed, List.of(), List.of()),
                    thirstPrompt, endgame, publicContest, false, "", 0, 0, Condition.CONSCIOUS, 0, "", "",
                    false, false, 0L, "", List.of(), 0, List.of(), List.of(), HudView.Score.NONE);
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
        return new HudView(true, turn, phase, gulls, weather, notifications, seats, removed, actor,
                new HudView.Sea(rowStack, helmsman, helmDeadline, revealed, rowing, offer),
                thirstPrompt, endgame, contest, true, character, health, maxHealth, condition, thirst,
                love, hate, yourTurn, designating, designateUntil,
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

    public void clearThirst() {
        this.thirstDeadline = 0L;
        this.thirstHighlight = 0;
        this.thirstDonors.clear();
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
        clearDesignation();
        clearProvisionTarget();
        this.session = started;
        this.occupants.clear();
        this.occupants.putAll(seats);
        this.interruptedTurn = 0;
        this.pendingDummies.clear();
        this.endedFor.clear();
        clearProvision();
        clearHelm();
        clearThirst();
        notifications.clear();
        extraProvisionPending = false;
        endgame = null;
        fogCleared = false;
        steps.clear();
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

    /** Dense Mist Sea fog clears when the fourth gull starts the shore approach. */
    private boolean fogCleared;

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

    public Optional<CharacterId> designating() {
        return Optional.ofNullable(designating);
    }

    public Optional<Contest.Kind> designationKind() {
        return Optional.ofNullable(designationKind);
    }

    public long designationDeadline() {
        return designationDeadline;
    }

    public void beginDesignation(CharacterId who, Contest.Kind kind, long millis) {
        this.designating = who;
        this.designationKind = kind;
        this.designationDeadline = System.currentTimeMillis() + millis;
    }

    public void clearDesignation() {
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
        occupants.values().stream().filter(o -> !o.isDummy()).map(Occupant::player).forEach(endedFor::add);
        if (owner instanceof ServerWorld serverWorld) {
            serverWorld.getPlayers().stream().map(ServerPlayerEntity::getUuid).forEach(endedFor::add);
        }
        clearDesignation();
        clearProvisionTarget();
        this.session = null;
        this.occupants.clear();
        clearProvision();
        clearHelm();
        clearThirst();
        notifications.clear();
        extraProvisionPending = false;
        endgame = null;
        seatIds = List.of();
        boatDisplayIds = List.of();
        gullIds = List.of();
        fogCleared = false;
        steps.clear();
    }

    public void notify(Text message) {
        notifications.addLast(Objects.requireNonNull(message, "message"));
        while (notifications.size() > MAX_NOTIFICATIONS) {
            notifications.removeFirst();
        }
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

    /**
     * A player's real inventory and return point while their game body is isolated in the Mist Sea.
     * Unlike the rule session this record is persisted: it is the recovery contract after a crash/restart.
     */
    public record VoyageEscrow(UUID player, String dimension, double x, double y, double z,
                               float yaw, float pitch, NbtList inventory) {
        public VoyageEscrow {
            inventory = inventory.copy();
        }
    }

    private final Map<UUID, VoyageEscrow> voyageEscrows = new LinkedHashMap<>();

    public boolean hasVoyageEscrow(UUID player) {
        return voyageEscrows.containsKey(player);
    }

    public void putVoyageEscrow(VoyageEscrow escrow) {
        voyageEscrows.put(escrow.player(), escrow);
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
                    saved.getList("inventory", NbtElement.COMPOUND_TYPE));
            voyageEscrows.put(escrow.player(), escrow);
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
            escrows.add(saved);
        }
        tag.put(KEY_ESCROWS, escrows);
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
