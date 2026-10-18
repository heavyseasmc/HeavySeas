package io.github.heavyseasmc.mod.ui;

/**
 * 「这是不是一扇新窗口」—— 客户端据此决定要不要把决策面自己弹出来（站队 · 押武器 · 表态 · 挑牌 · 口渴 · 替人打水 · 舵手 · 落海）。
 *
 * <p>❗身份里<b>带着到点时刻</b>。窗口会在一段中途重置：有人加入把站队那 15 秒重置成 8 秒、有人押牌把 10 秒重置成 6 秒 ——
 * 重置就是一扇新窗口：局面变了（多了一个人、多了一张暗牌），该再问一次（{@code HeavySeasClient} 的 contestWindowShown 注释）。
 * 2026-10-13 验收抓到：试合入那一版只认服务端的决定编号（{@code ExperienceView#decisionId}，它故意不含可调的截止时刻），
 * 于是重置之后再也不弹 —— 一条没说明的行为变更。
 *
 * <p>决定编号不含截止时刻是对的：它是「返回原决定」的身份（{@code DecisionNavigation.Context}），截止时刻一变就当成另一个决定的话，
 * 从规则书、聊天回来时会被当成「决定已经结束」。两件事用两个身份：返回认编号，弹不弹认编号 + 截止时刻。
 *
 * <p>纯函数，单测见 {@code DecisionWindowsTest}。
 */
public final class DecisionWindows {

    private DecisionWindows() {
    }

    /** 一扇窗口的身份：哪一局 · 哪一个决定 · 到点时刻（服务端时钟）。 */
    public static String key(String matchId, String decisionId, long deadlineMs) {
        return matchId + "|" + decisionId + "|" + deadlineMs;
    }

    /**
     * 这一帧该不该当新窗口弹。
     *
     * @param decisionId 服务端给的决定编号；空 = 这一刻没有欠我的决定（答过了 · 轮不到我），不弹
     * @param key        这一帧的窗口身份（{@link #key}）
     * @param shown      上一次为它弹过的那一扇
     */
    public static boolean fresh(String decisionId, String key, String shown) {
        return !decisionId.isEmpty() && !key.equals(shown);
    }
}
