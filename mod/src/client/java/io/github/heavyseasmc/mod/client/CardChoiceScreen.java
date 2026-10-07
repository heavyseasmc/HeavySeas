package io.github.heavyseasmc.mod.client;

import io.github.heavyseasmc.engine.state.Phase;
import io.github.heavyseasmc.mod.net.CardActionC2S;
import io.github.heavyseasmc.mod.state.GameComponents;
import io.github.heavyseasmc.mod.state.TableView;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * 一张牌连同收下它的人一起定：赠送给谁 · 落海那一窗把救生物资打给谁。
 *
 * <p>头像版（ADR-0050，用户 2026-10-01 看样图定）：左边是这张牌，右边一排收得下它的人，一眼看全 ——
 * 原先一次只看一个人（物资牌 + 那一位的角色牌），按 ← → 一个个翻，再点一枚「赠送」按钮。
 * 落海那一窗每个选项可能是不同的牌，所以左边那张跟着选中的那一项走。
 */
public final class CardChoiceScreen extends GameScreen {
    /** 头像直径 · 一格多宽（稿子像素，样图）：赠送一排最多七个人，比医疗箱小一号。 */
    private static final double TOKEN = 80;
    private static final double CELL = 112;

    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger(io.github.heavyseasmc.mod.HeavySeasMod.MOD_ID);
    private final String gift;
    private final boolean fromFront;
    private final int token;
    private List<TableView.Play> choices = List.of();
    private final PortraitPick pick = new PortraitPick(this, TOKEN, CELL);

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
        pick.focus(Math.max(0, Math.min(pick.focus(), rowSize() - 1)));
        if (choices.isEmpty()) {
            close();
        }
    }

    /** 落海那一窗的这一排最后多一格「什么也不做」（= 这一窗不用）；赠送没有它（不送就 Esc 取消）。 */
    private boolean hasPass() {
        return token > 0;
    }

    private int rowSize() {
        return choices.size() + (hasPass() ? 1 : 0);
    }

    private boolean onPass() {
        return hasPass() && pick.focus() == choices.size();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackdrop(context, mouseX, mouseY, delta);
        var view = projection();
        Bands b = drawChrome(context, view);
        if (choices.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        long dt = frameDelta(now);
        int stageTop = b.stageTop();
        if (token > 0) {
            // 落海窗口：先说谁要落海。原先这一面只有「救生圈 · 目标 · 给 X · 打出」，屏幕上一个字都不说情境 ——
            // 全局情绪最高的那一下被做成了一张普通表单（ADR-0045 §1.4 B4）。朱砂：紧迫 · 不可逆。
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
            }
        } else {
            drawLine(context, Text.translatable("heavyseas.trade.ask", provisionName(gift)), width / 2, stageTop,
                    GuiLanguage.ink());
        }
        List<PortraitPick.Person> people = new ArrayList<>();
        for (TableView.Play p : choices) {
            people.add(new PortraitPick.Person(p.target(), 0, 0));
        }
        if (hasPass()) {
            people.add(PortraitPick.Person.pass());
        }
        var l = sheet();
        double top0 = Math.max(l.railBottom() + CardRow.RAIL_CLEAR * l.k(),
                (stageTop + lineStep() + HINT_GAP) * (double) guiScale());
        // 左边那张跟着选中的那一项走：挑到「什么也不做」时它也是那一张。
        // ❗在画的那一刻才取：render 里悬停可能刚挪过焦点，先取好的话会拿着上一格去画这一格
        pick.render(context, mouseX, mouseY, dt, top0, people, (ctx, x, y, w, h) -> {
            if (onPass()) {
                CardTexture.drawAction(ctx, io.github.heavyseasmc.mod.card.ActionCard.PASS, x, y, w, h);
            } else {
                CardTexture.drawProvision(ctx, choices.get(Math.min(pick.focus(), choices.size() - 1)).card(),
                        x, y, w, h);
            }
        });
        // 总长取投影（ADR-0099 D8：时限可配，不再用编译进去的常量）
        TableView table = GameComponents.of(client.world).tableView();
        Countdown countdown = token > 0 ? new Countdown(table.deadline(), table.window(), 0) : null;
        // 落海那一窗：Esc 只收起（金签上按 G 回来），「不用」是那一排最后一格；赠送：Esc 取消
        drawFootBand(context, b, List.of(keys("select", "←", "→")),
                hasPass() ? List.of(confirm("Enter")) : List.of(confirm("Enter"), keys("cancel", "Esc")),
                now, countdown);
    }

    private void commit() {
        if (onPass()) {
            // 「什么也不做」= 这一窗不用（ADR-0095 D1）：告诉服务端，手上有牌的真人都不用了，这一窗当场收
            ClientPlayNetworking.send(CardActionC2S.of(CardActionC2S.Kind.OVERBOARD_DONE, "", "", token));
            LOGGER.info("落海：这一窗不用牌");
            close();
            return;
        }
        int selected = pick.focus();
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
        int i = pick.indexAt(x, y, rowSize());
        if (i >= 0) {
            pick.focus(i);
            commit();
            return true;
        }
        return false;
    }

    @Override
    public boolean keyPressed(int key, int scan, int modifiers) {
        // Esc（与背包键）只收起，不替你做决定（用户 2026-10-07：「esc 是关闭页面」）—— 落海那一窗的「不用」是最后那一格
        if (pick.keyPressed(key, rowSize())) {
            return true;
        }
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER || key == GLFW.GLFW_KEY_SPACE) {
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
