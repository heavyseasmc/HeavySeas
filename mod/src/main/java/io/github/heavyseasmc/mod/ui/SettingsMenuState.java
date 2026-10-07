package io.github.heavyseasmc.mod.ui;

import io.github.heavyseasmc.mod.config.SettingDef;
import io.github.heavyseasmc.mod.config.SettingsCategory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 设置菜单的状态与操作（ADR-0099 版式 A）：哪一组签、指着哪一行、改了什么还没存、在不在输字、要不要问「存不存」。
 * 纯逻辑，不碰 Minecraft 的界面类 —— 界面（{@code SettingsScreen}）只把按键与鼠标翻译成这里的调用，再照这里的状态画；
 * 「只发改过的键」「只读时不发包」「密钥不露值」「有改动时 Esc 先问一次」都在这里，单测直接测。
 *
 * <h2>两类行</h2>
 * <ul>
 *   <li><b>本机</b>（{@link SettingsCategory#LOCAL}）：改了当场经 {@link LocalPrefs} 生效、存盘（与 F8 · H 一样），不攒。</li>
 *   <li><b>服务端</b>：改了先攒着（「已改，未保存」），按 S 一次发出去 —— 只发和服务端此刻的值不一样的那几项；
 *       密钥另走一条只写的包（{@link Outbox#secret}），一项一包。</li>
 * </ul>
 *
 * <h2>能不能改</h2>
 * 服务端那几组看 {@link Access}：没进存档（{@link Access#OFFLINE}，从标题画面的 Mod Menu 进来）只看不改、显示「进存档后可改」；
 * 快照说不能改（{@link Access#READ_ONLY}）只看不改、挂「只读 · 非管理员」的牌，S 一个包都不发。
 *
 * <h2>密钥</h2>
 * 这里<b>从不持有服务端存着的值</b>（快照里本来就没有）；只记「设了没有」与这一次打算设的新值（攒着、按 S 发）。
 * {@link #display} 对密钥只给状态，不给字；输字的时候界面也只画同样多的圆点（{@link #editMask}）。
 */
public final class SettingsMenuState {

    /** 本机那一组的读写口：客户端用 {@code ClientPrefs} 实现（改了当场生效、存盘），单测用假的。 */
    public interface LocalPrefs {

        /** 这一项此刻的值（{@link SettingDef#format} 过的字符串）。 */
        String get(String key);

        /** 设新值：当场生效、当场存盘。 */
        void set(String key, String value);
    }

    /** 发包口：客户端用网络实现，单测记下来。 */
    public interface Outbox {

        /** 一批改动（只含改过的键）。 */
        void save(Map<String, String> changes);

        /** 设一项密钥；空串 = 清掉。 */
        void secret(String key, String value);
    }

    /** 服务端那几组此刻能不能改。 */
    public enum Access { EDITABLE, READ_ONLY, OFFLINE }

    /** 密钥那一行显示什么（永远没有值）。 */
    public enum SecretState { SET, UNSET, PENDING_SET, PENDING_CLEAR }

    /** 右栏顶上那一行说什么。 */
    public enum Notice { NONE, CONFIRM_DISCARD, OFFLINE, SENT, SAVED, NOTHING_TO_SAVE }

    /** 按 S 的结果。 */
    public enum SaveResult { SENT, NOTHING, NOT_ALLOWED }

    private final Map<SettingsCategory, List<SettingDef>> rows = new EnumMap<>(SettingsCategory.class);
    private final List<SettingsCategory> categories;
    private final LocalPrefs local;
    private final Outbox outbox;

    private boolean connected;
    private boolean canEdit;
    private Map<String, String> base = Map.of();
    private Set<String> secretsSet = Set.of();
    /** 攒着的服务端改动：键 → 新值（与 {@link #base} 不同的才留着）。 */
    private final Map<String, String> pending = new LinkedHashMap<>();
    /** 攒着的密钥：键 → 新值；空串 = 清掉。 */
    private final Map<String, String> pendingSecrets = new LinkedHashMap<>();

    private int category;
    private int focus;
    private int scroll;
    /** 键盘挪过焦点：下一次排版时把它滚进视野。鼠标滚轮不设它（滚轮只滚，不把视野拽回焦点）。 */
    private boolean followFocus = true;

    /** 正在输字的那一项；没在输时为 {@code null}。 */
    private SettingDef editing;
    private final StringBuilder buffer = new StringBuilder();
    private boolean editInvalid;

    private boolean confirming;
    private Notice notice = Notice.NONE;

    /**
     * @param defs 全部行（本机的与服务端的；密钥也在里面）。先后就是各组里的先后
     */
    public SettingsMenuState(List<SettingDef> defs, LocalPrefs local, Outbox outbox) {
        this.local = Objects.requireNonNull(local, "local");
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        for (SettingDef def : defs) {
            rows.computeIfAbsent(def.category(), c -> new ArrayList<>()).add(def);
        }
        List<SettingsCategory> present = new ArrayList<>();
        for (SettingsCategory c : SettingsCategory.values()) {
            if (!rows.getOrDefault(c, List.of()).isEmpty()) {
                present.add(c);                          // 一项都没有的组不画签（动脑替身 · 大模型等第三刀）
            }
        }
        this.categories = List.copyOf(present);
        if (categories.isEmpty()) {
            throw new IllegalArgumentException("一项设置都没有");
        }
    }

    // ---------------------------------------------------------------- 服务端快照

    /**
     * 服务端此刻的值。{@code connected = false} = 没进存档（标题画面的 Mod Menu）：服务端那几组只看不改。
     * 攒着的改动里与新值相同的丢掉（别人刚好存了同一个值）；改不了了（被撤了管理员）就整个丢掉 —— 存不出去的改动留着只会骗人。
     */
    public void snapshot(boolean connected, boolean canEdit, Map<String, String> values, Set<String> secretsSet) {
        this.connected = connected;
        this.canEdit = canEdit;
        this.base = Collections.unmodifiableMap(new LinkedHashMap<>(values));
        this.secretsSet = Set.copyOf(secretsSet);
        pending.entrySet().removeIf(e -> e.getValue().equals(base.get(e.getKey())));
        if (access() != Access.EDITABLE) {
            pending.clear();
            pendingSecrets.clear();
            confirming = false;
            if (editing != null && editing.category().server()) {
                cancelEdit();
            }
        }
        if (notice == Notice.SENT) {
            notice = Notice.SAVED;
        }
    }

    public Access access() {
        return !connected ? Access.OFFLINE : canEdit ? Access.EDITABLE : Access.READ_ONLY;
    }

    // ---------------------------------------------------------------- 组与行

    public List<SettingsCategory> categories() {
        return categories;
    }

    public int categoryIndex() {
        return category;
    }

    public SettingsCategory category() {
        return categories.get(category);
    }

    /** 当前这一组的行。 */
    public List<SettingDef> rows() {
        return rows.get(category());
    }

    public int focusIndex() {
        return focus;
    }

    public SettingDef focused() {
        return rows().get(focus);
    }

    public int scroll() {
        return scroll;
    }

    public void selectCategory(int index) {
        int next = Math.floorMod(index, categories.size());
        touch();
        if (next != category) {
            cancelEdit();
            category = next;
            focus = 0;
            scroll = 0;
            followFocus = true;
        }
    }

    public void nextCategory(int direction) {
        selectCategory(category + (direction < 0 ? -1 : 1));
    }

    public void moveFocus(int delta) {
        touch();
        cancelEdit();
        focus = Math.max(0, Math.min(rows().size() - 1, focus + delta));
        followFocus = true;
    }

    /** 鼠标指到 / 点到第 {@code index} 行（当前组里的下标）。 */
    public void focusRow(int index) {
        if (index < 0 || index >= rows().size()) {
            return;
        }
        if (index != focus) {
            cancelEdit();
        }
        focus = index;
    }

    /** 滚轮：只滚视野，不挪焦点。 */
    public void scrollBy(int delta, int visible) {
        scroll = clampScroll(scroll + delta, visible);
        followFocus = false;
    }

    /**
     * 每一帧排版时调：这一屏放得下 {@code visible} 行。键盘挪过焦点就把焦点滚进视野；滚动量一律夹在合法范围里。
     *
     * @return 此刻的滚动量（第一行显示的是第几行）
     */
    public int layout(int visible) {
        if (followFocus) {
            scroll = keepVisible(focus, scroll, visible, rows().size());
            followFocus = false;
        }
        scroll = clampScroll(scroll, visible);
        return scroll;
    }

    private int clampScroll(int s, int visible) {
        return Math.max(0, Math.min(Math.max(0, rows().size() - Math.max(1, visible)), s));
    }

    /** 让第 {@code focus} 行落在 {@code [scroll, scroll + visible)} 里的最小滚动。 */
    public static int keepVisible(int focus, int scroll, int visible, int count) {
        int v = Math.max(1, visible);
        int s = scroll;
        if (focus < s) {
            s = focus;
        } else if (focus >= s + v) {
            s = focus - v + 1;
        }
        return Math.max(0, Math.min(Math.max(0, count - v), s));
    }

    // ---------------------------------------------------------------- 读

    /** 这一行此刻改得了吗。 */
    public boolean editable(SettingDef def) {
        return !def.category().server() || access() == Access.EDITABLE;
    }

    /**
     * 这一行此刻显示的值（{@link SettingDef#format} 过的字符串）：本机的取本机；服务端的先看攒着的、再看快照。
     * 没进存档时服务端那几组没有值（空）；<b>密钥永远是空</b> —— 那一类看 {@link #secretState}。
     */
    public Optional<String> display(SettingDef def) {
        if (def.secret()) {
            return Optional.empty();
        }
        if (!def.category().server()) {
            return Optional.ofNullable(local.get(def.key()));
        }
        String pendingValue = pending.get(def.key());
        if (pendingValue != null) {
            return Optional.of(pendingValue);
        }
        return Optional.ofNullable(base.get(def.key()));
    }

    /** 改过、还没存（「已改，未保存」）。 */
    public boolean changed(SettingDef def) {
        return def.secret() ? pendingSecrets.containsKey(def.key()) : pending.containsKey(def.key());
    }

    public SecretState secretState(SettingDef def) {
        String next = pendingSecrets.get(def.key());
        if (next != null) {
            return next.isEmpty() ? SecretState.PENDING_CLEAR : SecretState.PENDING_SET;
        }
        return secretsSet.contains(def.key()) ? SecretState.SET : SecretState.UNSET;
    }

    /** 有没有攒着没存的改动（服务端那几组）。 */
    public boolean hasPending() {
        return !pending.isEmpty() || !pendingSecrets.isEmpty();
    }

    public Notice notice() {
        if (confirming) {
            return Notice.CONFIRM_DISCARD;
        }
        if (notice == Notice.NONE && access() == Access.OFFLINE && category().server()) {
            return Notice.OFFLINE;
        }
        return notice;
    }

    public boolean confirming() {
        return confirming;
    }

    // ---------------------------------------------------------------- 改

    /**
     * ← → ：开关选关 / 开；选项往前 / 往后（转圈）；数值减 / 加一步（{@code big} = 十步），夹在范围里。文字与密钥没有这一下。
     *
     * @return 改了没有
     */
    public boolean step(int direction, boolean big) {
        touch();
        SettingDef def = focused();
        if (editing != null || !editable(def)) {
            return false;
        }
        Optional<String> now = display(def);
        if (now.isEmpty()) {
            return false;
        }
        String next = switch (def.kind()) {
            case FLAG -> direction < 0 ? "false" : "true";
            case CHOICE -> {
                int at = Math.max(0, def.choices().indexOf(now.get()));
                yield def.choices().get(Math.floorMod(at + (direction < 0 ? -1 : 1), def.choices().size()));
            }
            case SECONDS, INT, DECIMAL -> stepped(def, now.get(), direction, big);
            case TEXT -> null;
        };
        return next != null && put(def, next);
    }

    private static String stepped(SettingDef def, String now, int direction, boolean big) {
        BigDecimal step = BigDecimal.valueOf(def.step()).multiply(BigDecimal.valueOf(big ? 10 : 1));
        BigDecimal value = new BigDecimal(now).add(direction < 0 ? step.negate() : step);
        return clampAndFormat(def, value);
    }

    /** 数值夹进范围、对齐到步长的格点、按这一项的写法格式化。 */
    static String clampAndFormat(SettingDef def, BigDecimal value) {
        BigDecimal min = BigDecimal.valueOf(def.min());
        BigDecimal max = BigDecimal.valueOf(def.max());
        BigDecimal step = BigDecimal.valueOf(def.step());
        BigDecimal snapped = value.subtract(min).divide(step, 0, RoundingMode.HALF_UP).multiply(step).add(min);
        BigDecimal clamped = snapped.max(min).min(max);
        return def.kind() == SettingDef.Kind.DECIMAL
                ? def.format(clamped.doubleValue())
                : def.format(clamped.setScale(0, RoundingMode.HALF_UP).intValueExact());
    }

    /** 液位管上点 / 拖到 {@code fraction}（0 = 下限，1 = 上限）。 */
    public boolean setFraction(SettingDef def, double fraction) {
        touch();
        if (!def.numeric() || !editable(def) || editing != null) {
            return false;
        }
        double f = Math.max(0, Math.min(1, fraction));
        BigDecimal value = BigDecimal.valueOf(def.min() + f * (def.max() - def.min()));
        return put(def, clampAndFormat(def, value));
    }

    /** 开关：直接点在「关」或「开」上。选项：直接定成某一个。 */
    public boolean set(SettingDef def, String value) {
        touch();
        return editable(def) && editing == null && put(def, value);
    }

    /**
     * Enter：开关换一下；选项往后一个；数值 · 文字 · 密钥开始输字（数值与文字从此刻的值起，密钥从空起 —— 存着的值这里本来就没有）。
     *
     * @return 处理了没有
     */
    public boolean activate() {
        touch();
        SettingDef def = focused();
        if (editing != null) {
            return commitEdit();
        }
        if (!editable(def)) {
            return false;
        }
        switch (def.kind()) {
            case FLAG -> {
                return display(def).map(v -> put(def, v.equals("true") ? "false" : "true")).orElse(false);
            }
            case CHOICE -> {
                return step(+1, false);
            }
            default -> {
                editing = def;
                buffer.setLength(0);
                if (!def.secret()) {
                    buffer.append(display(def).orElse(""));
                }
                editInvalid = false;
                return true;
            }
        }
    }

    /**
     * R：恢复默认。本机的当场设回去；服务端的攒成「改回默认」；密钥攒成「清掉」（它的默认就是没设）。
     */
    public boolean restoreDefault() {
        touch();
        SettingDef def = focused();
        if (!editable(def)) {
            return false;
        }
        cancelEdit();
        if (def.secret()) {
            if (secretsSet.contains(def.key())) {
                pendingSecrets.put(def.key(), "");
            } else {
                pendingSecrets.remove(def.key());
            }
            return true;
        }
        return put(def, def.formattedFallback());
    }

    /** 记下一个新值：先按这一项的规矩核，不合就不收。 */
    private boolean put(SettingDef def, String value) {
        SettingDef.Parsed parsed = def.parse(value);
        if (!parsed.ok()) {
            return false;
        }
        String formatted = def.format(parsed.value());
        if (def.secret()) {
            if (formatted.isEmpty() && !secretsSet.contains(def.key())) {
                pendingSecrets.remove(def.key());     // 没设过又「清掉」：什么都不用发
            } else {
                pendingSecrets.put(def.key(), formatted);
            }
            return true;
        }
        if (!def.category().server()) {
            local.set(def.key(), formatted);
            return true;
        }
        if (formatted.equals(base.get(def.key()))) {
            pending.remove(def.key());               // 改回了服务端此刻的值：不算改动
        } else {
            pending.put(def.key(), formatted);
        }
        return true;
    }

    // ---------------------------------------------------------------- 输字

    public Optional<SettingDef> editing() {
        return Optional.ofNullable(editing);
    }

    /** 输到一半的字（<b>密钥不给</b>：那一类用 {@link #editMask}）。 */
    public String editText() {
        return editing == null || editing.secret() ? "" : buffer.toString();
    }

    /** 密钥输到一半时画几个圆点（与输入同样长，不露字）。 */
    public int editMask() {
        return editing != null && editing.secret() ? buffer.codePointCount(0, buffer.length()) : 0;
    }

    public boolean editInvalid() {
        return editInvalid;
    }

    /** 输一个字。数值只收数字、小数点与负号；控制字符一律不收；超过这一项的长度上限不再收。 */
    public void type(int codePoint) {
        if (editing == null || codePoint < 0x20 || codePoint == 0x7F) {
            return;
        }
        if (editing.numeric() && !(Character.isDigit(codePoint) || codePoint == '.' || codePoint == '-')) {
            return;
        }
        int limit = editing.kind() == SettingDef.Kind.TEXT ? editing.maxLength() : 24;
        if (buffer.codePointCount(0, buffer.length()) >= limit) {
            return;
        }
        buffer.appendCodePoint(codePoint);
        editInvalid = false;
    }

    /** 粘贴（API 密钥这类长串要靠它）：一个字一个字照 {@link #type} 收，换行之类的控制字符自然丢掉。 */
    public void paste(String text) {
        if (text == null) {
            return;
        }
        text.codePoints().forEach(this::type);
    }

    public void backspace() {
        if (editing == null || buffer.length() == 0) {
            return;
        }
        int end = buffer.length();
        buffer.delete(buffer.offsetByCodePoints(end, -1), end);
        editInvalid = false;
    }

    /** Enter：收下输的字；不合规矩就留在输字里、标红（「取值不对」）。 */
    public boolean commitEdit() {
        if (editing == null) {
            return false;
        }
        SettingDef def = editing;
        if (!put(def, buffer.toString())) {
            editInvalid = true;
            return true;
        }
        cancelEdit();
        return true;
    }

    /** Esc（输字时）：不要了，回到原来的值。 */
    public void cancelEdit() {
        editing = null;
        buffer.setLength(0);
        editInvalid = false;
    }

    // ---------------------------------------------------------------- 存与关

    /**
     * S：把攒着的发出去 —— 普通的一包、只含改过的键；密钥一项一包。改不了（只读 · 没进存档）时一个包都不发。
     */
    public SaveResult save() {
        touch();
        if (access() != Access.EDITABLE) {
            return SaveResult.NOT_ALLOWED;
        }
        if (editing != null) {
            commitEdit();
            if (editing != null) {
                return SaveResult.NOTHING;            // 输的字不合规矩：先改对
            }
        }
        if (!hasPending()) {
            notice = Notice.NOTHING_TO_SAVE;
            return SaveResult.NOTHING;
        }
        Map<String, String> changes = new LinkedHashMap<>();
        pending.forEach((key, value) -> {
            if (!value.equals(base.get(key))) {
                changes.put(key, value);
            }
        });
        if (!changes.isEmpty()) {
            outbox.save(Collections.unmodifiableMap(changes));
        }
        pendingSecrets.forEach(outbox::secret);
        pending.clear();
        pendingSecrets.clear();
        notice = Notice.SENT;
        return SaveResult.SENT;
    }

    /**
     * Esc：在输字就先只退出输字；有攒着没存的改动就先问一次（「S 存 · Esc 不存」）；问过再按就扔掉、关。
     *
     * @return 这一下之后该不该真的关
     */
    public boolean requestClose() {
        if (editing != null) {
            cancelEdit();
            return false;
        }
        if (hasPending() && !confirming) {
            confirming = true;
            return false;
        }
        pending.clear();
        pendingSecrets.clear();
        confirming = false;
        return true;
    }

    // ---------------------------------------------------------------- 被顶掉时的草稿

    /**
     * 菜单被别的界面顶掉时留下的东西：对局里自动弹出的决策面会直接换掉当前界面，不经过 {@link #requestClose}。
     * 留的是服务端那几组攒着没存的值，与当时在哪一组哪一行；由客户端收着，下次开菜单接回去。
     *
     * <p>❗密钥不留：输到一半的密钥不该在菜单之外多活一刻（值不出这个类）。
     */
    public record Draft(Map<String, String> pending, int category, int focus) {
        public Draft {
            pending = Collections.unmodifiableMap(new LinkedHashMap<>(pending));
        }
    }

    /** 有没存的服务端改动就留一份草稿。自己关掉的（存了 · 不存）在 {@link #requestClose} 里已经清空，这里给空。 */
    public Optional<Draft> draft() {
        return pending.isEmpty() ? Optional.empty() : Optional.of(new Draft(pending, category, focus));
    }

    /**
     * 接回草稿 —— 在 {@link #snapshot} 之后调：与服务端此刻相同的值不算改动，改不了的整份不接；
     * 表里已经没有的键、密钥、解析不了的值逐项丢掉。草稿是上一个界面留的，不能比快照更可信。
     */
    public void resume(Draft draft) {
        if (access() != Access.EDITABLE) {
            return;
        }
        Map<String, SettingDef> byKey = new LinkedHashMap<>();
        rows.forEach((cat, defs) -> {
            if (cat.server()) {
                defs.stream().filter(d -> !d.secret()).forEach(d -> byKey.put(d.key(), d));
            }
        });
        draft.pending().forEach((key, value) -> {
            SettingDef def = byKey.get(key);
            if (def != null && def.parse(value).ok() && !value.equals(base.get(key))) {
                pending.put(key, value);
            }
        });
        if (draft.category() >= 0 && draft.category() < categories.size()) {
            selectCategory(draft.category());
            focusRow(Math.min(draft.focus(), rows().size() - 1));
            followFocus = true;
        }
    }

    /** 问「存不存」的时候按了别的：不问了，回到菜单。 */
    private void touch() {
        confirming = false;
        if (notice == Notice.SAVED || notice == Notice.NOTHING_TO_SAVE) {
            notice = Notice.NONE;
        }
    }

    /** 攒着的改动（单测与调试用）。 */
    public Map<String, String> pendingChanges() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(pending));
    }

    /** 攒着的密钥改动只给键（单测用）：值不出这个类。 */
    public Set<String> pendingSecretKeys() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(pendingSecrets.keySet()));
    }
}
