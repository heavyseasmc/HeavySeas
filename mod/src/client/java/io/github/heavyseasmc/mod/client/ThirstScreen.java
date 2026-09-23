package io.github.heavyseasmc.mod.client;

import io.github.heavyseasmc.engine.play.Session;
import io.github.heavyseasmc.mod.HeavySeasMod;
import io.github.heavyseasmc.mod.game.ThirstPhase;
import io.github.heavyseasmc.mod.net.ThirstActionC2S;
import io.github.heavyseasmc.mod.state.HudView;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 口渴：喝几张水（ADR-0021）。
 *
 * <h2>这一面问的是一个数，不是一张牌</h2>
 * 手上三张水完全等价，没有哪一张值得挑。所以中带摆的是<b>你所有的水</b>，
 * 左边 N 张抬起来、镶金边 —— 那 N 张就是要喝掉的；右边几张留在原位，是留着的。
 * 左右键调这个数，看得见「喝几张、挨几点」同时在变。
 *
 * <h2>默认答案是「刚好够」</h2>
 * 另外三面的高亮默认落在第一张，因为没有更好的猜测；这一面有 ——
 * <b>口渴的伤害是必然的，而水的唯一用途就是挡它</b>。所以一进来就预选「刚好够」，
 * 想省水的人自己往下调。默认 0 的话，挂机的人会一边攥着水一边掉血，那不是他的默认答案。
 *
 * <h2>本人没得选时不弹这一面</h2>
 * 一张水都没有、或者昏迷着（自己打不出水）时，本人不看喝水面；若船上有人能帮，旁人的捐水面仍会打开。
 * 全船确实没人能出水时，服务端才直接结算伤害。
 */
public final class ThirstScreen extends GameScreen {

    private static final Logger LOGGER = LoggerFactory.getLogger(HeavySeasMod.MOD_ID);

    private HudView view;
    /** 打开时手上有几张水。结算那一刻投影里牌就没了，而这一面还要画完最后一帧。 */
    private final int waters;
    /** 还需要化解几次。 */
    private final int remaining;
    private final int waterPerSource;
    private final long deadlineMs;
    private final long dealAt;
    private final float[] lift;
    /** 打算喝几张。一进来就是「刚好够」。 */
    private int chosen;
    private boolean committed;

    public ThirstScreen(HudView view) {
        super(Text.translatable("heavyseas.thirst.title"));
        this.view = view;
        this.waters = view.myWaters();
        this.remaining = view.thirstPrompt().remaining();
        this.waterPerSource = view.thirstPrompt().waterPerSource();
        this.deadlineMs = view.thirstPrompt().deadlineMs();
        this.lift = new float[Math.max(1, waters)];
        this.chosen = normalized(Math.min(Math.max(0,
                remaining * waterPerSource - view.thirstPrompt().donated()), waters), true);
        this.dealAt = System.currentTimeMillis();
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;                         // 逃了也只是超时按当前的数喝
    }

    @Override
    public void tick() {
        view = projection();
        if (!view.active() || !view.myThirstChoice()
                || view.thirstPrompt().deadlineMs() != deadlineMs) {
            close();
            return;
        }
        // 别人刚替我打了一张，自己的预选就从右边收一张；服务端同一刻也做了同样的夹取。
        chosen = Math.min(chosen, Math.min(waters,
                Math.max(0, remaining * waterPerSource - view.thirstPrompt().donated())));
        chosen = normalized(chosen, false);
    }

    /** 一帧的版面，全部以 GUI 单位计。带位归 {@link Bands}，这里只排舞台那一格里的东西。 */
    private record Layout(int w, int h, int left, int cardsTop, int rowW,
                          int titleY, int detailY, int hintY) {

        int cardX(int i) {
            return left + i * (w + CARD_GAP);
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackdrop(context, mouseX, mouseY, delta);
        long now = System.currentTimeMillis();
        long dt = frameDelta(now);
        Bands b = drawChrome(context, view);
        Layout l = layout(b);

        for (int i = 0; i < waters; i++) {
            float in = GuiLanguage.deal(now, dealAt, i);
            if (in <= 0f) {
                continue;
            }
            boolean drinking = i < chosen;
            lift[i] = GuiLanguage.approach(lift[i], drinking ? GuiLanguage.LIFT_PX : 0f, dt);
            float rise = (1f - in) * GuiLanguage.DEAL_RISE;
            float scale = GuiLanguage.dealScale(in);
            context.getMatrices().push();
            context.getMatrices().translate(l.cardX(i) + l.w() / 2f, l.cardsTop() + l.h() - lift[i] + rise, 0);
            context.getMatrices().scale(scale, scale, 1f);
            context.getMatrices().translate(-l.w() / 2f, -l.h(), 0);
            CardTexture.drawProvision(context, Session.WATER, 0, 0, l.w(), l.h());
            if (drinking) {
                drawCardFrame(context, l.w(), l.h());
            }
            context.getMatrices().pop();
        }


        HudView.Thirst prompt = view.thirstPrompt();
        int hurt = Math.max(0, remaining - (prompt.donated() + chosen) / waterPerSource);
        drawLine(context, Text.translatable("heavyseas.thirst.title", remaining), width / 2, l.titleY(),
                GuiLanguage.ink());
        drawLine(context, Text.translatable("heavyseas.thirst.detail", prompt.sources(), prompt.covered(),
                        prompt.shared(), prompt.donated()),
                width / 2, l.detailY(), GuiLanguage.muted());
        drawLine(context, chosen == 0
                        ? Text.translatable("heavyseas.thirst.none")
                        : Text.translatable("heavyseas.thirst.drink", chosen, hurt),
                width / 2, l.hintY(), hurt > 0 ? GuiLanguage.cinnabar() : GuiLanguage.verdigris());
        drawFootBand(context, b, List.of(keys("amount", "←", "→")), List.of(confirm("Enter")), now,
                new Countdown(deadlineMs, ThirstPhase.CHOOSE_MILLIS, l.rowW()));
    }

    private Layout layout(Bands b) {
        int n = Math.max(1, waters);
        // 贴舞台底边的三行全是**此刻的状态**：还需化解几次 · 来源明细 · 喝几张挨几点。
        // 键位挪到了倒计时那一条带的两头；「划船堆 N 张 · 舵手 X」在这一面去掉了（与口渴无关）。
        int titleY = footerTop(b, 3);
        int step = lineStep();
        int room = liftRoom();
        int avail = titleY - HINT_GAP - b.stageTop();
        int h = cardHeightFor(n, avail - room);
        int w = GuiLanguage.cardWidth(h);
        int cardsTop = cardsTopIn(b.stageTop(), titleY - HINT_GAP, h, room);
        int rowW = cardRowWidth(n, w);
        return new Layout(w, h, (width - rowW) / 2, cardsTop, rowW,
                titleY, titleY + step, titleY + 2 * step);
    }

    /** 改张数并上报：超时认的是它，服务端不知道的话只能按开窗时那个默认值算。 */
    private void setChosen(int next) {
        int need = Math.max(0, remaining * waterPerSource - view.thirstPrompt().donated());
        int clamped = MathHelper.clamp(next, 0, Math.min(need, waters));
        clamped = normalized(clamped, clamped > chosen);
        if (clamped == chosen) {
            return;
        }
        chosen = clamped;
        ClientPlayNetworking.send(new ThirstActionC2S(chosen, false));
    }

    private int normalized(int amount, boolean upward) {
        int donated = view.thirstPrompt().donated();
        int limit = Math.min(waters, Math.max(0, remaining * waterPerSource - donated));
        int value = MathHelper.clamp(amount, 0, limit);
        while ((donated + value) % waterPerSource != 0) {
            if (upward && value < limit) {
                value++;
            } else if (value > 0) {
                value--;
            } else {
                break;
            }
        }
        return value;
    }

    private void commit() {
        if (committed) {
            return;
        }
        committed = true;
        ClientPlayNetworking.send(new ThirstActionC2S(chosen, true));
        // 验收靠这一行与服务端那行「口渴（界面）」对上。
        LOGGER.info("口渴：确认喝 {} 张（还需化解 {} 次）", chosen, remaining);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        Layout l = layout(bands());
        int i = cardIndexAt((int) mouseX, (int) mouseY, l.left(), l.cardsTop(), l.w(), l.h(), waters);
        if (i >= 0) {
            // 点第 i 张 = 「喝到这一张为止」。再点同一张就是取消它。
            setChosen(chosen == i + 1 ? i : i + 1);
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (committed) {
            return super.keyPressed(keyCode, scanCode, modifiers);
        }
        switch (keyCode) {
            case GLFW.GLFW_KEY_LEFT -> {
                setChosen(chosen - 1);
                return true;
            }
            case GLFW.GLFW_KEY_RIGHT -> {
                setChosen(chosen + 1);
                return true;
            }
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER, GLFW.GLFW_KEY_SPACE -> {
                commit();
                return true;
            }
            default -> {
                return super.keyPressed(keyCode, scanCode, modifiers);
            }
        }
    }
}
