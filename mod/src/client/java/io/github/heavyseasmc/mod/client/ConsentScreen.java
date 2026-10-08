package io.github.heavyseasmc.mod.client;

import io.github.heavyseasmc.engine.play.Contest;
import io.github.heavyseasmc.mod.HeavySeasMod;
import io.github.heavyseasmc.mod.card.ActionCard;
import io.github.heavyseasmc.mod.net.ContestActionC2S;
import io.github.heavyseasmc.mod.state.HudView;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 表态：有人冲着你来了 —— 同意，还是打一架（ADR-0023 · 规则 §9.1）。
 *
 * <h2>两张牌（ADR-0050）</h2>
 * 与行动一面同一形态（{@link ChoiceCards}）：「同意」一面白旗，「战斗」两把交叉的弯刀，牌名朱砂。
 *
 * <h2>这一面是整场战斗唯一的入口</h2>
 * 规则里战斗的<b>触发条件唯一</b>：换座位或抢夺的目标清醒且不同意。所以选「战斗」的那一下，
 * 是全局唯一一处「打起来」的来源 —— 不能因为讨厌某人就主动开战。
 *
 * <h2>默认答案是「同意」，所以焦点一进来就在它上面</h2>
 * 超时按同意算（ADR-0023 §7.2）。默认「战斗」会给挂机的人<b>凭空制造伤害</b> ——
 * 那不是他的默认答案，那是替他做了一个没人会做的选择（与口渴那一面同一条理由）。
 */
public final class ConsentScreen extends GameScreen {

    private static final Logger LOGGER = LoggerFactory.getLogger(HeavySeasMod.MOD_ID);
    private static final List<ActionCard> CARDS = List.of(ActionCard.AGREE, ActionCard.FIGHT);

    private HudView view;
    /**
     * 开这一面时的那个窗口。
     *
     * <p>❗收界面认<b>投影</b>，不认「还剩几秒」：客户端自己数秒的话，改过的客户端可以永远不超时，
     * 而真正的判定在服务端（{@code ContestPhase#tick}）。
     */
    private final long deadlineMs;
    private final Contest.Kind kind;
    private final String attacker;
    private final ChoiceCards cards;

    public ConsentScreen(HudView view) {
        super(Text.translatable("heavyseas.consent.title"));
        this.view = view;
        this.deadlineMs = view.contest().deadlineMs();
        this.kind = view.contest().kind();
        this.attacker = view.contest().attacker();
        // 焦点一进来就在「同意」—— 超时算的就是它。
        this.cards = new ChoiceCards(this, CARDS, false, 0, i -> true, c -> Text.translatable(c.effectKey()));
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;                         // 逃了也只是超时按「同意」算 —— 那是替你做的，不是你做的
    }

    @Override
    public void tick() {
        view = projection();
        if (cards.decided()) {
            if (!cards.snapping()) {
                close();                      // 「顿」播完再收：服务端认得快，别把那一下截掉
            }
            return;
        }
        if (!view.myConsent() || view.contest().deadlineMs() != deadlineMs) {
            close();
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackdrop(context, mouseX, mouseY, delta);
        long now = System.currentTimeMillis();
        long dt = frameDelta(now);

        Bands b = drawChrome(context, view);
        int askY = b.stageTop();
        drawLine(context, Text.translatable(switch (kind) {
                    case SWAP -> "heavyseas.consent.swap";
                    case STEAL -> "heavyseas.consent.steal";
                    case RATION -> "heavyseas.consent.ration";
                }, nameOf(attacker)),
                width / 2, askY, GuiLanguage.ink());
        int s = guiScale();
        var l = sheet();
        double top0 = Math.max(l.railBottom() + CardRow.RAIL_CLEAR * l.k(), (askY + lineStep() + HINT_GAP) * (double) s);
        cards.render(context, b, mouseX, mouseY, now, dt, top0, 0);

        drawFootBand(context, b, List.of(keys("select", "←", "→")), inspectHints("confirm"), now,
                new Countdown(deadlineMs, view.contest().windowMs(), 0));
    }

    /**
     * 表态。
     *
     * <p>❗定了就不再改：再点再按都不作数（{@link ChoiceCards} 定了之后一概不认），否则会往服务端发第二个表态 ——
     * 而第二个会撞上「现在不是等表态的时候」，在日志上与一个真正的 bug 长得一样。
     */
    private boolean confirm(int i) {
        boolean fight = CARDS.get(i) == ActionCard.FIGHT;
        ClientPlayNetworking.send(ContestActionC2S.of(fight
                ? ContestActionC2S.Kind.CONSENT_FIGHT : ContestActionC2S.Kind.CONSENT_AGREE));
        // 与语言无关的一行：验收靠它与服务端那行「表态（界面）」对上 —— 客户端按了、服务端认了，两个来源。
        LOGGER.info("表态：确认「{}」", fight ? "FIGHT" : "AGREE");
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
    protected boolean onKey(int keyCode, int scanCode, int modifiers) {
        if (cards.keyPressed(keyCode, this::confirm)) {
            return true;
        }
        return super.onKey(keyCode, scanCode, modifiers);
    }

    /** 上带左头那一句（ADR-0043 D3 (b)）：只在还没定的时候说。 */
    @Override
    protected Text cue() {
        return cards.decided() ? null : Text.translatable("heavyseas.cue.consent");
    }
}
