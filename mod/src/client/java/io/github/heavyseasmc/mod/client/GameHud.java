package io.github.heavyseasmc.mod.client;

import io.github.heavyseasmc.engine.state.Condition;
import io.github.heavyseasmc.engine.state.GameState;
import io.github.heavyseasmc.engine.state.Phase;
import io.github.heavyseasmc.mod.state.GameComponents;
import io.github.heavyseasmc.mod.state.HudView;
import io.github.heavyseasmc.mod.ui.NotificationSidebarLayout;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * 主画面 HUD：一块状态牌、一条座位轨、一枚轮到你时落下来的金签，右上一扇天候舷窗与一枚日志页签（样张 b-1 · b-2）。
 *
 * <h2>为什么一行字都没有</h2>
 * ADR-0037 §7.1 第 5 条「主画面不要成行的文字」，稿子上的主画面是<b>物件</b>：
 * 你的头像（金圈 = 你）· 体力点 · 口渴水滴 · 清醒的眼睛；天候 · 阶段轮盘 · 四只海鸥；第几天 · 手牌几张。
 * 2026-09-30 复核之前这里仍是左上六行带阴影的字 —— 定了没做（ADR-0045 §1.4 B2）。
 * 例外只有一处，是用户 2026-09-25 定的（ADR-0043 D3 (b)）：<b>轮到你做决定时</b>，金签上一句不超过 8 个字的动词短语。
 *
 * <h2>它只说「有」，不说「是什么」</h2>
 * 手牌只给张数与开界面的键。牌面本身归手牌那一面（{@link HandScreen}）——
 * 把牌名铺在 HUD 上，一是挤，二是<b>别人凑过来看屏幕就全知道了</b>，而本作的手牌是隐藏信息。
 *
 * <h2>在等谁，看座位轨</h2>
 * 原先「口渴：等 X · N 秒」「换座位：X 对 Y」这几行，现在都落在座位轨上：正轮到的那一位铜绿圈，
 * 被抢 / 被换的那一位朱砂圈，舵手身上挂一枚舵轮（带划船堆张数）。**等待要看得见**（决策 ⑨ ⑭），
 * 看的是位置，不是读数字。
 *
 * <h2>键永远是实际绑定的那一个</h2>
 * 金签与手牌上的键帽印的是 {@link KeyBinding#getBoundKeyLocalizedText()}：改了键位还印 G 就是在说谎。
 *
 * <h2>色只从 {@link GuiLanguage} 取</h2>
 * 金 = 你 · 轮到你 · 铜绿 = 正轮到 · 朱砂 = 紧迫与伤害（ADR-0018 §7.3）。压在世界上的东西永远用深色那一套浅色（{@code onWorld}）。
 */
public final class GameHud {

    private static final int MARGIN = NotificationSidebarLayout.MARGIN;
    /** 普通 HUD 底部留给热栏；对局 Screen 没有热栏，可以用到窗口底。 */
    private static final int HUD_BOTTOM_SAFE = 48;

    /** 状态牌的内边距与各件的尺寸，GUI 单位。按样张 b-1 在 1280×720（界面尺寸 3）量出来再取整。 */
    private static final int PAD = 4;
    private static final int AVATAR = 18;
    private static final int PIP = 5;
    private static final int PIP_GAP = 1;
    /** 体力点那枚图标整张画多大：实心圆只占母版 30/64，按圆的直径 {@link #PIP} 反推。 */
    private static final int PIP_ICON = Math.round(PIP * 64f / 30f);
    private static final int SMALL_ICON = 8;
    private static final int ICON_GAP = 3;
    /** 座位轨一格的头像直径与格宽上限。 */
    private static final int RAIL_AVATAR = 14;
    private static final int RAIL_CELL_MAX = 19;
    /** 右上角舷窗的直径。 */
    private static final int PORTHOLE = 20;

    /** 金签上次换内容的时刻：换了就重新「发」一次（ADR-0018 §7.2：新东西到你面前）。 */
    private static String cueShown = "";
    private static long cueAt = 0L;
    /** 日志页签上的未读数：展开到底那一刻，这一局一共到过几条。 */
    private static int seenArrived = 0;

    private GameHud() {
    }

    public static void render(DrawContext context, RenderTickCounter tickCounter) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null || client.player == null || client.options.hudHidden) {
            return;
        }
        // HudRenderCallback 发生在 Screen.renderWithTooltip 之前。对局界面自己的底色会盖住这里画的东西，
        // 所以它们的侧栏交给 GameScreenSidebar 的 afterRender；这里必须彻底跳过，不能画一份在背后。
        if (client.currentScreen instanceof GameScreen) {
            return;
        }
        HudView view = GameComponents.of(client.world).hudView();
        if (!view.active()) {
            SidebarReveal.forget();          // 这一局结束了：下一局第一条播报要能触发滑出来
            cueShown = "";
            seenArrived = 0;
            return;                          // 没有对局就什么都不画，不留一个空框
        }
        long now = System.currentTimeMillis();
        SidebarReveal.observe(view.notifications(), now);
        int screenW = context.getScaledWindowWidth();

        int plateRight = drawStatusPlate(context, view, now);
        NotificationSidebarLayout overlay = NotificationSidebarLayout.of(screenW, true);
        drawSeatRail(context, view, plateRight + 2 * MARGIN, overlay.sidebarX() - 2 * MARGIN);
        drawTray(context, client, view, now, overlay);
    }

    // ---------------------------------------------------------------- 状态牌（左上）

    /** 画状态牌与它下面的金签，返回状态牌的右沿（座位轨从这里之后排）。 */
    private static int drawStatusPlate(DrawContext context, HudView view, long now) {
        int x = MARGIN;
        int y = MARGIN;
        int ring = GuiMaterial.ringMargin(AVATAR);
        int head = AVATAR + 2 * ring;
        int rowsX = x + PAD + head + PAD;
        int max = view.seated() ? Math.max(1, view.maxHealth()) : 0;
        int pipsW = max * PIP + Math.max(0, max - 1) * PIP_GAP;
        int statusW = view.seated() ? pipsW + ICON_GAP + SMALL_ICON + ICON_GAP + SMALL_ICON : 0;
        boolean weather = !view.weather().isEmpty();
        int gullsW = GameState.GULLS_TO_LAND * SMALL_ICON + (GameState.GULLS_TO_LAND - 1) * 2;
        int publicW = (weather ? SMALL_ICON + ICON_GAP : 0) + SMALL_ICON + ICON_GAP + gullsW;
        int w = rowsX - x + Math.max(statusW, publicW) + PAD;
        int rowH = SMALL_ICON + 3;
        int h = PAD + Math.max(head, 2 * rowH) + PAD + keyRowH() + PAD;
        GuiMaterial.sheet(context, x, y, w, h);

        // 头像：金圈 = 你。旁观的人没有座位 —— 画一枚空圈也是在说谎，所以不画。
        if (view.seated()) {
            GuiMaterial.avatar(context, view.character(), x + PAD + ring, y + PAD + ring, AVATAR, GuiLanguage.gold(),
                    view.condition() == Condition.DEAD ? 0.35f : 1f);
            // 第一行：体力点 · 口渴水滴 · 清醒的眼睛
            int ry = y + PAD;
            int px = rowsX;
            for (int i = 0; i < max; i++) {
                boolean left = i < view.health();
                int s = PIP_ICON;
                GuiMaterial.icon(context, left ? "pip" : "pip_empty", px + (PIP - s) / 2, ry + (SMALL_ICON - s) / 2, s,
                        left ? GuiLanguage.ink() : GuiLanguage.dim());
                px += PIP + PIP_GAP;
            }
            px += ICON_GAP - PIP_GAP;
            // 口渴标记：没有就是一滴淡的；有就是朱砂（它会在航海之后伤人 —— 紧迫）
            GuiMaterial.icon(context, "drop", px, ry, SMALL_ICON,
                    view.thirst() > 0 ? GuiLanguage.cinnabar() : GuiLanguage.dim());
            px += SMALL_ICON + ICON_GAP;
            String eye = switch (view.condition()) {
                case CONSCIOUS -> "eye";
                case UNCONSCIOUS -> "eye_closed";
                case DEAD -> "dead";
            };
            GuiMaterial.icon(context, eye, px, ry, SMALL_ICON,
                    view.condition() == Condition.CONSCIOUS ? GuiLanguage.ink() : GuiLanguage.cinnabar());
        }
        // 第二行（公开）：天候 · 阶段轮盘 · 四只海鸥 —— 与各面上带同一排图，同一个意思不许两套画法
        int ry2 = y + PAD + rowH;
        int px = rowsX;
        if (weather) {
            GuiMaterial.icon(context, "weather_" + view.weather(), px, ry2, SMALL_ICON, GuiLanguage.ink());
            px += SMALL_ICON + ICON_GAP;
        }
        GuiMaterial.icon(context, "phase_" + view.phase().ordinal(), px, ry2, SMALL_ICON, GuiLanguage.verdigris());
        px += SMALL_ICON + ICON_GAP;
        for (int i = 0; i < GameState.GULLS_TO_LAND; i++) {
            GuiMaterial.icon(context, "gull", px + i * (SMALL_ICON + 2), ry2, SMALL_ICON,
                    i < view.gulls() ? GuiLanguage.verdigris() : GuiLanguage.dim());
        }
        // 第三行：第几天（罗马数字，样张 b-1 的「III」）· 手牌几张 + 开手牌的键
        int ky = y + h - PAD - keyRowH();
        GuiText.line(context, Text.literal(roman(view.turn())), x + PAD, ky + GuiMaterial.KEY_PAD_TOP,
                head, GuiText.BODY, true, GuiLanguage.ink(), GuiText.Align.CENTER);
        if (view.seated() && (!view.hand().isEmpty() || !view.love().isEmpty())) {
            String key = keyLabel(HeavySeasClient.handKey());
            int kw = keycapW(key);
            int count = GuiText.width(Integer.toString(view.hand().size()), GuiText.BODY, false);
            int hx = x + w - PAD - kw;
            drawKeycap(context, key, hx, ky);
            hx -= 2 + count;
            GuiText.line(context, Text.literal(Integer.toString(view.hand().size())), hx, ky + GuiMaterial.KEY_PAD_TOP,
                    count, GuiText.BODY, false, GuiLanguage.ink(), GuiText.Align.LEFT);
            GuiMaterial.icon(context, "hand", hx - 2 - SMALL_ICON, ky + 1, SMALL_ICON, GuiLanguage.ink());
        }
        drawCue(context, view, now, x, y + h + 3);
        return x + w;
    }

    /**
     * 轮到你时落下来的那枚签（样张 b-1 的金铃）：铃 · 一句不超过 8 个字的短语 · 键帽。
     * 「轮到你 = 一件金色的物件落下来，不是描边发光」（样张页 · 哈比列车一节）。
     */
    private static void drawCue(DrawContext context, HudView view, long now, int x, int y) {
        Cue cue = cue(view, now);
        if (cue == null) {
            cueShown = "";
            return;
        }
        if (!cue.id().equals(cueShown)) {
            cueShown = cue.id();
            cueAt = now;
        }
        float p = GuiLanguage.deal(now, cueAt, 0);
        int dy = Math.round((1f - p) * GuiLanguage.DEAL_RISE);
        String key = keyLabel(HeavySeasClient.actKey());
        int textW = GuiText.width(cue.text().getString(), GuiText.BODY, false);
        int kw = keycapW(key);
        int h = keyRowH() + 4;
        int w = 4 + SMALL_ICON + 3 + textW + 4 + kw + 3;
        int ty = y + dy;
        context.setShaderColor(1f, 1f, 1f, p);
        GuiMaterial.tag(context, x, ty, w, h);
        context.setShaderColor(1f, 1f, 1f, 1f);
        int ring = cue.color();
        context.drawBorder(x, ty, w, h, ring);
        context.drawBorder(x + 1, ty + 1, w - 2, h - 2, ring);
        GuiMaterial.icon(context, "bell", x + 4, ty + (h - SMALL_ICON) / 2, SMALL_ICON, GuiLanguage.onTag(ring));
        GuiText.line(context, cue.text(), x + 4 + SMALL_ICON + 3, ty + 2 + GuiMaterial.KEY_PAD_TOP, textW,
                GuiText.BODY, false, GuiLanguage.onTag(GuiLanguage.ink()), GuiText.Align.LEFT);
        drawKeycap(context, key, x + w - 3 - kw, ty + 2);
    }

    /** 金签上说什么。一次只有一件事要你做；按「最不能错过」排。 */
    private record Cue(String id, Text text, int color) {
    }

    private static Cue cue(HudView view, long now) {
        if (!view.seated()) {
            return null;
        }
        if (view.endgame().active()) {
            return new Cue("endgame", Text.translatable("heavyseas.hud.cue.endgame"), GuiLanguage.gold());
        }
        if (view.myDesignating()) {
            // 举着拳头找人：有倒计时，到点就退回 —— 紧迫，朱砂（ADR-0025）
            long left = Math.max(0L, view.designateUntil() - now);
            return new Cue("designate", Text.translatable("heavyseas.hud.cue.designate", (left + 999) / 1000),
                    GuiLanguage.cinnabar());
        }
        if (view.myContestChoice()) {
            return new Cue("contest", Text.translatable("heavyseas.hud.cue.contest"), GuiLanguage.gold());
        }
        if (view.myThirstChoice()) {
            return new Cue("thirst", Text.translatable("heavyseas.hud.cue.thirst"), GuiLanguage.gold());
        }
        if (view.myWaterDonation()) {
            return new Cue("donate", Text.translatable("heavyseas.hud.cue.donate",
                    Text.translatable("heavyseas.character." + view.thirstPrompt().who())), GuiLanguage.gold());
        }
        if (view.myRowPending()) {
            return new Cue("row", Text.translatable("heavyseas.hud.cue.row"), GuiLanguage.gold());
        }
        if (view.myTurnToAct()) {
            return new Cue("act", Text.translatable("heavyseas.hud.cue.act"), GuiLanguage.gold());
        }
        return null;
    }

    // ---------------------------------------------------------------- 座位轨（正上方）

    /**
     * 这一局的座位：金圈 = 你，铜绿圈 = 正轮到的那一位，朱砂圈 = 被抢 / 被换的那一位，
     * 舵手身上一枚舵轮（带划船堆张数），移出游戏的淡下去。
     *
     * <p>排在状态牌与右栏之间那一段的正中；那一段放不下八格就缩格子，绝不压到两边。
     */
    private static void drawSeatRail(DrawContext context, HudView view, int from, int to) {
        List<String> seats = view.seats();
        if (seats.isEmpty() || to - from < seats.size() * 8) {
            return;
        }
        int cell = Math.min(RAIL_CELL_MAX, (to - from - 2 * PAD) / seats.size());
        int d = Math.min(RAIL_AVATAR, cell - 2 * GuiMaterial.ringMargin(RAIL_AVATAR) - 1);
        if (d <= 4) {
            return;
        }
        int ring = GuiMaterial.ringMargin(d);
        int w = seats.size() * cell + 2 * PAD;
        int h = d + 2 * ring + 2 * PAD;
        int x = from + (to - from - w) / 2;
        int y = MARGIN;
        GuiMaterial.sheet(context, x, y, w, h);
        String waitingOn = waitingOn(view);
        String target = view.contest().active() ? view.contest().target() : "";
        boolean sea = !view.endgame().active()
                && (view.phase() == Phase.ACTION || view.phase() == Phase.NAVIGATION);
        for (int i = 0; i < seats.size(); i++) {
            String id = seats.get(i);
            int cx = x + PAD + i * cell + (cell - d) / 2;
            int cy = y + PAD + ring;
            int mark = id.equals(view.character()) && view.seated() ? GuiLanguage.gold()
                    : id.equals(target) ? GuiLanguage.cinnabar()
                    : id.equals(waitingOn) ? GuiLanguage.verdigris() : 0;
            GuiMaterial.avatar(context, id, cx, cy, d, mark, view.removed().contains(id) ? 0.3f : 1f);
            if (sea && id.equals(view.sea().helmsman())) {
                int bw = helmBadgeW(view.sea().rowStack());
                int bx = Math.min(cx + d - bw + 3, x + w - PAD - bw);
                drawHelmBadge(context, view.sea().rowStack(), bx, cy + d - 6);
            }
        }
    }

    /** 此刻全船在等谁：口渴轮到的那一位，否则是正轮到行动的那一位。 */
    private static String waitingOn(HudView view) {
        if (view.thirstPrompt().active()) {
            return view.thirstPrompt().who();
        }
        if (view.contest().active()) {
            return view.contest().attacker();
        }
        return view.actor();
    }

    /** 舵手身上那一枚：舵轮 + 划船堆张数（样张 b-1 小孩头像右下的「⎈2」）。 */
    private static int helmBadgeW(int rowStack) {
        return 2 + 6 + 1 + GuiText.width(Integer.toString(rowStack), GuiText.CAPTION, false) + 2;
    }

    private static void drawHelmBadge(DrawContext context, int rowStack, int x, int y) {
        String n = Integer.toString(rowStack);
        int tw = GuiText.width(n, GuiText.CAPTION, false);
        int w = helmBadgeW(rowStack);
        int h = 8;
        GuiMaterial.tag(context, x, y, w, h);
        GuiMaterial.icon(context, "helm", x + 2, y + 1, 6, GuiLanguage.onTag(GuiLanguage.ink()));
        GuiText.line(context, Text.literal(n), x + 2 + 6 + 1, y, tw, GuiText.CAPTION, false,
                GuiLanguage.onTag(GuiLanguage.ink()), GuiText.Align.LEFT);
    }

    // ---------------------------------------------------------------- 右上：舷窗与日志页签

    /**
     * 右栏收着的时候只剩一扇天候舷窗与一枚日志页签（带未读数）；有新播报时自己展开几秒再收回，按日志键钉住
     * （ADR-0037 §7.1 第 4 条 · 样张 b-1 / b-2）。展开的那一份就是对局界面里那本日志，同一个画法。
     */
    private static void drawTray(DrawContext context, MinecraftClient client, HudView view, long now,
                                 NotificationSidebarLayout overlay) {
        float open = SidebarReveal.openness(now);
        if (open >= 1f) {
            seenArrived = SidebarReveal.arrived();
        }
        if (open < 1f) {
            int screenW = context.getScaledWindowWidth();
            int x = screenW - MARGIN - PORTHOLE - GuiMaterial.ringMargin(PORTHOLE);
            int y = MARGIN + GuiMaterial.ringMargin(PORTHOLE);
            if (!view.weather().isEmpty()) {
                // 舷窗：一块圆玻璃套一圈金属，里面是天候图标。玻璃借实心那枚点（圆占母版 30/64）着成纸色 ——
                // 纸色 = 深色那一套的正文色，两个主题下都是它；图标用标签上的深墨。
                int glass = Math.round(PORTHOLE * 64f / 30f);
                GuiMaterial.icon(context, "pip", x + (PORTHOLE - glass) / 2, y + (PORTHOLE - glass) / 2, glass,
                        GuiLanguage.onWorld(GuiLanguage.ink()));
                GuiMaterial.ringOnly(context, x, y, PORTHOLE);
                int icon = PORTHOLE - 6;
                GuiMaterial.icon(context, "weather_" + view.weather(), x + 3, y + 3, icon,
                        GuiLanguage.onTag(GuiLanguage.ink()));
            }
            int unread = Math.max(0, SidebarReveal.arrived() - seenArrived);
            int tabW = 16;
            int tabH = 14;
            int tx = screenW - MARGIN - tabW - 2;
            int ty = y + PORTHOLE + GuiMaterial.ringMargin(PORTHOLE) + 3;
            GuiMaterial.tag(context, tx, ty, tabW, tabH);
            GuiMaterial.icon(context, "book", tx + (tabW - 10) / 2, ty + (tabH - 10) / 2, 10,
                    GuiLanguage.onTag(GuiLanguage.ink()));
            if (unread > 0) {
                String n = Integer.toString(Math.min(unread, 99));
                int bw = Math.max(8, GuiText.width(n, GuiText.CAPTION, false) + 3);
                GuiMaterial.tag(context, tx + tabW - bw / 2, ty - 4, bw, 8);
                GuiText.line(context, Text.literal(n), tx + tabW - bw / 2, ty - 4, bw, GuiText.CAPTION, false,
                        GuiLanguage.onTag(GuiLanguage.ink()), GuiText.Align.CENTER);
            }
            String key = keyLabel(HeavySeasClient.logKey());
            drawKeycap(context, key, tx + (tabW - keycapW(key)) / 2, ty + tabH + 2);
        }
        if (open > 0f) {
            int slide = Math.round((1f - open) * (overlay.sidebarWidth() + NotificationSidebarLayout.MARGIN));
            context.getMatrices().push();
            context.getMatrices().translate(slide, 0, 0);
            drawNotifications(context, client, view.weather(), view.notifications(), overlay);
            context.getMatrices().pop();
        }
    }

    // ---------------------------------------------------------------- 对局界面里的日志（最后一层）

    /**
     * 对局界面最后一层的通知侧栏。只由 {@link GameScreenSidebar} 的 afterRender 调用；
     * 放回普通 HUD 回调会被 Screen 的底色盖住。
     */
    static void renderSidebar(DrawContext context, MinecraftClient client) {
        if (client.world == null || client.player == null || client.options.hudHidden
                || !(client.currentScreen instanceof GameScreen)) {
            return;
        }
        HudView view = GameComponents.of(client.world).hudView();
        if (!view.active()) {
            SidebarReveal.forget();
            return;
        }
        long now = System.currentTimeMillis();
        SidebarReveal.observe(view.notifications(), now);
        float open = SidebarReveal.openness(now);
        if (open <= 0f) {
            return;                          // 收着的时候一个像素都不画 —— 第一刀把这块屏幕让给了牌
        }
        // ❗**盖在舞台上**，不占版面：这里用「看得见的那一份」几何（visible = true），
        //   而 sidebarLayout 给各面的仍是 visible = false —— 舞台一个像素都不动。
        //   否则每来一条播报整屏就要重排一次，那正是 §7.13 要避免的割裂感。
        NotificationSidebarLayout overlay =
                NotificationSidebarLayout.of(context.getScaledWindowWidth(), true);
        int slide = Math.round((1f - open) * (overlay.sidebarWidth() + NotificationSidebarLayout.MARGIN));
        // ❗自己滑出来的那一下**只给「刚刚发生了什么」**：最新的两条，不带天候卡。
        //   整本日志加天候卡有半屏高，实拍到它盖住了右边三张牌与两个座位 ——
        //   而牌是主体（§7.8）。要看全的按 L 钉住：钉住是你自己要的，盖住也是你自己认的。
        List<Text> shown = view.notifications();
        String weather = view.weather();
        if (!SidebarReveal.pinned()) {
            shown = shown.subList(0, Math.min(REVEAL_LINES, shown.size()));
            weather = "";
        }
        context.getMatrices().push();
        context.getMatrices().translate(slide, 0, 0);
        drawNotifications(context, client, weather, shown, overlay);
        context.getMatrices().pop();
    }

    /** 自己滑出来时最多给几条。再多就成了「整本日志盖住牌」，那是钉住才该发生的事。 */
    private static final int REVEAL_LINES = 2;

    /** GameScreen 与实际绘制共用这一份几何；两边各算一遍仍会得到完全相同的边界。 */
    static NotificationSidebarLayout sidebarLayout(int screenWidth, HudView view) {
        boolean visible = view.active() && (!view.notifications().isEmpty() || !view.weather().isEmpty())
                && !(MinecraftClient.getInstance().currentScreen instanceof GameScreen);
        return NotificationSidebarLayout.of(screenWidth, visible);
    }

    /**
     * 系统播报的侧栏：最新事件在最上面，保留最多八条，不污染聊天历史。
     *
     * <p>播报文本自带的样式（服务端给战斗结算标的红）照原样显示 —— 那是服务端的事。
     */
    private static void drawNotifications(DrawContext context, MinecraftClient client,
                                          String weather, List<Text> notifications,
                                          NotificationSidebarLayout layout) {
        int width = layout.sidebarWidth();
        if ((notifications.isEmpty() && weather.isEmpty()) || width <= 0) {
            return;
        }
        int x = layout.sidebarX();
        int y = MARGIN;
        if (!weather.isEmpty()) {
            int cardW = Math.min(width, 168);
            int cardH = cardW * 5 / 7;
            CardTexture.drawWeather(context, weather, x + (width - cardW) / 2, y, cardW, cardH);
            y += cardH + MARGIN;
        }
        if (notifications.isEmpty()) {
            return;
        }
        int inner = width - 2 * MARGIN;
        // 第一条是栏名（次要色），下面才是播报，最新的在最上。
        List<Text> entries = new ArrayList<>();
        entries.add(Text.translatable("heavyseas.hud.notifications"));
        for (int i = notifications.size() - 1; i >= 0; i--) {
            entries.add(notifications.get(i));
        }
        // 天候卡已经占掉上半截；按剩余高度裁，而不是按整屏高度裁。否则低分辨率下
        // 八条长通知会把侧栏画出屏幕底边。先量后画：放不下的那一条整条不要，不画半条。
        int bottomSafe = client.currentScreen == null ? HUD_BOTTOM_SAFE : 2 * MARGIN;
        int room = context.getScaledWindowHeight() - y - bottomSafe - 2 * MARGIN;
        int used = 0;
        int shown = 0;
        for (Text entry : entries) {
            int h = GuiText.height(entry.getString(), inner, GuiText.BODY, false, MAX_LINES_PER_NOTE);
            if (used + h > room) {
                break;
            }
            used += h;
            shown++;
        }
        if (shown == 0) {
            return;
        }
        GuiMaterial.tag(context, x, y, width, used + 2 * MARGIN);
        int textY = y + MARGIN;
        for (int i = 0; i < shown; i++) {
            Text entry = entries.get(i);
            textY += GuiText.draw(context, entry.getString(), x + MARGIN, textY, inner, GuiText.BODY, false,
                    GuiLanguage.onTag(i == 0 ? GuiLanguage.muted() : noteColor(entry)), GuiText.Align.LEFT, MAX_LINES_PER_NOTE);
        }
    }

    /** 一条播报最多折几行；再长就截断 —— 侧栏是提要，全文该去的地方不是这里。 */
    private static final int MAX_LINES_PER_NOTE = 4;

    /** 播报自带的颜色（服务端给战斗结算标的红）照原样用；没标色的是纸色。 */
    private static int noteColor(Text entry) {
        net.minecraft.text.TextColor c = entry.getStyle().getColor();
        if (c == null && !entry.getSiblings().isEmpty()) {
            c = entry.getSiblings().get(0).getStyle().getColor();
        }
        return c == null ? GuiLanguage.ink() : GuiLanguage.semantic(c.getRgb());
    }

    // ---------------------------------------------------------------- 小件

    private static String keyLabel(KeyBinding key) {
        return key.getBoundKeyLocalizedText().getString();
    }

    private static int keyRowH() {
        return GuiText.lineHeight(GuiText.BODY, false) + GuiMaterial.KEY_PAD_TOP + GuiMaterial.KEY_PAD_BOTTOM;
    }

    private static int keycapW(String key) {
        return GuiText.width(key, GuiText.BODY, false) + 2 * GuiMaterial.KEY_PAD_X;
    }

    private static void drawKeycap(DrawContext context, String key, int x, int y) {
        int w = keycapW(key);
        GuiMaterial.keycap(context, x, y, w, keyRowH());
        GuiText.line(context, Text.literal(key), x + 1, y + GuiMaterial.KEY_PAD_TOP, w - 2, GuiText.BODY, false,
                GuiLanguage.onTag(GuiLanguage.ink()), GuiText.Align.CENTER);
    }

    /** 第几天：罗马数字（样张 b-1）。一局到不了几十天，写到 39 就够。 */
    static String roman(int n) {
        if (n <= 0) {
            return "—";
        }
        int[] v = {10, 9, 5, 4, 1};
        String[] s = {"X", "IX", "V", "IV", "I"};
        StringBuilder out = new StringBuilder();
        int left = Math.min(n, 39);
        for (int i = 0; i < v.length; i++) {
            while (left >= v[i]) {
                out.append(s[i]);
                left -= v[i];
            }
        }
        return out.toString();
    }
}
