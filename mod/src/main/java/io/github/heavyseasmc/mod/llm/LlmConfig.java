package io.github.heavyseasmc.mod.llm;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * 大模型替身接入层的设置（ADR-0096 §3 · A「开发期开关」）：<b>一份不可变的纯数据</b>，不带任何 JSON / Gson 的类型。
 *
 * <h2>设置与来源分开</h2>
 * 来源是设置菜单的「大模型」一组（服务端设置）：那一侧把值填进 {@link Draft}，交给 {@link #fromSettings}
 * （密钥先取设置菜单存的，没有再看环境变量）。旧的 {@code config/heavyseas/llm.json} 只在起服时迁一次（{@link LlmConfigFile}）。
 * 每个字段的缺省值、取值范围、是不是机密、该不该只给管理员改，都写在下面与 {@link Field} 里，一项对一项 ——
 * 设置表的默认值与范围也从这里取，不另抄一份。
 *
 * <h2>构造器会校验</h2>
 * 每一项按 {@link Field} 的范围查，外加字段之间的约束（{@code enabled} 时 {@code baseUrl} 与 {@code model} 必填；
 * {@code backoffMaxMs ≥ backoffBaseMs}）；不合规矩抛 {@link IllegalArgumentException}，消息里一次报全。
 * {@code baseUrl} 末尾的 {@code /} 与各字符串首尾的空白会先去掉。
 *
 * <h2>❗机密</h2>
 * {@link #apiKey} 是<b>唯一的机密</b>（{@link Field#secret()}）：只进请求头，不进日志（{@link #toString()} 与 {@link #describe()} 都不带）。
 * 这份设置不得原样发给客户端；会同步给客户端的配置（例如 SERVER 类型的配置）里也不能放它 —— 留在环境变量 {@value #KEY_ENV}
 * 或服务端自己的文件里，合成设置时再填进来（{@link #from} 就是这么做的：环境变量压过来源里的值）。
 *
 * <p>❗<b>默认关</b>：没有配置、{@code enabled} 不是 {@code true}、或者写坏了，一律关着 —— 关着时 {@link LlmService#choose}
 * 当场回 {@code DISABLED}，不发任何网络请求。
 *
 * <p>时间预算怎么分给每一次尝试、退避怎么算、熔断怎么开合，见 {@link LlmService} 的类注释（公式写在那里）。
 *
 * @param enabled           总开关。缺省 {@code false}。只给管理员改。
 * @param baseUrl           OpenAI 兼容接口的根，写到 {@code /v1} 为止，后面自动接 {@code /chat/completions}。缺省空串；
 *                          {@code enabled} 时必填：{@code http(s)://} 带主机名，不带 {@code ?} {@code #}。只给管理员改（决定局面发到哪里去）。
 * @param apiKey            ❗机密。缺省空串（本机 Ollama、LM Studio 不要密钥）。不得含控制字符。只给管理员改，且不放进会同步给客户端的配置。
 * @param model             模型名。缺省空串；{@code enabled} 时必填；最多 200 字、不含控制字符。
 * @param maxTokens         回答的 token 上限（发成 {@code max_tokens}；推理模型的思考也算在里面）。缺省 64，范围 1–32768。
 * @param temperature       发成 {@code temperature}。缺省 -1 = 不发（用服务端的默认）；否则 0–2。
 * @param reasoningEffort   发成 {@code reasoning_effort}。缺省空串 = 不发；否则 1–16 个小写字母（取值看服务商，实测有的认 low · none、不认 minimal）。
 * @param attemptTimeoutMs  单次尝试的超时上限（实际还要按剩下的预算再分，见 {@link LlmService}）。缺省 15000，范围 500–120000。
 * @param attemptShare      不是最后一次时，单次最多花掉剩下预算的这一份（其余留给退避与下一次）。缺省 0.55，范围 0.3–1.0；
 *                          1.0 = 不切分。实测（2026-10-07 本机代理）：慢而稳的推理模型在 8 秒的站队窗口里被 0.55 切成两次超时 ——
 *                          那种模型宜调高这一项，或者设 reasoningEffort 让它快下来。
 * @param maxAttempts       一次决定最多发几次请求（含第一次）。缺省 3，范围 1–10。
 * @param minAttemptMs      地板：剩下的预算不到这么多就不再发（发出去也等不到回答），直接走退路。缺省 800，范围 100–10000。
 * @param backoffBaseMs     退避的底数：第 k 次失败后等 min(backoffMaxMs, backoffBaseMs·2^(k−1)) 再加最多 backoffBaseMs/2 的随机。缺省 300，范围 0–10000。
 * @param backoffMaxMs      退避的上限（{@code Retry-After} 不受它管）。缺省 3000，范围 0–60000，且不小于 backoffBaseMs。
 * @param maxConcurrent     全服同时在途的请求数上限（所有座位共用）。缺省 4，范围 1–64。
 * @param maxQueued         排队等名额的上限，再多当场回 {@code QUEUE_FULL}。缺省 32，范围 0–1024。
 * @param deadlineMarginMs  截止时间往前留的余量：结果要赶在窗口收之前回到主线程。缺省 1000，范围 0–10000。
 * @param breakerThreshold  熔断：连续这么多次传输失败 / 超时 / 5xx 就熔断。缺省 5，范围 0–100（0 = 不熔断）。
 * @param breakerCooldownMs 熔断之后多久内的决定直接走退路（不发请求）；过了就放行，再失败一次立刻重新熔断。缺省 30000，范围 1000–600000。
 * @param language          规则摘录与提示词的语言。缺省 {@code zh_cn}，只认 {@code zh_cn} · {@code en_us}。
 * @param debugLog          把每次的请求体与回包也写进服务端日志（密钥照样不写；❗请求体里有这一座的手牌与爱恨）。缺省 {@code false}。
 */
public record LlmConfig(boolean enabled, String baseUrl, String apiKey, String model, int maxTokens, double temperature,
                        String reasoningEffort, int attemptTimeoutMs, double attemptShare, int maxAttempts, int minAttemptMs,
                        int backoffBaseMs, int backoffMaxMs, int maxConcurrent, int maxQueued, int deadlineMarginMs,
                        int breakerThreshold, int breakerCooldownMs, String language, boolean debugLog) {

    /** 密钥的环境变量：有值就压过配置里的 {@code apiKey}（密钥不必落进服务端的配置目录）。 */
    public static final String KEY_ENV = "HEAVYSEAS_LLM_API_KEY";

    /**
     * {@value #KEY_ENV} 里的密钥只发往这个变量写明的地址（审查 2026-10-07 L1）：写一个地址就行（{@code https://api.example.com}，
     * 写到 {@code /v1} 也可以），比的是它的「协议 + 主机 + 端口」（{@link #origin}）。<b>没写就不带环境变量里的密钥</b> ——
     * 能改设置的人（专用服务端上任意一个 2 级管理员、局域网开了作弊的客人、下载来的存档自带的 {@code serverconfig/}）
     * 都改得了 {@code llm.base_url}，而环境变量是服主一个人定的。
     */
    public static final String KEY_ORIGIN_ENV = "HEAVYSEAS_LLM_KEY_ORIGIN";

    public static final List<String> LANGUAGES = List.of("zh_cn", "en_us");

    /** 整数项的范围：缺省值与上下限（含）。 */
    public record IntRange(int fallback, int min, int max) {
        String check(String key, int value) {
            return value < min || value > max ? key + " 要在 " + min + " 到 " + max + " 之间，写的是 " + value : null;
        }
    }

    public static final IntRange MAX_TOKENS = new IntRange(64, 1, 32_768);
    public static final IntRange ATTEMPT_TIMEOUT_MS = new IntRange(15_000, 500, 120_000);
    public static final IntRange MAX_ATTEMPTS = new IntRange(3, 1, 10);
    public static final IntRange MIN_ATTEMPT_MS = new IntRange(800, 100, 10_000);
    public static final IntRange BACKOFF_BASE_MS = new IntRange(300, 0, 10_000);
    public static final IntRange BACKOFF_MAX_MS = new IntRange(3_000, 0, 60_000);
    public static final IntRange MAX_CONCURRENT = new IntRange(4, 1, 64);
    public static final IntRange MAX_QUEUED = new IntRange(32, 0, 1_024);
    public static final IntRange DEADLINE_MARGIN_MS = new IntRange(1_000, 0, 10_000);
    public static final IntRange BREAKER_THRESHOLD = new IntRange(5, 0, 100);
    public static final IntRange BREAKER_COOLDOWN_MS = new IntRange(30_000, 1_000, 600_000);
    /** {@code temperature} 小于 0 = 不发。 */
    public static final double TEMPERATURE_UNSET = -1.0;
    public static final double TEMPERATURE_MAX = 2.0;
    public static final double ATTEMPT_SHARE_DEFAULT = 0.55;
    public static final double ATTEMPT_SHARE_MIN = 0.3;
    public static final double ATTEMPT_SHARE_MAX = 1.0;
    public static final int MODEL_MAX_CHARS = 200;
    private static final Pattern EFFORT = Pattern.compile("[a-z]{1,16}");

    /**
     * 每一项的说明书：键名（= 字段名 = 配置文件里的键）、缺省值、取值范围、是不是机密、是不是只给管理员改。
     * 与字段一项对一项、次序相同，缺省值与 {@link #defaults()} 一致 —— 单测逐项核（加了字段而没补这张表，那里红）。
     */
    public enum Field {
        ENABLED("enabled", "false", "true / false", false, true),
        BASE_URL("baseUrl", "", "空串，或 http(s):// 带主机名、不带 ? #、写到 /v1 为止（enabled 时必填）", false, true),
        API_KEY("apiKey", "", "任意不含控制字符的字符串（环境变量 " + KEY_ENV + " 有值时以它为准）", true, true),
        MODEL("model", "", "最多 200 字、不含控制字符（enabled 时必填）", false, true),
        MAX_TOKENS("maxTokens", "64", "1–32768", false, true),
        TEMPERATURE("temperature", "-1.0", "-1（不发）或 0–2", false, true),
        REASONING_EFFORT("reasoningEffort", "", "空串（不发）或 1–16 个小写字母", false, true),
        ATTEMPT_TIMEOUT_MS("attemptTimeoutMs", "15000", "500–120000", false, true),
        ATTEMPT_SHARE("attemptShare", "0.55", "0.3–1.0（1.0 = 不切分）", false, true),
        MAX_ATTEMPTS("maxAttempts", "3", "1–10", false, true),
        MIN_ATTEMPT_MS("minAttemptMs", "800", "100–10000", false, true),
        BACKOFF_BASE_MS("backoffBaseMs", "300", "0–10000", false, true),
        BACKOFF_MAX_MS("backoffMaxMs", "3000", "0–60000，且不小于 backoffBaseMs", false, true),
        MAX_CONCURRENT("maxConcurrent", "4", "1–64", false, true),
        MAX_QUEUED("maxQueued", "32", "0–1024", false, true),
        DEADLINE_MARGIN_MS("deadlineMarginMs", "1000", "0–10000", false, true),
        BREAKER_THRESHOLD("breakerThreshold", "5", "0–100（0 = 不熔断）", false, true),
        BREAKER_COOLDOWN_MS("breakerCooldownMs", "30000", "1000–600000", false, true),
        LANGUAGE("language", "zh_cn", "zh_cn · en_us", false, true),
        DEBUG_LOG("debugLog", "false", "true / false", false, true);

        private final String key;
        private final String defaultValue;
        private final String range;
        private final boolean secret;
        private final boolean opOnly;

        Field(String key, String defaultValue, String range, boolean secret, boolean opOnly) {
            this.key = key;
            this.defaultValue = defaultValue;
            this.range = range;
            this.secret = secret;
            this.opOnly = opOnly;
        }

        /** 字段名，也是配置文件里的键。 */
        public String key() {
            return key;
        }

        /** 缺省值的文字形式（{@code String.valueOf(defaults() 里那一项)}）。 */
        public String defaultValue() {
            return defaultValue;
        }

        public String range() {
            return range;
        }

        /** 机密：不进日志、不进会同步给客户端的配置、界面上只能显示「有 / 无」。 */
        public boolean secret() {
            return secret;
        }

        /**
         * 只给管理员改。这一组全是：它们决定服务端把局面发到哪里、花谁的钱、多久放弃、日志里写不写手牌 —— 没有一项该由普通玩家改。
         */
        public boolean opOnly() {
            return opOnly;
        }
    }

    public LlmConfig {
        baseUrl = baseUrl == null ? "" : baseUrl.strip();
        while (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        apiKey = apiKey == null ? "" : apiKey.strip();
        model = model == null ? "" : model.strip();
        reasoningEffort = reasoningEffort == null ? "" : reasoningEffort.strip();
        language = language == null ? "" : language.strip();
        List<String> problems = new ArrayList<>();
        if (!baseUrl.isEmpty()) {
            add(problems, checkBaseUrl(baseUrl));
        }
        if (hasControl(apiKey)) {
            problems.add("apiKey 里有控制字符（换行之类）");
        }
        if (model.length() > MODEL_MAX_CHARS || hasControl(model)) {
            problems.add("model 最多 " + MODEL_MAX_CHARS + " 字、不能有控制字符");
        }
        add(problems, MAX_TOKENS.check("maxTokens", maxTokens));
        if (!(temperature == TEMPERATURE_UNSET || (temperature >= 0.0 && temperature <= TEMPERATURE_MAX))) {
            problems.add("temperature 要么是 -1（不发），要么在 0 到 2 之间，写的是 " + temperature);
        }
        if (!reasoningEffort.isEmpty() && !EFFORT.matcher(reasoningEffort).matches()) {
            problems.add("reasoningEffort 要么是空串（不发），要么是 1–16 个小写字母，写的是「" + reasoningEffort + "」");
        }
        add(problems, ATTEMPT_TIMEOUT_MS.check("attemptTimeoutMs", attemptTimeoutMs));
        if (!(attemptShare >= ATTEMPT_SHARE_MIN && attemptShare <= ATTEMPT_SHARE_MAX)) {
            problems.add("attemptShare 要在 " + ATTEMPT_SHARE_MIN + " 到 " + ATTEMPT_SHARE_MAX + " 之间，写的是 " + attemptShare);
        }
        add(problems, MAX_ATTEMPTS.check("maxAttempts", maxAttempts));
        add(problems, MIN_ATTEMPT_MS.check("minAttemptMs", minAttemptMs));
        add(problems, BACKOFF_BASE_MS.check("backoffBaseMs", backoffBaseMs));
        add(problems, BACKOFF_MAX_MS.check("backoffMaxMs", backoffMaxMs));
        if (backoffMaxMs < backoffBaseMs) {
            problems.add("backoffMaxMs（" + backoffMaxMs + "）不能小于 backoffBaseMs（" + backoffBaseMs + "）");
        }
        add(problems, MAX_CONCURRENT.check("maxConcurrent", maxConcurrent));
        add(problems, MAX_QUEUED.check("maxQueued", maxQueued));
        add(problems, DEADLINE_MARGIN_MS.check("deadlineMarginMs", deadlineMarginMs));
        add(problems, BREAKER_THRESHOLD.check("breakerThreshold", breakerThreshold));
        add(problems, BREAKER_COOLDOWN_MS.check("breakerCooldownMs", breakerCooldownMs));
        if (!LANGUAGES.contains(language)) {
            problems.add("language 只认 " + String.join(" · ", LANGUAGES) + "，写的是「" + language + "」");
        }
        if (enabled && baseUrl.isEmpty()) {
            problems.add("enabled 是 true，但没写 baseUrl");
        }
        if (enabled && model.isEmpty()) {
            problems.add("enabled 是 true，但没写 model");
        }
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException(String.join("；", problems));
        }
    }

    /** 全部取缺省值：关着。 */
    public static LlmConfig defaults() {
        return new LlmConfig(false, "", "", "", MAX_TOKENS.fallback(), TEMPERATURE_UNSET, "",
                ATTEMPT_TIMEOUT_MS.fallback(), ATTEMPT_SHARE_DEFAULT, MAX_ATTEMPTS.fallback(), MIN_ATTEMPT_MS.fallback(),
                BACKOFF_BASE_MS.fallback(), BACKOFF_MAX_MS.fallback(), MAX_CONCURRENT.fallback(), MAX_QUEUED.fallback(),
                DEADLINE_MARGIN_MS.fallback(), BREAKER_THRESHOLD.fallback(), BREAKER_COOLDOWN_MS.fallback(), "zh_cn", false);
    }

    /** 关着的那一份（= {@link #defaults()}）：没有配置、写坏了、或者 {@code enabled} 不是 {@code true}。 */
    public static LlmConfig off() {
        return defaults();
    }

    private static void add(List<String> out, String problem) {
        if (problem != null) {
            out.add(problem);
        }
    }

    private static boolean hasControl(String s) {
        return s.chars().anyMatch(Character::isISOControl);
    }

    private static String checkBaseUrl(String baseUrl) {
        URI uri;
        try {
            uri = new URI(baseUrl);
        } catch (Exception e) {
            return "baseUrl 不是合法的地址：「" + baseUrl + "」";
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            return "baseUrl 只认 http:// 或 https://";
        }
        if (uri.getHost() == null) {
            return "baseUrl 里没有主机名";
        }
        if (uri.getRawUserInfo() != null) {
            // 审查 2026-10-07 L1：地址随设置同步给每个客户端；凭据只走密钥那一项（只写不读、绑地址）
            return "baseUrl 不能带用户名或密码（user:pass@）：凭据放进密钥那一项";
        }
        if (uri.getQuery() != null || uri.getFragment() != null) {
            return "baseUrl 不能带 ? 或 #（后面要接 /chat/completions）";
        }
        if (baseUrl.endsWith("/chat/completions")) {
            return "baseUrl 写到 /v1 为止就行，/chat/completions 会自动接上";
        }
        return null;
    }

    /**
     * 一个地址的「协议://主机:端口」（协议与主机小写、没写端口补上 80 / 443）：密钥绑的就是它（审查 2026-10-07 L1）。
     * 路径不算 —— 同一台服务器上换个路径不是「换了地方」。不是 {@code http(s)://} 带主机名的地址返回 {@code null}。
     * 与 {@link #endpoint()} 读同一个 {@link URI} 的同几个部分，{@code HttpClient} 连的就是这三样。
     */
    public static String origin(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        URI uri;
        try {
            uri = new URI(url.strip());
        } catch (Exception e) {
            return null;
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if ((!scheme.equals("http") && !scheme.equals("https")) || uri.getHost() == null) {
            return null;
        }
        int port = uri.getPort() >= 0 ? uri.getPort() : scheme.equals("https") ? 443 : 80;
        return scheme + "://" + uri.getHost().toLowerCase(Locale.ROOT) + ":" + port;
    }

    // ---- 从某个来源合成设置 ----

    /**
     * 某个来源读到的原始值。{@code null} = 那一项没写，用缺省值。
     * 「写了但类型不对」「认不出的键」这类问题是来源自己的事（{@link LlmConfigFile} 查），这里只管值本身。
     */
    public record Draft(Boolean enabled, String baseUrl, String apiKey, String model, Integer maxTokens,
                        Double temperature, String reasoningEffort, Integer attemptTimeoutMs, Double attemptShare, Integer maxAttempts,
                        Integer minAttemptMs, Integer backoffBaseMs, Integer backoffMaxMs, Integer maxConcurrent,
                        Integer maxQueued, Integer deadlineMarginMs, Integer breakerThreshold,
                        Integer breakerCooldownMs, String language, Boolean debugLog) {

        /** ❗不带密钥。 */
        @Override
        public String toString() {
            return "Draft[enabled=" + enabled + ", baseUrl=" + baseUrl + ", apiKey=" + (apiKey == null ? "null" : "（不显示）")
                    + ", model=" + model + ", …]";
        }
    }

    /**
     * 读的结果：用的那一份设置，加上为什么是这一份。
     *
     * @param config    一定不是 {@code null}；写坏了就是 {@link #off()}
     * @param problem   写坏了的原因（一句人话，可直接进日志）；没坏是 {@code null}
     * @param note      没坏时的一句说明（「没有配置文件」「enabled 不是 true」「开着」）
     * @param keySource 密钥的来源（「环境变量 …」「配置文件」「无」）—— 给 status 看，密钥本身不在这里
     * @param keyWithheld 有密钥、却因为地址对不上没带的那一句（审查 2026-10-07 L1）；都带上了或者本来就没有是 {@code null}。
     *                    接入层起来时记一行 WARN、{@code /seasllm status} 也说 —— 「没带」与「没有」在请求里长得一样（都是 401）
     */
    public record Loaded(LlmConfig config, String problem, String note, String keySource, String keyWithheld) {

        public Loaded(LlmConfig config, String problem, String note, String keySource) {
            this(config, problem, note, keySource, null);
        }

        public boolean broken() {
            return problem != null;
        }

        public static Loaded broken(String problem) {
            return new Loaded(off(), problem, null, "无");
        }
    }

    /**
     * 原始值 → 设置：补缺省值、按范围与约束查（就是构造器那一套）、定密钥取自哪里（环境变量 {@value #KEY_ENV} 压过来源里的 {@code apiKey}）。
     * 一次把错都报全；有错就整份关着。
     *
     * @param sourceProblems 来源自己查出来的问题（类型不对、认不出的键……），一起报
     * @param env            查环境变量（服务端传 {@code System::getenv}，单测传一张表）
     */
    public static Loaded from(Draft d, List<String> sourceProblems, Function<String, String> env) {
        // ❗只给旧文件那一套读法（LlmConfigFile）与单测用：不查地址绑定。服务端起的接入层一律走 fromSettings
        String envKey = env.apply(KEY_ENV);
        if (envKey != null && !envKey.isBlank()) {
            return build(d, sourceProblems, envKey, KEY_SOURCE_ENV, null);
        }
        if (d.apiKey() != null && !d.apiKey().isBlank()) {
            return build(d, sourceProblems, d.apiKey(), "配置文件", null);
        }
        return build(d, sourceProblems, "", KEY_SOURCE_NONE, null);
    }

    /** 密钥取自设置菜单（服务端自己的密钥文件）时 {@link Loaded#keySource()} 说的话。 */
    public static final String KEY_SOURCE_STORE = "设置菜单（服务端的密钥文件）";
    /** 密钥取自环境变量时说的话。 */
    public static final String KEY_SOURCE_ENV = "环境变量 " + KEY_ENV;
    /** 没有密钥。 */
    public static final String KEY_SOURCE_NONE = "无";

    /**
     * 服务端设置（设置菜单的「大模型」一组）→ 设置。密钥<b>先看设置菜单存的</b>（{@code d.apiKey()}，服务端的密钥文件），
     * 没有再看环境变量 {@value #KEY_ENV} —— 习惯用环境变量的服务端照旧能用，菜单里设了就以菜单为准。
     *
     * <h2>❗密钥只发往它该去的地址（审查 2026-10-07 L1）</h2>
     * 「只写不读」原先没和地址绑在一起：谁能改 {@code llm.base_url}，谁就能把地址改到自己的服务器、让服务端把密钥送过去
     * （专用服务端上任意一个 2 级管理员 · 局域网开了作弊的客人 · 下载来的存档自带的 {@code serverconfig/} 覆盖全局那份）。所以：
     * <ul>
     *   <li>菜单存的密钥只在 {@code storedKeyOrigin}（设它那一刻的「协议 + 主机 + 端口」，{@link #origin}）与此刻的地址一致时带；
     *       改了地址要在菜单里重新设一次。旧版本存的、没记下地址的一律不带。</li>
     *   <li>环境变量里的密钥只发往 {@value #KEY_ORIGIN_ENV} 写明的地址；没写就不带。</li>
     * </ul>
     * 带不了的那一句进 {@link Loaded#keyWithheld()}（接入层起来时记 WARN）。比对就在这里做一次：
     * 结果是一份不可变的设置，请求发往的 {@link #endpoint()} 与带的密钥出自同一份，中间没有第二处能改地址。
     *
     * @param storedKeyOrigin 菜单存的密钥绑的地址（{@link #origin} 的写法）；没有绑（旧版本存的）是 {@code null}
     */
    public static Loaded fromSettings(Draft d, String storedKeyOrigin, Function<String, String> env) {
        boolean enabled = Boolean.TRUE.equals(d.enabled());
        String target = origin(d.baseUrl());
        List<String> withheld = new ArrayList<>();
        if (d.apiKey() != null && !d.apiKey().isBlank()) {
            if (target != null && target.equals(storedKeyOrigin)) {
                return build(d, List.of(), d.apiKey(), KEY_SOURCE_STORE, null);
            }
            withheld.add(storedKeyOrigin == null
                    ? "设置菜单里的密钥没记下是给哪个地址设的（旧版本存的）"
                    : "设置菜单里的密钥是给 " + storedKeyOrigin + " 设的，地址现在是 " + (target == null ? "（无）" : target));
        }
        String envKey = env.apply(KEY_ENV);
        if (envKey != null && !envKey.isBlank()) {
            String allowed = origin(env.apply(KEY_ORIGIN_ENV));
            if (target != null && target.equals(allowed)) {
                return build(d, List.of(), envKey, KEY_SOURCE_ENV, enabled ? withheldNote(withheld) : null);
            }
            withheld.add(allowed == null
                    ? "环境变量 " + KEY_ENV + " 里有密钥，但没用 " + KEY_ORIGIN_ENV + " 写明它发往哪个地址"
                    : "环境变量 " + KEY_ENV + " 的密钥只发往 " + allowed + "，地址现在是 " + (target == null ? "（无）" : target));
        }
        return build(d, List.of(), "", KEY_SOURCE_NONE, enabled ? withheldNote(withheld) : null);
    }

    private static String withheldNote(List<String> withheld) {
        return withheld.isEmpty() ? null
                : String.join("；", withheld) + "。改了地址要在设置菜单里重新设一次密钥（环境变量的密钥改 " + KEY_ORIGIN_ENV + "）";
    }

    /**
     * 这一份原始值合不合得成一份设置（构造器那一套）；合得成是 {@code null}，否则是一句理由。不碰密钥。
     * 设置菜单存「大模型」一组之前先问它（审查 2026-10-07 R8：原先每项单看都合法就存，存完整个大模型层静默关掉）。
     */
    public static String problemOf(Draft d) {
        return build(d, List.of(), "", KEY_SOURCE_NONE, null).problem();
    }

    private static Loaded build(Draft d, List<String> sourceProblems, String key, String keySource, String keyWithheld) {
        LlmConfig def = defaults();
        boolean enabled = d.enabled() != null && d.enabled();
        List<String> problems = new ArrayList<>(sourceProblems);
        try {
            LlmConfig config = new LlmConfig(enabled,
                    or(d.baseUrl(), def.baseUrl()), key, or(d.model(), def.model()),
                    or(d.maxTokens(), def.maxTokens()), or(d.temperature(), def.temperature()),
                    or(d.reasoningEffort(), def.reasoningEffort()),
                    or(d.attemptTimeoutMs(), def.attemptTimeoutMs()), or(d.attemptShare(), def.attemptShare()),
                    or(d.maxAttempts(), def.maxAttempts()),
                    or(d.minAttemptMs(), def.minAttemptMs()), or(d.backoffBaseMs(), def.backoffBaseMs()),
                    or(d.backoffMaxMs(), def.backoffMaxMs()), or(d.maxConcurrent(), def.maxConcurrent()),
                    or(d.maxQueued(), def.maxQueued()), or(d.deadlineMarginMs(), def.deadlineMarginMs()),
                    or(d.breakerThreshold(), def.breakerThreshold()), or(d.breakerCooldownMs(), def.breakerCooldownMs()),
                    or(d.language(), def.language()), d.debugLog() != null && d.debugLog());
            if (!problems.isEmpty()) {
                return new Loaded(off(), String.join("；", problems), null, keySource, keyWithheld);
            }
            return new Loaded(config, null, enabled ? "开着" : "enabled 不是 true，大模型替身关着", keySource, keyWithheld);
        } catch (IllegalArgumentException e) {
            // 合不起来时照样说密钥取自哪里（status 要用）；构造器的理由里没有密钥本身（只说「有控制字符」）
            problems.add(e.getMessage());
            return new Loaded(off(), String.join("；", problems), null, keySource, keyWithheld);
        }
    }

    private static <T> T or(T value, T fallback) {
        return value == null ? fallback : value;
    }

    // ---- 派生出来的东西 ----

    public boolean hasKey() {
        return !apiKey.isEmpty();
    }

    public boolean sendsTemperature() {
        return temperature >= 0;
    }

    public boolean sendsReasoningEffort() {
        return !reasoningEffort.isEmpty();
    }

    /** 请求发往哪里：{@code baseUrl} 后面接 {@code /chat/completions}。 */
    public URI endpoint() {
        return URI.create(baseUrl + "/chat/completions");
    }

    /** 给人看的地址：只到 {@code 主机:端口}，路径都不给。 */
    public String hostLabel() {
        if (baseUrl.isEmpty()) {
            return "（无）";
        }
        URI uri = URI.create(baseUrl);
        return uri.getPort() < 0 ? uri.getHost() : uri.getHost() + ":" + uri.getPort();
    }

    /** ❗不带密钥：record 自动生成的那一版会把每个字段都打出来，哪天有人把设置写进日志就漏了。 */
    @Override
    public String toString() {
        return "LlmConfig[enabled=" + enabled + ", host=" + hostLabel() + ", key=" + (hasKey() ? "有" : "无")
                + ", model=" + model + ", maxTokens=" + maxTokens + ", temperature=" + temperature
                + ", reasoningEffort=" + reasoningEffort + ", attemptTimeoutMs=" + attemptTimeoutMs + ", attemptShare=" + attemptShare
                + ", maxAttempts=" + maxAttempts + ", minAttemptMs=" + minAttemptMs + ", backoffBaseMs=" + backoffBaseMs
                + ", backoffMaxMs=" + backoffMaxMs + ", maxConcurrent=" + maxConcurrent + ", maxQueued=" + maxQueued
                + ", deadlineMarginMs=" + deadlineMarginMs + ", breakerThreshold=" + breakerThreshold
                + ", breakerCooldownMs=" + breakerCooldownMs + ", language=" + language + ", debugLog=" + debugLog + "]";
    }

    /** 可以给人看的那几项（status 用）：不带密钥，地址只到主机:端口。 */
    public Map<String, String> describe() {
        Map<String, String> out = new LinkedHashMap<>();
        out.put("模型", model.isEmpty() ? "（无）" : model);
        out.put("地址", hostLabel());
        out.put("密钥", hasKey() ? "有" : "无");
        out.put("语言", language);
        out.put("请求", "max_tokens " + maxTokens
                + (sendsTemperature() ? " · temperature " + temperature : "")
                + (sendsReasoningEffort() ? " · reasoning_effort " + reasoningEffort : ""));
        out.put("重试", "最多 " + maxAttempts + " 次 · 单次上限 " + attemptTimeoutMs + " ms、不是最后一次只用剩下的 " + attemptShare + " · 地板 " + minAttemptMs
                + " ms · 退避 " + backoffBaseMs + "–" + backoffMaxMs + " ms · 余量 " + deadlineMarginMs + " ms");
        out.put("并发", "在途 " + maxConcurrent + " · 排队 " + maxQueued);
        out.put("熔断", breakerThreshold == 0 ? "关" : "连续 " + breakerThreshold + " 次失败 · 冷却 " + breakerCooldownMs + " ms");
        out.put("调试日志", debugLog ? "开（请求体与回包进日志）" : "关");
        return out;
    }
}
