package io.github.heavyseasmc.mod.game;

import io.github.heavyseasmc.engine.model.CharacterId;
import io.github.heavyseasmc.engine.play.Session;
import io.github.heavyseasmc.engine.state.Phase;
import io.github.heavyseasmc.mod.HeavySeasMod;
import io.github.heavyseasmc.mod.net.ThirstActionC2S;
import io.github.heavyseasmc.mod.net.WaterDonationC2S;
import io.github.heavyseasmc.mod.state.GameComponent;
import io.github.heavyseasmc.mod.state.GameComponents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 口渴结算的服务端一侧：逐个问「喝不喝水」，超时代答，最后交回 {@link GameFlow} 收尾（ADR-0021）。
 *
 * <h2>为什么要逐个问，而且必须串行</h2>
 * 陪酒女排在所有人之后结算，为的就是<b>看着别人喝完再决定自己喝不喝</b>。
 * 并行收集所有人的决定会把这条规则抹掉，而抹掉之后没有任何断言会红 ——
 * 她照样能蹭到水，只是蹭的是「同时发生的那一轮」而不是「她之前发生的那些」。
 *
 * <h2>自己没得选，不等于全船没得选</h2>
 * 三种情况里只有前一种直接结算；后两种若船上有清醒真人拿得出水，仍开同一个窗口让旁人救他：
 * <ul>
 *   <li>这个人这一回合一点都不渴（引擎排队时就已经剔除）；</li>
 *   <li>他自己拿不出水 —— 本人没有决定，但别人可以替他打；</li>
 *   <li>他不清醒 —— <b>昏迷者不能自己打水</b>（规则 §9.3），旁人的水正是唯一救法。</li>
 * </ul>
 * 只有全船确实没人能帮时才立即按不喝结算，避免无意义地等满 12 秒。
 *
 * <h2>超时认当前高亮，而高亮的默认值是「喝够」</h2>
 * 与另外三面同一条规则（用户 2026-09-15 定）。区别在于<b>默认答案取什么</b>：
 * 补给箱与舵手堆默认第一张（没有更好的猜测），而这里有 ——
 * <b>口渴造成的伤害是必然的，而水的唯一用途就是化解它</b>。所以一进界面就预选「刚好够」，
 * 想省水的人自己往下调。把默认值定成 0 的话，挂机的人会一边攥着水一边掉血，
 * 那不是「他的默认答案」，那是替他做了一个没人会做的选择。
 */
public final class ThirstPhase {

    /** 每个人的决定时限。与舵手挑牌同一个数：都是「看一眼就能答」的决定。12 → 20 秒（用户 2026-10-07：决策窗口一律至少 20 秒，「有的时候决策时间太短」）。默认值；这一局实际用的在 {@link GameTiming}（ADR-0099 D8）。 */
    public static final long CHOOSE_MILLIS = 20_000L;

    private static final Logger LOGGER = LoggerFactory.getLogger(HeavySeasMod.MOD_ID);

    private ThirstPhase() {
    }

    /**
     * 开始结算这一张牌的口渴。由 {@link GameFlow#navigate} 在落海之后调用。
     *
     * <p>❗<b>不递归</b>：每处理完一个人就把下一个排到下一 tick。理由与替身自动推进同一条 ——
     * 全是替身的一局会在一次调用里把整个队列走完，客户端一帧都看不到。
     */
    public static void begin(ServerWorld world, GameComponent component) {
        advance(world, component);
    }

    /** 处理队首：能直接结算的直接结算，需要人答的开窗口。 */
    private static void advance(ServerWorld world, GameComponent component) {
        Session session = component.requireSession();
        Optional<Session.ThirstPrompt> pending = session.thirstPending();
        if (pending.isEmpty()) {
            component.clearThirst();
            GameFlow.afterNavigation(world, component);
            return;
        }
        Session.ThirstPrompt prompt = pending.get();
        CharacterId who = prompt.who();
        GameComponent.Occupant occupant = component.occupantOf(who).orElseThrow();

        int own = session.watersOf(who);
        // ❗一次都不必化解（阳伞挡下了，或者蹭到了别人喝的水）—— 那不是「没水硬扛」，那是好事。
        //   1280x720 实拍抓到：陪酒女蹭到水之后被报成「一张水都没有，只能硬扛 0 次」。
        if (prompt.remaining() == 0) {
            if (prompt.shared() > 0) {
                GameFlow.broadcast(world, Text.translatable("heavyseas.game.thirst_shared",
                        GameFlow.characterName(who), prompt.shared()).formatted(Formatting.DARK_GRAY));
            } else if (prompt.covered() > 0) {
                GameFlow.broadcast(world, Text.translatable("heavyseas.game.thirst_covered",
                        GameFlow.characterName(who), prompt.covered()).formatted(Formatting.DARK_GRAY));
            }
            LOGGER.info("口渴：{} 不必化解（遮蔽 {} · 蹭到 {}）", who.value(), prompt.covered(), prompt.shared());
            resolve(world, component, 0);
            return;
        }
        boolean canDecide = canDecide(session, prompt);
        boolean canReceiveDonation = hasHumanDonor(session, component, who);
        if (StandInMinds.thinks(component)) {
            if (canDecide && occupant.isDummy()) {
                // 动脑 / 大模型：他自己定喝几次、还差的开不开口，再依座位问有水的替身递不递（StandInMinds.thirst）
                LOGGER.info("口渴（替身）：{} 在想喝几张（还需化解 {} 次 · 手上 {} 张）", who.value(), prompt.remaining(), own);
                StandInMinds.thirst(world, component, prompt, true);
                return;
            }
            if (!canDecide && !canReceiveDonation && StandInMinds.anyStandInDonor(session, component, prompt)) {
                // 他自己定不了（昏迷 · 水不够），也没有真人能递：船上有水的替身还能递
                LOGGER.info("口渴（替身）：{} 自己定不了（{} · 水 {} 张），问有水的替身递不递", who.value(),
                        session.state().conditionOf(who), own);
                StandInMinds.thirst(world, component, prompt, occupant.isDummy());
                return;
            }
        }
        if (!canDecide && !canReceiveDonation) {
            // 没有可做的决定。说一句为什么，不然屏幕上只会看到血无缘无故掉了。
            Text reason = own == 0
                    ? Text.translatable("heavyseas.game.thirst_no_water",
                    GameFlow.characterName(who), prompt.remaining())
                    : !session.state().conditionOf(who).canAct()
                    ? Text.translatable("heavyseas.game.thirst_unconscious",
                    GameFlow.characterName(who), prompt.remaining())
                    : Text.translatable("heavyseas.game.thirst_not_enough",
                    GameFlow.characterName(who), own, prompt.waterPerSource());
            GameFlow.broadcast(world, reason.copy().formatted(Formatting.DARK_GRAY));
            LOGGER.info("口渴：{} 无从决定也没人能代打（水 {} 张 · {}），按不喝结算 {} 次", who.value(), own,
                    session.state().conditionOf(who), prompt.remaining());
            resolve(world, component, 0);
            return;
        }
        if (canDecide && occupant.isDummy() && component.dummyAutoplay()) {
            int drink = Math.min(prompt.waterNeeded(), own);
            drink -= drink % prompt.waterPerSource();
            LOGGER.info("口渴（替身自动）：{} 喝 {} 张（还需化解 {} 次）", who.value(), drink, prompt.remaining());
            resolve(world, component, drink);
            return;
        }
        int suggested = canDecide ? clamp(session, prompt, prompt.waterNeeded(), 0) : 0;
        component.clearDecided();
        long chooseMs = component.timing().thirstMs();   // 开局快照（ADR-0099 D8）
        component.openThirstWindow(component.humanWindow(chooseMs));   // 演示局里等真人不限时（用户 2026-10-07）
        component.setThirstHighlight(suggested);
        GameFlow.broadcast(world, Text.translatable(canDecide
                        ? "heavyseas.game.thirst_choose" : "heavyseas.game.thirst_ask_donors",
                GameFlow.characterName(who), prompt.remaining()).formatted(Formatting.AQUA));
        LOGGER.info("口渴选择：{}（{}）· 还需化解 {} 次 · 手上 {} 张 · {} 秒",
                who.value(), occupant.isDummy() ? "替身" : "真人", prompt.remaining(), own,
                chooseMs / 1000);
        GameComponents.sync(world);
    }

    /** 口渴一面上的一下：移高亮，或者就按这个数喝。 */
    public static void onAction(ServerPlayerEntity player, ThirstActionC2S action) {
        ServerWorld world = player.getServerWorld();
        GameComponent component = GameComponents.of(world);
        if (component.session().isEmpty() || component.thirstDeadline() <= 0) {
            return;
        }
        Session session = component.requireSession();
        if (session.state().phase() != Phase.NAVIGATION) {
            return;
        }
        Optional<Session.ThirstPrompt> pending = session.thirstPending();
        Optional<CharacterId> seat = component.seatOf(player.getUuid());
        // ❗只认正被问的那个人本人。不校验的话，谁都能替别人把水喝掉。
        if (pending.isEmpty() || seat.isEmpty() || !seat.get().equals(pending.get().who())) {
            return;
        }
        if (!canDecide(session, pending.get())) {
            return;                             // 这是只给旁人捐水的窗口；本人无权用 0 提前收场
        }
        int waters = clamp(session, pending.get(), action.waters(), component.thirstDonors().size());
        component.setThirstHighlight(waters);
        if (action.commit()) {
            LOGGER.info("口渴（界面）：{} 喝 {} 张", pending.get().who().value(), waters);
            resolve(world, component, waters);
        }
    }

    /** 旁人的捐水一面：一次只承诺 1 张，真正扣牌与当前角色自己的选择一起在 {@link #resolve} 完成。 */
    public static void onDonation(ServerPlayerEntity player, WaterDonationC2S action) {
        if (action.waters() != 1 && action.waters() != 0) {
            return;
        }
        ServerWorld world = player.getServerWorld();
        GameComponent component = GameComponents.of(world);
        if (component.session().isEmpty() || component.thirstDeadline() <= 0) {
            return;
        }
        Optional<CharacterId> donor = component.seatOf(player.getUuid());
        if (donor.isEmpty()) {
            return;
        }
        if (action.waters() == 0) {
            decline(world, component, donor.get());   // 「不给」（ADR-0095 D1）
            return;
        }
        donate(world, component, donor.get());
    }

    /**
     * 替人打水那一面按了「不给」（ADR-0095 D1）。原先只是收起界面、服务端不知道，代打窗口只能空等到时；
     * 演示局不限时之后会一直等下去。本人无从决定（昏迷 · 水不够）的那一窗，所有能打水的真人都给过或不给了，就照到时那样结算。
     */
    private static void decline(ServerWorld world, GameComponent component, CharacterId donor) {
        Session session = component.requireSession();
        Optional<Session.ThirstPrompt> pending = session.thirstPending();
        if (pending.isEmpty() || donor.equals(pending.get().who())) {
            return;
        }
        component.markDecided(donor);
        LOGGER.info("口渴：{} 不替 {} 打水", donor.value(), pending.get().who().value());
        settleIfNoDonorLeft(world, component, pending.get());
    }

    /**
     * 本人无从决定的那一窗：还能打水、又没表过态的真人一个都不剩，就照到时那样结算。
     *
     * <p>❗「不给」与「打出最后一张」都要走到这里。只在「不给」时问的话，把水打光的人那一面会自己收起（他没水了），
     * 再没有人告诉服务端他做完了 —— 正常局等满 20 秒，演示局不限时就永远等下去（用户 2026-10-07：「无限时间后，有的阶段会卡住」）。
     *
     * @return 结算了没有
     */
    private static boolean settleIfNoDonorLeft(ServerWorld world, GameComponent component, Session.ThirstPrompt prompt) {
        Session session = component.requireSession();
        if (!donationsSettled(session, component, prompt)) {
            return false;
        }
        LOGGER.info("代打水窗口：能打水的真人都表过态了（或水打光了），按已收到的承诺结算");
        resolve(world, component, clamp(session, prompt, component.thirstHighlight(), component.thirstDonors().size()));
        return true;
    }

    /**
     * 代打水这一窗还要不要等：本人能自己定（等他）· 或者还有真人能打水、又没表过态（给过一张、手上还有的也算 —— 他可能还要再给）。
     * 纯判断，单测在 {@code ThirstDonationSettleTest}。
     */
    static boolean donationsSettled(Session session, GameComponent component, Session.ThirstPrompt prompt) {
        if (canDecide(session, prompt)) {
            return false;                     // 本人还要自己定喝几张：等他
        }
        return component.occupants().entrySet().stream().noneMatch(e -> !e.getValue().isDummy()
                && !e.getKey().equals(prompt.who()) && !component.hasDecided(e.getKey())
                && session.state().canAct(e.getKey()) && !session.state().isOffline(e.getKey())
                && availableDonationWater(session, component, e.getKey()) > 0);
    }

    /** 指令那条路（dev）：{@code /seas water <张数>}。 */
    public static void chooseByCommand(ServerWorld world, GameComponent component, int waters) {
        Session session = component.requireSession();
        Session.ThirstPrompt prompt = session.thirstPending().orElseThrow();
        int drink = clamp(session, prompt, waters, component.thirstDonors().size());
        LOGGER.info("口渴（指令）：{} 喝 {} 张", prompt.who().value(), drink);
        resolve(world, component, drink);
    }

    /**
     * 别人替他打水（规则 §5.2 的例外：航海阶段可以给口渴者打水立即喝掉）。
     *
     * <p>❗<b>这是昏迷者唯一的水源</b> —— 他自己打不出水。
     *
     * @return 真的打出去了吗
     */
    public static boolean donateByCommand(ServerWorld world, GameComponent component, CharacterId donor) {
        return donate(world, component, donor);
    }

    private static boolean donate(ServerWorld world, GameComponent component, CharacterId donor) {
        Session session = component.requireSession();
        Optional<Session.ThirstPrompt> pending = session.thirstPending();
        if (pending.isEmpty() || component.thirstDeadline() <= 0
                || session.state().phase() != Phase.NAVIGATION) {
            return false;
        }
        Session.ThirstPrompt prompt = pending.get();
        if (donor.equals(prompt.who()) || !session.state().canAct(donor)) {
            return false;                     // 本人走喝水选择；昏迷者不能替别人打牌
        }
        // ❗攒着，不因第一张就结算：那会把他还没说话的其余口渴直接变成伤害。
        int already = component.thirstDonors().size();
        if (already >= prompt.waterNeeded()) {
            return false;                     // 已经够了，再打就是白白扔掉一张水
        }
        if (availableDonationWater(session, component, donor) <= 0) {
            return false;
        }
        component.addThirstDonor(donor);
        component.setThirstHighlight(clamp(session, prompt, component.thirstHighlight(), already + 1));
        GameFlow.broadcast(world, Text.translatable("heavyseas.game.thirst_donated",
                GameFlow.characterName(donor), GameFlow.characterName(prompt.who())));
        LOGGER.info("口渴：{} 替 {} 打了一张水（这一轮共 {} 张）",
                donor.value(), prompt.who().value(), already + 1);
        if (already + 1 >= prompt.waterNeeded()) {
            resolve(world, component, 0);      // 全部被代打化解了，不再让全船空等倒计时
        } else if (!settleIfNoDonorLeft(world, component, prompt)) {
            GameComponents.sync(world);        // 打出这一张之后还有人能再打（或本人还要自己定）：接着等
        }
        return true;
    }

    /**
     * 替身递一张水（动脑 / 大模型，{@link StandInMinds#thirst}）：与真人在捐水一面上按「给」同样攒进这一轮的承诺，
     * 同样播一句。这里不开窗口（替身不等窗口），不提前结算 —— 递完由 {@link #resolveForStandIn} 一起结算。
     *
     * @return 真的记下了吗（已经够了、他没水了、他不能动，都不记）
     */
    static boolean donateForStandIn(ServerWorld world, GameComponent component, CharacterId donor) {
        Session session = component.requireSession();
        Optional<Session.ThirstPrompt> pending = session.thirstPending();
        if (pending.isEmpty() || donor.equals(pending.get().who()) || !session.state().canAct(donor)) {
            return false;
        }
        Session.ThirstPrompt prompt = pending.get();
        int already = component.thirstDonors().size();
        if (already >= prompt.waterNeeded() || availableDonationWater(session, component, donor) <= 0) {
            return false;
        }
        component.addThirstDonor(donor);
        GameFlow.broadcast(world, Text.translatable("heavyseas.game.thirst_donated",
                GameFlow.characterName(donor), GameFlow.characterName(prompt.who())));
        LOGGER.info("口渴：{} 替 {} 打了一张水（替身 · 这一轮共 {} 张）", donor.value(), prompt.who().value(), already + 1);
        return true;
    }

    /** 替身那一轮问完：本人自己喝 {@code waters} 张，连同替身递的一起结算（与超时、界面同一个 {@link #resolve}）。 */
    static void resolveForStandIn(ServerWorld world, GameComponent component, int waters) {
        Session.ThirstPrompt prompt = component.requireSession().thirstPending().orElseThrow();
        LOGGER.info("口渴（替身）：{} 喝 {} 张（别人递了 {} 张）", prompt.who().value(), waters, component.thirstDonors().size());
        resolve(world, component, waters);
    }

    /** 每 tick 检查超时。**服务端超时，客户端不参与判定。** */
    public static void tick(MinecraftServer server) {
        for (ServerWorld world : server.getWorlds()) {
            GameComponent component = GameComponents.of(world);
            long deadline = component.thirstDeadline();
            if (component.session().isEmpty() || deadline <= 0 || System.currentTimeMillis() < deadline) {
                continue;
            }
            Session session = component.requireSession();
            Optional<Session.ThirstPrompt> pending = session.thirstPending();
            if (pending.isEmpty()) {
                component.clearThirst();
                continue;
            }
            int waters = clamp(session, pending.get(), component.thirstHighlight(),
                    component.thirstDonors().size());
            if (canDecide(session, pending.get())) {
                LOGGER.info("口渴超时：替 {} 喝了 {} 张（当前高亮）", pending.get().who().value(), waters);
            } else {
                LOGGER.info("代打水窗口结束：{} 本人无从决定，按已收到的承诺结算", pending.get().who().value());
            }
            resolve(world, component, waters);
        }
    }

    private static boolean canDecide(Session session, Session.ThirstPrompt prompt) {
        CharacterId who = prompt.who();
        return session.state().canAct(who) && ThirstEligibility.canChoose(
                session.state().conditionOf(who), session.watersOf(who), prompt.waterPerSource());
    }

    /**
     * 把一个数夹进「他拿得出、而且真的需要」的范围。改过的客户端发什么都越不过这一关。
     *
     * <p>别人已经替他打进来的那几张要先扣掉：他自己最多再喝「还差的那些」。
     */
    static int clamp(Session session, Session.ThirstPrompt prompt, int waters, int donated) {
        if (!session.state().canAct(prompt.who())) {
            return 0;
        }
        int own = session.watersOf(prompt.who());
        int need = Math.max(0, prompt.waterNeeded() - donated);
        int chosen = Math.max(0, Math.min(waters, Math.min(need, own)));
        while (chosen > 0 && (donated + chosen) % prompt.waterPerSource() != 0) {
            chosen--;
        }
        return chosen;
    }

    /** 某人还真正拿得出几张水：已承诺的尚未从手里扣，必须在这里逐张减掉。 */
    private static int availableDonationWater(Session session, GameComponent component, CharacterId donor) {
        long promised = component.thirstDonors().stream().filter(donor::equals).count();
        return Math.max(0, session.watersOf(donor) - (int) promised);
    }

    /** 只为真人开等待窗口；替身不会操作捐水界面，不能让全替身局平白多等 12 秒。 */
    private static boolean hasHumanDonor(Session session, GameComponent component, CharacterId recipient) {
        return component.occupants().entrySet().stream().anyMatch(entry ->
                !entry.getKey().equals(recipient)
                        && !entry.getValue().isDummy()
                        && session.state().canAct(entry.getKey())
                        && availableDonationWater(session, component, entry.getKey()) > 0);
    }

    private static void resolve(ServerWorld world, GameComponent component, int waters) {
        Session session = component.requireSession();
        Session.ThirstPrompt prompt = session.thirstPending().orElseThrow();
        CharacterId who = prompt.who();
        List<CharacterId> donors = new ArrayList<>(component.thirstDonors().stream()
                .filter(session.state()::canAct).toList());
        int own = clamp(session, prompt, waters, donors.size());
        for (int i = 0; i < own; i++) {
            donors.add(who);                  // 界面这条路只喝自己的；别人替他打走 /seas water from
        }
        int promised = donors.size();
        while (!donors.isEmpty() && donors.size() % prompt.waterPerSource() != 0) {
            donors.removeLast();              // 不足一组的承诺不扣牌，也不能凭空化解口渴
        }
        int returned = promised - donors.size();
        if (returned > 0) {
            GameFlow.broadcast(world, Text.translatable("heavyseas.game.thirst_returned", returned)
                    .formatted(Formatting.GRAY));
        }
        session.decideThirst(donors);
        waters = donors.size();
        int damage = prompt.remaining() - waters / prompt.waterPerSource();
        if (waters > 0) {
            GameFlow.broadcast(world, Text.translatable("heavyseas.game.thirst_drank",
                    GameFlow.characterName(who), waters));
        }
        if (damage > 0) {
            GameFlow.broadcast(world, Text.translatable("heavyseas.game.thirst_hurt",
                    GameFlow.characterName(who), damage));
        }
        component.clearThirst();
        step(world, component);
    }

    /**
     * 把「问下一个人」排到下一 tick。
     *
     * <p>❗不当场递归：8 个人全是替身时，整段口渴会在一次调用里走完，客户端一帧都看不到 ——
     * 与 ADR-0019 里替身自动推进那条是同一个坑。
     */
    private static void step(ServerWorld world, GameComponent component) {
        GameComponents.sync(world);
        GameFlow.schedule(component, 0L, "口渴：问下一个人", () -> advance(world, component));
    }
}
