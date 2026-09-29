package io.github.heavyseasmc.mod.client;

import io.github.heavyseasmc.engine.state.Phase;
import io.github.heavyseasmc.mod.game.OverboardPhase;
import io.github.heavyseasmc.mod.net.CardActionC2S;
import io.github.heavyseasmc.mod.state.GameComponents;
import io.github.heavyseasmc.mod.state.TableView;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/** A card and its recipient are confirmed together. */
public final class CardChoiceScreen extends GameScreen {
    private final String gift;
    private final boolean fromFront;
    private final int token;
    private List<TableView.Play> choices = List.of();
    private int selected;
    private Box confirmBox;
    private Box previousBox;
    private Box nextBox;

    public CardChoiceScreen(String gift, boolean fromFront) {
        this(gift, fromFront, 0);
    }

    public CardChoiceScreen(int token) {
        this("", false, token);
    }

    private CardChoiceScreen(String gift, boolean fromFront, int token) {
        super(Text.translatable(token > 0 ? "heavyseas.overboard.title" : "heavyseas.trade.title"));
        this.gift = gift;
        this.fromFront = fromFront;
        this.token = token;
    }

    @Override
    protected void init() {
        super.init();
        tick();
    }

    @Override
    public void tick() {
        var view = projection();
        if (client == null || client.world == null || !view.active() || !view.seated()) {
            close();
            return;
        }
        TableView table = GameComponents.of(client.world).tableView();
        if (token > 0) {
            if (!table.overboardOpen() || table.token() != token) {
                close();
                return;
            }
            choices = table.plays();
        } else {
            boolean held = fromFront ? view.front().stream().anyMatch(p -> p.id().equals(gift))
                    : view.hand().contains(gift);
            if (!held || view.phase() != Phase.ACTION || view.contest().active() || view.endgame().active()) {
                close();
                return;
            }
            choices = table.seats().stream().filter(s -> !s.removed() && !s.id().equals(view.character()))
                    .map(s -> new TableView.Play(gift, s.id())).toList();
        }
        selected = Math.max(0, Math.min(selected, choices.size() - 1));
        if (choices.isEmpty()) {
            close();
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackdrop(context, mouseX, mouseY, delta);
        var view = projection();
        Bands b = drawChrome(context, view);
        if (choices.isEmpty()) {
            return;
        }
        TableView.Play choice = choices.get(selected);
        int bottom = b.stageBottom() - buttonHeight() - lineStep() - HINT_GAP - BORDER_ROOM;
        // 落海窗口：先说谁要落海。原先这一面只有「救生圈 · 目标 · 给 X · 打出」，屏幕上一个字都不说情境 ——
        // 全局情绪最高的那一下被做成了一张普通表单（ADR-0045 §1.4 B4）。朱砂：紧迫 · 不可逆。
        int stageTop = b.stageTop();
        if (token > 0) {
            List<String> swimmers = GameComponents.of(client.world).tableView().swimmers();
            if (!swimmers.isEmpty()) {
                net.minecraft.text.MutableText names = Text.empty();
                for (String id : swimmers) {
                    if (!names.getSiblings().isEmpty()) {
                        names.append("、");
                    }
                    names.append(nameOf(id));
                }
                drawLine(context, Text.translatable("heavyseas.overboard.pending", names), width / 2, stageTop,
                        GuiLanguage.cinnabar());
                stageTop += lineStep() + HINT_GAP;
            }
        }
        int arrowW = buttonWidth(Text.literal("←"));
        int h = cardHeightFor(2, bottom - stageTop - liftRoom(),
                width - 2 * (SIDE + arrowW + BTN_GAP));
        int w = GuiLanguage.cardWidth(h);
        int top = cardsTopIn(stageTop, bottom, h, liftRoom());
        int left = (width - 2 * w - CARD_GAP) / 2;
        CardTexture.drawProvision(context, choice.card(), left, top, w, h);
        CardTexture.drawCharacter(context, choice.target(), left + w + CARD_GAP, top, w, h);
        previousBox = new Box(SIDE, top + (h - buttonHeight()) / 2, arrowW, buttonHeight());
        nextBox = new Box(width - SIDE - arrowW, previousBox.y(), arrowW, buttonHeight());
        if (selected > 0) {
            drawButton(context, previousBox, Text.literal("←"), false, GuiLanguage.ink(), 0);
        }
        if (selected + 1 < choices.size()) {
            drawButton(context, nextBox, Text.literal("→"), false, GuiLanguage.ink(), 0);
        }
        drawLine(context, Text.translatable("heavyseas.trade.recipient", nameOf(choice.target())),
                width / 2, bottom + HINT_GAP, GuiLanguage.ink());
        Text label = Text.translatable(token > 0 ? "heavyseas.keys.play" : "heavyseas.trade.give");
        confirmBox = new Box((width - buttonWidth(label)) / 2,
                bottom + HINT_GAP + lineStep(), buttonWidth(label), buttonHeight());
        drawButton(context, confirmBox, label, true, GuiLanguage.verdigris(), 0);
        Countdown countdown = token > 0 ? new Countdown(GameComponents.of(client.world).tableView().deadline(),
                OverboardPhase.CHOOSE_MILLIS, w) : null;
        drawFootBand(context, b, List.of(keys("select", "←", "→")),
                List.of(confirm("Enter"), keys("cancel", "Esc")), System.currentTimeMillis(), countdown);
    }

    private void commit() {
        if (selected < 0 || selected >= choices.size()) {
            return;
        }
        var choice = choices.get(selected);
        var kind = token > 0 ? CardActionC2S.Kind.OVERBOARD
                : fromFront ? CardActionC2S.Kind.GIVE_FRONT : CardActionC2S.Kind.GIVE_HAND;
        ClientPlayNetworking.send(CardActionC2S.of(kind, choice.card(), choice.target(), token));
        close();
    }

    @Override
    protected boolean leftClick(double x, double y) {
        if (previousBox != null && selected > 0 && previousBox.contains((int) x, (int) y)) {
            selected--;
            return true;
        }
        if (nextBox != null && selected + 1 < choices.size() && nextBox.contains((int) x, (int) y)) {
            selected++;
            return true;
        }
        if (confirmBox != null && confirmBox.contains((int) x, (int) y)) {
            commit();
            return true;
        }
        return false;
    }

    @Override
    public boolean keyPressed(int key, int scan, int modifiers) {
        if (key == GLFW.GLFW_KEY_LEFT || key == GLFW.GLFW_KEY_RIGHT) {
            selected = Math.max(0, Math.min(choices.size() - 1, selected + (key == GLFW.GLFW_KEY_RIGHT ? 1 : -1)));
            return true;
        }
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
            commit();
            return true;
        }
        return super.keyPressed(key, scan, modifiers);
    }

    /** 上带左头那一句（ADR-0043 D3 (b)）：只在还没定的时候说。 */
    @Override
    protected Text cue() {
        return Text.translatable(token > 0 ? "heavyseas.cue.overboard" : "heavyseas.cue.gift");
    }
}
