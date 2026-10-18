package io.github.heavyseasmc.mod.client;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;
import java.util.List;
import java.util.function.Supplier;

/** A readable, scrollable explanation. It inherits its parent's decision and live clock. */
final class DecisionNotesScreen extends GameScreen {
    private final Supplier<Text> content;
    private int firstLine;
    private int lastLine;
    DecisionNotesScreen(Text title, Supplier<Text> content) {
        super(title);
        this.content = content;
    }
    @Override public void tick() { if (!projection().active()) close(); }
    @Override public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackdrop(context, mouseX, mouseY, delta);
        Bands b = drawChrome(context, projection());
        int x = SIDE + PLATE_MARGIN;
        int room = paneW() - 2 * x;
        int line = GuiText.readingLineHeight(GuiText.BODY, false);
        int count = Math.max(1, (b.stageBottom() - b.stageTop() - line) / line);
        String text = content.get().getString();
        lastLine = Math.max(0, (GuiText.readingHeight(text, room, GuiText.BODY, false) + line - 1) / line - count);
        firstLine = Math.min(firstLine, lastLine);
        GuiText.drawReading(context, text, x, b.stageTop(), room, GuiText.BODY, false,
                GuiLanguage.ink(), firstLine, count);
        drawFootBand(context, b, List.of(keys("read_scroll", "↑", "↓")), List.of(keys("close", "Esc")),
                System.currentTimeMillis(), null);
    }
    private static final int PLATE_MARGIN = 12;
    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (vertical != 0) { firstLine = Math.max(0, Math.min(lastLine, firstLine + (vertical > 0 ? -2 : 2))); return true; }
        return super.mouseScrolled(x, y, horizontal, vertical);
    }
    @Override protected boolean onKey(int key, int scan, int modifiers) {
        if (key == GLFW.GLFW_KEY_DOWN || key == GLFW.GLFW_KEY_UP) {
            firstLine = Math.max(0, Math.min(lastLine, firstLine + (key == GLFW.GLFW_KEY_DOWN ? 1 : -1)));
            return true;
        }
        return super.onKey(key, scan, modifiers);
    }
}
