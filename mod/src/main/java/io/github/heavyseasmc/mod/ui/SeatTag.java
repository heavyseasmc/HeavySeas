package io.github.heavyseasmc.mod.ui;

import io.github.heavyseasmc.mod.ui.HudLayout.Rect;

/**
 * 座位上那枚「昏」签放在哪（ADR-0048 D1 (a)）。样张里在头像正中；与体力印章相交时让开印章 —— 只在头像那个方框里挪，
 * 绝不伸出头像上下沿：主画面座位轨的下沿就在头像下 9（稿子像素），各面的轨上名字就在头像下 3。
 *
 * <p>2026-10-13 第二轮真机截图 50：印章顶边恰好落在头像正中那一行（{@code seal.y == t.y + t.h / 2}），
 * 原先「印章在下半就挪到它上面」的比较用了 {@code >}，于是判成「印章在上」，签挪到了印章下面、伸出座位轨下沿。
 * 这里不按印章在哪一半猜，而是两边都试：先上后下，哪边放得进方框就放哪边；都放不进就贴着方框顶。
 */
public final class SeatTag {

    private SeatTag() {
    }

    /**
     * @param token 头像那个圆的方框
     * @param seal  体力印章占的那一块
     * @param w     签宽
     * @param h     签高
     */
    public static Rect place(Rect token, Rect seal, int w, int h) {
        int x = token.x() + (token.w() - w) / 2;
        int y = token.y() + (token.h() - h) / 2;
        Rect centred = new Rect(x, y, w, h);
        if (!centred.intersects(seal)) {
            return centred;
        }
        int above = seal.y() - 1 - h;
        if (above >= token.y()) {
            return new Rect(x, above, w, h);
        }
        int below = seal.y() + seal.h() + 1;
        if (below + h <= token.y() + token.h()) {
            return new Rect(x, below, w, h);
        }
        return new Rect(x, token.y(), w, h);
    }
}
