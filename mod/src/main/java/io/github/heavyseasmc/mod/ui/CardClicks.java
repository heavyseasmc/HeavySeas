package io.github.heavyseasmc.mod.ui;

/**
 * 点牌：<b>单击只选中，双击才打出</b>（用户 2026-10-13 定，座位面板与手牌一面同一条）。
 *
 * <p>原先手牌一面左键点牌就打出（「左键点牌 = 就它了」），座位面板也一样 —— 验收时实拍到单击就把面前的牌打了出去，
 * 想看一眼说明、想换一张都来不及。按钮与 Enter 照旧一下就打出：那是「我定了」的那一下，点牌是「我看这张」。
 *
 * <p>双击的窗口取 Minecraft 自带的世界列表（单人游戏选存档那一面）同一个数：两下相隔不到 250 ms、点在同一张上。
 * 点在别的牌上、或者隔得太久，都只算重新选中。
 *
 * <p>纯逻辑，不碰 Minecraft，单测见 {@code CardClicksTest}。
 */
public final class CardClicks {

    /** 两下之间最多隔多久（毫秒）：照 Minecraft 自带世界列表的双击。 */
    public static final long DOUBLE_CLICK_MS = 250L;

    /** 这一下点完之后该做什么。 */
    public enum Result {
        /** 选中这一张（不打出）。 */
        SELECT,
        /** 同一张上的第二下：打出。 */
        PLAY
    }

    private int lastIndex = -1;
    private long lastAt;

    /**
     * 点在第 {@code index} 张上。
     *
     * @param index 点中第几张（{@code < 0} = 没点在牌上，只清掉上一下）
     * @param now   这一下的时刻（毫秒）
     */
    public Result click(int index, long now) {
        if (index < 0) {
            reset();
            return Result.SELECT;
        }
        boolean second = index == lastIndex && now - lastAt >= 0 && now - lastAt < DOUBLE_CLICK_MS;
        if (second) {
            reset();                          // 第三下不再连着算：再打一张要重新双击
            return Result.PLAY;
        }
        lastIndex = index;
        lastAt = now;
        return Result.SELECT;
    }

    /** 选中换了（方向键、牌少了一张）：上一下不再算数。 */
    public void reset() {
        lastIndex = -1;
        lastAt = 0L;
    }
}
