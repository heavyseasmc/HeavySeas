package io.github.heavyseasmc.mod.client;

/**
 * 对局各面共有的那几个「去别处」的键（规则书 · 回到决定 · 手牌 · 聊天 · 指令）交给谁 —— {@code GameScreen#keyPressed} 的判据本体，
 * 不碰 Minecraft，好写单测（与 {@link KeyRoute} 同一个路数）。
 *
 * <p>两条护栏（2026-10-13 验收）：
 * <ol>
 *   <li><b>正在输字时一个都不接</b>：设置界面编辑文字项时按 T 或 /，原先被拽进了聊天 —— 这一组排在各面自己的按键之前，
 *       而聊天那一支不问这一面在不在收字。收字的面（{@code GameScreen#capturingText}）先拿到每一个键。</li>
 *   <li><b>聊天与指令只在对局里接</b>：这一组是「决定做到一半先去聊两句」的入口；没有对局时（设置界面从标题画面进来）
 *       T 与 / 不归它管。</li>
 * </ol>
 */
final class NavigationKeys {

    /** 这一下交给谁。 */
    enum Route {
        /** 不归这一组：交给这一面自己。 */
        NONE,
        RULES,
        /** 从规则书 · 聊天 · 手牌这类二级页回到原来那一个决定。 */
        BACK_TO_DECISION,
        /** 不是决策面：收起这一面，去开此刻欠着的那一个决定（按 G）。 */
        REQUEST_DECISION,
        OPEN_HAND,
        CHAT,
        COMMAND
    }

    /** 一次按键的事实。都是调用方从按键绑定与投影里读出来的布尔值。 */
    record Press(boolean capturingText, boolean rules, boolean act, boolean hand, boolean chat, boolean command,
                 boolean giftKeyHere, boolean active, boolean seated, boolean decisionFace, boolean handOrAction,
                 boolean childOfDecision) {
    }

    private NavigationKeys() {
    }

    static Route route(Press p) {
        if (p.capturingText()) {
            return Route.NONE;                // 输字的面先拿到每一个键：T、/、R、G 都只是字
        }
        if (p.rules()) {
            return Route.RULES;
        }
        if (p.childOfDecision() && p.act()) {
            return Route.BACK_TO_DECISION;
        }
        if (!p.decisionFace() && p.act() && p.active()) {
            return Route.REQUEST_DECISION;
        }
        if (!p.handOrAction() && p.hand() && p.seated()) {
            return Route.OPEN_HAND;
        }
        if (!p.active()) {
            return Route.NONE;                // 没有对局：聊天与指令照 Minecraft 自己的办法（这一面不拦）
        }
        // T 在手牌与座位面板上是「赠送」；那两面的聊天走左上角那一枚按钮
        if (p.chat() && !p.giftKeyHere()) {
            return Route.CHAT;
        }
        if (p.command()) {
            return Route.COMMAND;
        }
        return Route.NONE;
    }
}
