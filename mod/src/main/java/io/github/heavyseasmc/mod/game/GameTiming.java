package io.github.heavyseasmc.mod.game;

/**
 * 一局里各扇等人窗口与几段节奏停顿有多长（ADR-0099 D8），<b>开局那一刻定下、一局之内不变</b>。
 *
 * <h2>为什么要快照</h2>
 * 时限可以在服务端设置里改（{@code heavyseas-server.toml}，文件被改会热重载）。各阶段若每次开窗口都现读设置，
 * 一局打到一半有人改了行动时限，同一局里前后几个人拿到的窗口就不一样长 —— 屏幕上不报错，只是不公平。
 * 所以 {@link GameFlow} 开局时从设置里取一份，挂在组件上，各阶段只读这一份。
 *
 * <h2>默认值</h2>
 * {@link #DEFAULTS} 就是各阶段原先写死的那几个常量，一个不差 —— 设置文件不存在、或者全是默认值时，行为与改之前完全相同。
 * 常量本身留在原处当默认值的出处（注释里写着每个数是谁、哪天定的）。
 *
 * <p>不进这里的几个数：终局翻牌的两段停顿（客户端的翻 · 看 · 滑动画是按它们排的）、靠岸那一段（客户端的雾按它渐变）、
 * 各段「该答的都答了」之后的那一小截尾巴（那是替身延迟推出来的，不是等人的时限）。
 *
 * @param untimedDemo 演示局里真人不限时（用户 2026-10-07）。默认开 —— 与原先推导出来的行为一致：
 *                    还要替身自动推进 · 随机行动都开着、名单里有真人也有替身才生效（{@code GameComponent#demoNoTimeout}）
 */
public record GameTiming(long actionMs, long rowMs,
                         long consentMs, long stanceMs, long stanceBumpMs, long weaponMs, long weaponBumpMs,
                         long contestPickMs, long designationMs, long helmPickMs, long overboardMs, long thirstMs,
                         long provisionPerCardMs, long provisionMinMs, long revealHoldMs, long scoreHoldMs,
                         boolean untimedDemo) {

    /** 改之前写死的那一套。 */
    public static final GameTiming DEFAULTS = new GameTiming(
            ActionPhase.ACTION_MILLIS, ActionPhase.ROW_MILLIS,
            ContestPhase.CONSENT_MILLIS, ContestPhase.STANCE_MILLIS, ContestPhase.STANCE_BUMP_MILLIS,
            ContestPhase.WEAPON_MILLIS, ContestPhase.WEAPON_BUMP_MILLIS, ContestPhase.PICK_MILLIS,
            DesignationPhase.WINDOW_MILLIS, NavigationPhase.PICK_MILLIS, OverboardPhase.CHOOSE_MILLIS,
            ThirstPhase.CHOOSE_MILLIS, ProvisionPhase.MILLIS_PER_CARD, ProvisionPhase.MIN_MILLIS,
            GameFlow.REVEAL_HOLD_MS, EndgamePhase.SCORE_HOLD_MS, true);

    /**
     * 补给箱这一手 {@code cards} 张的窗口多长（决策 ⑨：按剩余张数缩放，但不少于下限）。
     * 只在服务端算；客户端画倒计时用投影里带过去的总长（{@code ProvisionUpdateS2C#windowMs}），不另算一遍 —— 两边各算就会分家。
     */
    public long provisionWindow(int cards) {
        return Math.max(provisionMinMs, Math.max(1, cards) * provisionPerCardMs);
    }

    /** 一行日志：开局时打出这一局用的时限，「改了设置」与「没改」在日志里分得开。单位秒。 */
    public String describe() {
        return "行动 %s · 划船 %s · 表态 %s · 站队 %s（追加 %s）· 押武器 %s（追加 %s）· 挑牌 %s · 指人 %s · 舵手 %s · 落海 %s · 口渴 %s · 补给箱每张 %s（至少 %s）· 航海停顿 %s · 计分停留 %s · 演示局真人不限时 %s"
                .formatted(s(actionMs), s(rowMs), s(consentMs), s(stanceMs), s(stanceBumpMs), s(weaponMs),
                        s(weaponBumpMs), s(contestPickMs), s(designationMs), s(helmPickMs), s(overboardMs),
                        s(thirstMs), s(provisionPerCardMs), s(provisionMinMs), s(revealHoldMs), s(scoreHoldMs),
                        untimedDemo ? "开" : "关");
    }

    private static String s(long ms) {
        return ms % 1000 == 0 ? Long.toString(ms / 1000) : Double.toString(ms / 1000.0);
    }
}
