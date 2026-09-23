package io.github.heavyseasmc.mod.client;

import io.github.heavyseasmc.mod.HeavySeasMod;
import io.github.heavyseasmc.mod.net.ContestActionC2S;
import io.github.heavyseasmc.mod.state.ContestView;
import io.github.heavyseasmc.mod.state.HudView;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 挂武器：参战的人押武器，<b>暗着</b>（ADR-0023 · 决策 ④）。
 *
 * <h2>全场唯一的暗牌</h2>
 * 押下只是<b>登记</b>，牌不挪、不亮 —— 结算那一刻才亮出来并加上战力。所以这一段里：
 * 别人只看得见「他押了一张」，看不见是什么；连播报与服务端日志都不写是哪一张
 * （开服的人往往也是玩家）。屏幕上那一排是<b>你自己</b>还押得出的牌。
 *
 * <h2>可以押好几张</h2>
 * 武器可叠加（规则 §9），同一个 id 最多押「手上 + 面前」那么多张 —— 两支船桨能押两次。
 * 每押一下服务端就把窗口重置成更短的一段（10 → 6 秒），这一排也跟着少一张。
 *
 * <h2>亮出时先用面前已有的</h2>
 * 那是引擎的事（{@code Session#resolveContest}），这一面不必管：面前本来就摆着一支船桨的人押一支，
 * 手里那支不该被翻出来。这里只告诉服务端「押哪个 id」。
 *
 * <h2>不押也是一种答案</h2>
 * 所以这一面 Esc 收得掉：窗口到点就结算，不押的人什么也不会失去。
 */
public final class WeaponScreen extends GameScreen {

    private static final Logger LOGGER = LoggerFactory.getLogger(HeavySeasMod.MOD_ID);

    private HudView view;
    /** 高亮。这一面的高亮只是个光标 —— 超时<b>不</b>按它押（不押就是不押）。 */
    private int highlight;
    private float[] lift = new float[0];

    public WeaponScreen(HudView view) {
        super(Text.translatable("heavyseas.weapon.title"));
        this.view = view;
        this.lift = new float[Math.max(1, view.contest().myWeapons().size())];
    }

    @Override
    public void tick() {
        view = projection();
        if (!view.myWeaponChoice()) {
            close();                          // 这一段过去了、押光了、这一场收场了 —— 都收
        }
    }

    /** 一帧的版面，全部以 GUI 单位计。每帧重算：窗口与界面尺寸随时会变。 */
    private record Layout(int w, int h, int left, int cardsTop, int rowW, int headerY, int committedY) {

        int cardX(int i) {
            return left + i * (w + CARD_GAP);
        }
    }

    private Layout layout(Bands b, int count) {
        int n = Math.max(1, count);
        // 牌下面贴着舞台底边的三行字：这一段是什么 · 已押几张 · 键位。
        int headerY = footerTop(b, 2);
        int step = lineStep();
        int room = liftRoom();
        int avail = headerY - HINT_GAP - b.stageTop();
        int h = cardHeightFor(n, avail - room);
        int w = GuiLanguage.cardWidth(h);
        int cardsTop = cardsTopIn(b.stageTop(), headerY - HINT_GAP, h, room);
        int rowW = cardRowWidth(n, w);
        return new Layout(w, h, (width - rowW) / 2, cardsTop, rowW, headerY, headerY + step);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackdrop(context, mouseX, mouseY, delta);
        long now = System.currentTimeMillis();
        long dt = frameDelta(now);
        ContestView c = view.contest();
        List<String> weapons = c.myWeapons();
        if (weapons.isEmpty()) {
            return;                           // 这一帧什么都不画，tick 会把它收起来
        }
        if (lift.length != weapons.size()) {
            lift = new float[weapons.size()];
            highlight = Math.min(highlight, weapons.size() - 1);
        }
        Bands b = drawChrome(context, view);
        Layout l = layout(b, weapons.size());
        Inspect in = inspect(b);
        float gathered = gathered(now);

        // 查看态里不认悬停：牌叠在一起了，命中框却还在摊开那一排的位置上。
        boolean moved = mouseActuallyMoved(mouseX, mouseY);
        if (moved && !inspecting()) {
            int hovered = indexAt(mouseX, mouseY, l, weapons.size());
            if (hovered >= 0) {
                highlight = hovered;
            }
        }
        // 高亮那一张最后画：收成一叠时它在堆顶，摊开时它抬起来。
        for (int i = 0; i < weapons.size(); i++) {
            if (i != highlight) {
                drawOne(context, dt, l, in, gathered, weapons, i);
            }
        }
        if (highlight >= 0 && highlight < weapons.size()) {
            drawOne(context, dt, l, in, gathered, weapons, highlight);
        }


        drawLine(context, Text.translatable("heavyseas.weapon.header"),
                width / 2, l.headerY(), GuiLanguage.ink());
        drawLine(context, c.myCommitted() == 0
                        ? Text.translatable("heavyseas.weapon.none")
                        : Text.translatable("heavyseas.weapon.committed", c.myCommitted()),
                width / 2, l.committedY(), c.myCommitted() == 0 ? GuiLanguage.muted() : GuiLanguage.verdigris());
        if (gathered > 0f && highlight >= 0 && highlight < weapons.size()) {
            String card = weapons.get(highlight);
            drawCardPlate(context, in.plateX(), in.plateY(), in.plateW(), -1,
                    null, provisionName(card), provisionEffect(card));
        }
        drawFootBand(context, b, List.of(keys("select", "←", "→")),
                inspectHints("commit", keys("skip", "Esc")), now,
                new Countdown(c.deadlineMs(), c.windowMs(), l.rowW()));
    }

    /** 画一张：位置与大小在「摊成一排」与「收成一叠」之间按 {@code gathered} 插值。 */
    private void drawOne(DrawContext context, long dt, Layout l, Inspect in, float gathered,
                         List<String> weapons, int i) {
        boolean hi = i == highlight;
        lift[i] = GuiLanguage.approach(lift[i], hi ? GuiLanguage.LIFT_PX : 0f, dt);
        CardPose pose = cardPose(in, gathered, l.cardX(i) + l.w() / 2f, l.cardsTop() + l.h(), l.w(), l.h(),
                hi ? 0 : 1 + Math.abs(i - Math.max(0, highlight)));
        context.getMatrices().push();
        context.getMatrices().translate(pose.cx() - pose.w() / 2f,
                pose.bottom() - pose.h() - lift[i] * (1f - gathered), 0);
        CardTexture.drawProvision(context, weapons.get(i), 0, 0, pose.w(), pose.h());
        if (hi) {
            drawCardFrame(context, pose.w(), pose.h());
        }
        context.getMatrices().pop();
    }

    private int indexAt(int mouseX, int mouseY, Layout l, int count) {
        return cardIndexAt(mouseX, mouseY, l.left(), l.cardsTop(), l.w(), l.h(), count);
    }

    /**
     * 押下高亮的那一张。
     *
     * <p>❗<b>不在客户端记「押过了」</b>：可以押好几张，而每一张的合法性由服务端与引擎判
     * （手上与面前一共几张、已经押了几张）。这一面只管把「押哪个 id」发出去。
     */
    private void commit() {
        List<String> weapons = view.contest().myWeapons();
        if (highlight < 0 || highlight >= weapons.size()) {
            return;
        }
        ClientPlayNetworking.send(ContestActionC2S.of(ContestActionC2S.Kind.COMMIT_WEAPON, weapons.get(highlight)));
        // 与语言无关的一行，而且**不写是哪一张** —— 暗牌（决策 ④）。验收只数次数。
        LOGGER.info("挂武器：押下一张（暗牌）");
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (inspectClick(button)) {
            return true;
        }
        List<String> weapons = view.contest().myWeapons();
        if (!weapons.isEmpty() && !inspecting()) {
            int i = indexAt((int) mouseX, (int) mouseY, layout(bands(), weapons.size()), weapons.size());
            if (i >= 0) {
                highlight = i;
                commit();
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (inspectKey(keyCode)) {
            return true;
        }
        int count = view.contest().myWeapons().size();
        switch (keyCode) {
            case GLFW.GLFW_KEY_LEFT -> {
                highlight = Math.max(0, highlight - 1);
                return true;
            }
            case GLFW.GLFW_KEY_RIGHT -> {
                highlight = Math.min(Math.max(0, count - 1), highlight + 1);
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
