package io.github.heavyseasmc.mod.client;

import java.util.HashSet;
import java.util.Set;
import java.util.function.IntPredicate;

/**
 * 确认键（回车 · 小键盘回车 · 空格）的闸，每一面一份，由 {@code GameScreen#keyPressed} 在各面之前问（审查 2026-10-07 U1）。
 *
 * <p>自动弹出的决策面会顶在玩家正在做的事上：补给箱到手时正在聊天里打字，下一个空格就替他把高亮的第 1 张留下了；
 * 按住回车时系统的按键重复（GLFW 的 {@code REPEAT}，1.21.1 的 {@code Keyboard.onKey} 把它与按下一样交给
 * {@code keyPressed}）也被当成新的一下。三条：
 * <ul>
 *   <li>这一面<b>第一次</b>显示出来之后 {@link #GRACE_MS} 内的确认键不算（宽限）；</li>
 *   <li>显示出来那一刻就按着的确认键，松开之前一律不算（弹出前就按下的不算）；</li>
 *   <li>一个确认键按下之后、松开之前再来的「按下」是系统重复，不算。</li>
 * </ul>
 * 方向键等别的键不受影响：它们不替玩家决定任何事，按住连走照旧。空格照样是确认键（用户认过的设计），只加宽限。
 *
 * <p>❗每次重新显示（从二级页面回来、窗口改尺寸）都要重新看一遍哪些键按着、并忘掉「按下没松开」的记录：
 * 按下那一下可能打开了二级页面，松开落在那一面上 —— 不忘掉的话，回来之后这个键第一下永远被当成重复。
 */
final class ConfirmGate {

    /** 弹出后多久内不认确认键。 */
    static final long GRACE_MS = 300L;

    private static final int[] CONFIRM_KEYS = {
            org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER, org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER, org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE};

    /** 第一次显示的时刻；还没显示过是 {@code -1}。 */
    private long shownAt = -1L;
    /** 在这一面上按下、还没松开的确认键。 */
    private final Set<Integer> down = new HashSet<>();
    /** 显示出来那一刻就按着的确认键：松开之前不算。 */
    private final Set<Integer> heldBefore = new HashSet<>();

    /**
     * 这一面显示出来了（{@code init}：打开、从二级页面回来、窗口改尺寸都会调）。宽限只从第一次算。
     *
     * @param physicallyDown 此刻这个键是不是按着（读窗口的按键状态；单测里给一个假的）
     */
    void shown(long now, IntPredicate physicallyDown) {
        if (shownAt < 0L) {
            shownAt = now;
        }
        down.clear();
        heldBefore.clear();
        for (int key : CONFIRM_KEYS) {
            if (physicallyDown.test(key)) {
                heldBefore.add(key);
            }
        }
    }

    /** 按下（含系统的按住重复）：这一下算不算数。不是确认键一律算。 */
    boolean press(int key, long now) {
        if (!isConfirm(key)) {
            return true;
        }
        if (heldBefore.contains(key)) {
            return false;
        }
        if (!down.add(key)) {
            return false;                     // 按下没松开又来一下：系统重复
        }
        return shownAt < 0L || now - shownAt >= GRACE_MS;
    }

    /** 松开。 */
    void release(int key) {
        heldBefore.remove(key);
        down.remove(key);
    }

    /** 回车 · 小键盘回车 · 空格。 */
    static boolean isConfirm(int key) {
        for (int k : CONFIRM_KEYS) {
            if (k == key) {
                return true;
            }
        }
        return false;
    }
}
