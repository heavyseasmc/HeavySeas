package io.github.heavyseasmc.mod.ui;

/**
 * 通知侧栏与对局主内容共享的一份横向几何。
 *
 * <p>这份计算刻意不引用任何 Minecraft 客户端类：服务端加载它也安全，普通单测也能把
 * 「主内容不能伸进侧栏」变成机械判据。客户端只负责告诉它当前有没有东西要放进侧栏。
 */
public record NotificationSidebarLayout(int screenWidth, int sidebarWidth, int sidebarX, int contentWidth) {

    public static final int MARGIN = 6;
    private static final int MIN_SIDEBAR_WIDTH = 140;
    private static final int MAX_SIDEBAR_WIDTH = 240;

    /**
     * @param screenWidth 当前 GUI 的完整宽度
     * @param visible     天候卡或历史通知是否让侧栏可见
     */
    public static NotificationSidebarLayout of(int screenWidth, boolean visible) {
        if (screenWidth < 0) {
            throw new IllegalArgumentException("屏幕宽度不能是负数: " + screenWidth);
        }
        if (!visible) {
            return new NotificationSidebarLayout(screenWidth, 0, screenWidth, screenWidth);
        }

        int wanted = Math.min(MAX_SIDEBAR_WIDTH, Math.max(MIN_SIDEBAR_WIDTH, screenWidth / 3));
        // 正常的 Minecraft GUI 远宽于这个下限；仍给极窄窗口留至少一个单位的主内容，
        // 免得窗口拖到最窄时产生负宽度，随后传进卡面与文字折行计算。
        int available = Math.max(0, screenWidth - 2 * MARGIN - 1);
        int sidebarWidth = Math.min(wanted, available);
        if (sidebarWidth == 0) {
            return new NotificationSidebarLayout(screenWidth, 0, screenWidth, screenWidth);
        }

        int sidebarX = screenWidth - sidebarWidth - MARGIN;
        int contentWidth = sidebarX - MARGIN;
        return new NotificationSidebarLayout(screenWidth, sidebarWidth, sidebarX, contentWidth);
    }
}
