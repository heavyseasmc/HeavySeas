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

/** Public front cards, reached by inspecting a seat. */
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
        int h = cardHeightFor(Math.max(1, n), bottom - b.stageTop() - lineStep() - liftRoom());
        int w = GuiLanguage.cardWidth(h);
        int top = cardsTopIn(b.stageTop() + lineStep(), bottom, h, liftRoom());
        if (n == 0) {
            drawLine(context, Text.translatable("heavyseas.hand.empty_none"), width / 2,
                    top + h / 2, GuiLanguage.muted());
        } else {
            int step = n <= 1 ? w : Math.min(w + CARD_GAP, (width - 2 * SIDE - w) / (n - 1));
            int left = (width - ((n - 1) * step + w)) / 2;
            rowLeft = left;
            rowTop = top;
            rowStep = step;
            rowW = w;
            rowH = h;
            Inspect in = inspect(b, b.stageTop() + lineStep());
            float gathered = gathered(System.currentTimeMillis());
            for (int i = 0; i < n; i++) {
                if (i != selected) {
                    drawCard(context, i, in, gathered);
                }
            }
            drawCard(context, selected, in, gathered);
            if (inspecting()) {
                String card = seat.front().get(selected);
                drawCardPlate(context, in.plateX(), in.plateY(), in.plateW(), -1,
                        provisionCaption(card), provisionName(card), provisionEffect(card));
            }
            if (canGive()) {
                Text label = Text.translatable("heavyseas.trade.give");
                Text play = Text.translatable("heavyseas.keys.play");
                int buttonsW = buttonWidth(label) + BTN_GAP + buttonWidth(play);
                giveBox = new Box((width - buttonsW) / 2, bottom + BTN_GAP,
                        buttonWidth(label), buttonHeight());
                playBox = new Box(giveBox.x() + giveBox.w() + BTN_GAP, giveBox.y(),
                        buttonWidth(play), buttonHeight());
                drawButton(context, giveBox, label, false, GuiLanguage.verdigris(), 0);
                drawButton(context, playBox, play, false, GuiLanguage.verdigris(), 0);
            }
        }
        drawFootBand(context, b, List.of(keys("select", "←", "→")),
                List.of(keys("close", "Esc")), System.currentTimeMillis(), null);
    }

    private void drawCard(DrawContext context, int index, Inspect in, float gathered) {
        CardPose pose = cardPose(in, gathered, rowLeft + index * rowStep + rowW / 2f,
                rowTop + rowH, rowW, rowH, index == selected ? 0 : 1 + Math.abs(index - selected));
        int x = Math.round(pose.cx() - pose.w() / 2f);
        int y = Math.round(pose.bottom() - pose.h());
        CardTexture.drawProvision(context, seat.front().get(index), x, y, pose.w(), pose.h());
        if (index == selected) {
            context.drawBorder(x - CARD_FRAME, y - CARD_FRAME,
                    pose.w() + 2 * CARD_FRAME, pose.h() + 2 * CARD_FRAME, GuiLanguage.gold());
        }
    }

    private int cardAt(double x, double y) {
        if (seat == null || y < rowTop || y >= rowTop + rowH || rowW <= 0) {
            return -1;
        }
        if (x >= rowLeft + selected * rowStep && x < rowLeft + selected * rowStep + rowW) {
            return selected;
        }
        for (int i = seat.front().size() - 1; i >= 0; i--) {
            if (x >= rowLeft + i * rowStep && x < rowLeft + i * rowStep + rowW) {
                return i;
            }
        }
        return -1;
    }

    private boolean canGive() {
        var view = projection();
        return seat != null && !seat.front().isEmpty() && character.equals(view.character())
                && view.phase() == Phase.ACTION && !view.contest().active() && !view.endgame().active()
                && view.condition().canAct();
    }

    private void give() {
        if (canGive()) {
            client.setScreen(new CardChoiceScreen(seat.front().get(selected), true));
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
        if (seat != null && !seat.front().isEmpty() && character.equals(view.character())
                && !view.endgame().active() && view.condition().canAct()) {
            ClientPlayNetworking.send(UseProvisionC2S.play(seat.front().get(selected)));
        }
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
        if (key == GLFW.GLFW_KEY_G) {
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
