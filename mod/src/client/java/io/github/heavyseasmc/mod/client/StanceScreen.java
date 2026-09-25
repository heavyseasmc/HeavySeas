package io.github.heavyseasmc.mod.client;

import io.github.heavyseasmc.engine.play.Contest;
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
 * 站队：打起来了，谁来帮谁（ADR-0023 · 决策 ④）。
 *
 * <h2>两边的体型和摆在脸上，武器一个字都不提</h2>
 * 阵营与体型和是<b>公开</b>的 —— 谈判就靠它：看得见「进攻 9 · 防守 7」，才谈得成「我帮你，你给我一张水」。
 * 而押下的武器是全场唯一的暗牌（决策 ④），所以这两个数里<b>不含武器</b>；
 * 含了的话，减一减就知道对面押了几点，挂武器那一段就白做了。
 *
 * <h2>焦点一进来在「旁观」</h2>
 * 什么也不做的结果就是不加入 —— 焦点落在那个「你什么都不按会发生的事」上，与另外几面同一条。
 * 更要紧的是<b>不替人预选一边</b>：预选进攻方等于替他站了队。
 *
 * <h2>加入之后这一面就没有可做的决定了</h2>
 * 「加入后不得反悔、也不能换边」（规则 §9.3），所以加入的那一刻这一面就收起来 ——
 * 留着它只会让人以为还能改。已经在场上的人（进攻方、防守方、先加入的助拳者）根本不会看到这一面。
 */
public final class StanceScreen extends GameScreen {

    private static final Logger LOGGER = LoggerFactory.getLogger(HeavySeasMod.MOD_ID);

    /** 三个按钮：加入进攻 · 加入防守 · 旁观。旁观在最后，也是默认焦点。 */
    private static final int WATCH = 2;

    private HudView view;
    private final Contest.Kind kind;
    private final String attacker;
    private final String target;

    /** 焦点。<b>一进来就在「旁观」</b>：那是你什么都不按会发生的事。 */
    private int focus = WATCH;
    private boolean committed;
    private final float[] lift = new float[3];
    private List<Box> boxes = List.of();

    public StanceScreen(HudView view) {
        super(Text.translatable("heavyseas.stance.title"));
        this.view = view;
        this.kind = view.contest().kind();
        this.attacker = view.contest().attacker();
        this.target = view.contest().target();
    }

    @Override
    public void tick() {
        view = projection();
        // ❗认的是「这一段还轮不轮得到我」，不是某个包：站队段结束、这一场收场、我被人拉进场上 —— 都该收。
        if (!view.myStance()) {
            close();
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackdrop(context, mouseX, mouseY, delta);
        long now = System.currentTimeMillis();
        long dt = frameDelta(now);
        int fh = textH();
        ContestView c = view.contest();

        Bands b = drawChrome(context, view);
        int headerY = b.stageTop();
        drawLine(context, Text.translatable(switch (kind) {
                            case SWAP -> "heavyseas.stance.header_swap";
                            case STEAL -> "heavyseas.stance.header_steal";
                            case RATION -> "heavyseas.stance.header_ration";
                        },
                        nameOf(attacker), nameOf(target)), width / 2, headerY, GuiLanguage.ink());
        // 两边各一行：站了谁 · 体型和。朱砂留给倒计时见底那一段，这里两边一视同仁。
        int attackY = headerY + lineStep() + BTN_GAP;
        int defendY = attackY + lineStep();
        drawLine(context, Text.translatable("heavyseas.stance.attack_side", names(c.attackSide()), c.attackPower()),
                width / 2, attackY, GuiLanguage.ink());
        drawLine(context, Text.translatable("heavyseas.stance.defend_side", names(c.defendSide()), c.defendPower()),
                width / 2, defendY, GuiLanguage.ink());

        List<Text> labels = List.of(Text.translatable("heavyseas.stance.join_attack"),
                Text.translatable("heavyseas.stance.join_defend"),
                Text.translatable("heavyseas.stance.watch"));
        // 按钮在两边那两行与舞台底边之间居中（先量一次有多高，再排定 —— §7.7 的教训）。
        int regionTop = defendY + lineStep() + BTN_GAP + buttonLiftRoom();
        int blockH = rowHeight(layoutButtonRow(labels, 0, BTN_GAP));
        int top = regionTop + Math.max(0, (b.stageBottom() - regionTop - blockH) / 2);
        boxes = layoutButtonRow(labels, top, BTN_GAP);

        boolean moved = mouseActuallyMoved(mouseX, mouseY);
        if (moved && !committed) {
            int hovered = indexAt(boxes, mouseX, mouseY);
            if (hovered >= 0) {
                focus = hovered;
            }
        }
        for (int i = 0; i < boxes.size(); i++) {
            lift[i] = GuiLanguage.approach(lift[i], i == focus ? GuiLanguage.LIFT_PX : 0f, dt);
            drawButton(context, boxes.get(i), labels.get(i), i == focus,
                    i == WATCH ? GuiLanguage.muted() : GuiLanguage.ink(), lift[i]);
        }

        drawFootBand(context, b, List.of(keys("select", "←", "→")), List.of(confirm("Enter")), now,
                new Countdown(c.deadlineMs(), c.windowMs(), rowWidth(boxes)));
    }

    /** 一边站了谁。一个人都没有时写一道破折号 —— 空白与「还没读到」长得一样。 */
    private Text names(List<String> ids) {
        if (ids.isEmpty()) {
            return Text.literal("—");
        }
        Text out = Text.empty();
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) {
                out = out.copy().append(Text.literal("、"));
            }
            out = out.copy().append(nameOf(ids.get(i)));
        }
        return out;
    }

    /** 站队。「旁观」不发包 —— 规则里没有「宣布中立」这种状态，不加入就只是不加入。 */
    private void confirm() {
        if (committed) {
            return;
        }
        if (focus == WATCH) {
            LOGGER.info("站队：确认「WATCH」，不发包");
            close();
            return;
        }
        committed = true;
        boolean attack = focus == 0;
        ClientPlayNetworking.send(ContestActionC2S.of(attack
                ? ContestActionC2S.Kind.JOIN_ATTACK : ContestActionC2S.Kind.JOIN_DEFEND));
        // 与语言无关的一行：验收靠它与服务端那行「站队（界面）」对上。
        LOGGER.info("站队：确认「{}」", attack ? "ATTACK" : "DEFEND");
    }

    @Override
    protected boolean leftClick(double mouseX, double mouseY) {
        if (!committed) {
            int i = indexAt(boxes, (int) mouseX, (int) mouseY);
            if (i >= 0) {
                focus = i;
                confirm();
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (committed) {
            return super.keyPressed(keyCode, scanCode, modifiers);
        }
        switch (keyCode) {
            case GLFW.GLFW_KEY_LEFT -> {
                focus = Math.max(0, focus - 1);
                return true;
            }
            case GLFW.GLFW_KEY_RIGHT -> {
                focus = Math.min(boxes.size() - 1, focus + 1);
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
}
