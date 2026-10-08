package io.github.heavyseasmc.mod.client;

/**
 * 亮出要按两下（审查 2026-10-07 Z2）。亮出<b>不可逆</b>（规则 §5.2），而原先有四条路能一碰就亮出、或者亮出之后
 * 顺手把下一张打了出去：
 * <ol>
 *   <li>打不出的牌不画「打出」，「亮出」挪进第一格 —— 点「原来打出的位置」就是亮出；</li>
 *   <li>↓ 再回车，而 ↓ 在世界里是日志往下翻，开手牌之后顺手一按；</li>
 *   <li>焦点挪到亮出之后换了选中，亮出的是另一张；</li>
 *   <li>亮出之后紧跟一下回车（连按，或按住时的系统重复）就变成打出。</li>
 * </ol>
 *
 * <p>所以：第一下只把那一枚变成「再按一次亮出」（{@link Result#ARMED}），{@link #WINDOW_MS} 内对<b>同一张</b>
 * （同一个牌 id、同一个下标 —— 同名的另一张不算）再按一下才发包（{@link Result#FIRE}）。换选中、别的键、超时都撤销
 * （调用方调 {@link #cancel}，超时由 {@link #armed} 自己判）。亮出之后 {@link #AFTER_MS} 内的那一下一律不算
 * （{@link Result#IGNORED}），手牌一面在这段里也不认「打出」。按住的系统重复在 {@link ConfirmGate} 那一层就拦掉了。
 *
 * <p>纯逻辑、时间由调用方给，好写单测（{@code RevealConfirmTest}）。
 */
final class RevealConfirm {

    /** 第一下之后多久内再按算数。 */
    static final long WINDOW_MS = 3_000L;
    /** 亮出之后多久内的确认不算（连按的第二下）。 */
    static final long AFTER_MS = 400L;

    enum Result {
        /** 进了「再按一次」那一态（或重新进）：什么都没发。 */
        ARMED,
        /** 第二下：现在发包。 */
        FIRE,
        /** 刚亮出过：这一下不算。 */
        IGNORED
    }

    private String card;
    private int index = -1;
    private long armedAt;
    private long firedAt = Long.MIN_VALUE / 2;

    /** 按了一下「亮出」（回车落在亮出那一枚上，或点了那一枚）。 */
    Result press(String card, int index, long now) {
        if (coolingDown(now)) {
            return Result.IGNORED;
        }
        if (armed(card, index, now)) {
            cancel();
            firedAt = now;
            return Result.FIRE;
        }
        this.card = card;
        this.index = index;
        this.armedAt = now;
        return Result.ARMED;
    }

    /** 此刻对这一张是不是「再按一次」那一态（按钮变色、字改成「再按一次亮出」）。 */
    boolean armed(String card, int index, long now) {
        return this.card != null && this.card.equals(card) && this.index == index && now - armedAt < WINDOW_MS;
    }

    /** 撤销「再按一次」：换了选中、按了别的键、进出查看态。 */
    void cancel() {
        card = null;
        index = -1;
    }

    /** 刚亮出过：紧跟着的那一下（亮出或打出）都不算。 */
    boolean coolingDown(long now) {
        return now - firedAt < AFTER_MS;
    }
}
