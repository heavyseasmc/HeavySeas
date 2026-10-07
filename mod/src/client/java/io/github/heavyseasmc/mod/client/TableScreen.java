package io.github.heavyseasmc.mod.client;

import io.github.heavyseasmc.engine.state.Phase;
import io.github.heavyseasmc.mod.net.UseProvisionC2S;
import io.github.heavyseasmc.mod.state.GameComponents;
import io.github.heavyseasmc.mod.state.TableView;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * 某一座面前的牌（公开的），在座位轨上右键那一座打开。
 *
 * <p>牌那一排与补给箱、手牌同一套（ADR-0049）：放得下就并排，放不下才叠；选中那张「抽出来」，其余压暗一点。
 * 2026-10-01 之前这一面按张数把牌缩小到并排放得下，选中只是一道一像素的金边。
 */
public final class TableScreen extends GameScreen {
    private final String character;
    private TableView.Seat seat;
    private int selected;
    private Box giveBox;
    private Box playBox;
    private int rowLeft;
    private int rowTop;
    private int rowStep;
    private int rowW;
    private int rowH;
    /** 每张抽出来的量（GUI 单位），向目标插值；下标与面前的牌对齐。 */
    private float[] lift = new float[0];
    private float pullLift;
    private final CardRow.Slide slide = new CardRow.Slide();

    public TableScreen(String character) {
        super(Text.translatable("heavyseas.table.title"));
        this.character = character;
    }

    @Override
    protected void init() {
        super.init();
        tick();
    }

    @Override
    public void tick() {
        if (client == null || client.world == null || !projection().active()) {
            close();
            return;
        }
        seat = GameComponents.of(client.world).tableView().seats().stream()
                .filter(s -> s.id().equals(character)).findFirst().orElse(null);
        if (seat == null) {
            close();
        } else {
            selected = Math.max(0, Math.min(selected, seat.front().size() - 1));
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackdrop(context, mouseX, mouseY, delta);
        var view = projection();
        Bands b = drawChrome(context, view);
        giveBox = null;
        playBox = null;
        if (seat == null) {
            return;
        }
        Text state = seat.offline() ? Text.translatable("heavyseas.table.offline") : conditionName(seat.condition());
        drawLine(context, Text.translatable("heavyseas.target.entry_down", nameOf(character),
                seat.health(), seat.size(), state), width / 2, b.stageTop(), GuiLanguage.ink());
        int n = seat.front().size();
        int bottom = b.stageBottom() - buttonHeight() - BTN_GAP - BORDER_ROOM;
        // 牌的大小只由竖向空间定（不再按张数缩小）；横着放不下就叠（ADR-0049）。
        // 顶上留的是「抽出来」那一截：提 + 往左转时右上角升起的一截 + 金框。
        double k = sheet().k();
        int s = guiScale();
        pullLift = (float) (CardRow.PULL_LIFT * k / s);
        float frame = (float) (CardRow.FRAME_OUT * k / s);
        int avail = bottom - b.stageTop() - lineStep();
        int h1 = cardHeightFor(1, avail - Math.round(pullLift + frame));
        int room = (int) Math.ceil(CardRow.pullRoom(pullLift, GuiLanguage.cardWidth(h1), h1, frame));
        int h = cardHeightFor(1, avail - room);
        int w = GuiLanguage.cardWidth(h);
        int top = cardsTopIn(b.stageTop() + lineStep(), bottom, h, room);
        if (n == 0) {
            // 这一面看的是「面前亮出的牌」，不是手牌；看别人时写那个人的名字（用户 2026-10-07：点他人头像时显示「你没有手牌」）
            Text empty = character.equals(projection().character())
                    ? Text.translatable("heavyseas.table.empty_self")
                    : Text.translatable("heavyseas.table.empty_other", nameOf(character));
            drawLine(context, empty, width / 2, top + h / 2, GuiLanguage.muted());
        } else {
            int step = CardRow.step(n, w, CARD_GAP, width - 2 * SIDE);
            int left = (width - ((n - 1) * step + w)) / 2;
            rowLeft = left;
            rowTop = top;
            rowStep = step;
            rowW = w;
            rowH = h;
            Inspect in = inspect(b, b.stageTop() + lineStep());
            long now = System.currentTimeMillis();
            long dt = frameDelta(now);
            float gathered = gathered(now);
            if (lift.length != n) {
                float[] grown = new float[n];
                System.arraycopy(lift, 0, grown, 0, Math.min(lift.length, n));
                lift = grown;
            }
            float[] target = new float[n];
            for (int i = 0; i < n; i++) {
                target[i] = left + i * step;
                lift[i] = GuiLanguage.approach(lift[i], i == selected ? pullLift : 0f, dt);
            }
            float[] at = slide.positions(seat.front(), target, dt);
            boolean stacked = n > 1 && step < w;
            for (int i = 0; i < n; i++) {
                if (i != selected) {
                    drawCard(context, i, at[i], in, gathered, stacked && i > 0 && i - 1 != selected);
                }
            }
            drawCard(context, selected, at[selected], in, gathered, false);
            if (inspecting()) {
                String card = seat.front().get(selected);
                drawCardPlate(context, in.plateX(), in.plateY(), in.plateW(), -1,
                        provisionCaption(card), provisionName(card), provisionEffect(card));
            }
            // 「打出」只给此刻打得出的那一张（与手牌一面同一份判据，CardPlay · ADR-0095 A3）：
            // 原先只要能赠送就画，没轮到你时按下去被服务端静默丢掉，已经撑开的伞还能再撑一次、白花一个行动。
            Text label = Text.translatable("heavyseas.trade.give");
            Text play = Text.translatable("heavyseas.keys.play");
            boolean give = canGive();
            boolean playable = canPlay();
            int buttonsW = (give ? buttonWidth(label) : 0) + (give && playable ? BTN_GAP : 0)
                    + (playable ? buttonWidth(play) : 0);
            int bx = (width - buttonsW) / 2;
            if (give) {
                giveBox = new Box(bx, bottom + BTN_GAP, buttonWidth(label), buttonHeight());
                drawButton(context, giveBox, label, false, GuiLanguage.verdigris(), 0);
                bx += giveBox.w() + BTN_GAP;
            }
            if (playable) {
                playBox = new Box(bx, bottom + BTN_GAP, buttonWidth(play), buttonHeight());
                drawButton(context, playBox, play, false, GuiLanguage.verdigris(), 0);
            }
        }
        // 这一面接 G（赠送）与 Enter（打出）却从没写在底下（ADR-0095 A8）：给得出、打得出时才写
        List<KeyHint> right = new java.util.ArrayList<>();
        if (canGive()) {
            right.add(keys("gift", "T"));
        }
        if (canPlay()) {
            right.add(keys("play", "Enter"));
        }
        right.add(keys("close", "Esc"));
        drawFootBand(context, b, List.of(keys("select", "←", "→")), right, System.currentTimeMillis(), null);
    }

    /** 自己面前、选中的那一张此刻打得出吗（{@link CardPlay}）。别人的座位一律不行。 */
    private boolean canPlay() {
        var view = projection();
        if (seat == null || seat.front().isEmpty() || !character.equals(view.character())) {
            return false;
        }
        String card = seat.front().get(Math.max(0, Math.min(seat.front().size() - 1, selected)));
        return CardPlay.playableNow(view, card, openParasol(view, card));
    }

    /** 这一张是不是已经撑开的伞（投影里自己面前那一排带着「撑开」标记）。 */
    private static boolean openParasol(io.github.heavyseasmc.mod.state.HudView view, String card) {
        return view.front().stream().anyMatch(f -> f.id().equals(card) && f.open());
    }

    /**
     * 画一张：位置在「摊成一排」与「收成一叠」（查看态）之间按 {@code gathered} 插值；
     * 选中那张抽出来（以牌底中点为轴往左转、往上提），收成一叠时转回平放。
     *
     * @param x    这一帧它在一排里的左沿（张数刚变过时还在往新位置滑）
     * @param edge 叠着、而且左边那张没被抽走：左边缘给下面那张投一道细影
     */
    private void drawCard(DrawContext context, int index, float x, Inspect in, float gathered, boolean edge) {
        boolean hi = index == selected;
        CardPose pose = cardPose(in, gathered, x + rowW / 2f, rowTop + rowH, rowW, rowH,
                hi ? 0 : 1 + Math.abs(index - selected));
        float up = index < lift.length ? lift[index] * (1f - gathered) : 0f;
        context.getMatrices().push();
        context.getMatrices().translate(pose.cx(), pose.bottom() - up, 0);
        CardRow.rotate(context, pullLift > 0f ? up / pullLift : 0f);
        context.getMatrices().translate(-pose.w() / 2f, -pose.h(), 0);
        drawCardShadow(context, pose.w(), pose.h(), up);
        if (edge && gathered <= 0f) {
            CardRow.edgeShadow(context, pose.h(), guiScale(), sheet().k());
        }
        CardTexture.drawProvision(context, seat.front().get(index), 0, 0, pose.w(), pose.h());
        if (hi) {
            drawCardFrame(context, pose.w(), pose.h());
        }
        context.getMatrices().pop();
    }

    private int cardAt(double x, double y) {
        if (seat == null || rowW <= 0) {
            return -1;
        }
        return CardRow.indexAt((float) x, (float) y, rowLeft, rowTop, rowStep, rowW, rowH, seat.front().size(),
                selected, pullLift);
    }

    private boolean canGive() {
        var view = projection();
        return seat != null && !seat.front().isEmpty() && character.equals(view.character())
                && view.phase() == Phase.ACTION && !view.contest().active() && !view.endgame().active()
                && view.condition().canAct();
    }

    private void give() {
        if (canGive()) {
            openChild(new CardChoiceScreen(seat.front().get(selected), true));   // 送完 / Esc 回座位面板
        }
    }

    @Override
    protected boolean leftClick(double x, double y) {
        if (giveBox != null && giveBox.contains((int) x, (int) y)) {
            give();
            return true;
        }
        if (playBox != null && playBox.contains((int) x, (int) y)) {
            play();
            return true;
        }
        int index = cardAt(x, y);
        if (!inspecting() && index >= 0) {
            selected = index;
            return true;
        }
        return false;
    }

    @Override
    protected boolean rightClick(double x, double y) {
        return inspectClick(inspecting() ? -1 : cardAt(x, y), index -> selected = index);
    }

    @Override
    protected int inspectedIndex() {
        return selected;
    }

    private void play() {
        var view = projection();
        if (seat == null || seat.front().isEmpty() || !character.equals(view.character())) {
            return;                           // 别人的座位：没有「打出」这回事
        }
        String card = seat.front().get(selected);
        if (!CardPlay.playableNow(view, card, openParasol(view, card))) {
            // 发出去只会被静默丢掉（或者白花一个行动）：当场说一句为什么（ADR-0095 A3）
            ActionBarEcho.record(CardPlay.whyNot(view, card, openParasol(view, card), provisionName(card)));
            return;
        }
        ClientPlayNetworking.send(UseProvisionC2S.play(card));
    }

    @Override
    public boolean keyPressed(int key, int scan, int modifiers) {
        if (inspectKey(key)) {
            return true;
        }
        if (seat != null && (key == GLFW.GLFW_KEY_LEFT || key == GLFW.GLFW_KEY_RIGHT)) {
            selected = Math.max(0, Math.min(seat.front().size() - 1, selected + (key == GLFW.GLFW_KEY_RIGHT ? 1 : -1)));
            return true;
        }
        if (key == GLFW.GLFW_KEY_T) {          // 赠送是 T（用户 2026-10-07），G 只表示「行动」
            give();
            return true;
        }
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
            play();
            return true;
        }
        return super.keyPressed(key, scan, modifiers);
    }
}
