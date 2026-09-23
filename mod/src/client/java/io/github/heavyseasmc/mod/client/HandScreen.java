package io.github.heavyseasmc.mod.client;

import io.github.heavyseasmc.engine.state.Phase;
import io.github.heavyseasmc.mod.HeavySeasMod;
import io.github.heavyseasmc.mod.net.UseProvisionC2S;
import io.github.heavyseasmc.mod.state.GameComponents;
import io.github.heavyseasmc.mod.state.HudView;
import net.minecraft.client.gui.DrawContext;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 手牌：看自己留下来的东西。
 *
 * <h2>为什么这一面是当前最要紧的缺口</h2>
 * 补给箱那一面让玩家挑了一张牌，然后那张牌就<b>消失了</b> —— 引擎里它进了手牌，
 * 屏幕上哪儿都没有。挑完即失踪，等于告诉玩家刚才那个选择没有意义，
 * 而「留哪张」正是本作头一个真正的决策。
 *
 * <h2>版面就是那一套带位（ADR-0037 §7.10）</h2>
 * 上带 · 座位轨 · 倒计时 · 身份行四条带由 {@code GameScreen} 排定，这一面只填舞台那一格：
 * 手牌摊成一排，吃掉舞台减去底下两行（爱恨 · 键位）之后剩下的全部高度。
 *
 * <p>这一面<b>没有倒计时</b>，因为它不会超时 —— 那一条带空着。
 * 空着与挪了位置是两回事：会超时的界面必须画它，不会超时的界面必须不画。
 *
 * <h2>放大镜去掉了</h2>
 * 第四刀之前这一面是「上面一张大图 + 下面一排小图」。大图当初是为了读卡面上的规则条，
 * 而第三刀把规则条整条从牌面上拿掉了（说明改走提示签），大图于是只是同一张牌画两遍 ——
 * 带位版面里它还把那一排挤成一条。现在只有一排，牌反而比原先那张大图还高。
 *
 * <h2>屏幕上几乎没有 UI 文字</h2>
 * 卡面自己印着名称与编号，这里一律不叠（用户 2026-09-15 看过真实客户端：卡名图上有）。
 *
 * <h2>动效的数不写在这里</h2>
 * 「发」与「抬」的时长、距离、缓动全部取自 {@link GuiLanguage} —— ADR-0018 §7.2：
 * 同一动词在不同界面用不同时长，就又变回十种游戏了。
 */
public final class HandScreen extends GameScreen {

    private static final Logger LOGGER = LoggerFactory.getLogger(HeavySeasMod.MOD_ID);

    private static final int HAND_GAP = 8;
    private HudView view = HudView.IDLE;

    /** 当前看的是第几张。<b>一进来就有</b>，不等玩家先动一下。 */
    private int selected;

    /** 每张的抬起量，向目标插值。下标与手牌对齐。 */
    private float[] lift = new float[0];

    /** 这一批「发」从第几张起、什么时候开始 —— 已经在手上的牌不重发。 */
    private int dealtFrom;
    private long dealtAt;

    /** 打开过了。见 {@link #init()}。 */
    private boolean opened;

    public HandScreen() {
        super(Text.translatable("heavyseas.hand.title"));
    }

    /**
     * ❗{@code init} 不只在打开时调：窗口每改一次尺寸、界面尺寸每改一次，Minecraft 都会重新调它。
     * 发牌与选中只该在<b>打开</b>时初始化 —— 否则拖一下窗口，整手牌就重新「发」一遍，选中也跳回第一张。
     */
    @Override
    protected void init() {
        super.init();                         // 先让 GameScreen 忘掉鼠标位置：坐标系可能刚变过
        if (opened) {
            return;
        }
        opened = true;
        view = currentView();
        selected = 0;
        lift = new float[view.hand().size()];
        dealtFrom = 0;                       // 一进来整手都是「新到你面前」，整排发一次
        dealtAt = System.currentTimeMillis();
    }

    private HudView currentView() {
        return client == null || client.world == null
                ? HudView.IDLE
                : GameComponents.of(client.world).hudView();
    }

    /**
     * 每帧对一次投影。
     *
     * <p>❗手牌变长时<b>只发新来的那几张</b>。整排重发会让「又拿到一张」读成「重新发牌」，
     * 而「发」这个动词的含义是「新东西到你面前」，不是「这里有牌」。
     */
    private void refresh() {
        HudView next = currentView();
        int before = view.hand().size();
        int after = next.hand().size();
        view = next;
        if (after != lift.length) {
            float[] grown = new float[after];
            System.arraycopy(lift, 0, grown, 0, Math.min(lift.length, after));
            lift = grown;
        }
        if (after > before) {
            dealtFrom = before;
            dealtAt = System.currentTimeMillis();
        }
        if (selected >= after) {
            select(Math.max(0, after - 1));
        }
    }

    /**
     * 对局结束或者座位没了就自己收起来。
     *
     * <p>❗关界面放在 tick 不放在 render：在 render 里换屏，这一帧余下的部分还在用一个
     * 已经 {@code removed} 的 Screen，是一类很难查的偶发崩溃。
     */
    @Override
    public void tick() {
        refresh();
        if (!view.active() || !view.seated()) {
            close();
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        refresh();
        if (!view.active() || !view.seated()) {
            return;                           // 这一帧什么都不画，tick 会把它收起来
        }
        renderBackdrop(context, mouseX, mouseY, delta);

        long now = System.currentTimeMillis();
        long dt = frameDelta(now);

        List<String> hand = view.hand();
        // 共有的四条带在 GameScreen 里排定（这一面没有倒计时，那一格空着 —— 空着与挪位置是两回事）。
        Bands b = drawChrome(context, view);
        Inspect ins = inspect(b);
        float gathered = gathered(now);

        // 爱恨那一行只有你看得到（全程保密，规则里也不许亮出来证明自己）；键位那一行紧贴手牌。
        // 两行都贴舞台底边，且<b>空手时照样让位</b> —— 牌不能因为手上有没有牌就上下跳。
        int lines = view.love().isEmpty() ? 0 : 1;
        int linesTop = footerTop(b, lines);
        int lineY = linesTop;
        if (!view.love().isEmpty()) {
            drawLine(context, Text.translatable("heavyseas.hand.affinity",
                            Text.translatable("heavyseas.character." + view.love()),
                            Text.translatable("heavyseas.character." + view.hate())),
                    width / 2, lineY, GuiLanguage.muted());
            lineY += lineStep();
        }
        // 手牌摊在舞台里，一排，吃掉舞台减去那两行之后剩下的全部高度。
        int room = liftRoom();
        int avail = linesTop - HINT_GAP - b.stageTop();
        int cardH = cardHeightFor(Math.max(1, hand.size()), avail - room);
        int cardW = GuiLanguage.cardWidth(cardH);
        int handTop = cardsTopIn(b.stageTop(), linesTop - HINT_GAP, cardH, room);

        if (hand.isEmpty()) {
            // 空手也留出牌的位置：下面那两行不能因为手上没牌就跳到别处去。
            // 「补给箱还没传到你手上」只在物资阶段是真话；终局里空手按绑定键进来的人，要的是「一张都没有」。
            boolean waiting = view.phase() == Phase.PROVISION && !view.endgame().active();
            drawLine(context, Text.translatable(waiting ? "heavyseas.hand.empty" : "heavyseas.hand.empty_none"),
                    width / 2, handTop + (cardH - textH()) / 2, GuiLanguage.dim());
            return;
        }

        int step = step(hand.size(), cardW);
        int left = (width - ((hand.size() - 1) * step + cardW)) / 2;

        // 鼠标真的动了才换选中（停着的指针不算指向，见 GameScreen#mouseActuallyMoved）。
        // 查看态里不认悬停：牌叠在一起了，命中框却还在摊开那一排的位置上。
        if (mouseActuallyMoved(mouseX, mouseY) && !inspecting()) {
            int hovered = indexAt(mouseX, mouseY, left, handTop, step, cardW, cardH);
            if (hovered >= 0) {
                select(hovered);              // 鼠标与键盘指的是同一个东西，不能各说各话
            }
        }

        // 选中的那张最后画 —— 叠起来时它必须在最上面，否则「抬」看不出来。
        for (int i = 0; i < hand.size(); i++) {
            lift[i] = GuiLanguage.approach(lift[i], i == selected ? GuiLanguage.LIFT_PX : 0f, dt);
            if (i != selected) {
                drawHandCard(context, now, hand, i, left + i * step, handTop, cardW, cardH, ins, gathered);
            }
        }
        drawHandCard(context, now, hand, selected, left + selected * step, handTop, cardW, cardH, ins, gathered);
        if (gathered > 0f && selected >= 0 && selected < hand.size()) {
            String card = hand.get(selected);
            drawCardPlate(context, ins.plateX(), ins.plateY(), ins.plateW(), -1,
                    provisionCaption(card), provisionName(card), provisionEffect(card));
        }
        // ❗键位要写出来。手上有牌却没人知道能拿它做什么，与没有手牌没有区别 ——
        //   与 HUD 那一行「按绑定键查看」同一条理由。这一面没有倒计时，提示就排在那一条空着的带上。
        drawFootBand(context, b, List.of(keys("select", "←", "→")),
                List.of(keys("reveal", "Enter"), keys("play", "U"),
                        keys(inspecting() ? "close" : "inspect", "Tab")), now, null);
    }

    /**
     * 相邻两张之间挪多远：放得下就并排，放不下就叠。
     *
     * <p>❗<b>不设「最小间距」下限。</b> 设了下限，牌多到连下限都摆不下时，
     * 右边几张会落到屏幕外面 —— 而画到屏幕外与没画长得一模一样，
     * 玩家只会觉得「我的牌少了几张」。挤到看不清仍然够得着，落在屏幕外就够不着了。
     */
    private int step(int count, int cardW) {
        int loose = cardW + HAND_GAP;
        if (count <= 1) {
            return loose;
        }
        // 900 是 GUI 单位的上限：界面尺寸设成 1 时一行只用得上屏幕中间一截，改成随宽度放开。
        int avail = Math.min(width - 32, Math.max(900, Math.round(width * 0.8f)));
        return Math.max(1, Math.min(loose, (avail - cardW) / (count - 1)));
    }

    private void drawHandCard(DrawContext context, long now, List<String> hand, int i,
                              int x, int top, int w, int h, Inspect ins, float gathered) {
        float in = GuiLanguage.deal(now, dealtAt, Math.max(0, i - dealtFrom));
        if (i >= dealtFrom && in <= 0f) {
            return;                           // 还没轮到它入场
        }
        float progress = i < dealtFrom ? 1f : in;
        float rise = (1f - progress) * GuiLanguage.DEAL_RISE;
        float scale = GuiLanguage.dealScale(progress);

        CardPose pose = cardPose(ins, gathered, x + w / 2f, top + h, w, h,
                i == selected ? 0 : 1 + Math.abs(i - Math.max(0, selected)));
        context.getMatrices().push();
        // 全部走矩阵，布局本身不动 —— 命中判定因此可以只看落位后的矩形。
        context.getMatrices().translate(pose.cx(), pose.bottom() - lift[i] * (1f - gathered) + rise, 0);
        context.getMatrices().scale(scale, scale, 1f);
        context.getMatrices().translate(-pose.w() / 2f, -pose.h(), 0);
        CardTexture.drawProvision(context, hand.get(i), 0, 0, pose.w(), pose.h());
        if (i == selected) {
            drawCardFrame(context, pose.w(), pose.h());
        }
        context.getMatrices().pop();
    }

    /** 叠起来时上面那张说了算，所以先问选中的那张，再从右往左问。 */
    private int indexAt(int mouseX, int mouseY, int left, int top, int step, int w, int h) {
        int count = view.hand().size();
        if (mouseY < top - GuiLanguage.LIFT_PX || mouseY > top + h) {
            return -1;
        }
        if (selected < count) {
            int x = left + selected * step;
            if (mouseX >= x && mouseX < x + w) {
                return selected;
            }
        }
        for (int i = count - 1; i >= 0; i--) {
            int x = left + i * step;
            if (mouseX >= x && mouseX < x + w) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 换选中的那张。鼠标、方向键、手牌变少，三条路都走这里。
     *
     * <p>❗换了就记下时刻，让大图重新「抬」一次。手里常有好几张水，两张一样的牌之间切换时
     * 大图的内容一模一样 —— 不动一下，看起来就像没换（用户 2026-09-15 在真实客户端上指出）。
     * 用「抬」而不是别的动词：大图换一张的含义就是「选中变了」（ADR-0018 §7.2）。
     */
    /**
     * 亮出正在看的那一张。
     *
     * <h2>为什么亮出值得有个键</h2>
     * 规则里一大半效果<b>只有亮在面前才算</b>：救生圈挡落水、阳伞能撑开、船桨让划船多抽、
     * 指南针让划船堆多一张。握在手里的那几张一点用都没有 —— 亮出不是装饰动作，是真的取舍
     * （亮了就看得见、落水时会被冲走）。
     *
     * <p>走的是 {@code /seas reveal}，不是新包：这一下每局最多十来次，而每加一个包
     * 就多一处「两端字段表要对上」。等这一面有了更多动作再一起做成包。
     */
    private void reveal() {
        List<String> hand = view.hand();
        if (selected < 0 || selected >= hand.size() || client == null || client.player == null) {
            return;
        }
        String card = hand.get(selected);
        // 与语言无关的一行：GUI 回归靠它判「亮出这一下真的发出去了」。
        LOGGER.info("手牌：亮出 {}", card);
        client.player.networkHandler.sendChatCommand(
                "seas reveal " + view.character() + " " + card);
    }

    /**
     * 打出正在看的那一张（特殊行动）。
     *
     * <p>不再借聊天指令：服务端确认牌的效果。医疗箱会进入目标一面，其余特殊行动一键完成。
     */
    private void use() {
        List<String> hand = view.hand();
        if (selected < 0 || selected >= hand.size() || client == null || client.player == null) {
            return;
        }
        String card = hand.get(selected);
        LOGGER.info("手牌：打出 {}", card);
        ClientPlayNetworking.send(UseProvisionC2S.play(card));
    }

    private void select(int index) {
        selected = index;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (inspectKey(keyCode)) {
            return true;
        }
        int count = view.hand().size();
        if (count > 0 && (keyCode == GLFW.GLFW_KEY_LEFT || keyCode == GLFW.GLFW_KEY_RIGHT)) {
            select(Math.max(0, Math.min(count - 1,
                    selected + (keyCode == GLFW.GLFW_KEY_RIGHT ? 1 : -1))));
            return true;
        }
        // 亮出：把正在看的那张放到面前。**不可逆**，所以要按回车而不是随手点一下（规则 §5.2）。
        if (count > 0 && (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER)) {
            reveal();
            return true;
        }
        // 用：花掉这一个行动打出去（医疗箱 · 撑伞 · 信号枪当信号 · 绝境）。
        // ❗只有轮到你时才行得通 —— 它占行动。按不动时服务端会回一句人话，不是静默丢掉。
        if (count > 0 && keyCode == GLFW.GLFW_KEY_U) {
            use();
            return true;
        }
        // 用哪个键开的，就用哪个键收起来。写死 R 的话玩家改了键位就收不起来了。
        if (HeavySeasClient.handKey().matchesKey(keyCode, scanCode)) {
            close();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
}
