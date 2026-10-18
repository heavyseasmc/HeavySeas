package io.github.heavyseasmc.mod.config;

import io.github.heavyseasmc.engine.seat.SeatPolicySettings;
import io.github.heavyseasmc.mod.game.ActionPhase;
import io.github.heavyseasmc.mod.game.ContestPhase;
import io.github.heavyseasmc.mod.game.DesignationPhase;
import io.github.heavyseasmc.mod.game.EndgamePhase;
import io.github.heavyseasmc.mod.game.GameFlow;
import io.github.heavyseasmc.mod.game.GameTiming;
import io.github.heavyseasmc.mod.game.NavigationPhase;
import io.github.heavyseasmc.mod.game.OverboardPhase;
import io.github.heavyseasmc.mod.game.ProvisionPhase;
import io.github.heavyseasmc.mod.game.StandInSettings;
import io.github.heavyseasmc.mod.game.ThirstPhase;
import io.github.heavyseasmc.mod.llm.LlmConfig;
import io.github.heavyseasmc.mod.state.StandInMind;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.DoubleFunction;
import java.util.function.Function;
import java.util.function.IntFunction;

/**
 * 服务端设置的全表（ADR-0099 §2.4 · §5）：哪几项、默认多少、范围多大、什么时候生效。纯数据，单测直接拿它说话。
 *
 * <h2>默认值取自哪里</h2>
 * 时限一族的默认值<b>就是各阶段原先写死的常量</b>（{@code ActionPhase.ACTION_MILLIS} 这些），不在这里另抄一份数 ——
 * 抄一份就会分家，而分家的表现是「设置文件里写着 60、游戏里其实是 20」。{@code GameTimingDefaultsTest} 核两边对得上。
 *
 * <h2>不在这张表里的</h2>
 * {@code /seas debug} 整棵、替人行动的那些子指令、{@code dummyFast}（只给调试用）、终局翻牌那两段停顿与靠岸那一段
 * （客户端的动画与雾按它们排）、各段「该答的都答了」之后的尾巴（替身延迟推出来的，不是等人的时限）。
 */
public final class ServerSettingsTable {

    // ---- 键（toml 里的路径）。界面（cut 2）与单测都用这几个常量，不在别处写字面量。
    public static final String ACTION = "windows.action_seconds";
    public static final String ROW = "windows.row_seconds";
    public static final String CONSENT = "windows.consent_seconds";
    public static final String STANCE = "windows.stance_seconds";
    public static final String STANCE_BUMP = "windows.stance_bump_seconds";
    public static final String WEAPON = "windows.weapon_seconds";
    public static final String WEAPON_BUMP = "windows.weapon_bump_seconds";
    public static final String PICK = "windows.pick_seconds";
    public static final String DESIGNATION = "windows.designation_seconds";
    public static final String HELM = "windows.helm_seconds";
    public static final String OVERBOARD = "windows.overboard_seconds";
    public static final String THIRST = "windows.thirst_seconds";
    public static final String PROVISION_PER_CARD = "windows.provision_seconds_per_card";
    public static final String PROVISION_MIN = "windows.provision_min_seconds";
    public static final String REVEAL_HOLD = "pacing.reveal_hold_seconds";
    public static final String SCORE_HOLD = "pacing.score_hold_seconds";
    public static final String AUTOPLAY = "stand_ins.autoplay";
    public static final String MIND = "stand_ins.mind";
    public static final String FILL_SEATS = "stand_ins.fill_empty_seats";
    public static final String UNTIMED_DEMO = "demo.untimed_humans";

    /**
     * 旧键：替身随机行动的开关（cut 3b 之前）。<b>不在表里</b> —— 起服时若旧文件里写着 {@code true}，迁成 {@link #MIND} = 随机
     * （{@link SettingsMigration}）；FCAP 读文件时会把不认识的键删掉，所以那一次读要赶在它前面。
     */
    public static final String LEGACY_RANDOM = "stand_ins.random";

    // ---- 动脑替身（引擎 SeatPolicySettings 的每一项；默认值与范围取自那个记录本身）
    public static final String SMART_SEARCH = "smart.search";
    public static final String SMART_MILLIS = "smart.millis_per_decision";
    public static final String SMART_ROLLOUTS = "smart.rollouts";
    public static final String SMART_WIDTH = "smart.width";
    public static final String SMART_HORIZON = "smart.horizon_days";
    public static final String SMART_TEMPERATURE = "smart.temperature";
    public static final String SMART_GRATITUDE = "smart.gratitude";
    public static final String SMART_RESENTMENT = "smart.resentment";
    public static final String SMART_MEMORY_DAYS = "smart.memory_days";

    // ---- 大模型（LlmConfig 的每一项，外加替身问它最多等多久；默认值与范围取自 LlmConfig）
    public static final String LLM_ENABLED = "llm.enabled";
    public static final String LLM_BASE_URL = "llm.base_url";
    public static final String LLM_MODEL = "llm.model";
    public static final String LLM_API_KEY = "llm.api_key";
    public static final String LLM_REASONING_EFFORT = "llm.reasoning_effort";
    public static final String LLM_LANGUAGE = "llm.language";
    public static final String LLM_DECISION_CAP = "llm.decision_cap_ms";
    public static final String LLM_MAX_TOKENS = "llm.max_tokens";
    public static final String LLM_TEMPERATURE = "llm.temperature";
    public static final String LLM_MAX_ATTEMPTS = "llm.max_attempts";
    public static final String LLM_ATTEMPT_TIMEOUT = "llm.attempt_timeout_ms";
    public static final String LLM_ATTEMPT_SHARE = "llm.attempt_share";
    public static final String LLM_MIN_ATTEMPT = "llm.min_attempt_ms";
    public static final String LLM_BACKOFF_BASE = "llm.backoff_base_ms";
    public static final String LLM_BACKOFF_MAX = "llm.backoff_max_ms";
    public static final String LLM_DEADLINE_MARGIN = "llm.deadline_margin_ms";
    public static final String LLM_MAX_CONCURRENT = "llm.max_concurrent";
    public static final String LLM_MAX_QUEUED = "llm.max_queued";
    public static final String LLM_BREAKER_THRESHOLD = "llm.breaker_threshold";
    public static final String LLM_BREAKER_COOLDOWN = "llm.breaker_cooldown_ms";
    public static final String LLM_DEBUG_LOG = "llm.debug_log";

    /** 段的注释（toml 里每一段上面那一行）。 */
    static final Map<String, String> SECTIONS = Map.of(
            "windows", "Decision windows, in seconds. Read once when a game starts; a running game keeps its own. · 各扇等人窗口的秒数：开局时取一份，进行中的那一局不变",
            "pacing", "Pauses between steps while a human is aboard, in seconds. · 有真人在座时几段停顿的秒数",
            "stand_ins", "Stand-ins (seats nobody took). · 替身（没人坐的座位）",
            "demo", "Demo games (stand-ins acting on their own with humans aboard). · 演示局（替身自己动、船上有真人）",
            "smart", "Thinking stand-ins (the engine's seat policy). Read when a game starts. · 动脑替身（引擎的席位策略）：开局时取一份",
            "llm", "Language-model stand-ins: an OpenAI-compatible endpoint picks a numbered option. Takes effect when saved. The API key is not in this file. · 大模型替身：存了就生效；密钥不在这个文件里");

    // ---------------------------------------------------------------- 动脑替身那几行：问记录本身

    private static final SeatPolicySettings SEAT = SeatPolicySettings.DEFAULTS;

    /** 记录收不收这一个值（越界它的构造器就抛）。 */
    private static boolean seatAccepts(Runnable build) {
        try {
            build.run();
            return true;
        } catch (IllegalArgumentException rejected) {
            return false;
        }
    }

    /** 记录里一个小数字段：默认值取 {@link SeatPolicySettings#DEFAULTS}，上下限问构造器（{@link RecordBounds}）。 */
    private static SettingDef seatDecimal(String key, double fallback, DoubleFunction<SeatPolicySettings> with,
                                          double step, String comment) {
        java.util.function.DoublePredicate ok = v -> seatAccepts(() -> with.apply(v));
        return SettingDef.decimal(SettingsCategory.SMART, key, fallback, RecordBounds.lowest(ok, fallback),
                RecordBounds.highest(ok, fallback), step, SettingDef.When.NEXT_GAME, comment);
    }

    /** 记录里一个整数字段。 */
    private static SettingDef seatInt(String key, int fallback, IntFunction<SeatPolicySettings> with, int step,
                                      SettingDef.Unit unit, String comment) {
        java.util.function.IntPredicate ok = v -> seatAccepts(() -> with.apply(v));
        return SettingDef.integer(SettingsCategory.SMART, key, fallback, RecordBounds.lowest(ok, fallback),
                RecordBounds.highest(ok, fallback), step, unit, SettingDef.When.NEXT_GAME, comment);
    }

    /** 大模型的一个整数项：默认值与上下限取 {@link LlmConfig} 那一张范围表。 */
    private static SettingDef llmInt(String key, LlmConfig.IntRange range, int step, SettingDef.Unit unit,
                                     String comment) {
        return SettingDef.integer(SettingsCategory.LLM, key, range.fallback(), range.min(), range.max(), step, unit,
                SettingDef.When.IMMEDIATE, comment);
    }

    private static final LlmConfig LLM = LlmConfig.defaults();

    /** 网址最长几个字（{@link LlmConfig} 不限长；菜单里输得下一个带端口与路径的地址就够）。 */
    static final int URL_MAX = 256;
    /** 密钥最长几个字（按码点数）。包要装得下它：{@code ServerSecretSetC2S} 的上限照它算（审查 2026-10-07 U2）。 */
    public static final int KEY_MAX = 512;

    /**
     * 密钥绑哪一项设置（审查 2026-10-07 L1）：{@code llm.api_key} 只发往设它那一刻 {@code llm.base_url} 的「协议 + 主机 + 端口」
     * （{@link LlmConfig#origin}）。设密钥的包要带上发包的人以为的那个地址，与服务端此刻的对不上就拒（{@link ServerSettings#setSecret}）。
     */
    private static final Map<String, String> SECRET_SCOPES = Map.of(LLM_API_KEY, LLM_BASE_URL);

    /** 这项密钥绑的那一项设置的键；不绑的是空。 */
    public static Optional<String> secretScope(String secretKey) {
        return Optional.ofNullable(SECRET_SCOPES.get(secretKey));
    }

    /**
     * 只给能改设置的人看原值的几项（审查 2026-10-07 L1）：发给只读的人的快照里换成 {@link #HIDDEN}。
     * ❗FCAP 进服时仍把整份 SERVER 文件发给每个客户端（{@code ConfigSync.syncConfigs}）—— 这一道只管本模组自己的快照包。
     */
    public static final java.util.Set<String> EDITORS_ONLY = java.util.Set.of(LLM_BASE_URL);
    /** 只读的人看到的那几项写成这个。 */
    public static final String HIDDEN = "***";
    /** {@code reasoning_effort} 最长几个字（{@link LlmConfig} 只认 1–16 个小写字母）。 */
    static final int EFFORT_MAX = 16;

    public static final ServerSettingsTable DEFAULT = new ServerSettingsTable(List.of(
            SettingDef.seconds(SettingsCategory.WINDOWS, ACTION, ActionPhase.ACTION_MILLIS, 10, 600,
                    "Action choice; timeout = do nothing. · 行动一面：超时按「什么也不做」"),
            SettingDef.seconds(SettingsCategory.WINDOWS, ROW, ActionPhase.ROW_MILLIS, 5, 300,
                    "Rowing: keep one of the drawn cards; timeout = all back under the deck. · 划船选一张：超时全部塞回牌堆底"),
            SettingDef.seconds(SettingsCategory.WINDOWS, CONSENT, ContestPhase.CONSENT_MILLIS, 5, 300,
                    "Swap / steal target answers; timeout = agree. · 被指定的人表态：超时按同意"),
            SettingDef.seconds(SettingsCategory.WINDOWS, STANCE, ContestPhase.STANCE_MILLIS, 5, 300,
                    "Taking sides. · 站队"),
            SettingDef.seconds(SettingsCategory.WINDOWS, STANCE_BUMP, ContestPhase.STANCE_BUMP_MILLIS, 1, 120,
                    "Taking sides: what is left after someone joins. · 站队：有人加入之后重置成这么多"),
            SettingDef.seconds(SettingsCategory.WINDOWS, WEAPON, ContestPhase.WEAPON_MILLIS, 5, 300,
                    "Committing weapons. · 押武器"),
            SettingDef.seconds(SettingsCategory.WINDOWS, WEAPON_BUMP, ContestPhase.WEAPON_BUMP_MILLIS, 1, 120,
                    "Committing weapons: what is left after someone commits. · 押武器：有人押下之后重置成这么多"),
            SettingDef.seconds(SettingsCategory.WINDOWS, PICK, ContestPhase.PICK_MILLIS, 5, 300,
                    "Winner of a steal picks a card; timeout = random hand card. · 抢赢的人挑牌：超时按手牌随机一张"),
            SettingDef.seconds(SettingsCategory.WINDOWS, DESIGNATION, DesignationPhase.WINDOW_MILLIS, 5, 300,
                    "Pointing at someone to swap / steal; timeout = do nothing. · 举着拳头找人：超时按「什么也不做」"),
            SettingDef.seconds(SettingsCategory.WINDOWS, HELM, NavigationPhase.PICK_MILLIS, 5, 300,
                    "Helmsman picks the navigation card; timeout = the highlighted one. · 舵手挑航海牌：超时认当前高亮"),
            SettingDef.seconds(SettingsCategory.WINDOWS, OVERBOARD, OverboardPhase.CHOOSE_MILLIS, 5, 300,
                    "Someone went overboard: play a card or not. · 有人落海：打不打牌"),
            SettingDef.seconds(SettingsCategory.WINDOWS, THIRST, ThirstPhase.CHOOSE_MILLIS, 5, 300,
                    "How much water to drink (and giving water). · 口渴喝几张（与替人打水）"),
            SettingDef.seconds(SettingsCategory.WINDOWS, PROVISION_PER_CARD, ProvisionPhase.MILLIS_PER_CARD, 1, 30,
                    "Supply crate: seconds per card left in it. · 补给箱：每剩一张给几秒"),
            SettingDef.seconds(SettingsCategory.WINDOWS, PROVISION_MIN, ProvisionPhase.MIN_MILLIS, 5, 300,
                    "Supply crate: never shorter than this. · 补给箱：至少这么多秒"),
            SettingDef.seconds(SettingsCategory.PACING, REVEAL_HOLD, GameFlow.REVEAL_HOLD_MS, 0, 30,
                    "After the navigation card resolves, before the next day. · 航海牌结算之后停多久再进下一天"),
            SettingDef.seconds(SettingsCategory.PACING, SCORE_HOLD, EndgamePhase.SCORE_HOLD_MS, 5, 600,
                    "Score sheet stays up this long before the game closes. · 计分面板停多久再收局"),
            SettingDef.flag(SettingsCategory.STAND_INS, AUTOPLAY, true, SettingDef.When.SERVER_START,
                    "Stand-ins act on their own (otherwise they wait for /seas). Default at server start; /seas dummy auto still switches it for this run. · 替身自动推进的起服默认值；这一次运行里 /seas dummy auto 照旧能改"),
            SettingDef.choice(SettingsCategory.STAND_INS, MIND, StandInMind.IDLE.name().toLowerCase(Locale.ROOT),
                    mindChoices(), SettingDef.When.SERVER_START,
                    "How stand-ins decide: idle = always pass, random = at random, smart = the engine's seat policy, llm = a language model with the smart answer as fallback. Default at server start; /seas dummy random|smart|llm still switches it for this run. With demo.untimed_humans on, every game with both humans and acting stand-ins becomes untimed. · 替身怎么拿主意的起服默认值；这一次运行里 /seas dummy random|smart|llm 照旧能改"),
            SettingDef.flag(SettingsCategory.STAND_INS, FILL_SEATS, false, SettingDef.When.IMMEDIATE,
                    "Drill skiff: with fewer than 6 people seated, stand-ins fill the empty seats up to 6. · 演习艇：入座不到 6 人时由替身补到 6 人"),
            SettingDef.flag(SettingsCategory.STAND_INS, UNTIMED_DEMO, true, SettingDef.When.NEXT_GAME,
                    "Humans are untimed in demo games (stand-ins acting on their own, humans and stand-ins aboard). · 演示局里真人不限时（替身自己动、船上既有真人也有替身）"),

            // ---- 动脑替身：默认值取 SeatPolicySettings.DEFAULTS，上下限问它的构造器；开局时取一份（每局每座的策略在那时造）
            SettingDef.flag(SettingsCategory.SMART, SMART_SEARCH, SEAT.search(), SettingDef.When.NEXT_GAME,
                    "Look ahead (layer 2: sample possible worlds and play them forward). Off = score the situation only (layer 1). · 往前推演（第二层）；关掉只按处境打分（第一层）"),
            seatInt(SMART_MILLIS, SEAT.millisPerDecision(),
                    v -> SEAT.withBudget(SEAT.rollouts(), v, SEAT.width(), SEAT.horizonDays()), 50,
                    SettingDef.Unit.MILLISECONDS,
                    "Wall-clock limit per decision; 0 = no limit, only the rollout count (the only reproducible setting). · 每个决定最多想多久；0 = 不限时，只看局数（只有这样才可复现）"),
            seatInt(SMART_ROLLOUTS, SEAT.rollouts(),
                    v -> SEAT.withBudget(v, SEAT.millisPerDecision(), SEAT.width(), SEAT.horizonDays()), 8,
                    SettingDef.Unit.NONE, "Most rollouts per decision. · 每个决定最多推演几局"),
            seatInt(SMART_WIDTH, SEAT.width(),
                    v -> SEAT.withBudget(SEAT.rollouts(), SEAT.millisPerDecision(), v, SEAT.horizonDays()), 1,
                    SettingDef.Unit.NONE, "Candidates kept after pruning. · 剪枝之后留几个候选进推演"),
            seatInt(SMART_HORIZON, SEAT.horizonDays(),
                    v -> SEAT.withBudget(SEAT.rollouts(), SEAT.millisPerDecision(), SEAT.width(), v), 1,
                    SettingDef.Unit.NONE, "Days each rollout looks ahead; 0 = to the end of the game. · 每局推演往前看几天；0 = 推到终局"),
            seatDecimal(SMART_TEMPERATURE, SEAT.temperature(), SEAT::withTemperature, 0.05,
                    "How often a stand-in picks a lesser option, in points; 0 = always the best. · 选择的随机程度（按分计）；0 = 永远挑最高的"),
            seatDecimal(SMART_GRATITUDE, SEAT.gratitude(),
                    v -> SEAT.withMemory(v, SEAT.resentment(), SEAT.memoryDays()), 0.1,
                    "How much help from others is remembered; 0 = none. · 别人帮过我，记多重；0 = 不记恩"),
            seatDecimal(SMART_RESENTMENT, SEAT.resentment(),
                    v -> SEAT.withMemory(SEAT.gratitude(), v, SEAT.memoryDays()), 0.1,
                    "How much harm from others is remembered; 0 = none. · 别人害过我，记多重；0 = 不记仇"),
            seatDecimal(SMART_MEMORY_DAYS, SEAT.memoryDays(),
                    v -> SEAT.withMemory(SEAT.gratitude(), SEAT.resentment(), v), 0.5,
                    "Half-life of a remembered deed, in days. · 恩怨的半衰期（天）"),

            // ---- 大模型：默认值与范围取 LlmConfig；存了就生效（接入层当场换一个服务）
            SettingDef.flag(SettingsCategory.LLM, LLM_ENABLED, LLM.enabled(), SettingDef.When.IMMEDIATE,
                    "Use a language model for stand-ins in the llm mind. · 大模型替身的总开关"),
            SettingDef.text(SettingsCategory.LLM, LLM_BASE_URL, LLM.baseUrl(), URL_MAX, SettingDef.When.IMMEDIATE,
                    "OpenAI-compatible endpoint up to /v1 (for example http://127.0.0.1:11434/v1). · 接口地址，写到 /v1 为止"),
            SettingDef.text(SettingsCategory.LLM, LLM_MODEL, LLM.model(), LlmConfig.MODEL_MAX_CHARS,
                    SettingDef.When.IMMEDIATE, "Model name. · 模型名"),
            SettingDef.secret(SettingsCategory.LLM, LLM_API_KEY, KEY_MAX, SettingDef.When.IMMEDIATE,
                    "API key. Kept in heavyseas-secrets.properties, never in this file and never sent to clients; without one the environment variable " + LlmConfig.KEY_ENV + " is used. · 密钥另存，不在这个文件里、不发给客户端；没设时用环境变量"),
            SettingDef.text(SettingsCategory.LLM, LLM_REASONING_EFFORT, LLM.reasoningEffort(), EFFORT_MAX,
                    SettingDef.When.IMMEDIATE, "Sent as reasoning_effort (low, medium, high, none …); empty = not sent. · 发成 reasoning_effort；空 = 不发"),
            SettingDef.choice(SettingsCategory.LLM, LLM_LANGUAGE, LLM.language(), LlmConfig.LANGUAGES,
                    SettingDef.When.IMMEDIATE, "Language of the prompt and the rules excerpt. · 提示词与规则摘录的语言"),
            SettingDef.integer(SettingsCategory.LLM, LLM_DECISION_CAP, (int) StandInSettings.LLM_DECISION_CAP_MS, 1_000,
                    60_000, 500, SettingDef.Unit.MILLISECONDS, SettingDef.When.IMMEDIATE,
                    "How long a stand-in waits for the model on one decision (never past the window). · 替身问一个决定最多等多久（不超过窗口）"),
            llmInt(LLM_MAX_TOKENS, LlmConfig.MAX_TOKENS, 1, SettingDef.Unit.NONE,
                    "max_tokens of the answer (reasoning models count their thinking too). · 回答的 token 上限"),
            SettingDef.decimal(SettingsCategory.LLM, LLM_TEMPERATURE, LLM.temperature(), LlmConfig.TEMPERATURE_UNSET,
                    LlmConfig.TEMPERATURE_MAX, 0.05, SettingDef.When.IMMEDIATE,
                    "Sent as temperature; below 0 = not sent. · 发成 temperature；小于 0 = 不发"),
            llmInt(LLM_MAX_ATTEMPTS, LlmConfig.MAX_ATTEMPTS, 1, SettingDef.Unit.NONE,
                    "Requests per decision, first one included. · 一个决定最多发几次请求"),
            llmInt(LLM_ATTEMPT_TIMEOUT, LlmConfig.ATTEMPT_TIMEOUT_MS, 500, SettingDef.Unit.MILLISECONDS,
                    "Upper limit of one request. · 单次请求的上限"),
            SettingDef.decimal(SettingsCategory.LLM, LLM_ATTEMPT_SHARE, LlmConfig.ATTEMPT_SHARE_DEFAULT,
                    LlmConfig.ATTEMPT_SHARE_MIN, LlmConfig.ATTEMPT_SHARE_MAX, 0.05, SettingDef.When.IMMEDIATE,
                    "Share of the remaining time a non-final request may use; 1 = no split. · 不是最后一次时最多用剩下时间的几成；1 = 不切分"),
            llmInt(LLM_MIN_ATTEMPT, LlmConfig.MIN_ATTEMPT_MS, 100, SettingDef.Unit.MILLISECONDS,
                    "With less time than this left, give up and fall back. · 剩下不到这么多就不再发"),
            llmInt(LLM_BACKOFF_BASE, LlmConfig.BACKOFF_BASE_MS, 100, SettingDef.Unit.MILLISECONDS,
                    "Wait before a retry (doubles each time). · 重试前等多久（每次翻倍）"),
            llmInt(LLM_BACKOFF_MAX, LlmConfig.BACKOFF_MAX_MS, 500, SettingDef.Unit.MILLISECONDS,
                    "Longest wait before a retry; not less than the base. · 重试前最多等多久；不小于上一项"),
            llmInt(LLM_DEADLINE_MARGIN, LlmConfig.DEADLINE_MARGIN_MS, 100, SettingDef.Unit.MILLISECONDS,
                    "Kept free before the deadline. · 截止之前留出的余量"),
            llmInt(LLM_MAX_CONCURRENT, LlmConfig.MAX_CONCURRENT, 1, SettingDef.Unit.NONE,
                    "Requests in flight at once, whole server. · 全服同时在途几个请求"),
            llmInt(LLM_MAX_QUEUED, LlmConfig.MAX_QUEUED, 1, SettingDef.Unit.NONE,
                    "Requests waiting for a slot; beyond this they fall back at once. · 排队上限，再多当场走退路"),
            llmInt(LLM_BREAKER_THRESHOLD, LlmConfig.BREAKER_THRESHOLD, 1, SettingDef.Unit.NONE,
                    "Failures in a row before pausing requests; 0 = never. · 连续失败几次就暂停发请求；0 = 不暂停"),
            llmInt(LLM_BREAKER_COOLDOWN, LlmConfig.BREAKER_COOLDOWN_MS, 1_000, SettingDef.Unit.MILLISECONDS,
                    "How long requests pause. · 暂停多久"),
            SettingDef.flag(SettingsCategory.LLM, LLM_DEBUG_LOG, LLM.debugLog(), SettingDef.When.IMMEDIATE,
                    "Write each request and answer to the server log (the key never; the request holds the seat's own hand). · 把请求与回答写进服务端日志（不含密钥；请求里有这一座的手牌）")));

    /** {@link #MIND} 的取值：{@link StandInMind} 的名字，小写（与 /seas dummy 的子指令同名）。 */
    private static List<String> mindChoices() {
        List<String> out = new ArrayList<>();
        for (StandInMind mind : StandInMind.values()) {
            out.add(mind.name().toLowerCase(Locale.ROOT));
        }
        return out;
    }

    private final List<SettingDef> defs;
    private final Map<String, SettingDef> byKey = new LinkedHashMap<>();

    public ServerSettingsTable(List<SettingDef> defs) {
        this.defs = List.copyOf(defs);
        for (SettingDef def : this.defs) {
            if (byKey.put(def.key(), def) != null) {
                throw new IllegalArgumentException("设置的键重复了：" + def.key());
            }
        }
    }

    /** 全部（含密钥）。 */
    public List<SettingDef> all() {
        return defs;
    }

    /**
     * 会同步给客户端的那几项：进 FCAP 的 SERVER spec、进快照包、能经存盘包改。
     * <b>密钥一律不在这里</b> —— FCAP 进服时会把 SERVER 那份文件整份发给每个客户端，快照包也发给每个人。
     */
    public List<SettingDef> synced() {
        return defs.stream().filter(def -> !def.secret()).toList();
    }

    public Optional<SettingDef> def(String key) {
        return Optional.ofNullable(byKey.get(key));
    }

    /**
     * 快照：每一项此刻的值（按表里的顺序，字符串）。<b>只含 {@link #synced()}</b>。
     *
     * @param current 读一项此刻的值
     */
    public LinkedHashMap<String, String> snapshot(Function<SettingDef, Object> current) {
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        for (SettingDef def : synced()) {
            out.put(def.key(), def.format(current.apply(def)));
        }
        return out;
    }

    /**
     * 整批核对客户端发来的改动（ADR-0099 D6 · TLM 教训 A5）：<b>一项不合法整批拒</b>，拒的时候一个值都不解析出去。
     *
     * @param changes 键 → 新值（字符串）；只该含改过的那几项
     */
    public Batch validate(Map<String, String> changes) {
        if (changes == null || changes.isEmpty()) {
            return Batch.rejected("这一批是空的");
        }
        if (changes.size() > synced().size()) {
            return Batch.rejected("这一批有 " + changes.size() + " 项，比全部设置还多");
        }
        List<String> problems = new ArrayList<>();
        LinkedHashMap<String, Object> parsed = new LinkedHashMap<>();
        for (Map.Entry<String, String> change : changes.entrySet()) {
            SettingDef def = byKey.get(change.getKey());
            if (def == null || def.secret()) {
                // 密钥不走这条路：这一版没有存它的地方，而它也绝不能落进会同步给客户端的那份文件
                problems.add("不认识的键「" + abbreviate(String.valueOf(change.getKey())) + "」");
                continue;
            }
            SettingDef.Parsed value = def.parse(change.getValue());
            if (value.ok()) {
                parsed.put(def.key(), value.value());
            } else {
                problems.add(value.rejection());
            }
        }
        return problems.isEmpty() ? Batch.accepted(parsed) : Batch.rejected(String.join("；", problems));
    }

    /**
     * 按此刻的值算一局的时限（开局快照，ADR-0099 D8）。
     *
     * @param current 读一项此刻的值（秒数是 {@link Number}，开关是 {@link Boolean}）
     */
    public GameTiming timing(Function<SettingDef, Object> current) {
        return new GameTiming(ms(current, ACTION), ms(current, ROW),
                ms(current, CONSENT), ms(current, STANCE), ms(current, STANCE_BUMP),
                ms(current, WEAPON), ms(current, WEAPON_BUMP), ms(current, PICK), ms(current, DESIGNATION),
                ms(current, HELM), ms(current, OVERBOARD), ms(current, THIRST),
                ms(current, PROVISION_PER_CARD), ms(current, PROVISION_MIN), ms(current, REVEAL_HOLD),
                ms(current, SCORE_HOLD), flag(current, UNTIMED_DEMO));
    }

    /** 一个开关此刻的值。 */
    public boolean flag(Function<SettingDef, Object> current, String key) {
        return (Boolean) current.apply(require(key));
    }

    private int integer(Function<SettingDef, Object> current, String key) {
        return ((Number) current.apply(require(key))).intValue();
    }

    private double decimal(Function<SettingDef, Object> current, String key) {
        return ((Number) current.apply(require(key))).doubleValue();
    }

    private String text(Function<SettingDef, Object> current, String key) {
        return String.valueOf(current.apply(require(key)));
    }

    /** 替身怎么拿主意的起服默认值（{@link #MIND}）。 */
    public StandInMind mind(Function<SettingDef, Object> current) {
        return StandInMind.valueOf(text(current, MIND).toUpperCase(Locale.ROOT));
    }

    /**
     * 动脑替身的那一份旋钮（引擎的 {@link SeatPolicySettings}）。各项的范围就是那个记录自己的，所以表里存得下的它都收；
     * 万一收不下（引擎改窄了范围、文件里还是老值），它的构造器抛 —— 调用方退回默认并说一句。
     */
    public SeatPolicySettings seatPolicy(Function<SettingDef, Object> current) {
        return new SeatPolicySettings(decimal(current, SMART_TEMPERATURE), decimal(current, SMART_GRATITUDE),
                decimal(current, SMART_RESENTMENT), decimal(current, SMART_MEMORY_DAYS), flag(current, SMART_SEARCH),
                integer(current, SMART_ROLLOUTS), integer(current, SMART_MILLIS), integer(current, SMART_WIDTH),
                integer(current, SMART_HORIZON));
    }

    /**
     * 大模型那一组的原始值，拼成 {@link LlmConfig.Draft}（交给 {@link LlmConfig#fromSettings}：补缺省值、查约束、定密钥取自哪里）。
     * 温度小于 0 一律当「不发」（菜单从 -1 起一格一格往上加，中间那几格不该把整份设置弄坏）。
     *
     * @param storedKey 设置菜单存的密钥（{@link SecretStore}）；没有是 {@code null}
     */
    public LlmConfig.Draft llmDraft(Function<SettingDef, Object> current, String storedKey) {
        double temperature = decimal(current, LLM_TEMPERATURE);
        return new LlmConfig.Draft(flag(current, LLM_ENABLED), text(current, LLM_BASE_URL), storedKey,
                text(current, LLM_MODEL), integer(current, LLM_MAX_TOKENS),
                temperature < 0 ? LlmConfig.TEMPERATURE_UNSET : temperature, text(current, LLM_REASONING_EFFORT),
                integer(current, LLM_ATTEMPT_TIMEOUT), decimal(current, LLM_ATTEMPT_SHARE),
                integer(current, LLM_MAX_ATTEMPTS), integer(current, LLM_MIN_ATTEMPT),
                integer(current, LLM_BACKOFF_BASE), integer(current, LLM_BACKOFF_MAX),
                integer(current, LLM_MAX_CONCURRENT), integer(current, LLM_MAX_QUEUED),
                integer(current, LLM_DEADLINE_MARGIN), integer(current, LLM_BREAKER_THRESHOLD),
                integer(current, LLM_BREAKER_COOLDOWN), text(current, LLM_LANGUAGE), flag(current, LLM_DEBUG_LOG));
    }

    /** {@link #llmDraft} 读的那几项。 */
    private static final List<String> LLM_DRAFT_KEYS = List.of(LLM_ENABLED, LLM_BASE_URL, LLM_MODEL, LLM_MAX_TOKENS,
            LLM_TEMPERATURE, LLM_REASONING_EFFORT, LLM_ATTEMPT_TIMEOUT, LLM_ATTEMPT_SHARE, LLM_MAX_ATTEMPTS, LLM_MIN_ATTEMPT,
            LLM_BACKOFF_BASE, LLM_BACKOFF_MAX, LLM_MAX_CONCURRENT, LLM_MAX_QUEUED, LLM_DEADLINE_MARGIN,
            LLM_BREAKER_THRESHOLD, LLM_BREAKER_COOLDOWN, LLM_LANGUAGE, LLM_DEBUG_LOG);

    /**
     * 按这一份值，「大模型」一组合不合得成大模型那一层收的设置（{@link LlmConfig#problemOf}）；合得成、或者这张表里没有整组
     * （单测里的小表）是 {@code null}。审查 2026-10-07 R8：每项单看都合法、合起来不收的一批（reasoning_effort 写成「High」、
     * 退避底数大于上限、地址没写 {@code http://}），原先存下去、显示「已保存」，然后整个大模型层静默关掉。
     */
    public String llmProblem(Function<SettingDef, Object> current) {
        if (!byKey.keySet().containsAll(LLM_DRAFT_KEYS)) {
            return null;
        }
        return LlmConfig.problemOf(llmDraft(current, null));
    }

    /** 替身问大模型一个决定最多等多久（毫秒）。 */
    public long llmDecisionCapMs(Function<SettingDef, Object> current) {
        return integer(current, LLM_DECISION_CAP);
    }

    private long ms(Function<SettingDef, Object> current, String key) {
        return ((Number) current.apply(require(key))).longValue() * 1000L;
    }

    private SettingDef require(String key) {
        SettingDef def = byKey.get(key);
        if (def == null) {
            throw new IllegalStateException("设置表里没有 " + key);
        }
        return def;
    }

    private static String abbreviate(String raw) {
        return raw.length() <= 48 ? raw : raw.substring(0, 48) + "…";
    }

    /** 整批核对的结果：要么全部解析好的值，要么一句拒绝的理由。 */
    public record Batch(Map<String, Object> values, String rejection) {

        static Batch accepted(Map<String, Object> values) {
            return new Batch(java.util.Collections.unmodifiableMap(new LinkedHashMap<>(values)), null);
        }

        static Batch rejected(String why) {
            return new Batch(Map.of(), why);
        }

        public boolean ok() {
            return rejection == null;
        }
    }
}
