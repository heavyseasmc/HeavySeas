package io.github.heavyseasmc.mod.config;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 设置里的一项（ADR-0099 §5 D2 · D6）：键、归哪一组、类型、默认值、范围、是不是密钥、改了什么时候生效。
 *
 * <p>纯数据，不碰 Forge Config API Port —— 菜单两端（界面、服务端的整批校验）与单测都拿这一张表说话，
 * FCAP 的 spec 也由它展开（{@link ServerSettings}），不另写第二份默认值与范围。
 *
 * <h2>加一项设置</h2>
 * 用下面那几个工厂方法写进 {@link ServerSettingsTable}（服务端）或 {@link LocalSettings}（本机），
 * 再在两份 lang 里写名字与一句说明（{@code heavyseas.settings.<键>} · {@code heavyseas.settings.<键>.desc}，
 * 选项另有 {@code heavyseas.settings.<键>.<值>}）。界面按 {@link #category()} 把它排进那一组的签里，按类型挑控件 ——
 * 不用碰界面代码。{@code SettingsLangKeysTest} 核每一项的键在两份 lang 里都在。
 *
 * <h2>值的类型</h2>
 * {@code SECONDS} · {@code INT} 是 {@link Integer}，{@code DECIMAL} 是 {@link Double}，{@code FLAG} 是 {@link Boolean}，
 * {@code CHOICE} · {@code TEXT} 是 {@link String}。网络上一律按字符串传：{@link #format} 与 {@link #parse} 互逆。
 *
 * @param key      在配置文件里的路径（带点的是段：{@code windows.action_seconds}）
 * @param category 归菜单里的哪一组
 * @param kind     值的类型
 * @param fallback 默认值（类型见上）
 * @param min      数值的下限（含）；别的类型不用
 * @param max      数值的上限（含）
 * @param step     菜单里 ← → 一下改多少（只管界面，不是校验：文件里写个不在格点上的数照样合法）
 * @param choices  {@code CHOICE} 的全部取值（按菜单里的先后）；别的类型是空表
 * @param maxLength {@code TEXT} 最长几个字；别的类型不用
 * @param unit     数值后面跟的单位（菜单里显示用）
 * @param secret   密钥：<b>永不进同步给客户端的东西</b> —— 不进 FCAP 的 SERVER spec（FCAP 进服时把整份文件发给每个客户端），
 *                 也不进快照包，只经 {@link SecretStore} 存、只写不读（ADR-0096 §8）。只能是 {@code TEXT}
 * @param when     改了从什么时候起作数：菜单要照实说，不能让人以为改了当场就生效
 * @param comment  写进配置文件的注释（FCAP 要求非空）
 */
public record SettingDef(String key, SettingsCategory category, Kind kind, Object fallback, double min, double max,
                         double step, List<String> choices, int maxLength, Unit unit, boolean secret, When when,
                         String comment) {

    public enum Kind { SECONDS, INT, DECIMAL, FLAG, CHOICE, TEXT }

    /** 数值后面的单位。 */
    public enum Unit { NONE, SECONDS, MILLISECONDS }

    /** 改了从什么时候起作数。 */
    public enum When {
        /** 下一局开局时（各扇窗口按开局快照，一局之内不变 · ADR-0099 D8）。 */
        NEXT_GAME,
        /** 下次起服时（替身两个开关的默认值：这一次运行里由指令说了算 · ADR-0019）。 */
        SERVER_START,
        /** 立刻（下一次用到时就读新值）。 */
        IMMEDIATE
    }

    public SettingDef {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(fallback, "fallback");
        Objects.requireNonNull(unit, "unit");
        Objects.requireNonNull(when, "when");
        choices = List.copyOf(Objects.requireNonNull(choices, "choices"));
        if (comment == null || comment.isBlank()) {
            // FCAP 遇到全空白的注释在开发环境直接抛、生产环境每次起服警告一次（ModConfigSpec#buildComment）
            throw new IllegalArgumentException(key + "：注释不能是空的");
        }
        if (secret && kind != Kind.TEXT) {
            throw new IllegalArgumentException(key + "：密钥只能是文字");
        }
        if (kind == Kind.CHOICE && choices.isEmpty()) {
            throw new IllegalArgumentException(key + "：选项一个都没有");
        }
        if ((kind == Kind.SECONDS || kind == Kind.INT || kind == Kind.DECIMAL) && (min > max || step <= 0)) {
            throw new IllegalArgumentException(key + "：范围 " + min + "–" + max + " / 步长 " + step + " 不对");
        }
        if (kind == Kind.TEXT && maxLength <= 0) {
            throw new IllegalArgumentException(key + "：文字最长几个字没给");
        }
        // 默认值自己就得合规矩：不合的话「恢复默认」会恢复出一个存不进去的值
        Optional<String> bad = checkValue(key, kind, fallback, min, max, choices, maxLength);
        if (bad.isPresent()) {
            throw new IllegalArgumentException(key + "：默认值 " + fallback + " 不合规矩（" + bad.get() + "）");
        }
    }

    // ---------------------------------------------------------------- 工厂（加设置就用这几个）

    /** 一扇窗口的秒数：默认值从毫秒常量换算过来（那个常量就是改之前写死的数）。 */
    public static SettingDef seconds(SettingsCategory category, String key, long defaultMs, int min, int max, String comment) {
        if (defaultMs % 1000 != 0) {
            throw new IllegalArgumentException(key + "：默认值 " + defaultMs + " 毫秒不是整秒，换算会丢精度");
        }
        return new SettingDef(key, category, Kind.SECONDS, (int) (defaultMs / 1000), min, max, 1, List.of(), 0,
                Unit.SECONDS, false, When.NEXT_GAME, comment);
    }

    /** 整数（{@code unit} 只管显示，例如毫秒）。 */
    public static SettingDef integer(SettingsCategory category, String key, int fallback, int min, int max, Unit unit,
                                     When when, String comment) {
        return integer(category, key, fallback, min, max, 1, unit, when, comment);
    }

    /**
     * 整数，← → 一下改 {@code step}（毫秒、局数这类一格一格挪没有意义的）。菜单从下限起按步长对齐格点，
     * 所以默认值最好落在格点上（{@code (fallback - min) % step == 0}），不然 ← → 一下会先跳到最近的格点。
     */
    public static SettingDef integer(SettingsCategory category, String key, int fallback, int min, int max, int step,
                                     Unit unit, When when, String comment) {
        return new SettingDef(key, category, Kind.INT, fallback, min, max, step, List.of(), 0, unit, false, when, comment);
    }

    /** 小数：{@code step} 是菜单里 ← → 一下改多少。 */
    public static SettingDef decimal(SettingsCategory category, String key, double fallback, double min, double max,
                                     double step, When when, String comment) {
        return new SettingDef(key, category, Kind.DECIMAL, fallback, min, max, step, List.of(), 0, Unit.NONE, false,
                when, comment);
    }

    public static SettingDef flag(SettingsCategory category, String key, boolean fallback, When when, String comment) {
        return new SettingDef(key, category, Kind.FLAG, fallback, 0, 0, 1, List.of(), 0, Unit.NONE, false, when, comment);
    }

    /** 几个固定取值里挑一个（取值是字符串，菜单里的名字另写 lang：{@code heavyseas.settings.<键>.<值>}）。 */
    public static SettingDef choice(SettingsCategory category, String key, String fallback, List<String> values,
                                    When when, String comment) {
        return new SettingDef(key, category, Kind.CHOICE, fallback, 0, 0, 1, values, 0, Unit.NONE, false, when, comment);
    }

    /** 一行字（去掉首尾空白；不许有控制字符）。 */
    public static SettingDef text(SettingsCategory category, String key, String fallback, int maxLength, When when,
                                  String comment) {
        return new SettingDef(key, category, Kind.TEXT, fallback, 0, 0, 1, List.of(), maxLength, Unit.NONE, false,
                when, comment);
    }

    /**
     * 密钥：只写不读。存在服务端自己的文件里（{@link SecretStore}），快照里只说「设了没有」，菜单里只显示 已设置 / 未设置。
     * 默认是空（没设）。
     */
    public static SettingDef secret(SettingsCategory category, String key, int maxLength, When when, String comment) {
        return new SettingDef(key, category, Kind.TEXT, "", 0, 0, 1, List.of(), maxLength, Unit.NONE, true, when, comment);
    }

    // ---------------------------------------------------------------- 解析 · 核对 · 格式化

    /**
     * 客户端发来的一个值（字符串）→ 这一项的类型。不认识的一律说清为什么，不猜。
     *
     * @return 解析好的值；或者拒绝的理由（一行，进日志）
     */
    public Parsed parse(String raw) {
        if (raw == null) {
            return Parsed.rejected(key + "：没有值");
        }
        String text = raw.strip();
        Object value;
        switch (kind) {
            case SECONDS, INT -> {
                try {
                    value = Integer.parseInt(text);
                } catch (NumberFormatException e) {
                    return Parsed.rejected(key + "：「" + abbreviate(raw) + "」不是整数");
                }
            }
            case DECIMAL -> {
                try {
                    double d = Double.parseDouble(text);
                    if (!Double.isFinite(d) || text.chars().anyMatch(c -> !(Character.isDigit(c) || c == '.' || c == '-'))) {
                        // 「1e3」「NaN」「0x10」这类 Java 认、菜单不会写出来的写法一律不认
                        return Parsed.rejected(key + "：「" + abbreviate(raw) + "」不是小数");
                    }
                    value = d;
                } catch (NumberFormatException e) {
                    return Parsed.rejected(key + "：「" + abbreviate(raw) + "」不是小数");
                }
            }
            case FLAG -> {
                // 只认这两个字面量：「yes」「1」之类的猜出来，等于替人做了一个他没说的决定
                if (text.equals("true")) {
                    value = Boolean.TRUE;
                } else if (text.equals("false")) {
                    value = Boolean.FALSE;
                } else {
                    return Parsed.rejected(key + "：「" + abbreviate(raw) + "」不是 true / false");
                }
            }
            case CHOICE, TEXT -> value = text;
            default -> throw new IllegalStateException("没处理的类型：" + kind);
        }
        return check(value).map(Parsed::rejected).orElseGet(() -> Parsed.ok(value));
    }

    /** 这个值合不合这一项（类型与范围）。合就是空。 */
    public Optional<String> check(Object value) {
        return checkValue(key, kind, value, min, max, choices, maxLength);
    }

    private static Optional<String> checkValue(String key, Kind kind, Object value, double min, double max,
                                               List<String> choices, int maxLength) {
        return switch (kind) {
            case SECONDS, INT -> !(value instanceof Integer n)
                    ? Optional.of(key + "：不是整数")
                    : n < min || n > max ? Optional.of(key + "：" + n + " 不在 " + plain(min) + "–" + plain(max) + " 里")
                    : Optional.empty();
            case DECIMAL -> !(value instanceof Double d) || !Double.isFinite(d)
                    ? Optional.of(key + "：不是小数")
                    : d < min || d > max ? Optional.of(key + "：" + plain(d) + " 不在 " + plain(min) + "–" + plain(max) + " 里")
                    : Optional.empty();
            case FLAG -> value instanceof Boolean ? Optional.empty() : Optional.of(key + "：不是开关");
            case CHOICE -> value instanceof String s && choices.contains(s)
                    ? Optional.empty() : Optional.of(key + "：不在可选的 " + choices + " 里");
            case TEXT -> !(value instanceof String s)
                    ? Optional.of(key + "：不是文字")
                    : s.codePointCount(0, s.length()) > maxLength ? Optional.of(key + "：超过 " + maxLength + " 个字")
                    : s.codePoints().anyMatch(c -> c < 0x20 || c == 0x7F) ? Optional.of(key + "：有控制字符")
                    : !s.equals(s.strip()) ? Optional.of(key + "：首尾有空白")
                    : Optional.empty();
        };
    }

    /** 值 → 网络上的字符串（与 {@link #parse} 互逆）。 */
    public String format(Object value) {
        if (kind == Kind.DECIMAL && value instanceof Number n) {
            return plain(n.doubleValue());
        }
        return String.valueOf(value);
    }

    /** 默认值的字符串（{@link #format} 过的）。 */
    public String formattedFallback() {
        return format(fallback);
    }

    /** 数值类型（有范围、能用液位管调）。 */
    public boolean numeric() {
        return kind == Kind.SECONDS || kind == Kind.INT || kind == Kind.DECIMAL;
    }

    /** 小数写成最短的那种（0.55、1、-1），不带科学计数法 —— 再解析回来是同一个数。 */
    static String plain(double d) {
        if (d == Math.rint(d) && Math.abs(d) < 1e15) {
            return Long.toString((long) d);
        }
        return BigDecimal.valueOf(d).stripTrailingZeros().toPlainString();
    }

    private static String abbreviate(String raw) {
        return raw.length() <= 32 ? raw : raw.substring(0, 32) + "…";
    }

    /** {@link #parse} 的结果：要么一个值，要么一句拒绝的理由。 */
    public record Parsed(Object value, String rejection) {

        static Parsed ok(Object value) {
            return new Parsed(Objects.requireNonNull(value, "value"), null);
        }

        static Parsed rejected(String why) {
            return new Parsed(null, Objects.requireNonNull(why, "why"));
        }

        public boolean ok() {
            return rejection == null;
        }
    }
}
