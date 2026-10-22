package io.github.heavyseasmc.mod.client;

import io.github.heavyseasmc.mod.HeavySeasMod;
import io.github.heavyseasmc.mod.rulebook.Rulebook;
import io.github.heavyseasmc.mod.state.HudView;
import io.github.heavyseasmc.mod.ui.SheetLayout;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 规则书的自家书页（2026-10-19，CURRENT_STATUS P3 第 3 条；样张 B「侧栏目录 + 单页」，HeavySeas-internal/evidence/2026-10-19-rulebook-pages）：
 * 对局各面那一块板上，左边一栏是目录（每一章 · 当前那一章的小节），右边一栏是正文一页；上带写书名，最下面一栏照各面的规矩写按键
 * （翻页 · 换章 · 收起），从某一面打开时那一面的倒计时照样在那一栏里。原来是 Minecraft 自带的书页（114 像素宽、一页 14 行、目录占页）。
 *
 * <p>版面按物理像素排（{@link RulebookLayout}，字照 {@link GuiText} 的阅读字号梯子，「大字阅读」偏好照样生效）；窗口一变就重排，
 * 停在原来那一页所在的章。键：左右 / PageUp PageDown / 滚轮翻页 · 上下换章 · Home 回扉页 · Esc 收起（从某一面打开的回到那一面）。
 */
final class RulebookScreen extends GameScreen {

    private static final Logger LOGGER = LoggerFactory.getLogger(HeavySeasMod.MOD_ID);

    /** 稿子像素（随 {@link SheetLayout#k} 缩放）。第一次实拍（1280 × 720）正文只有 11 像素左右，比样张（16）小一圈 —— 一律放大约 1.4 倍。 */
    private static final double BODY_PX = 24;
    private static final double HEADING_PX = 27;
    private static final double CHAPTER_PX = 36;
    private static final double TITLE_PX = 44;
    private static final double SIDE_PX = 20;
    private static final double SUB_PX = 17;

    /** 版面（GUI 单位）：板里左右留白 · 两栏之间 · 栏内留白。 */
    private static final int MARGIN = 22;
    private static final int GUTTER = 12;
    private static final int PAD = 4;

    private List<Rulebook.Block> blocks;
    private String title;
    private String anchor;
    /** 翻的是搭建手册（方案 A14）而不是规则书：M 在两本之间换。 */
    private boolean manual;

    private RulebookLayout.Result layout;
    private int page;
    private int sideScroll;
    private final List<int[]> sideHits = new ArrayList<>();      // {x0, y0, x1, y1, 目标页}（GUI 单位）
    private int sideX, sideW, pageX, pageW, top, bottom;

    RulebookScreen(List<Rulebook.Block> blocks, String anchor) {
        super(Text.translatable("heavyseas.rulebook.title"));
        use(blocks);
        this.anchor = anchor;
    }

    private void use(List<Rulebook.Block> blocks) {
        this.blocks = blocks;
        this.title = blocks.stream().filter(b -> b instanceof Rulebook.TitlePage).map(b -> ((Rulebook.TitlePage) b).title())
                .findFirst().orElse("");
    }

    /** 换到另一本（规则书 ↔ 搭建手册），从扉页翻起。 */
    private void otherBook() {
        MinecraftClient client = MinecraftClient.getInstance();
        manual = !manual;
        use(manual ? RulebookBook.manual(client) : RulebookBook.rulebook(client));
        sideScroll = 0;
        relayout();
        page = 0;
    }

    @Override
    protected void init() {
        super.init();
        Bands b = bands();
        // 量板子里面那一块（物理像素的板框换成 GUI 单位），不量窗口：第一次实拍两栏挨着窗口边量，左边的章名、右边的正文都出了板
        int s = scale();
        var board = sheet().sheet();
        int left = Math.floorDiv(board.x(), s) + MARGIN;
        int right = Math.floorDiv(board.x() + board.w(), s) - MARGIN;
        int inner = right - left;
        sideW = Math.max(90, Math.min(150, (int) (inner * 0.28)));
        pageW = Math.min(inner - sideW - GUTTER, 380);
        sideX = left + Math.max(0, (inner - sideW - GUTTER - pageW) / 2);
        pageX = sideX + sideW + GUTTER;
        top = b.railY();                      // 座位轨不画：这一面与对局无关，那一截给书
        bottom = b.stageBottom();
        int keepChapter = layout == null ? -1 : layout.pages().get(page).chapter();
        relayout();
        if (anchor != null) {
            page = layout.anchors().getOrDefault(anchor, 0);
            anchor = null;
        } else if (keepChapter >= 0 && keepChapter < layout.chapters().size()) {
            page = layout.chapters().get(keepChapter).page();
        }
        page = Math.max(0, Math.min(page, layout.pages().size() - 1));
    }

    private int scale() {
        return Math.max(1, (int) Math.round(MinecraftClient.getInstance().getWindow().getScaleFactor()));
    }

    private void relayout() {
        int s = scale();
        SheetLayout l = sheet();
        int boxPx = (pageW - 2 * PAD) * s;
        int heightPx = (bottom - top - 2 * PAD) * s - GuiText.readingLinePx(l.len(SUB_PX), false);   // 底下留一行给页码
        RulebookLayout.Sizes sizes = new RulebookLayout.Sizes(l.len(TITLE_PX), l.len(CHAPTER_PX), l.len(HEADING_PX), l.len(BODY_PX));
        layout = RulebookLayout.layout(blocks, boxPx, Math.max(1, heightPx), sizes, METRICS);
        LOGGER.debug("Rulebook laid out: {} pages, {} chapters, box {}x{} px", layout.pages().size(), layout.chapters().size(),
                boxPx, heightPx);
    }

    /** 量字：照 {@link GuiText} 的阅读字号与折行（避头尾 · 数字与量词不拆）。 */
    private static final RulebookLayout.Metrics METRICS = new RulebookLayout.Metrics() {
        @Override
        public List<int[]> wrap(String text, boolean[] bold, int boxPx, int basePx) {
            if (text.isEmpty()) {
                return List.of(new int[]{0, 0});
            }
            List<int[]> out = new ArrayList<>();
            for (var span : GuiText.readingRunLinesPx(text, bold, new boolean[text.length()], boxPx, basePx)) {
                out.add(new int[]{span.start(), span.end()});
            }
            return out;
        }

        @Override
        public int lineStep(int basePx) {
            return GuiText.readingLinePx(basePx, false);
        }

        @Override
        public int width(String text, int basePx, boolean bold) {
            return GuiText.widthPx(text, GuiText.readingSizePx(basePx, bold), bold, 0);
        }
    };

    private void turn(int d) {
        page = Math.max(0, Math.min(layout.pages().size() - 1, page + d));
    }

    private void chapterStep(int d) {
        int cur = layout.pages().get(page).chapter();
        if (cur < 0) {
            if (d > 0 && !layout.chapters().isEmpty()) {
                page = layout.chapters().get(0).page();
            }
            return;
        }
        page = layout.chapters().get(Math.max(0, Math.min(layout.chapters().size() - 1, cur + d))).page();
    }

    @Override
    protected boolean onKey(int key, int scan, int modifiers) {
        switch (key) {
            case GLFW.GLFW_KEY_LEFT, GLFW.GLFW_KEY_PAGE_UP -> {
                turn(-1);
                return true;
            }
            case GLFW.GLFW_KEY_RIGHT, GLFW.GLFW_KEY_PAGE_DOWN -> {
                turn(1);
                return true;
            }
            case GLFW.GLFW_KEY_UP -> {
                chapterStep(-1);
                return true;
            }
            case GLFW.GLFW_KEY_DOWN -> {
                chapterStep(1);
                return true;
            }
            case GLFW.GLFW_KEY_HOME -> {
                page = 0;
                return true;
            }
            case GLFW.GLFW_KEY_M -> {
                otherBook();
                return true;
            }
            default -> {
                return super.onKey(key, scan, modifiers);
            }
        }
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double h, double v) {
        if (mx >= sideX && mx < sideX + sideW && my >= top && my < bottom) {
            sideScroll = Math.max(0, sideScroll - (int) Math.signum(v) * 12);
        } else {
            turn(v > 0 ? -1 : 1);
        }
        return true;
    }

    @Override
    protected boolean leftClick(double mx, double my) {
        for (int[] r : sideHits) {
            if (mx >= r[0] && mx < r[2] && my >= r[1] && my < r[3]) {
                page = r[4];
                return true;
            }
        }
        return super.leftClick(mx, my);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackdrop(context, mouseX, mouseY, delta);
        Bands b = drawChrome(context, projection());
        int s = scale();
        SheetLayout l = sheet();
        context.getMatrices().push();
        context.getMatrices().scale(1f / s, 1f / s, 1f);
        drawSidebar(context, s, l);
        drawPage(context, s, l);
        context.getMatrices().pop();
        drawFootBand(context, b, List.of(keys("page", "←", "→"), keys("chapter", "↑", "↓"), keys(manual ? "rulebook" : "manual", "M")),
                List.of(keys("close", "Esc")),
                System.currentTimeMillis(), null);
    }

    /** 上带写书名。 */
    @Override
    protected void drawTopBand(DrawContext context, HudView view, Bands b) {
        drawLine(context, title, paneW() / 2, b.topY(), GuiLanguage.ink());
    }

    /** 不画座位轨：这一面与对局无关（那一截给了书）。 */
    @Override
    protected void drawRailBand(DrawContext context, HudView view, Bands b) {
    }

    private void drawSidebar(DrawContext context, int s, SheetLayout l) {
        sideHits.clear();
        int x = (sideX + PAD) * s;
        int w = (sideW - 2 * PAD) * s;
        int listTop = (top + PAD) * s;
        int listBottom = (bottom - PAD) * s;
        // 两栏之间一道细线（照各面的分隔线：边框色）
        context.fill((sideX + sideW + GUTTER / 2) * s, top * s, (sideX + sideW + GUTTER / 2) * s + Math.max(1, s), bottom * s,
                GuiLanguage.rim());
        int current = layout.pages().get(page).chapter();
        int sidePx = GuiText.readingSizePx(l.len(SIDE_PX), false);
        int subPx = GuiText.readingSizePx(l.len(SUB_PX), false);
        int row = GuiText.readingLinePx(l.len(SIDE_PX), false);
        int subRow = GuiText.readingLinePx(l.len(SUB_PX), false);
        // 当前那一章要在看得见的范围里：往下滚到它连同小节露出来为止
        int want = Math.max(0, current) * row;
        int extra = current >= 0 ? layout.chapters().get(current).sections().size() * subRow : 0;
        int scroll = Math.max(sideScroll * s, Math.max(0, want + row + extra - (listBottom - listTop)));
        context.enableScissor(sideX, listTop / s, sideX + sideW, listBottom / s);    // 剪裁用 GUI 单位（不吃矩阵的缩放）
        int y = listTop - scroll;
        for (int c = 0; c < layout.chapters().size(); c++) {
            RulebookLayout.Chapter ch = layout.chapters().get(c);
            boolean on = c == current;
            GuiText.drawPx(context, ch.title(), x, y + (row - GuiText.linePxAt(sidePx, on)) / 2, w, sidePx, on,
                    on ? GuiLanguage.cinnabar() : GuiLanguage.ink(), GuiText.Align.LEFT, 0);
            hit(x / s, y / s, (x + w) / s, (y + row) / s, ch.page(), listTop / s, listBottom / s);
            y += row;
            if (on) {
                for (RulebookLayout.Section sec : ch.sections()) {
                    GuiText.drawPx(context, sec.title(), x + l.len(16), y + (subRow - GuiText.linePxAt(subPx, false)) / 2,
                            w - l.len(16), subPx, false, sec.page() == page ? GuiLanguage.cinnabar() : GuiLanguage.secondary(),
                            GuiText.Align.LEFT, 0);
                    hit(x / s, y / s, (x + w) / s, (y + subRow) / s, sec.page(), listTop / s, listBottom / s);
                    y += subRow;
                }
            }
        }
        context.disableScissor();
    }

    private void hit(int x0, int y0, int x1, int y1, int target, int clipTop, int clipBottom) {
        if (y1 > clipTop && y0 < clipBottom) {
            sideHits.add(new int[]{x0, Math.max(y0, clipTop), x1, Math.min(y1, clipBottom), target});
        }
    }

    private void drawPage(DrawContext context, int s, SheetLayout l) {
        RulebookLayout.Page p = layout.pages().get(page);
        int x0 = (pageX + PAD) * s;
        int boxPx = (pageW - 2 * PAD) * s;
        int y = (top + PAD) * s;
        for (RulebookLayout.Line line : p.lines()) {
            if (!line.text().isEmpty()) {
                int color = switch (line.kind()) {
                    case TITLE, CHAPTER -> GuiLanguage.cinnabar();
                    case SUBTITLE, MOTTO -> GuiLanguage.secondary();
                    default -> GuiLanguage.ink();
                };
                boolean centred = line.kind() == RulebookLayout.Kind.TITLE || line.kind() == RulebookLayout.Kind.SUBTITLE
                        || line.kind() == RulebookLayout.Kind.MOTTO;
                int x = x0 + line.indentPx();
                if (centred) {
                    int w = METRICS.width(line.text(), line.basePx(), line.bold().length > 0 && line.bold()[0]);
                    x = x0 + Math.max(0, (boxPx - w) / 2);
                }
                if (line.marker() != null) {
                    int mw = METRICS.width(line.marker(), line.basePx(), false);
                    GuiText.drawReadingRunPx(context, line.marker(), fill(line.marker().length(), GuiLanguage.ink()),
                            new boolean[line.marker().length()], x - mw, y, line.basePx());
                }
                GuiText.drawReadingRunPx(context, line.text(), fill(line.text().length(), color), line.bold(), x, y, line.basePx());
            }
            y += line.stepPx();
        }
        // 页码：正文那一栏右下角
        String folio = (page + 1) + " / " + layout.pages().size();
        int fpx = GuiText.readingSizePx(l.len(SUB_PX), false);
        GuiText.drawPx(context, folio, x0, (bottom - PAD) * s - GuiText.linePxAt(fpx, false), boxPx, fpx, false,
                GuiLanguage.secondary(), GuiText.Align.RIGHT, 0);
    }

    private static int[] fill(int n, int color) {
        int[] out = new int[n];
        java.util.Arrays.fill(out, color);
        return out;
    }

    /** 测试与日志用：现在在第几页 / 共几页 / 第几章。 */
    String state() {
        return "page " + (page + 1) + "/" + layout.pages().size() + " chapter " + layout.pages().get(page).chapter();
    }
}
