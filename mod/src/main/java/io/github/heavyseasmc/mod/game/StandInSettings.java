package io.github.heavyseasmc.mod.game;

import io.github.heavyseasmc.engine.scoring.TreasureScoring;
import io.github.heavyseasmc.engine.seat.HeuristicSeatPolicy;
import io.github.heavyseasmc.engine.seat.SearchSeatPolicy;
import io.github.heavyseasmc.engine.seat.SeatPolicy;
import io.github.heavyseasmc.engine.seat.SeatPolicySettings;
import io.github.heavyseasmc.mod.config.ServerSettings;
import io.github.heavyseasmc.mod.data.GameDataLoader;
import io.github.heavyseasmc.mod.llm.DecisionKind;
import io.github.heavyseasmc.mod.llm.LlmHooks;
import io.github.heavyseasmc.mod.llm.LlmService;

import java.util.EnumSet;
import java.util.Set;

/**
 * 「动脑」与「大模型」两种替身读设置的<b>唯一</b>入口（接缝）。
 *
 * <p>来源：设置菜单的「动脑替身」「大模型」两组（服务端设置，{@link ServerSettings}）。动脑那一层的旋钮在每局开局时取一份
 * （{@link StandInThinker} 每局造一次每座的策略），大模型那一组存了就生效（接入层当场换一个服务，{@link LlmHooks}）。
 * ❗别在别处直接读 {@code SeatPolicySettings.DEFAULTS}、{@code ServerSettings} 或 {@code LlmHooks}：两处来源就有两个真相。
 */
public final class StandInSettings {

    /**
     * 问大模型的一个决定最多等多久的<b>默认值</b>（毫秒；设置里的那一项 {@code llm.decision_cap_ms} 以它为默认）。
     * 窗口本身还剩多少也算进去，取两者小的那个（{@link StandInMinds}）。演示局里等真人的窗口长 365 天 —— 没有这个上限，
     * 替身就会陪着等下去。实测（ADR-0096 实测，本机代理）：gpt-6-luna 低思考 p50 1.8 秒、p95 5.6 秒，所以给 8 秒。
     */
    public static final long LLM_DECISION_CAP_MS = 8_000L;

    /**
     * 动脑的那一下最多想多久的底数（毫秒）：第一层只按处境打分，平时几毫秒；第二层（往前推演）在这之上再加它自己的时限，
     * 见 {@link #smartThinkCapMs(SeatPolicySettings)}。超过上限当作没想出来，退回「什么也不做」那一档的默认。
     */
    public static final long SMART_THINK_CAP_MS = 2_000L;

    /**
     * 第二层的时限要乘几倍：两条工作线程上，一段里最多八座同时想（站队 · 押武器），排在最后的那一座要等前面
     * 三轮做完才轮到自己 —— 4 × 每个决定的时限，盖得住。
     */
    static final int SEARCH_QUEUE_FACTOR = 4;

    /** 第二层不限时（{@code millisPerDecision = 0}，只看局数）时，一个决定按这么久算上限。 */
    static final long UNLIMITED_SEARCH_MS = 10_000L;

    private StandInSettings() {
    }

    /** 动脑的那一层的旋钮（第一层的温度与恩怨，第二层要不要推演、推演几局、限时多久……）：此刻存着的那一份。 */
    public static SeatPolicySettings smart() {
        return ServerSettings.seatPolicy();
    }

    /**
     * 「动脑」那一座的策略：旋钮里开着推演（{@link SeatPolicySettings#search()}，默认开）就是第二层（往前推演），否则第一层。
     * 第二层带着自己的随机流与统计，<b>每局每座一个</b>（{@link StandInThinker#seat}）。
     *
     * @param settings 这一局开局时取的那一份旋钮
     * @param seed     这一座这一局自己的种子（由开局种子按座位派生）
     */
    public static SeatPolicy smartPolicy(SeatPolicySettings settings, long seed) {
        return settings.search() ? new SearchSeatPolicy(settings, treasureScoring(), seed) : new HeuristicSeatPolicy(settings);
    }

    /**
     * 第一层（按处境打分）：大模型那一种先算的那个答案、以及它的退路，都用这一层 ——
     * 那边的大头是等模型（秒级），再在前面加一次 ¼ 秒的推演只是多等；退回它的也只有百里挑几的几次。
     */
    public static SeatPolicy quickPolicy(SeatPolicySettings settings) {
        return new HeuristicSeatPolicy(settings);
    }

    /** 终局计分用的财宝分值表（第二层推演打到终局时按它算分）；与终局那一幕（{@code EndgamePhase}）同一份。 */
    static TreasureScoring treasureScoring() {
        return GameDataLoader.require().roster().treasureScoring();
    }

    /** 大模型接入层（关着时它当场回 DISABLED，替身照动脑那一层的答案走）。 */
    public static LlmService llm() {
        return LlmHooks.service();
    }

    /** 一个决定问大模型最多等多久（设置里的 {@code llm.decision_cap_ms}；存了就生效）。 */
    public static long llmDecisionCapMs() {
        return ServerSettings.llmDecisionCapMs();
    }

    /**
     * 动脑的那一下最多想多久（从照相到想完，含在工作线程上排队）：底数 {@value #SMART_THINK_CAP_MS} ms；
     * 开着推演时再加 {@value #SEARCH_QUEUE_FACTOR} × 每个决定的时限（默认 250 ms → 共 3 秒）。
     *
     * @param s 这一局开局时取的那一份旋钮
     */
    static long smartThinkCapMs(SeatPolicySettings s) {
        if (!s.search()) {
            return SMART_THINK_CAP_MS;
        }
        long perDecision = s.millisPerDecision() > 0 ? s.millisPerDecision() : UNLIMITED_SEARCH_MS;
        return SMART_THINK_CAP_MS + SEARCH_QUEUE_FACTOR * perDecision;
    }

    /**
     * 大模型脑子里，哪几种决定真的去问大模型；其余的照动脑那一层的答案走。
     *
     * <p>不问的两种：<b>不占行动的小事</b>（亮牌 · 喝酒 · 送牌 · 打架前喝酒，一回合里可能问好几次）与<b>递水</b>
     * （一次口渴结算要依座位问每一个有水的人）—— 它们每问一次就是一两秒，一回合累起来比窗口还长。
     */
    public static Set<DecisionKind> llmKinds() {
        Set<DecisionKind> kinds = EnumSet.allOf(DecisionKind.class);
        kinds.remove(DecisionKind.FREE_ACTION);
        kinds.remove(DecisionKind.GIVE_WATER);
        return kinds;
    }
}
