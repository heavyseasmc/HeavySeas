package io.github.heavyseasmc.mod.client;

import io.github.heavyseasmc.engine.state.Phase;
import io.github.heavyseasmc.mod.net.ActionChoiceC2S;
import io.github.heavyseasmc.mod.net.CardActionC2S;
import io.github.heavyseasmc.mod.state.ExperienceView;
import io.github.heavyseasmc.mod.state.GameComponents;
import io.github.heavyseasmc.mod.state.HudView;
import io.github.heavyseasmc.mod.ui.WorldTargetRules;
import io.github.heavyseasmc.mod.world.skiff.SkiffBlocks;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.Entity;
import net.minecraft.text.Text;
import net.minecraft.util.hit.BlockHitResult;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/** Cursor-free shortcuts: the view aims, the wheel selects, and a separate right press commits. */
public final class WorldInteraction {
    private enum Mode { NONE, MENU, GIFT }
    private enum Action { VIEW, GIVE, STEAL, SWAP }
    private static Mode mode = Mode.NONE;
    private static WorldTargets.Person hover;
    private static String target = "";
    private static String match = "";
    private static int turn;
    private static Phase phase;
    private static boolean viewOnly;
    private static int action;
    private static String card = "";
    private static boolean front;
    private static GameScreen previous;
    private static boolean released = true;
    private static List<WorldTargets.Person> people = List.of();
    private static float yaw, pitch;
    private WorldInteraction() { }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(WorldInteraction::tick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> reset());
        HudRenderCallback.EVENT.register((context, counter) -> draw(context, MinecraftClient.getInstance()));
    }

    public static boolean active() { return mode != Mode.NONE; }
    public static boolean waitingForRelease() { return !released; }
    public static void consumeRightPress() { released = false; }

    public static void beginGift(String gift, boolean fromFront, GameScreen parent) {
        var client = MinecraftClient.getInstance();
        var view = view(client);
        if (!canGive(view, gift, fromFront)) return;
        mode = Mode.GIFT; card = gift; front = fromFront; previous = parent; released = false;
        match = view.experience().matchId(); turn = view.turn(); phase = view.phase(); target = "";
        rememberView(client);
        client.setScreen(null);
    }

    public static boolean handleActionKey(boolean pressed) {
        if (!active()) return false;
        if (pressed) {
            var client = MinecraftClient.getInstance();
            GameScreen back = previous;
            reset();
            if (back != null && back.canReturnTo(view(client))) client.setScreen(back);
        }
        return true;
    }

    private static HudView view(MinecraftClient client) {
        return client.world == null ? HudView.IDLE : GameComponents.of(client.world).hudView();
    }

    private static void tick(MinecraftClient client) {
        if (client.player == null || client.world == null) { reset(); return; }
        if (GLFW.glfwGetMouseButton(client.getWindow().getHandle(), GLFW.GLFW_MOUSE_BUTTON_RIGHT) == GLFW.GLFW_RELEASE) released = true;
        HudView view = view(client);
        if (!view.active() || !view.seated() || view.endgame().active() || view.myDesignating()) { reset(); return; }
        if (!view.myTurnToAct() && DecisionNavigation.countdown(view) != null) { reset(); return; }
        if (active() && (!match.equals(view.experience().matchId()) || turn != view.turn() || phase != view.phase()
                || (mode == Mode.GIFT && !canGive(view, card, front)))) { reset(); return; }
        if (client.currentScreen != null) { hover = null; return; }
        people = WorldTargets.collect(client, view);
        // 只认准星直接压在身上的那一个（用户 2026-10-13）：原先 12° 以内都算，坐成一排时随便一看就弹菜单
        hover = WorldTargets.underCrosshair(client, people);
        // The lantern and sail retain their real block interaction when looked at directly.
        if (mode == Mode.NONE && client.crosshairTarget instanceof BlockHitResult hit) {
            var block = client.world.getBlockState(hit.getBlockPos()).getBlock();
            if (block == SkiffBlocks.LANTERN || block == SkiffBlocks.MAST) hover = null;
        }
        if (mode == Mode.GIFT) {
            boolean turned = Math.abs(net.minecraft.util.math.MathHelper.wrapDegrees(client.player.getYaw() - yaw))
                    + Math.abs(client.player.getPitch() - pitch) > 8;
            if ((target.isEmpty() || turned) && hover != null) { target = hover.character(); rememberView(client); }
        }
        if (active() && !target.isEmpty() && selected() == null) { target = ""; if (mode == Mode.MENU) reset(); }
    }

    private static void rememberView(MinecraftClient client) {
        if (client.player != null) { yaw = client.player.getYaw(); pitch = client.player.getPitch(); }
    }

    public static boolean scroll(double wheel) {
        if (!active() || wheel == 0 || MinecraftClient.getInstance().currentScreen != null) return false;
        var client = MinecraftClient.getInstance();
        // 菜单里滚轮换动作、按住「换人」键（按键绑定，默认左 Ctrl）再滚换人；递牌时滚轮就是换人
        boolean changePerson = HeavySeasClient.switchPersonHeld(client);
        if (mode == Mode.MENU && !changePerson) {
            if (!viewOnly) action = WorldTargetRules.cycle(action, Action.values().length, wheel);
        }
        else if (!people.isEmpty()) {
            // 只在画面里看得见的人之间换（WorldTargetRules#cycleOnScreen）：描边跟着换过去，画面上看得出选的是谁
            int at = -1;
            for (int i = 0; i < people.size(); i++) if (people.get(i).character().equals(target)) at = i;
            int next = WorldTargetRules.cycleOnScreen(WorldTargets.bearings(client, people), at,
                    WorldTargets.halfFov(client), wheel);
            if (next >= 0) target = people.get(next).character();
            rememberView(MinecraftClient.getInstance());
        }
        return true;
    }

    public static boolean rightClick() {
        var client = MinecraftClient.getInstance();
        if (client.currentScreen != null || client.world == null || client.player == null) return false;
        if (view(client).myDesignating()) return false;
        if (mode == Mode.NONE) {
            if (hover == null || !released) return hover != null;
            mode = Mode.MENU; target = hover.character(); action = 0; previous = null;
            match = view(client).experience().matchId(); turn = view(client).turn(); phase = view(client).phase();
            viewOnly = phase != Phase.ACTION || view(client).contest().active(); released = false;
            return true;
        }
        if (!released) return true;
        released = false;
        var person = selected();
        if (person == null) { ActionBarEcho.record(Text.translatable("heavyseas.world.no_target")); return true; }
        HudView view = view(client);
        if (mode == Mode.GIFT) {
            if (canGive(view, card, front)) CardRequests.send(CardActionC2S.of(front
                    ? CardActionC2S.Kind.GIVE_FRONT : CardActionC2S.Kind.GIVE_HAND, card, person.character(), 0));
            reset();
            released = false;
            return true;
        }
        Action chosen = viewOnly ? Action.VIEW : Action.values()[action];
        String who = person.character();
        if (chosen == Action.VIEW) { reset(); client.setScreen(new TableScreen(who)); }
        else if (chosen == Action.GIVE) {
            boolean any = view.experience().playability().stream().anyMatch(p -> p.enabled()
                    && (p.action() == ExperienceView.Action.GIVE_HAND || p.action() == ExperienceView.Action.GIVE_FRONT));
            if (any) { reset(); client.setScreen(HandScreen.giftTo(who)); }
            else ActionBarEcho.record(Text.translatable("heavyseas.world.no_gift"));
        } else if (view.myTurnToAct()) {
            reset();
            DesignationAim.preferTarget(who);
            ClientPlayNetworking.send(ActionChoiceC2S.of(chosen == Action.STEAL ? ActionChoiceC2S.Kind.STEAL : ActionChoiceC2S.Kind.SWAP));
        } else ActionBarEcho.record(Text.translatable("heavyseas.hand.not_your_turn"));
        released = false;
        return true;
    }

    private static boolean canGive(HudView view, String card, boolean front) {
        return view.active() && view.seated() && !view.endgame().active() && view.phase() == Phase.ACTION && !view.contest().active()
                && view.experience().availability(card, front ? ExperienceView.Action.GIVE_FRONT : ExperienceView.Action.GIVE_HAND)
                .map(ExperienceView.Playability::enabled).orElse(false);
    }

    private static WorldTargets.Person selected() {
        return people.stream().filter(p -> p.character().equals(target)).findFirst().orElse(null);
    }

    public static boolean outlined(Entity entity) {
        if (MinecraftClient.getInstance().currentScreen != null) return false;
        var person = active() ? selected() : hover;
        return person != null && person.body() == entity;
    }

    /**
     * 准星下那张字条：<b>只在指着人时出现</b>（用户 2026-10-13）—— 看着一个人（还没右键）、菜单里锁定的那个、递牌时挑中的那个。
     *
     * <p>用签子那一件材质（{@link GuiMaterial#plate}，跟主题），字照签上的字走 {@link GuiLanguage#onTag}；
     * 宽度按两行里宽的那一行量，不超过窗口。原先写死 300 宽与一块深色 {@code fill}、两种写死的字色（2026-10-13 验收）。
     */
    private static void draw(DrawContext context, MinecraftClient client) {
        if (client.currentScreen != null || client.player == null) return;
        if (view(client).myDesignating()) return;
        var person = active() ? selected() : hover;
        if (person == null) return;
        String who = GameScreen.nameOf(person.character()).getString();
        String key = HeavySeasClient.actKey().getBoundKeyLocalizedText().getString();
        Text first;
        if (mode == Mode.GIFT) first = Text.translatable("heavyseas.world.gift", GameScreen.provisionName(card), who);
        else if (mode == Mode.MENU) first = Text.translatable("heavyseas.world.locked", who,
                HeavySeasClient.switchPersonKey().getBoundKeyLocalizedText());
        else first = Text.translatable("heavyseas.world.look", who);
        Text second = mode == Mode.MENU ? Text.translatable("heavyseas.world.menu", actionLabel(), key)
                : mode == Mode.GIFT ? Text.translatable("heavyseas.world.gift_keys", key) : Text.translatable("heavyseas.world.open");
        int screenW = context.getScaledWindowWidth();
        int pad = STRIP_PAD;
        // 小一号、贴着准星下沿：原先两行正文字号、离准星 18 个单位，一大块盖在人身上（2026-10-13 验收 11/13）。
        // 第一行（指着谁 / 锁定了谁）照正文粗体，第二行键位用小一号的字。
        int widest = Math.max(GuiText.width(first.getString(), GuiText.BODY, true),
                GuiText.width(second.getString(), GuiText.CAPTION, false)) + 2;
        int w = Math.max(1, Math.min(widest, screenW - 2 * (STRIP_MARGIN + pad)));
        int x = (screenW - w) / 2;
        int y = context.getScaledWindowHeight() / 2 + STRIP_BELOW_CROSSHAIR;
        int firstH = GuiText.lineHeight(GuiText.BODY, true);
        int secondH = GuiText.lineHeight(GuiText.CAPTION, false);
        GuiMaterial.plate(context, x - pad, y - pad, w + 2 * pad, firstH + STRIP_LINE_GAP + secondH + 2 * pad, -1);
        GuiText.draw(context, first.getString(), x, y, w, GuiText.BODY, true,
                GuiLanguage.onTag(GuiLanguage.ink()), GuiText.Align.CENTER, 1);
        GuiText.draw(context, second.getString(), x, y + firstH + STRIP_LINE_GAP, w, GuiText.CAPTION, false,
                GuiLanguage.onTag(GuiLanguage.muted()), GuiText.Align.CENTER, 1);
    }

    /** 字条离准星往下多少（准星本身半高 7，再空 3）· 两行之间 · 内边距 · 离窗口两边至少多少（GUI 单位）。 */
    private static final int STRIP_BELOW_CROSSHAIR = 10;
    private static final int STRIP_LINE_GAP = 1;
    private static final int STRIP_PAD = 3;
    private static final int STRIP_MARGIN = 8;

    private static Text actionLabel() {
        if (viewOnly) return Text.translatable("heavyseas.world.view");
        return Text.translatable(switch (Action.values()[action]) {
            case VIEW -> "heavyseas.world.view"; case GIVE -> "heavyseas.world.give";
            case STEAL -> "heavyseas.world.steal"; case SWAP -> "heavyseas.world.swap";
        });
    }

    private static void reset() {
        mode = Mode.NONE; target = ""; hover = null; people = List.of(); previous = null;
        card = ""; match = ""; action = 0;
    }
}
