package io.github.heavyseasmc.mod.client;

import io.github.heavyseasmc.mod.HeavySeasMod;
import io.github.heavyseasmc.mod.card.ActionCard;
import io.github.heavyseasmc.mod.net.ActionChoiceC2S;
import io.github.heavyseasmc.mod.state.GameComponents;
import io.github.heavyseasmc.mod.state.HudView;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 行动：轮到你时，从五张牌里选一件事（决策 ⑦ · ADR-0018 §7.4）。
 *
 * <h2>五件事画成牌（ADR-0050，用户 2026-10-01：「这些也应该使用卡牌表示」）</h2>
 * 原先是五枚搪瓷按钮。用户看样图定 B：五张牌放大、不挂说明签（说明按 U 看），再加 C 的两处 ——
 * 「什么也不做」小一号退到一边（它是超时的默认），按不动的那张压暗（无风那天的划船）。
 * 排版 · 抽出来 · 「顿」· 命中都在 {@link ChoiceCards}，与表态 · 站队两面同一份。
 *
 * <h2>版面就是那一套带位（ADR-0037 §7.10）</h2>
 * 上带 · 座位轨 · 倒计时 · 身份行四条带由 {@code GameScreen} 排定 —— 与别的每一面逐像素相同。
 *
 * <h2>长倒计时</h2>
 * 行动选择留一分钟谈判；超时按「什么也不做」。Esc 仍可收起，服务端倒计时继续走，按行动键可再开。
 *
 * <h2>用物资打开手牌</h2>
 * 这一张不是当场猜一张牌：它打开手牌一面，让玩家看到卡面再打出。医疗箱随后还会进入目标一面。
 */
public final class ActionScreen extends GameScreen {

    private static final Logger LOGGER = LoggerFactory.getLogger(HeavySeasMod.MOD_ID);

    /** 次序照交互稿：划船在前（稿子的第一步），「什么也不做」在最后、小一号。 */
    private static final List<ActionCard> CARDS = List.of(ActionCard.ROW, ActionCard.SWAP, ActionCard.STEAL,
            ActionCard.USE, ActionCard.PASS);

    private HudView view = HudView.IDLE;
    private final ChoiceCards cards;

    public ActionScreen() {
        super(Text.translatable("heavyseas.action.title"));
        refresh();
        // 焦点一进来就在「划船」；无风那天它按不动，ChoiceCards 自己挪到第一张按得动的。
        cards = new ChoiceCards(this, CARDS, true, 0, i -> enabled(CARDS.get(i)), this::effect);
    }

    private void refresh() {
        MinecraftClient mc = MinecraftClient.getInstance();
        view = mc.world == null ? HudView.IDLE : GameComponents.of(mc.world).hudView();
    }

    /**
     * 这一件现在按不按得动。
     *
     * <p>❗2026-09-23 之前按钮那一版写的是 {@code kind != null || this == USE} —— 五件事<b>永远都可按</b>，
     * 无风那一天界面照样给出「划船」，按下去才被服务端拒。<b>界面给出一件必然失败的事，比不给更糟</b>。
     */
    private boolean enabled(ActionCard c) {
        if (c == ActionCard.USE) {
            return true;                      // 「用物资」打开手牌：没轮到你时也能进去看（二级页面，Esc 回来）
        }
        // 没轮到你时这一面只能看（用户 2026-10-07：「行动页面要能随时打开」）—— 四件事一律按不动
        return view.myTurnToAct() && (c != ActionCard.ROW || view.canRow());
    }

    /** 说明板上那一句：按不动的划船说为什么按不动，其余照牌上的说明。 */
    private Text effect(ActionCard c) {
        return c == ActionCard.ROW && !view.canRow()
                ? Text.translatable("heavyseas.action.row_becalmed_hint") : Text.translatable(c.effectKey());
    }

    private static ActionChoiceC2S.Kind kindOf(ActionCard c) {
        return switch (c) {
            case ROW -> ActionChoiceC2S.Kind.ROW;
            case SWAP -> ActionChoiceC2S.Kind.SWAP;
            case STEAL -> ActionChoiceC2S.Kind.STEAL;
            case PASS -> ActionChoiceC2S.Kind.PASS;
            default -> null;                  // 用物资：先打开手牌，没有自己的行动包
        };
    }

    /**
     * 收界面只在这里做。
     *
     * <p>❗不在 render 里换屏：这一帧余下的部分还在用一个已经 {@code removed} 的 Screen。
     */
    @Override
    public void tick() {
        refresh();
        if (cards.decided()) {
            if (!cards.snapping()) {
                close();                      // 「顿」播完：面板收起，交给世界
            }
            return;
        }
        if (!view.active() || !view.seated()) {
            close();                          // 对局结束 / 不在局里了。轮次走了不收：没轮到你时这一面照样能看
        }
    }

    /** 从手牌（二级页面）回来时：还在局里就回来，轮没轮到都一样（没轮到时是只能看的那一版）。 */
    @Override
    protected boolean stillWanted() {
        refresh();
        return view.active() && view.seated();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        refresh();
        if (!view.active() || !view.seated()) {
            return;                           // 这一帧什么都不画，tick 会把它收起来
        }
        renderBackdrop(context, mouseX, mouseY, delta);
        long now = System.currentTimeMillis();
        long dt = frameDelta(now);
        Bands b = drawChrome(context, view);
        var l = sheet();
        cards.render(context, b, mouseX, mouseY, now, dt, l.railBottom() + CardRow.RAIL_CLEAR * l.k(), 0);
        // R 在这一面打开手牌（用户 2026-10-07：「行动页面里也要能打开手牌页面」）；倒计时只在轮到你时有
        List<KeyHint> right = new java.util.ArrayList<>(inspectHints("confirm"));
        right.add(0, keys("hand", HeavySeasClient.handKey().getBoundKeyLocalizedText().getString()));
        drawFootBand(context, b, List.of(keys("select", "←", "→")), right, now,
                view.myTurnToAct() ? new Countdown(view.actionDeadlineMs(), view.actionWindowMs(), 0) : null);
    }

    /** 确认第 i 张。返回 {@code true} = 定了，播一次「顿」。 */
    private boolean confirm(int i) {
        ActionCard c = CARDS.get(i);
        if (c == ActionCard.USE) {
            if (parent() instanceof HandScreen) {
                close();                      // 从手牌进来的：回手牌就是「去挑物资」，不再叠一层
            } else {
                openChild(new HandScreen());  // 二级页面：Esc 回到这一面（用户 2026-10-07）
            }
            LOGGER.info("行动：打开手牌挑特殊物资");
            return false;
        }
        if (!view.myTurnToAct()) {
            ActionBarEcho.record(Text.translatable("heavyseas.hand.not_your_turn"));   // 只能看的那一版：说一句，不发包
            return false;
        }
        ClientPlayNetworking.send(ActionChoiceC2S.of(kindOf(c)));
        // 验收靠这一行与服务端那行「行动（界面）」对上：客户端按了、服务端认了，两个来源。
        LOGGER.info("行动：确认「{}」，播一次「顿」", c.name());
        return true;
    }

    @Override
    protected boolean leftClick(double mouseX, double mouseY) {
        return cards.leftClick(mouseX, mouseY, this::confirm);
    }

    @Override
    protected boolean rightClick(double mouseX, double mouseY) {
        return cards.rightClick(mouseX, mouseY);
    }

    @Override
    protected int inspectedIndex() {
        return cards.focus();
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // 用哪个键开的，就用哪个键收起来（理由同手牌一面：写死的键，玩家改了键位就收不起来）。
        if (HeavySeasClient.actKey().matchesKey(keyCode, scanCode)) {
            close();
            return true;
        }
        if (HeavySeasClient.handKey().matchesKey(keyCode, scanCode)) {
            // 行动一面里按 R 看手牌（二级页面，Esc / R 回来）；从手牌进来的就回手牌，不再叠一层 ——
            // 与手牌一面的 G 对称，来回按 R · G 不会越叠越深、最后要按好几次 Esc
            if (parent() instanceof HandScreen) {
                close();
            } else {
                openChild(new HandScreen());
            }
            return true;
        }
        if (cards.keyPressed(keyCode, this::confirm)) {
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    /** 上带左头那一句（ADR-0043 D3 (b)）：只在还没定的时候说。 */
    @Override
    protected Text cue() {
        return Text.translatable(view.myTurnToAct() ? "heavyseas.hud.cue.act" : "heavyseas.hud.cue.not_your_turn");
    }
}
