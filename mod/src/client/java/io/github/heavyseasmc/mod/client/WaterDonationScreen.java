package io.github.heavyseasmc.mod.client;

import io.github.heavyseasmc.engine.play.Session;
import io.github.heavyseasmc.mod.HeavySeasMod;
import io.github.heavyseasmc.mod.card.ActionCard;
import io.github.heavyseasmc.mod.game.ThirstPhase;
import io.github.heavyseasmc.mod.net.WaterDonationC2S;
import io.github.heavyseasmc.mod.state.HudView;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 旁人的口渴窗口：每按一次，替当前角色打 1 张水；挑「什么也不做」就是不给。Esc 只收起（金签上按 G 回来）。
 *
 * <h2>「不给」是一张牌，不是 Esc</h2>
 * 用户 2026-10-07：「给水不能跳过 esc 是关闭页面」「而且界面不能随时打开」—— 原先 Esc 就是「不给」，按完这一窗就收了，
 * 想再看一眼也回不来。现在与行动面、站队面同一个做法：那一排最后一张「什么也不做」，← → 挑、Enter / 点它；
 * Esc（与背包键）只收起。已有的键复用，不另起一个（同一天定）。
 *
 * <h2>一张水，没有按钮</h2>
 * 舞台上是一张水牌：按下去时它「抬」起来（正在送出去），服务端认下之后落回来、再镶上金框（还能再给一张）。
 * 能按的那一件是下面那一栏的搪瓷按钮（Enter），鼠标点牌也是同一件事 —— 与口渴一面同一个做法。
 *
 * <p>❗2026-09-25 第一次实拍（四面补拍）：舞台里原先还有一颗「给 1 张水」按钮，而下面那一栏又写着「Enter 给水」——
 * 同一件事画了两遍；按钮与两行说明从上往下各占一截之后，牌只剩 44 个单位高（1280×720 下约 130 像素），
 * 舞台一大半空着。与 ADR-0037 §7.8「牌是主体」、§7.12「一屏只该有一件东西看上去能按」都不合。
 * 现在两行状态贴舞台底边（与口渴一面相同），牌拿剩下的全部高度。
 */
public final class WaterDonationScreen extends GameScreen {

    private static final Logger LOGGER = LoggerFactory.getLogger(HeavySeasMod.MOD_ID);

    private HudView view;
    private long deadlineMs;
    private int seenMine;
    private boolean awaiting;
    private float cardLift;
    private float passLift;
    /** 0 = 水（给一张）· 1 = 「什么也不做」（不给）。 */
    private int focus;
    /** 这一帧两张牌画在哪（点牌 = 定这一张）。 */
    private Box card = new Box(0, 0, 0, 0);
    private Box pass = new Box(0, 0, 0, 0);

    public WaterDonationScreen(HudView view) {
        super(Text.translatable("heavyseas.donate.title", Text.empty()));
        this.view = view;
        this.deadlineMs = view.thirstPrompt().deadlineMs();
        this.seenMine = view.myDonatedWater();
    }

    @Override
    public void tick() {
        view = projection();
        if (!view.active() || view.thirstPrompt().deadlineMs() != deadlineMs || !view.myWaterDonation()) {
            close();
            return;
        }
        if (view.myDonatedWater() > seenMine) {
            seenMine = view.myDonatedWater();
            awaiting = false;                 // 服务端认下上一张，若还有水便可再给
        } else if (awaiting && System.currentTimeMillis() - sentAt > AWAIT_MS) {
            // 服务端拒了（不回话，ThirstPhase 只返回 false —— 比如这一下与窗口收尾擦肩而过）：牌落回原处、能再按，
            // 不再一直抬着、按什么都没反应（ADR-0095 A8）
            awaiting = false;
            LOGGER.info("口渴：替人打水那一下没被认下，牌落回");
        }
    }

    /** 等服务端认下这一张最多等多久；投影一个往返通常不到 0.2 秒。 */
    private static final long AWAIT_MS = 1_500L;
    private long sentAt;

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackdrop(context, mouseX, mouseY, delta);
        long now = System.currentTimeMillis();
        long dt = frameDelta(now);
        // 「划船堆 N 张 · 舵手 X」这一行在这一面去掉了：替人打水与它无关（§7.10 第 3 条：字太多）。
        Bands b = drawChrome(context, view);

        HudView.Thirst prompt = view.thirstPrompt();
        int still = Math.max(0, prompt.remaining() * prompt.waterPerSource() - prompt.donated());
        int available = Math.max(0, view.myWaters() - view.myDonatedWater());
        // 两行状态贴舞台底边（替谁打 · 还差几张），与口渴一面同一个位置：换面时下半屏不跳。
        int titleY = footerTop(b, 2);
        drawLine(context, Text.translatable("heavyseas.donate.title", nameOf(prompt.who())),
                width / 2, titleY, GuiLanguage.ink());
        drawLine(context, Text.translatable("heavyseas.donate.detail", still, prompt.donated(), available),
                width / 2, titleY + lineStep(), GuiLanguage.muted());

        // 舞台里剩下的全部高度给两张牌：水 · 什么也不做（§7.8）。牌上面留出「抬」的高度。
        int room = liftRoom();
        int bottom = titleY - HINT_GAP;
        int cardH = cardHeightFor(2, bottom - b.stageTop() - room);
        int cardW = GuiLanguage.cardWidth(cardH);
        int cardY = cardsTopIn(b.stageTop(), bottom, cardH, room);
        int rowW = cardRowWidth(2, cardW);
        int cardX = (width - rowW) / 2;
        int passX = cardX + cardW + CARD_GAP;
        card = new Box(cardX, cardY, cardW, cardH);
        pass = new Box(passX, cardY, cardW, cardH);
        if (mouseActuallyMoved(mouseX, mouseY)) {
            int hovered = indexAt(List.of(card, pass), mouseX, mouseY);
            if (hovered >= 0) {
                focus = hovered;
            }
        }
        float lift = GuiMetrics.units(GuiLanguage.LIFT_PX);
        // 水那一张：送出去的那一下抬着（等服务端认下）；挑中时也抬起来
        cardLift = GuiLanguage.approach(cardLift, awaiting || focus == 0 ? lift : 0f, dt);
        passLift = GuiLanguage.approach(passLift, focus == 1 ? lift : 0f, dt);
        int waterY = Math.round(cardY - cardLift);
        int passY = Math.round(cardY - passLift);
        CardTexture.drawProvision(context, Session.WATER, cardX, waterY, cardW, cardH);
        CardTexture.drawAction(context, ActionCard.PASS, passX, passY, cardW, cardH);
        if (!awaiting) {
            int fx = focus == 0 ? cardX : passX;
            int fy = focus == 0 ? waterY : passY;
            context.drawBorder(fx - CARD_FRAME, fy - CARD_FRAME, cardW + 2 * CARD_FRAME, cardH + 2 * CARD_FRAME,
                    GuiLanguage.gold());
        }
        // Esc 不写在这里：底栏自己补「Esc 收起」（GameScreen.drawFootBand）
        drawFootBand(context, b, List.of(keys("select", "←", "→")),
                List.of(primary(focus == 0 ? "give" : "confirm", "Enter")), now,
                new Countdown(deadlineMs, ThirstPhase.CHOOSE_MILLIS, rowW));
    }

    @Override
    protected boolean leftClick(double mouseX, double mouseY) {
        int i = indexAt(List.of(card, pass), (int) mouseX, (int) mouseY);
        if (i >= 0) {
            focus = i;
            confirm();
            return true;
        }
        return false;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // Esc（与背包键）只收起，不替你做决定（用户 2026-10-07：「esc 是关闭页面」）—— 不给是「什么也不做」那一张
        switch (keyCode) {
            case GLFW.GLFW_KEY_LEFT -> {
                focus = 0;
                return true;
            }
            case GLFW.GLFW_KEY_RIGHT -> {
                focus = 1;
                return true;
            }
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER, GLFW.GLFW_KEY_SPACE -> {
                confirm();
                return true;
            }
            default -> {
                return super.keyPressed(keyCode, scanCode, modifiers);
            }
        }
    }

    /** 定挑中的那一张：水 = 再给一张（等服务端认下之前不重复发）· 什么也不做 = 不给，收起。 */
    private void confirm() {
        if (focus == 1) {
            // 「不给」真的告诉服务端（ADR-0095 D1）：没人等你了，代打窗口就提前结算。
            // 刚给出去的那一张还没认下也照发：服务端按先后处理，先认下那一张、再记你不给了
            ClientPlayNetworking.send(new WaterDonationC2S(0));
            LOGGER.info("口渴：不替 {} 打水", view.thirstPrompt().who());
            close();
            return;
        }
        if (!awaiting) {
            donate();
        }
    }

    private void donate() {
        awaiting = true;
        sentAt = System.currentTimeMillis();
        ClientPlayNetworking.send(new WaterDonationC2S(1));
        LOGGER.info("口渴：替 {} 打 1 张水", view.thirstPrompt().who());
    }

    /** 上带左头那一句（ADR-0043 D3 (b)）：只在还没定的时候说。 */
    @Override
    protected Text cue() {
        return Text.translatable("heavyseas.cue.water");
    }
}
