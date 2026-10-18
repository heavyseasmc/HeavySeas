package io.github.heavyseasmc.mod.client;

import io.github.heavyseasmc.mod.state.GameComponents;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ChatScreen;

/**
 * Chat keeps its draft and returns after both Escape and the vanilla send path.
 *
 * <p>开在一面对局界面上时（行动一面按 T），那一面照样画在底下、聊天页与输入框叠在它之上，聊天页收进左下那一角、
 * 不压牌与倒计时（{@link GameScreen#chatRoom}）；关掉聊天回到它。2026-10-13 总截图 12：原先底下露出的是世界视图 HUD，
 * 刚才那一面不见了，聊天页还压住了左侧图例板。
 */
final class DecisionChatScreen extends ChatScreen implements DecisionNavigation.Child {
    private static String draft = "";
    private static String draftMatch = "";
    private final GameScreen parent;
    private final DecisionNavigation.Context origin;
    private boolean sent;

    DecisionChatScreen(GameScreen parent, boolean command) {
        super(initial(command));
        this.parent = parent;
        this.origin = parent.decisionContext();
    }

    private static String initial(boolean command) {
        String match = DecisionNavigation.view().experience().matchId();
        if (!match.equals(draftMatch)) {
            draft = "";
            draftMatch = match;
        }
        return command ? "/" : draft;
    }

    @Override public DecisionNavigation.Context decisionContext() { return origin; }

    /** 底下那一面（还回得去时）；那个决定已经过去了（超时 · 轮次走了）就是 {@code null}，聊天照常画在世界之上。 */
    GameScreen faceBelow() {
        if (client == null || client.world == null) {
            return null;
        }
        return parent.canReturnTo(GameComponents.of(client.world).hudView()) ? parent : null;
    }

    @Override protected void init() {
        // 输入框的宽在建的时候就定了（ChatSkin#fieldSpan）：先把这一角报给皮肤，再让 Minecraft 建
        GameScreen face = faceBelow();
        if (face != null && (face.width != width || face.height != height)) {
            face.init(client, width, height);    // 聊天开着时改了窗口：底下那一面跟着重排
        }
        ChatSkin.faceRoom(face == null ? null : face.chatRoom());
        super.init();
    }

    @Override public void sendMessage(String message, boolean addToHistory) {
        super.sendMessage(message, addToHistory);
        sent = true;
        draft = "";
    }

    @Override public void removed() {
        if (!sent && chatField != null) {
            draft = chatField.getText();
        }
        ChatSkin.faceRoom(null);
        super.removed();
    }

    @Override public boolean keyPressed(int key, int scan, int modifiers) {
        boolean handled = super.keyPressed(key, scan, modifiers);
        if (client != null && client.currentScreen == null) {
            DecisionNavigation.back(parent, origin);
        }
        return handled;
    }

    @Override public void close() { DecisionNavigation.back(parent, origin); }

    @Override public void render(DrawContext context, int x, int y, float delta) {
        GameScreen face = faceBelow();
        if (face != null) {
            face.renderUnderChat(context, delta);
        } else {
            ChatSkin.faceRoom(null);              // 那一面中途过去了：聊天页回到平常的大小
        }
        super.render(context, x, y, delta);
        if (face == null) {
            DecisionNavigation.drawPending(context, origin);   // 那一面就在底下时它自己的倒计时看得见，不再挂一枚
        }
    }
}
