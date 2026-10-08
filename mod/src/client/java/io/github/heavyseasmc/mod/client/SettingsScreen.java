package io.github.heavyseasmc.mod.client;

import io.github.heavyseasmc.mod.config.LocalSettings;
import io.github.heavyseasmc.mod.config.ServerSettingsTable;
import io.github.heavyseasmc.mod.config.SettingDef;
import io.github.heavyseasmc.mod.config.SettingsCategory;
import io.github.heavyseasmc.mod.net.ServerSettingsS2C;
import io.github.heavyseasmc.mod.state.HudView;
import io.github.heavyseasmc.mod.ui.HudLayout.Rect;
import io.github.heavyseasmc.mod.ui.HudPart;
import io.github.heavyseasmc.mod.ui.SettingsLayout;
import io.github.heavyseasmc.mod.ui.SettingsMenuState;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 设置菜单（ADR-0099 版式 A：账本式）：左栏分组签、签下面是指着的那一项的说明签；右栏一行一项，控件按类型挑；
 * 右上角在只读时挂「只读 · 非管理员」的牌。与对局各面同一副骨架（板 · 上带 · 最下面一行按键提示），同一套材质与字。
 *
 * <h2>这一面只翻译、只画</h2>
 * 「只发改过的键」「只读时不发包」「密钥不露值」「有改动时 Esc 先问一次」都在 {@link SettingsMenuState} 里、有单测；
 * 版面（不重叠 · 不出界 · 说明签放得下 · 选中的那一行滚进视野）在 {@link SettingsLayout} 里、有单测。
 * 这里只把按键与鼠标翻成那边的调用，再照那边的状态画 —— 看得见的只有实拍才验得了。
 *
 * <h2>从哪进来</h2>
 * Mod Menu 的设置按钮（{@link ModMenuEntry}，标题画面也能开 —— 那时只有「本机」改得了）· 对局里 {@code /seas config}。
 * 关的时候回到打开它的那一面（Mod Menu 的模组列表）；从世界里开的照常合上。
 */
public final class SettingsScreen extends GameScreen {

    /** 关的时候回到哪（Mod Menu 的模组列表）；从世界里开的是 {@code null}。 */
    private final Screen returnTo;
    private final SettingsMenuState state;
    /** 这一帧的版面（物理像素）；鼠标命中按它判。 */
    private SettingsLayout layout;
    private int scroll;
    /** 正拖着哪一行的液位管（当前组里的下标）；没在拖是 -1。 */
    private int dragging = -1;

    public SettingsScreen(Screen returnTo) {
        super(Text.translatable("heavyseas.settings.title"));
        this.returnTo = returnTo;
        List<SettingDef> defs = new ArrayList<>(LocalSettings.ROWS);
        defs.addAll(ServerSettingsTable.DEFAULT.all());       // 密钥也在里面：那一类只画「设了没有」
        this.state = new SettingsMenuState(defs, ClientPrefs.menuAccess(), ServerSettingsClient.outbox());
        snapshotArrived();
        ServerSettingsClient.takeDraft().ifPresent(state::resume);   // 上次被决策面顶掉时没存的改动
    }

    /** 被换掉：自己关的已经在 {@link #close} 里清空了改动；被决策面顶掉的，没存的改动留成草稿。 */
    @Override
    public void removed() {
        super.removed();
        state.draft().ifPresent(ServerSettingsClient::keepDraft);
    }

    /** 服务端发来新快照（{@link ServerSettingsClient} 收到时调）：没进存档 = 服务端那几组只看不改。 */
    void snapshotArrived() {
        var client = net.minecraft.client.MinecraftClient.getInstance();
        Optional<ServerSettingsS2C> snap = ServerSettingsClient.latest();
        boolean connected = client.world != null && snap.isPresent();
        state.snapshot(connected, snap.map(ServerSettingsS2C::canEdit).orElse(false),
                snap.map(ServerSettingsS2C::values).orElse(Map.of()),
                snap.map(s -> Set.copyOf(s.secretsSet())).orElse(Set.of()));
    }

    // ------------------------------------------------------------------ 画

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackdrop(context, mouseX, mouseY, delta);
        long now = System.currentTimeMillis();
        frameDelta(now);
        Bands b = drawChrome(context, projection());
        var window = (client == null ? net.minecraft.client.MinecraftClient.getInstance() : client).getWindow();
        layout = SettingsLayout.of(window.getFramebufferWidth(), window.getFramebufferHeight(), GuiText::linePxAt,
                state.categories().size(), state.rows().size());
        scroll = state.layout(layout.rowsVisible());

        // 鼠标真的动了才把焦点带过去（停着的指针不算指向，GameScreen#mouseActuallyMoved）；输字时不抢焦点
        if (mouseActuallyMoved(mouseX, mouseY) && state.editing().isEmpty() && dragging < 0) {
            int row = rowAt(mouseX, mouseY);
            if (row >= 0) {
                state.focusRow(row);
            }
        }

        pxBegin(context);
        drawTags(context);
        drawDetail(context);
        drawHeader(context);
        drawRows(context, now);
        pxEnd(context);
        drawFootBand(context, b, hints(), List.of(), now, null);
    }

    /** 上带写这一面的标题（没有回合与阶段可写）。 */
    @Override
    protected void drawTopBand(DrawContext context, HudView view, Bands b) {
        drawLine(context, title, width / 2, b.topY(), GuiLanguage.ink());
    }

    /** 没有座位轨：中间那一块整个归左右两栏。 */
    @Override
    protected void drawRailBand(DrawContext context, HudView view, Bands b) {
    }

    private double k() {
        return layout.sheet().k();
    }

    private void drawTags(DrawContext context) {
        List<SettingsCategory> cats = state.categories();
        int first = layout.tagScroll(state.categoryIndex());
        int px = layout.len(SettingsLayout.TAG_PX);
        for (int i = 0; i < layout.tagsVisible() && first + i < cats.size(); i++) {
            Rect r = layout.tag(i);
            boolean selected = first + i == state.categoryIndex();
            GuiMaterial.hudPart(context, selected ? HudPart.BTN_FOCUS : HudPart.BTN, r.x(), r.y(), r.w(), r.h(), k());
            int pad = layout.padX() * 2;
            GuiText.drawPx(context, categoryName(cats.get(first + i)).getString(), r.x() + pad,
                    r.y() + (r.h() - GuiText.linePxAt(px, false)) / 2, r.w() - 2 * pad, px, false,
                    GuiLanguage.onTag(GuiLanguage.ink()), GuiText.Align.LEFT, 0);
        }
    }

    /** 说明签：名字 · 一句说明 · 默认与范围 · 什么时候生效。放不下时先少给说明几行，再省掉后面的。 */
    private void drawDetail(DrawContext context) {
        SettingDef def = state.focused();
        int pad = layout.detailPad();
        Rect area = layout.detailArea();
        int textW = Math.max(1, area.w() - 2 * pad);
        int px = layout.len(SettingsLayout.DETAIL_PX);
        int line = layout.detailLine();
        String desc = Text.translatable("heavyseas.settings." + def.key() + ".desc").getString();
        List<String> meta = new ArrayList<>();
        meta.add(defaultLine(def).getString());
        meta.add(whenName(def.when()).getString());
        int budget = layout.detailMaxLines();
        int descLines = Math.max(0, Math.min(Math.max(1, budget - meta.size()),
                GuiText.paragraphLines(desc, textW, px, false, Math.max(1, budget - meta.size()))));
        int metaShown = Math.max(0, Math.min(meta.size(), budget - descLines));
        Rect sign = layout.detail(descLines + metaShown);
        if (sign.h() <= 0) {
            return;
        }
        GuiMaterial.hudPart(context, HudPart.ENAMEL, sign.x(), sign.y(), sign.w(), sign.h(), k());
        int ink = GuiLanguage.Hud.ENAMEL_LINE;
        int y = sign.y() + pad;
        int titlePx = layout.len(SettingsLayout.DETAIL_TITLE_PX);
        GuiText.drawPx(context, rowName(def).getString(), sign.x() + pad, y, textW, titlePx, false, ink, GuiText.Align.LEFT, 0);
        y += layout.detailTitleLine() + layout.detailGap();
        if (descLines > 0) {
            GuiText.paragraphPx(context, desc, sign.x() + pad, y, textW, px, false, ink, descLines, line);
            y += descLines * line;
        }
        for (int i = 0; i < metaShown; i++) {
            GuiText.drawPx(context, meta.get(i), sign.x() + pad, y, textW, px, false, GuiLanguage.Hud.alpha(ink, 0.72f),
                    GuiText.Align.LEFT, 0);
            y += line;
        }
    }

    /** 右栏顶上那一行：状态（左）· 只读时的锁牌（右头）。 */
    private void drawHeader(DrawContext context) {
        int px = layout.len(SettingsLayout.NOTE_PX);
        Rect s = layout.status();
        SettingsMenuState.Notice notice = state.notice();
        // 服务端拒了这一次存盘：说没存上、为什么（审查 2026-10-07 U2：原先被拒的存盘照样显示「已保存」）
        boolean rejected = notice == SettingsMenuState.Notice.NONE && state.rejection().isPresent();
        Text text = rejected
                ? Text.translatable("heavyseas.settings.notice.rejected", state.rejection().get())
                : noticeText(notice);
        if (text != null) {
            int color = rejected ? GuiLanguage.cinnabar() : switch (notice) {
                case CONFIRM_DISCARD -> GuiLanguage.cinnabar();
                case SENT, SAVED -> GuiLanguage.verdigris();
                default -> GuiLanguage.muted();
            };
            GuiText.drawPx(context, text.getString(), s.x(), s.y() + (s.h() - GuiText.linePxAt(px, false)) / 2, s.w(), px,
                    false, color, GuiText.Align.LEFT, 0);
        }
        if (state.access() == SettingsMenuState.Access.READ_ONLY) {
            Rect lock = layout.lockTag();
            GuiMaterial.hudPart(context, HudPart.TAG, lock.x(), lock.y(), lock.w(), lock.h(), k());
            int lockPx = layout.len(SettingsLayout.LOCK_PX);
            GuiText.drawPx(context, Text.translatable("heavyseas.settings.lock").getString(), lock.x() + layout.padX(),
                    lock.y() + (lock.h() - GuiText.linePxAt(lockPx, false)) / 2, lock.w() - 2 * layout.padX(), lockPx, false,
                    LOCK_INK, GuiText.Align.CENTER, 0);
        }
    }

    /** 锁牌上的字：那块牌是深色的签（与座位上「昏」签同一件），字用纸色。 */
    private static final int LOCK_INK = 0xFFEFE6CF;

    private void drawRows(DrawContext context, long now) {
        List<SettingDef> rows = state.rows();
        int rule = GuiLanguage.Hud.alpha(GuiLanguage.ink(), 0.16f);
        for (int i = 0; i < layout.rowsVisible() && scroll + i < rows.size(); i++) {
            int index = scroll + i;
            SettingDef def = rows.get(index);
            Rect r = layout.row(i);
            context.fill(r.x(), r.bottom() - 1, r.right(), r.bottom(), rule);
            if (index == state.focusIndex()) {
                GuiMaterial.hudPart(context, HudPart.CARD_SEL, r.x(), r.y(), r.w(), r.h(), k());
            }
            boolean editable = state.editable(def);
            int ink = editable ? GuiLanguage.ink() : withAlpha(GuiLanguage.ink(), DISABLED_TEXT_ALPHA);
            int px = layout.len(SettingsLayout.ROW_PX);
            Rect name = layout.name(r);
            GuiText.drawPx(context, rowName(def).getString(), name.x(), name.y(), name.w(), px, false, ink,
                    GuiText.Align.LEFT, 0);
            drawNote(context, def, layout.note(r));
            drawWidget(context, def, layout.widget(r), editable, now);
        }
        if (rows.size() > layout.rowsVisible()) {
            Rect track = layout.scrollTrack();
            context.fill(track.x(), track.y(), track.right(), track.bottom(), GuiLanguage.Hud.alpha(GuiLanguage.ink(), 0.10f));
            Rect thumb = layout.scrollThumb(scroll);
            context.fill(thumb.x(), thumb.y(), thumb.right(), thumb.bottom(), GuiLanguage.Hud.alpha(GuiLanguage.ink(), 0.45f));
        }
    }

    /** 名字下面那一小行：在输字（不合规矩时标红）· 改过没存（朱砂点 + 一句）。 */
    private void drawNote(DrawContext context, SettingDef def, Rect note) {
        int px = layout.len(SettingsLayout.NOTE_PX);
        boolean editingThis = state.editing().map(def::equals).orElse(false);
        Text text;
        int color;
        if (editingThis && state.editInvalid()) {
            text = Text.translatable("heavyseas.settings.note.invalid");
            color = GuiLanguage.cinnabar();
        } else if (editingThis) {
            text = Text.translatable("heavyseas.settings.note.editing");
            color = GuiLanguage.muted();
        } else if (state.changed(def)) {
            text = Text.translatable("heavyseas.settings.note.changed");
            color = GuiLanguage.cinnabar();
        } else {
            return;
        }
        int dot = Math.max(2, layout.len(6));
        int line = GuiText.linePxAt(px, false);
        int x = note.x();
        if (color == GuiLanguage.cinnabar() && !editingThis) {
            context.fill(x, note.y() + (line - dot) / 2, x + dot, note.y() + (line + dot) / 2, color);
            x += dot + layout.len(6);
        }
        GuiText.drawPx(context, text.getString(), x, note.y(), Math.max(1, note.right() - x), px, false, color,
                GuiText.Align.LEFT, 0);
    }

    private void drawWidget(DrawContext context, SettingDef def, Rect w, boolean editable, long now) {
        int px = layout.len(SettingsLayout.WIDGET_PX);
        int textTop = w.y() + (w.h() - GuiText.linePxAt(px, false)) / 2;
        float alpha = editable ? 1f : DISABLED_PART;
        int enamelInk = editable ? GuiLanguage.onTag(GuiLanguage.ink()) : withAlpha(GuiLanguage.onTag(GuiLanguage.ink()), DISABLED_TEXT_ALPHA);
        Optional<String> value = state.display(def);
        boolean editingThis = state.editing().map(def::equals).orElse(false);
        switch (def.kind()) {
            case FLAG -> {
                Rect[] seg = layout.toggle(w);
                boolean on = value.map("true"::equals).orElse(false);
                for (int i = 0; i < 2; i++) {
                    boolean picked = value.isPresent() && (i == 1) == on;
                    context.setShaderColor(1f, 1f, 1f, picked ? alpha : alpha * DISABLED_PART);
                    GuiMaterial.hudPart(context, HudPart.BTN, seg[i].x(), seg[i].y(), seg[i].w(), seg[i].h(), k());
                    context.setShaderColor(1f, 1f, 1f, 1f);
                    int color = !picked ? withAlpha(GuiLanguage.onTag(GuiLanguage.muted()), DISABLED_TEXT_ALPHA)
                            : i == 1 ? GuiLanguage.onTag(GuiLanguage.verdigris()) : enamelInk;
                    GuiText.drawPx(context, Text.translatable(i == 1 ? "heavyseas.settings.on" : "heavyseas.settings.off").getString(),
                            seg[i].x(), textTop, seg[i].w(), px, false, color, GuiText.Align.CENTER, 0);
                }
            }
            case CHOICE -> {
                Rect[] parts = layout.cycle(w);
                context.setShaderColor(1f, 1f, 1f, alpha);
                GuiMaterial.hudPart(context, HudPart.KEY, parts[0].x(), parts[0].y(), parts[0].w(), parts[0].h(), k());
                GuiMaterial.hudPart(context, HudPart.BTN, parts[1].x(), parts[1].y(), parts[1].w(), parts[1].h(), k());
                GuiMaterial.hudPart(context, HudPart.KEY, parts[2].x(), parts[2].y(), parts[2].w(), parts[2].h(), k());
                context.setShaderColor(1f, 1f, 1f, 1f);
                int keyInk = editable ? GuiLanguage.Hud.ENAMEL_LINE : withAlpha(GuiLanguage.Hud.ENAMEL_LINE, DISABLED_TEXT_ALPHA);
                GuiText.drawPx(context, "←", parts[0].x(), textTop, parts[0].w(), px, true, keyInk, GuiText.Align.CENTER, 0);
                GuiText.drawPx(context, "→", parts[2].x(), textTop, parts[2].w(), px, true, keyInk, GuiText.Align.CENTER, 0);
                String label = value.map(v -> Text.translatable("heavyseas.settings." + def.key() + "." + v).getString())
                        .orElse(DASH);
                GuiText.drawPx(context, label, parts[1].x() + layout.padX(), textTop, parts[1].w() - 2 * layout.padX(), px,
                        false, enamelInk, GuiText.Align.CENTER, 0);
            }
            case SECONDS, INT, DECIMAL -> {
                Rect[] parts = layout.gauge(w);
                String shown = editingThis ? state.editText() + caret(now) : value.map(v -> withUnit(def, v)).orElse(DASH);
                int numberInk = editingThis && state.editInvalid() ? GuiLanguage.cinnabar()
                        : editable ? GuiLanguage.ink() : withAlpha(GuiLanguage.ink(), DISABLED_TEXT_ALPHA);
                GuiText.drawPx(context, shown, parts[0].x(), textTop, parts[0].w(), px, false, numberInk,
                        GuiText.Align.RIGHT, 0);
                Rect bar = parts[1];
                double frac = value.map(v -> fraction(def, v)).orElse(0.0);
                context.setShaderColor(1f, 1f, 1f, alpha);
                GuiMaterial.hudPart(context, HudPart.COUNT_BAR, bar.x(), bar.y(), bar.w(), bar.h(), k());
                GuiMaterial.hudPartLeft(context, HudPart.COUNT_FILL, bar.x(), bar.y(), bar.w(), bar.h(),
                        (int) Math.round(bar.w() * frac), k());
                context.setShaderColor(1f, 1f, 1f, 1f);
            }
            case TEXT -> {
                Rect f = layout.field(w);
                context.setShaderColor(1f, 1f, 1f, alpha);
                GuiMaterial.hudPart(context, editingThis ? HudPart.BTN_FOCUS : HudPart.BTN, f.x(), f.y(), f.w(), f.h(), k());
                context.setShaderColor(1f, 1f, 1f, 1f);
                String shown;
                int color = enamelInk;
                if (def.secret()) {
                    // ❗密钥只有状态，没有字：输的时候也只画同样多的星号
                    shown = editingThis ? "*".repeat(Math.min(32, state.editMask())) + caret(now)
                            : secretText(state.secretState(def)).getString();
                } else if (editingThis) {
                    shown = state.editText() + caret(now);
                } else {
                    shown = value.filter(v -> !v.isEmpty()).orElse(null);
                    if (shown == null) {
                        shown = value.isPresent() ? Text.translatable("heavyseas.settings.empty").getString() : DASH;
                        color = withAlpha(GuiLanguage.onTag(GuiLanguage.muted()), DISABLED_TEXT_ALPHA);
                    }
                }
                GuiText.drawPx(context, shown, f.x() + layout.padX(), textTop, f.w() - 2 * layout.padX(), px, false, color,
                        GuiText.Align.LEFT, 0);
            }
        }
    }

    /** 按不动的控件淡到多少（底与字一起淡，与行动一面按不动的那几件同一个样子）。 */
    private static final float DISABLED_PART = 0.45f;
    private static final String DASH = "—";

    private static String caret(long now) {
        return (now / 500) % 2 == 0 ? "|" : " ";
    }

    private static double fraction(SettingDef def, String value) {
        try {
            double v = Double.parseDouble(value);
            return def.max() <= def.min() ? 0 : Math.max(0, Math.min(1, (v - def.min()) / (def.max() - def.min())));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ------------------------------------------------------------------ 字

    private static Text rowName(SettingDef def) {
        return Text.translatable("heavyseas.settings." + def.key());
    }

    /** 数值带单位（「20 秒」）。 */
    private static String withUnit(SettingDef def, String value) {
        return switch (def.unit()) {
            case SECONDS -> Text.translatable("heavyseas.settings.unit.seconds", value).getString();
            case MILLISECONDS -> Text.translatable("heavyseas.settings.unit.milliseconds", value).getString();
            case NONE -> value;
        };
    }

    /** 说明签上「默认 · 范围」那一行（密钥没有默认，只说最长几个字）。 */
    private static Text defaultLine(SettingDef def) {
        return switch (def.kind()) {
            case SECONDS, INT, DECIMAL -> Text.translatable("heavyseas.settings.detail.range_default",
                    withUnit(def, def.formattedFallback()), bound(def, def.min()), bound(def, def.max()));
            case FLAG -> Text.translatable("heavyseas.settings.detail.default", Text.translatable(
                    Boolean.TRUE.equals(def.fallback()) ? "heavyseas.settings.on" : "heavyseas.settings.off"));
            case CHOICE -> Text.translatable("heavyseas.settings.detail.default",
                    Text.translatable("heavyseas.settings." + def.key() + "." + def.fallback()));
            case TEXT -> def.secret() ? Text.translatable("heavyseas.settings.detail.length", def.maxLength())
                    : Text.translatable("heavyseas.settings.detail.default", def.formattedFallback().isEmpty()
                    ? Text.translatable("heavyseas.settings.empty") : Text.literal(def.formattedFallback()));
        };
    }

    /** 范围的一头按这一项的写法印（整数不带「.0」）。 */
    private static String bound(SettingDef def, double v) {
        return def.kind() == SettingDef.Kind.DECIMAL ? def.format(v) : def.format((int) v);
    }

    static Text categoryName(SettingsCategory category) {
        return Text.translatable(switch (category) {
            case LOCAL -> "heavyseas.settings.category.local";
            case WINDOWS -> "heavyseas.settings.category.windows";
            case PACING -> "heavyseas.settings.category.pacing";
            case STAND_INS -> "heavyseas.settings.category.stand_ins";
            case SMART -> "heavyseas.settings.category.smart";
            case LLM -> "heavyseas.settings.category.llm";
        });
    }

    private static Text whenName(SettingDef.When when) {
        return Text.translatable(switch (when) {
            case NEXT_GAME -> "heavyseas.settings.when.next_game";
            case SERVER_START -> "heavyseas.settings.when.server_start";
            case IMMEDIATE -> "heavyseas.settings.when.immediate";
        });
    }

    private static Text secretText(SettingsMenuState.SecretState s) {
        return Text.translatable(switch (s) {
            case SET -> "heavyseas.settings.secret.set";
            case UNSET -> "heavyseas.settings.secret.unset";
            case PENDING_SET -> "heavyseas.settings.secret.pending_set";
            case PENDING_CLEAR -> "heavyseas.settings.secret.pending_clear";
        });
    }

    private static Text noticeText(SettingsMenuState.Notice notice) {
        return switch (notice) {
            case NONE -> null;
            case CONFIRM_DISCARD -> Text.translatable("heavyseas.settings.notice.confirm");
            case OFFLINE -> Text.translatable("heavyseas.settings.notice.offline");
            case SENT -> Text.translatable("heavyseas.settings.notice.sent");
            case SAVED -> Text.translatable("heavyseas.settings.notice.saved");
            case NOTHING_TO_SAVE -> Text.translatable("heavyseas.settings.notice.nothing");
        };
    }

    /** 最下面那一行按键提示：只给此刻按得动的那几件。 */
    private List<KeyHint> hints() {
        if (state.editing().isPresent()) {
            return List.of(primary("accept", "Enter"), keys("cancel", "Esc"), keys("paste", "Ctrl", "V"));
        }
        if (state.confirming()) {
            return List.of(primary("save", "S"), keys("discard", "Esc"));
        }
        List<KeyHint> out = new ArrayList<>();
        out.add(keys("back", "Esc"));
        out.add(keys("group", "Tab"));
        if (state.editable(state.focused())) {
            out.add(keys("change", "Enter"));
            out.add(keys("restore", "R"));
        }
        if (state.access() == SettingsMenuState.Access.EDITABLE) {
            out.add(state.hasPending() ? primary("save", "S") : keys("save", "S"));
        }
        return out;
    }

    // ------------------------------------------------------------------ 按键与鼠标

    @Override
    protected boolean onKey(int keyCode, int scanCode, int modifiers) {
        boolean shift = (modifiers & GLFW.GLFW_MOD_SHIFT) != 0;
        if (state.editing().isPresent()) {
            // 输字的时候：Enter 收下、Esc 不要、退格、Ctrl+V 粘贴；别的键一律吞掉 —— E（背包键）不许把界面关了，R · S 不许当成快捷键
            switch (keyCode) {
                case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> state.commitEdit();
                case GLFW.GLFW_KEY_ESCAPE -> state.cancelEdit();
                case GLFW.GLFW_KEY_BACKSPACE -> state.backspace();
                case GLFW.GLFW_KEY_V -> {
                    if ((modifiers & GLFW.GLFW_MOD_CONTROL) != 0 && client != null) {
                        state.paste(client.keyboard.getClipboard());
                    }
                }
                default -> {
                }
            }
            return true;
        }
        switch (keyCode) {
            case GLFW.GLFW_KEY_UP -> state.moveFocus(-1);
            case GLFW.GLFW_KEY_DOWN -> state.moveFocus(+1);
            case GLFW.GLFW_KEY_PAGE_UP -> state.moveFocus(-Math.max(1, visibleRows()));
            case GLFW.GLFW_KEY_PAGE_DOWN -> state.moveFocus(Math.max(1, visibleRows()));
            case GLFW.GLFW_KEY_LEFT -> state.step(-1, shift);
            case GLFW.GLFW_KEY_RIGHT -> state.step(+1, shift);
            case GLFW.GLFW_KEY_TAB -> state.nextCategory(shift ? -1 : +1);
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER, GLFW.GLFW_KEY_SPACE -> state.activate();
            case GLFW.GLFW_KEY_R -> state.restoreDefault();
            case GLFW.GLFW_KEY_S -> {
                boolean wasConfirming = state.confirming();
                state.save();
                if (wasConfirming && !state.hasPending()) {
                    close();                          // 问「存不存」时按 S：存了就走
                }
            }
            default -> {
                return super.onKey(keyCode, scanCode, modifiers);
            }
        }
        return true;
    }

    @Override
    public boolean charTyped(char chr, int modifiers) {
        if (state.editing().isPresent()) {
            state.type(chr);
            return true;
        }
        return super.charTyped(chr, modifiers);
    }

    /**
     * 关：在输字就只退出输字；有攒着没存的改动先问一次（右栏顶上一行「S 存 · Esc 不存」）；
     * 从 Mod Menu 进来的回到模组列表，从世界里开的照常合上。
     */
    @Override
    public void close() {
        if (!state.requestClose()) {
            return;
        }
        if (returnTo != null && client != null) {
            client.setScreen(returnTo);
            return;
        }
        super.close();
    }

    /**
     * 窗口小于支持尺寸时按 Esc（审查 2026-10-07 Z6）：整面换成「窗口太小」，右栏顶上那一问「S 存 · Esc 不存」看不见、S 也被拦下 ——
     * 原先第一下 Esc 进了看不见的「问存不存」，第二下就把改动静默扔掉。改成<b>留草稿再关</b>：不经 {@link #requestClose}，
     * 没存的服务端改动由 {@link #removed} 留成草稿（与被决策面顶掉同一条路），放大窗口再开设置会接回来。
     */
    @Override
    void escapeInSmallWindow() {
        if (state.editing().isPresent()) {
            state.cancelEdit();               // 输到一半的字不留（与被顶掉时一样）；已经收下的改动照留
        }
        if (returnTo != null && client != null) {
            client.setScreen(returnTo);
            return;
        }
        super.close();                        // GameScreen#close：合上再收，removed() 里留草稿
    }

    private int visibleRows() {
        return layout == null ? 1 : layout.rowsVisible();
    }

    /** 物理像素：鼠标给的是 GUI 单位。 */
    private int px(double gui) {
        return (int) Math.floor(gui * guiScale());
    }

    private static boolean inside(Rect r, int x, int y) {
        return x >= r.x() && x < r.right() && y >= r.y() && y < r.bottom();
    }

    /** 指针在第几行（当前组里的下标）；不在任何一行上是 -1。 */
    private int rowAt(double mouseX, double mouseY) {
        if (layout == null) {
            return -1;
        }
        int x = px(mouseX);
        int y = px(mouseY);
        for (int i = 0; i < layout.rowsVisible() && scroll + i < state.rows().size(); i++) {
            if (inside(layout.row(i), x, y)) {
                return scroll + i;
            }
        }
        return -1;
    }

    @Override
    protected boolean leftClick(double mouseX, double mouseY) {
        if (layout == null) {
            return false;
        }
        int x = px(mouseX);
        int y = px(mouseY);
        int first = layout.tagScroll(state.categoryIndex());
        for (int i = 0; i < layout.tagsVisible() && first + i < state.categories().size(); i++) {
            if (inside(layout.tag(i), x, y)) {
                state.selectCategory(first + i);
                return true;
            }
        }
        int row = rowAt(mouseX, mouseY);
        if (row < 0) {
            return false;
        }
        state.focusRow(row);
        SettingDef def = state.rows().get(row);
        Rect w = layout.widget(layout.row(row - scroll));
        switch (def.kind()) {
            case FLAG -> {
                Rect[] seg = layout.toggle(w);
                if (inside(seg[0], x, y)) {
                    state.set(def, "false");
                } else if (inside(seg[1], x, y)) {
                    state.set(def, "true");
                }
            }
            case CHOICE -> {
                Rect[] parts = layout.cycle(w);
                if (inside(parts[0], x, y)) {
                    state.step(-1, false);
                } else if (inside(parts[1], x, y) || inside(parts[2], x, y)) {
                    state.step(+1, false);
                }
            }
            case SECONDS, INT, DECIMAL -> {
                Rect[] parts = layout.gauge(w);
                if (inside(parts[1], x, y)) {
                    dragging = row;
                    state.setFraction(def, (x - parts[1].x()) / (double) Math.max(1, parts[1].w()));
                } else if (inside(parts[0], x, y) && state.editing().isEmpty()) {
                    state.activate();
                }
            }
            case TEXT -> {
                if (inside(layout.field(w), x, y) && state.editing().isEmpty()) {
                    state.activate();
                }
            }
        }
        return true;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        if (dragging >= 0 && layout != null && dragging - scroll >= 0 && dragging - scroll < layout.rowsVisible()
                && dragging < state.rows().size()) {
            SettingDef def = state.rows().get(dragging);
            Rect bar = layout.gauge(layout.widget(layout.row(dragging - scroll)))[1];
            state.setFraction(def, (px(mouseX) - bar.x()) / (double) Math.max(1, bar.w()));
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        dragging = -1;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    /** 滚轮在右栏上：滚那一列（不挪焦点）；别处照对局各面的老规矩（钉住日志时翻日志）。 */
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (layout != null && verticalAmount != 0 && inside(layout.right(), px(mouseX), px(mouseY))) {
            state.scrollBy(verticalAmount > 0 ? -1 : 1, layout.rowsVisible());
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    /** 从标题画面（Mod Menu）开的时候没有世界：这一面照样值得回来。 */
    @Override
    protected boolean stillWanted() {
        return true;
    }
}
