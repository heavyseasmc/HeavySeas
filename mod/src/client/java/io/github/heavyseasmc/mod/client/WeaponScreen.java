package io.github.heavyseasmc.mod.client;

import io.github.heavyseasmc.mod.HeavySeasMod;
import io.github.heavyseasmc.mod.card.ActionCard;
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
 * 那一排最后一张是「什么也不做」（行动面那一张）：挑到它、Enter / 点它，就告诉服务端不押了（ADR-0095 D1）。
 * Esc（与背包键）只收起、不替你做决定（用户 2026-10-07：「esc 是关闭页面」），金签上按 G 回来；
 * 窗口到点就结算，不押的人什么也不会失去。
 */
public final class WeaponScreen extends GameScreen {

    private static final Logger LOGGER = LoggerFactory.getLogger(HeavySeasMod.MOD_ID);

    private HudView view;
    /** 高亮。这一面的高亮只是个光标 —— 超时<b>不</b>按它押（不押就是不押）。 */
    private int highlight;
    private float[] lift = new float[0];
    private int awaitingCount = -1;
    private long sentAt;

    public WeaponScreen(HudView view) {
        super(Text.translatable("heavyseas.weapon.title"));
        this.view = view;
        this.lift = new float[view.contest().myWeapons().size() + 1];
    }

    /**
     * 押下一张之后，等服务端的投影把「已押几张」加上去，才认下一张（同一张连点两下不会押两次）。
     * 等这么久还没回就放开 —— 不是规则里的数：投影每 tick 推一次，回环上一般百毫秒内就到，1.5 秒留给慢网与卡顿；
     * 等不到的那一下由服务端照常判（押不出就拒，拒绝那句由 {@link ActionBarEcho} 显出来）。
     */
    private static final long COMMIT_ECHO_TIMEOUT_MS = 1500L;

    @Override
    public void tick() {
        view = projection();
        if (awaitingCount >= 0 && (view.contest().myCommitted() != awaitingCount
                || System.currentTimeMillis() - sentAt > COMMIT_ECHO_TIMEOUT_MS)) {
            awaitingCount = -1;
        }
        // 这一段过去了、这一场收场了、我不在战里、或者我已经说过「不押了」—— 都收
        if (!view.contest().waiting() || view.contest().stage() != io.github.heavyseasmc.engine.play.Contest.Stage.WEAPONS
                || !view.contest().isCombatant(view.character()) || HeavySeasClient.weaponsDone(view)) {
            close();
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
        int headerY = footerTop(b, 3);
        int step = lineStep();
        int room = liftRoom();
        int top = b.stageTop() + 2 * lineStep();
        int avail = headerY - HINT_GAP - top;
        int h = cardHeightFor(n, avail - room);
        int w = GuiLanguage.cardWidth(h);
        int cardsTop = cardsTopIn(top, headerY - HINT_GAP, h, room);
        int rowW = cardRowWidth(n, w);
        return new Layout(w, h, (paneW() - rowW) / 2, cardsTop, rowW, headerY, headerY + step);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackdrop(context, mouseX, mouseY, delta);
        long now = System.currentTimeMillis();
        long dt = frameDelta(now);
        ContestView c = view.contest();
        List<String> weapons = c.myWeapons();
        int count = weapons.size() + 1;       // 最后一张是「什么也不做」
        if (lift.length != count) {
            lift = new float[count];
            highlight = Math.min(highlight, count - 1);
        }
        Bands b = drawChrome(context, view);
        drawLine(context, Text.translatable("heavyseas.weapon.public_power", c.attackPower(), c.defendPower()),
                paneW() / 2, b.stageTop(), GuiLanguage.ink());
        drawLine(context, Text.translatable("heavyseas.weapon.hidden_power"), paneW() / 2,
                b.stageTop() + lineStep(), GuiLanguage.secondary());
        Layout l = layout(b, count);
        Inspect in = inspect(b, b.stageTop() + 2 * lineStep());
        float gathered = gathered(now);

        // 查看态里不认悬停：牌叠在一起了，命中框却还在摊开那一排的位置上。
        boolean moved = mouseActuallyMoved(mouseX, mouseY);
        if (moved && !inspecting()) {
            int hovered = indexAt(mouseX, mouseY, l, count);
            if (hovered >= 0) {
                highlight = hovered;
            }
        }
        // 高亮那一张最后画：收成一叠时它在堆顶，摊开时它抬起来。
        for (int i = 0; i < count; i++) {
            if (i != highlight) {
                drawOne(context, dt, l, in, gathered, weapons, i);
            }
        }
        if (highlight >= 0 && highlight < count) {
            drawOne(context, dt, l, in, gathered, weapons, highlight);
        }


        // 押了几张、加值多少、押了哪几张：三样取自<b>同一份</b>投影里的同一张清单（只有你看得到 —— 暗牌，决策 ④）。
        // 原先张数取自对局那一包、明细另取一次最新投影，界面重开那一帧两边对不上：「你已押 1 张」下面写着「还没押」（2026-10-13 验收 A08）
        Text[] pledged = pledgeLines(view.experience().committedWeapons(), view.experience().committedPower(), GameScreen::provisionName);
        drawLine(context, pledged[0], paneW() / 2, l.headerY(), GuiLanguage.ink());
        if (pledged[1] != null) {
            drawParagraph(context, pledged[1], l.committedY(), 2, GuiLanguage.verdigris());
        }
        if (gathered > 0f && highlight >= 0 && highlight < weapons.size()) {
            String card = weapons.get(highlight);
            drawCardPlate(context, in.plateX(), in.plateY(), in.plateW(), -1,
                    provisionCaption(card), provisionName(card), provisionEffect(card));
        } else if (gathered > 0f && highlight == weapons.size()) {
            drawCardPlate(context, in.plateX(), in.plateY(), in.plateW(), -1,
                    Text.translatable(ActionCard.PASS.group().captionKey()), Text.translatable(ActionCard.PASS.titleKey()),
                    Text.translatable(ActionCard.PASS.effectKey()));
        }
        // Esc 不写在这里：底栏自己补「Esc 收起」（GameScreen.drawFootBand）
        drawFootBand(context, b, List.of(keys("select", "←", "→")),
                inspectHints(highlight == weapons.size() ? "confirm" : "commit"), now,
                new Countdown(c.deadlineMs(), c.windowMs(), l.rowW()));
    }

    /** 画一张：位置与大小在「摊成一排」与「收成一叠」之间按 {@code gathered} 插值。 */
    private void drawOne(DrawContext context, long dt, Layout l, Inspect in, float gathered,
                         List<String> weapons, int i) {
        boolean hi = i == highlight;
        lift[i] = GuiLanguage.approach(lift[i], hi ? GuiMetrics.units(GuiLanguage.LIFT_PX) : 0f, dt);
        CardPose pose = cardPose(in, gathered, l.cardX(i) + l.w() / 2f, l.cardsTop() + l.h(), l.w(), l.h(),
                hi ? 0 : 1 + Math.abs(i - Math.max(0, highlight)));
        context.getMatrices().push();
        context.getMatrices().translate(pose.cx() - pose.w() / 2f,
                pose.bottom() - pose.h() - lift[i] * (1f - gathered), 0);
        if (i < weapons.size()) {
            CardTexture.drawProvision(context, weapons.get(i), 0, 0, pose.w(), pose.h());
        } else {
            CardTexture.drawAction(context, ActionCard.PASS, 0, 0, pose.w(), pose.h());
        }
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
        if (sentAt > 0 && System.currentTimeMillis() - sentAt < ConfirmGate.GRACE_MS) return;
        List<String> weapons = view.contest().myWeapons();
        if (highlight == weapons.size()) {
            // 「什么也不做」= 不押了（ADR-0095 D1）：该押的人都说完了，这一段就提前结算
            ClientPlayNetworking.send(ContestActionC2S.of(ContestActionC2S.Kind.WEAPONS_DONE));
            LOGGER.info("挂武器：不押了");
            HeavySeasClient.answeredContest(view);   // 服务端随即把这一段收短，那不是一扇新窗（U14）
            close();
            return;
        }
        if (highlight < 0 || highlight >= weapons.size()) {
            return;
        }
        if (awaitingCount >= 0) return;
        String card = weapons.get(highlight);
        if (!CardPlay.allowed(view, card, io.github.heavyseasmc.mod.state.ExperienceView.Action.WEAPON)) {
            ActionBarEcho.record(CardPlay.reason(view, card, io.github.heavyseasmc.mod.state.ExperienceView.Action.WEAPON));
            return;
        }
        awaitingCount = view.contest().myCommitted();
        sentAt = System.currentTimeMillis();
        ClientPlayNetworking.send(ContestActionC2S.of(ContestActionC2S.Kind.COMMIT_WEAPON, weapons.get(highlight)));
        // 与语言无关的一行，而且**不写是哪一张** —— 暗牌（决策 ④）。验收只数次数。
        LOGGER.info("挂武器：押下一张（暗牌）");
    }

    @Override
    protected boolean leftClick(double mouseX, double mouseY) {
        List<String> weapons = view.contest().myWeapons();
        if (!inspecting()) {
            int count = weapons.size() + 1;
            int i = indexAt((int) mouseX, (int) mouseY, layout(bands(), count), count);
            if (i >= 0) {
                highlight = i;
                commit();
                return true;
            }
        }
        return false;
    }

    @Override
    protected boolean rightClick(double mouseX, double mouseY) {
        List<String> weapons = view.contest().myWeapons();
        int i = weapons.isEmpty() || inspecting()
                ? -1 : indexAt((int) mouseX, (int) mouseY, layout(bands(), weapons.size() + 1), weapons.size() + 1);
        return inspectClick(i, k -> highlight = k);
    }
    @Override
    protected int inspectedIndex() {
        return highlight;
    }


    @Override
    protected boolean onKey(int keyCode, int scanCode, int modifiers) {
        if (inspectKey(keyCode)) {
            return true;
        }
        int count = view.contest().myWeapons().size() + 1;   // 最后一张是「什么也不做」
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
                return super.onKey(keyCode, scanCode, modifiers);
            }
        }
    }

    /** 上带左头那一句（ADR-0043 D3 (b)）：只在还没定的时候说。 */
    @Override
    protected Text cue() {
        return Text.translatable("heavyseas.cue.weapon");
    }

    /**
     * 押注那两行：还没押时只一行「还没押」；押了就是「你已押 N 张 · 武器加值 +P」与「鱼叉 ×1 · …」。
     * N 就是清单的长度 —— 张数与明细出自同一张清单，结构上不可能一行说押了、一行说没押。
     *
     * @return 两个元素；第二个为 {@code null} 时不画
     */
    static Text[] pledgeLines(java.util.List<String> committed, int power,
                              java.util.function.Function<String, Text> name) {
        if (committed.isEmpty()) {
            return new Text[]{Text.translatable("heavyseas.weapon.none"), null};
        }
        var counts = new java.util.LinkedHashMap<String, Integer>();
        committed.forEach(card -> counts.merge(card, 1, Integer::sum));
        var detail = Text.empty();
        counts.forEach((card, count) -> {
            if (!detail.getSiblings().isEmpty()) detail.append(" · ");
            detail.append(name.apply(card)).append(" ×" + count);
        });
        return new Text[]{Text.translatable("heavyseas.weapon.private_total", committed.size(), power), detail};
    }

    @Override
    protected boolean decisionFace() {
        return true;
    }

    @Override
    protected boolean ownsDecision(io.github.heavyseasmc.mod.state.HudView v) {
        return v.myWeaponChoice();
    }

    @Override
    protected String rulesAnchor() {
        return "weapons";
    }

    /** 规则签再带上「谁赢」：这几面做的决定就看两边力气怎么比（ADR-0043 §11 第六组）。 */
    @Override
    protected List<String> rulesAnchors() {
        return List.of(rulesAnchor(), "section-39");
    }

    @Override
    protected Text timeoutText() {
        return Text.translatable("heavyseas.timeout.weapon");
    }
}
