package io.github.heavyseasmc.mod.client;

import io.github.heavyseasmc.mod.state.GameComponents;
import io.github.heavyseasmc.mod.state.HudView;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

/** Temporary reading and conversation keep the original decision, never its old deadline. */
final class DecisionNavigation {
    record Context(String match, String decision, GameScreen owner) {
        boolean valid(HudView view) {
            return view.active() && !decision.isEmpty() && match.equals(view.experience().matchId())
                    && decision.equals(view.experience().decisionId());
        }
    }

    interface Child {
        Context decisionContext();
    }

    private DecisionNavigation() { }

    static HudView view() {
        var client = MinecraftClient.getInstance();
        return client.world == null ? HudView.IDLE : GameComponents.of(client.world).hudView();
    }

    /** 以 {@code owner} 为原决定记一笔：它是决策面、它代表的决定此刻还欠着我（各面自己报，{@link GameScreen#ownsDecision}）。 */
    static Context capture(GameScreen owner) {
        HudView view = view();
        if (!owner.decisionFace() || !owner.ownsDecision(view) || view.experience().decisionId().isEmpty()) {
            return null;
        }
        return new Context(view.experience().matchId(), view.experience().decisionId(), owner);
    }

    static String identity(HudView view) {
        return view.experience().matchId() + "|" + view.experience().decisionId();
    }

    /**
     * 这一面上给不给「返回决定」：它是从一个决定走进来的二级页（{@code here} 不是那个决定自己），而那个决定<b>此刻还欠着</b>。
     * 过期的决定不给入口 —— 原先只看「是不是二级页」，行动超时之后导航条照样写「G 返回决定」，按下去打开的是「没轮到你」（2026-10-13 验收）。
     */
    static boolean offersReturn(Context origin, Object here, HudView view) {
        return origin != null && origin.owner() != here && origin.valid(view);
    }

    /**
     * 导航小签上给不给「最近一局」：只在<b>没有对局</b>时给（2026-10-13 第二轮验收 B08 · L06：进行中的对局里多出一枚
     * 「最近一局」，按下去把正在打的这一局盖掉、去看上一局的账）。计分面板自己有上一局的入口，不重复给。
     */
    static boolean offersLastResult(HudView view, boolean onScoreScreen) {
        return !view.active() && !onScoreScreen && view.experience().lastResult().present();
    }

    /**
     * 这一面上的这一下按键 / 点击，要不要先「收起 → 去开此刻欠着的决定」：它就是那个决定自己的一面、那个决定已经过去，
     * 而它不是过期后照样留着的那种面（{@code keepsOpen}，行动一面）。纯函数，单测核对。
     */
    static boolean redirects(Context origin, Object here, boolean keepsOpen, HudView view) {
        return origin != null && origin.owner() == here && !keepsOpen && !origin.valid(view);
    }

    static boolean browsing(Screen screen, HudView view) {
        Context context = screen instanceof GameScreen game ? game.decisionContext()
                : screen instanceof Child child ? child.decisionContext() : null;
        return context != null && context.owner() != screen && context.valid(view);
    }

    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger(io.github.heavyseasmc.mod.HeavySeasMod.MOD_ID);

    static void back(GameScreen parent, Context context) {
        MinecraftClient client = MinecraftClient.getInstance();
        HudView view = view();
        // 回到哪一面打一行（与 GameScreen#close 的「界面：返回 X」同一个写法）：回去的那一面不再打「打开」，
        // 回归脚本只看「打开 / 收起」时，「回到了行动一面」与「什么都没开」读起来一样（2026-10-13 复拍）
        if (parent != null && parent.canReturnTo(view)) {
            LOGGER.info("界面：返回 {}", parent.getClass().getSimpleName());
            client.setScreen(parent);
        } else if (context != null && context.valid(view)) {
            LOGGER.info("界面：返回 {}", context.owner().getClass().getSimpleName());
            client.setScreen(context.owner());
        } else {
            client.setScreen(null);
            if (context != null) HeavySeasClient.requestDecision(client);
        }
    }

    static GameScreen.Countdown countdown(HudView view) {
        var client = MinecraftClient.getInstance();
        if (client.world == null || !view.active()) {
            return null;
        }
        if (view.endgame().active()) {
            // 终局独占排程，没有哪个决定还在等人；补给箱开着时靠岸，客户端留着那只箱子的倒计时，
            // 计分面板底下就多出一条走完了的横杠（2026-10-13 实拍 round2）
            return null;
        }
        var table = GameComponents.of(client.world).tableView();
        if (table.overboardOpen() && !table.plays().isEmpty()) {
            return new GameScreen.Countdown(table.deadline(), table.window(), 0);
        }
        if (view.contest().waiting() && (view.myConsent() || view.myStance() || view.myWeaponChoice() || view.myPick())) {
            return new GameScreen.Countdown(view.contest().deadlineMs(), view.contest().windowMs(), 0);
        }
        if (view.myThirstChoice() || view.myWaterDonation()) {
            return new GameScreen.Countdown(view.thirstPrompt().deadlineMs(), view.thirstPrompt().windowMs(), 0);
        }
        if (view.myHelmPick()) {
            return new GameScreen.Countdown(view.sea().helmDeadlineMs(), view.sea().helmWindowMs(), 0);
        }
        if (view.actionDeadlineMs() > 0) {
            return new GameScreen.Countdown(view.actionDeadlineMs(), view.actionWindowMs(), 0);
        }
        return HeavySeasClient.provisionCountdown();
    }

    /** 聊天：顶上那一条空着，输入框在底下，所以签子挂在顶上。 */
    /**
     * 聊天开着时那张签：<b>一行</b>，贴着输入框上沿、靠右 —— 让开左边的聊天记录、顶上的状态牌与座位轨、钉住时右边那一栏日志。
     * 原先挂在顶上居中：按正文字号量宽、按阅读字号画，加大字号时折成两行，第二行（秒数）落进聊天记录的底色里，
     * 签子本身压住座位轨第 2–7 座（2026-10-13 验收 34）。放不下先换小一号的字，再不行由 {@link GuiText#line} 缩字号再截。
     */
    static void drawPending(DrawContext context, Context origin) {
        HudView view = view();
        var timer = countdown(view);
        if (origin == null && timer == null) return;
        Text text = pendingText(origin, view, timer);
        MinecraftClient client = MinecraftClient.getInstance();
        int screenW = context.getScaledWindowWidth();
        int screenH = context.getScaledWindowHeight();
        // 聊天记录在 GUI 单位里画多宽：ChatHud 按「宽 ÷ 缩放」排、再整体乘缩放，落到屏幕上就是 getWidth()
        int chatRight = client.inGameHud == null ? 0 : client.inGameHud.getChatHud().getWidth() + CHAT_HISTORY_PAD;
        int sidebarLeft = screenW;
        if (SidebarReveal.pinned()) {
            var window = client.getWindow();
            var hud = io.github.heavyseasmc.mod.ui.HudLayout.of(window.getFramebufferWidth(), window.getFramebufferHeight());
            int s = Math.max(1, (int) Math.round(window.getScaleFactor()));
            sidebarLeft = hud.drawerCard().x() / s;
        }
        int size = GuiText.BODY;
        int textW = GuiText.width(text.getString(), size, false) + 2;
        int room = chatSlotRoom(screenW, chatRight, sidebarLeft);
        if (textW > room) {
            size = GuiText.CAPTION;
            textW = GuiText.width(text.getString(), size, false) + 2;
        }
        int lineH = GuiText.lineHeight(size, false);
        int[] slot = chatSlot(screenW, screenH, chatRight, sidebarLeft, textW, lineH);
        GuiMaterial.plate(context, slot[0] - PENDING_PAD, slot[1] - PENDING_PAD, slot[2] + 2 * PENDING_PAD,
                lineH + 2 * PENDING_PAD, -1);
        GuiText.line(context, text, slot[0], slot[1], slot[2], size, false, GuiLanguage.onTag(GuiLanguage.ink()),
                GuiText.Align.LEFT);
    }

    private static Text pendingText(Context origin, HudView view, GameScreen.Countdown timer) {
        return origin != null && !origin.valid(view) ? Text.translatable("heavyseas.navigation.changed")
                : timer == null ? Text.translatable("heavyseas.navigation.return")
                : Text.translatable("heavyseas.navigation.running", time(timer));
    }

    /** Minecraft 的聊天输入框占窗口底下多高（{@code ChatScreen}：离底 14 起、2 收），聊天记录比它的宽多出的边。 */
    static final int CHAT_INPUT_H = 16;
    static final int CHAT_HISTORY_PAD = 8;

    /** 聊天记录右沿与右边那一栏（或窗口右沿）之间能给签子多宽（不含内边距）。 */
    static int chatSlotRoom(int screenW, int chatRight, int sidebarLeft) {
        int right = Math.min(screenW, sidebarLeft) - PENDING_MARGIN - PENDING_PAD;
        int left = Math.max(0, chatRight) + PENDING_MARGIN + PENDING_PAD;
        return Math.max(1, right - left);
    }

    /**
     * 聊天开着时签子里那一行字放在哪（GUI 单位，不含内边距）：{@code {x, y, w}}。
     * 一行高，底边贴着输入框上沿；横向靠右、不越过聊天记录右沿与右边那一栏。纯函数，单测核对它不上顶、不进聊天记录。
     */
    static int[] chatSlot(int screenW, int screenH, int chatRight, int sidebarLeft, int textW, int lineH) {
        int room = chatSlotRoom(screenW, chatRight, sidebarLeft);
        int w = Math.max(1, Math.min(textW, room));
        int right = Math.min(screenW, sidebarLeft) - PENDING_MARGIN - PENDING_PAD;
        int x = right - w;
        int y = screenH - CHAT_INPUT_H - PENDING_MARGIN - PENDING_PAD - lineH;
        return new int[]{x, y, w};
    }

    /** 规则书：Minecraft 自带的书占中间那一竖条，签子放在书底下，放不下就挪到书左边的空白里。 */
    static void drawBookPending(DrawContext context, Context origin) {
        HudView view = view();
        var timer = countdown(view);
        if (origin == null && timer == null) return;
        Text text = pendingText(origin, view, timer);
        int screenW = context.getScaledWindowWidth();
        int screenH = context.getScaledWindowHeight();
        int pad = PENDING_PAD;
        int line = GuiText.readingLineHeight(GuiText.BODY, false);
        // 量与画同一档阅读字号（GuiText.readingWidth）：按正文字号量宽，加大字号时一行就折成两行
        int oneLine = GuiText.readingWidth(text.getString(), GuiText.BODY, false) + 2;
        int width = Math.max(1, Math.min(oneLine, screenW - 2 * (PENDING_MARGIN + pad)));
        int x = (screenW - width) / 2;
        int maxLines = PENDING_MAX_LINES;
        int height = Math.min(maxLines * line, GuiText.readingHeight(text.getString(), width, GuiText.BODY, false));
        int y;
        int size = GuiText.BODY;
        {
            // 书与它底下那一排按钮占到哪儿：问书自己的版面（BookScreen 的 y = 2 起 192 高，按钮在 196 起 20 高）。
            // 签子只放书页<b>下方居中</b>：左上角是 HUD 的状态牌，原先放不下就挪到左上、把它盖住（2026-10-13 验收）。
            // 放不下先换小一号的字、收紧内边距；再放不下就贴着窗口底边、横着让开书那一竖条，挂在书右边的空白里。
            int bookBottom = BOOK_TOP + BOOK_H + BOOK_BUTTON_GAP + BOOK_BUTTON_H;
            y = screenH - height - PENDING_MARGIN - pad;
            if (y - pad < bookBottom) {
                size = GuiText.CAPTION;
                pad = PENDING_PAD_SMALL;
                line = GuiText.readingLineHeight(size, false);
                width = Math.max(1, Math.min(GuiText.readingWidth(text.getString(), size, false) + 2,
                        screenW - 2 * (PENDING_MARGIN + pad)));
                x = (screenW - width) / 2;
                maxLines = 1;
                height = line;
                y = Math.max(bookBottom + pad, screenH - height - PENDING_MARGIN - pad);
                if (y + height + pad > screenH) {
                    // 书底下连一行小字都放不下（窗口极矮）：挂在书右边那片空白的底部，不上去碰 HUD
                    int bookRight = (screenW + BOOK_BUTTON_ROW_W) / 2;
                    x = bookRight + PENDING_MARGIN + pad;
                    width = Math.max(1, screenW - x - PENDING_MARGIN - pad);
                    maxLines = Math.max(1, Math.min(PENDING_MAX_LINES, (screenH - 2 * (PENDING_MARGIN + pad)) / line));
                    height = Math.min(maxLines * line, GuiText.readingHeight(text.getString(), width, size, false));
                    y = screenH - height - PENDING_MARGIN - pad;
                }
            }
        }
        GuiMaterial.plate(context, x - pad, y - pad, width + 2 * pad, height + 2 * pad, -1);
        GuiText.drawReading(context, text.getString(), x, y, width, size, false,
                GuiLanguage.onTag(GuiLanguage.ink()), 0, maxLines);
    }

    /** 书底下放不下时那一档签子的内边距（GUI 单位）。 */
    private static final int PENDING_PAD_SMALL = 2;

    /** 签子离窗口边、签子的内边距（GUI 单位），最多几行。 */
    private static final int PENDING_MARGIN = 2;
    private static final int PENDING_PAD = 4;
    private static final int PENDING_MAX_LINES = 3;
    /**
     * Minecraft 自带 {@code BookScreen} 的版面（GUI 单位，1.21.1）：书从 y = 2 起、高 192；底下那排按钮在 196 起、高 20，
     * 两枚 98 宽并排共 200 宽。规则书那一面的按钮就是照这一排摆的（{@code RulebookBook.NavigationBook#addCloseButton}）。
     */
    private static final int BOOK_TOP = 2;
    private static final int BOOK_H = 192;
    private static final int BOOK_BUTTON_GAP = 2;
    private static final int BOOK_BUTTON_H = 20;
    private static final int BOOK_BUTTON_ROW_W = 200;

    static Text time(GameScreen.Countdown timer) {
        long left = GameClock.remaining(timer.deadlineMs());
        return timer.totalMs() >= GameScreen.UNLIMITED_THRESHOLD_MS
                ? Text.translatable("heavyseas.hud.unlimited")
                : Text.literal(Long.toString(io.github.heavyseasmc.mod.ui.CountdownDigits.seconds(left)));
    }
}
