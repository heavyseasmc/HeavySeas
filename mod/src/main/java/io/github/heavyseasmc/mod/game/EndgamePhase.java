package io.github.heavyseasmc.mod.game;

import io.github.heavyseasmc.engine.model.Affinities;
import io.github.heavyseasmc.engine.model.CharacterId;
import io.github.heavyseasmc.engine.play.Session;
import io.github.heavyseasmc.engine.scoring.ScoreSheet;
import io.github.heavyseasmc.engine.state.GameState;
import io.github.heavyseasmc.mod.HeavySeasMod;
import io.github.heavyseasmc.mod.data.GameDataLoader;
import io.github.heavyseasmc.mod.state.EndgameProgress;
import io.github.heavyseasmc.mod.state.GameComponent;
import io.github.heavyseasmc.mod.state.GameComponents;
import io.github.heavyseasmc.mod.world.Nameplates;
import io.github.heavyseasmc.mod.world.Seats;
import io.github.heavyseasmc.mod.world.Gulls;
import io.github.heavyseasmc.mod.world.MistSea;

import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 终局序列（决策 ⑪ 的第二、三幕 · ADR-0022）：翻恨一轮 → 翻爱一轮 → 计分面板 → 收起会话。
 *
 * <h2>这是一段演出，不是决策</h2>
 * 没有任何人要做选择，所以没有倒计时、没有超时代选；节奏由排程推进（{@code GameComponent#schedule}），
 * 一步一个 tick 起跳 —— 与替身自动推进同一个理由：当场递归的话，没有真人时整段会在一次调用里演完，客户端一帧都看不到。
 *
 * <h2>每轮最后一张不翻</h2>
 * 爱恨是置换，翻到只剩一人时全船已经推得出来 —— 就停在那儿，让人自己喊（决策 ⑪ 补丁二）。
 * 所以那一张<b>谁都不发</b>：服务端日志也只写「最后一张不翻（谁）」，不写他指着谁。
 *
 * <h2>节奏</h2>
 * 有真人在座时每翻一张停 {@link #FLIP_HOLD_MS}、最后一张停 {@link #WITHHELD_HOLD_MS}、计分面板停 {@link #SCORE_HOLD_MS}；
 * <b>没有真人时一律不停</b> —— 出口验收里没人要看。这几个数属 ADR-0018 §8「实现中打磨」那一列。
 */
public final class EndgamePhase {

    /** 翻开一张之后停多久再点名下一个：翻 400ms + 看清 1.6s + 滑 550ms。 */
    public static final long FLIP_HOLD_MS = 2600L;

    /** 每轮最后一张不翻，停多久让全船自己喊出来。 */
    public static final long WITHHELD_HOLD_MS = 6000L;

    /** 计分面板停多久再收起会话。{@code /seas end} 随时照样能提前结束。 */
    public static final long SCORE_HOLD_MS = 45_000L;
    public static final long ARRIVAL_STEP_MS = 500L;
    public static final int ARRIVAL_STEPS = 8;

    private static final Logger LOGGER = LoggerFactory.getLogger(HeavySeasMod.MOD_ID);

    private EndgamePhase() {
    }

    /**
     * 开始终局。一局只开始一次 —— 结束可能从好几条路先后到达（航海结算、口渴结算、打架），第二下什么也不做。
     *
     * <p>分数在这里一次算好：翻牌不改变任何人的分，而算晚了的话，中途 {@code /seas end} 会让它永远算不出来。
     */
    public static void begin(ServerWorld world, GameComponent component) {
        if (component.endgame().isPresent()) {
            return;
        }
        // 终局从这里起独占排程。上一阶段可能在同一 tick 已经排下一步替身动作；
        // 若不清掉，它会在靠岸演出期间继续碰一局已经结算完的状态。
        component.clearScheduledSteps();
        Session session = component.requireSession();
        GameState end = session.state();
        Map<CharacterId, ScoreSheet> scores = session.scores(GameDataLoader.require().roster().treasureScoring());
        GameState.Outcome outcome = end.outcome().orElseThrow();
        EndgameProgress.Stage first = outcome == GameState.Outcome.LANDED
                ? EndgameProgress.Stage.ARRIVAL : EndgameProgress.Stage.HATE;
        EndgameProgress progress = new EndgameProgress(outcome, end.turn(),
                session.aliveCount(), end.bySeat(), first, 0, false, scores);
        component.setEndgame(progress);
        if (first == EndgameProgress.Stage.ARRIVAL) {
            component.clearFog();
        }
        // 与语言无关的一行：验收脚本从这一行起数「终局揭示」。
        LOGGER.info("终局开始：{} · 翻牌 {} 人 · {}", progress.outcome(), progress.order().size(),
                first == EndgameProgress.Stage.ARRIVAL ? "先向岸航行，再恨后爱" : "先恨后爱");
        GameFlow.broadcast(world, Text.translatable("heavyseas.endgame.begin").formatted(Formatting.GOLD));
        GameComponents.sync(world);
        GameFlow.schedule(component, hold(component,
                        first == EndgameProgress.Stage.ARRIVAL ? ARRIVAL_STEP_MS : FLIP_HOLD_MS),
                first == EndgameProgress.Stage.ARRIVAL ? "终局：雾散向岸" : "终局：点名第一个",
                () -> step(world, component));
    }

    /** 走一步：翻开当前这一张；或者宣布最后一张不翻；或者进下一段。 */
    static void step(ServerWorld world, GameComponent component) {
        EndgameProgress progress = component.endgame().orElseThrow(
                () -> new IllegalStateException("终局序列在排程里，组件上却没有终局状态"));
        if (progress.stage() == EndgameProgress.Stage.ARRIVAL) {
            if (progress.flipped() < ARRIVAL_STEPS) {
                Seats.move(world, component, MistSea.SHORE_DIRECTION.multiply(4.0));
                component.setEndgame(progress.withFlipped(progress.flipped() + 1));
                GameComponents.sync(world);
                GameFlow.schedule(component, hold(component, ARRIVAL_STEP_MS), "终局：救生艇向岸前进",
                        () -> step(world, component));
                return;
            }
            LOGGER.info("终局第一幕：第四只海鸥引航 · 浓雾散去 · 岸边出现 · 船上人员保持乘坐");
            GameFlow.broadcast(world, Text.translatable("heavyseas.endgame.arrived").formatted(Formatting.GOLD));
            component.setEndgame(progress.nextStage());
            GameComponents.sync(world);
            GameFlow.schedule(component, hold(component, FLIP_HOLD_MS), "终局：靠岸后翻恨",
                    () -> step(world, component));
            return;
        }
        if (progress.stage() == EndgameProgress.Stage.SCORES) {
            finish(world, component);
            return;
        }
        boolean hate = progress.stage() == EndgameProgress.Stage.HATE;
        if (progress.withheld()) {
            EndgameProgress next = progress.nextStage();
            component.setEndgame(next);
            if (next.stage() == EndgameProgress.Stage.SCORES) {
                announceScores(world, next);
                GameComponents.sync(world);
                GameFlow.schedule(component, hold(component, SCORE_HOLD_MS), "终局：计分面板",
                        () -> step(world, component));
            } else {
                LOGGER.info("终局：第二轮 · 爱");
                GameFlow.broadcast(world, Text.translatable("heavyseas.endgame.round_love").formatted(Formatting.GOLD));
                GameComponents.sync(world);
                GameFlow.schedule(component, hold(component, FLIP_HOLD_MS), "终局：第二轮点名第一个",
                        () -> step(world, component));
            }
            return;
        }
        Affinities affinities = component.requireSession().affinities().orElseThrow();
        int lastIndex = progress.order().size() - 1;
        if (progress.flipped() < lastIndex) {
            CharacterId who = progress.order().get(progress.flipped());
            CharacterId target = hate ? affinities.hateOf(who) : affinities.loveOf(who);
            LOGGER.info("终局揭示：{} · {} → {}（第 {}/{} 张）", hate ? "恨" : "爱", who.value(), target.value(),
                    progress.flipped() + 1, progress.order().size());
            GameFlow.broadcast(world, Text.translatable(hate ? "heavyseas.endgame.reveal_hate" : "heavyseas.endgame.reveal_love",
                    GameFlow.characterName(who), GameFlow.characterName(target)));
            component.setEndgame(progress.withFlipped(progress.flipped() + 1));
            GameComponents.sync(world);
            GameFlow.schedule(component, hold(component, FLIP_HOLD_MS), "终局：点名下一个",
                    () -> step(world, component));
            return;
        }
        CharacterId last = progress.last();
        // ❗只写他是谁，不写他指着谁：这一张谁都不发，日志也一样（开服的人往往也是玩家）。
        LOGGER.info("终局揭示：{} · 最后一张不翻（{}）", hate ? "恨" : "爱", last.value());
        GameFlow.broadcast(world, Text.translatable(hate ? "heavyseas.endgame.withheld_hate" : "heavyseas.endgame.withheld_love",
                GameFlow.characterName(last)).formatted(Formatting.GRAY));
        component.setEndgame(progress.withWithheld());
        GameComponents.sync(world);
        GameFlow.schedule(component, hold(component, WITHHELD_HOLD_MS), "终局：最后一张不翻",
                () -> step(world, component));
    }

    /** 全员合计播给全场，最高分点名；四项明细只在各自的计分面板上（投影按人裁剪）。 */
    private static void announceScores(ServerWorld world, EndgameProgress progress) {
        int best = Integer.MIN_VALUE;
        for (CharacterId id : progress.order()) {
            ScoreSheet s = progress.scores().get(id);
            best = Math.max(best, s.total());
            // 与语言无关的一行：验收脚本按这一行数「每人都算了分」。
            LOGGER.info("计分：{} 存活 {} + 财宝 {} + 所爱 {} + 所恨 {} = {}", id.value(),
                    s.selfSurvival(), s.treasure(), s.loved(), s.hated(), s.total());
            GameFlow.broadcast(world, Text.translatable("heavyseas.endgame.score_line",
                    GameFlow.characterName(id), s.total()));
        }
        List<CharacterId> winners = new ArrayList<>();
        for (CharacterId id : progress.order()) {
            if (progress.scores().get(id).total() == best) {
                winners.add(id);
            }
        }
        Text names = Text.empty();
        for (int i = 0; i < winners.size(); i++) {
            names = names.copy().append(i == 0 ? Text.empty() : Text.literal("、")).append(GameFlow.characterName(winners.get(i)));
        }
        GameFlow.broadcast(world, Text.translatable("heavyseas.endgame.winner", names, best).formatted(Formatting.GOLD));
    }

    private static void finish(ServerWorld world, GameComponent component) {
        LOGGER.info("终局结束：会话收起");
        DesignationPhase.clear(world, component);   // 还举着拳头的那一位要熄灯
        Nameplates.clear(world);             // 队伍进存档：不删的话下一局名牌上还挂着上一局的数
        Gulls.clear(world, component);
        Seats.clear(world, component);       // 先收座位再收会话：clear 要读组件里那份名单
        component.end();
        GameComponents.sync(world);          // 结束那一帧也要推到（endedFor），否则计分面板一直挂着
        MistSea.restoreAll(world, component); // idle frame first; cross-dimension teleport comes last
    }

    private static long hold(GameComponent component, long millis) {
        return component.anyHumanSeated() ? millis : 0L;
    }
}
