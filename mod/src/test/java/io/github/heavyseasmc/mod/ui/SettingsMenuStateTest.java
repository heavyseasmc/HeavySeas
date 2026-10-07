package io.github.heavyseasmc.mod.ui;

import io.github.heavyseasmc.mod.config.LocalSettings;
import io.github.heavyseasmc.mod.config.ServerSettingsTable;
import io.github.heavyseasmc.mod.config.SettingDef;
import io.github.heavyseasmc.mod.config.SettingsCategory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 设置菜单的行为（ADR-0099 D6 · 版式 A）：只发改过的键 · 只读与没进存档时一个包都不发 · 本机的改了当场经 ClientPrefs 存 ·
 * 密钥不露值 · 有改动时 Esc 先问一次。界面只翻译按键、照这里的状态画，所以行为在这里测。
 */
final class SettingsMenuStateTest {

    private static final String SECRET_KEY = "llm.api_key";
    private static final String TYPED = "sk-typed-secret-0123";

    /** 本机那一组的假读写口：记下每一次写。 */
    private static final class FakeLocal implements SettingsMenuState.LocalPrefs {
        final Map<String, String> values = new HashMap<>(Map.of(
                LocalSettings.THEME, LocalSettings.THEME_LIGHT,
                LocalSettings.VANILLA_HUD, "false",
                LocalSettings.LEGEND, "true"));
        final List<String> writes = new ArrayList<>();

        @Override
        public String get(String key) {
            return values.get(key);
        }

        @Override
        public void set(String key, String value) {
            values.put(key, value);
            writes.add(key + "=" + value);
        }
    }

    /** 假发包口：记下发了什么。 */
    private static final class FakeOutbox implements SettingsMenuState.Outbox {
        final List<Map<String, String>> saves = new ArrayList<>();
        final List<String> secrets = new ArrayList<>();

        @Override
        public void save(Map<String, String> changes) {
            saves.add(Map.copyOf(changes));
        }

        @Override
        public void secret(String key, String value) {
            secrets.add(key + (value.isEmpty() ? " 清" : " 设"));
            lastSecretValue = value;
        }

        String lastSecretValue;
    }

    private final FakeLocal local = new FakeLocal();
    private final FakeOutbox outbox = new FakeOutbox();

    private static List<SettingDef> defs() {
        List<SettingDef> defs = new ArrayList<>(LocalSettings.ROWS);
        defs.addAll(ServerSettingsTable.DEFAULT.all());          // 「大模型」一组里带着密钥那一项（SECRET_KEY）
        return defs;
    }

    /** 服务端此刻的值：全是默认值。 */
    private static Map<String, String> serverDefaults() {
        Map<String, String> values = new LinkedHashMap<>();
        for (SettingDef def : ServerSettingsTable.DEFAULT.synced()) {
            values.put(def.key(), def.formattedFallback());
        }
        return values;
    }

    private SettingsMenuState state(boolean connected, boolean canEdit) {
        SettingsMenuState s = new SettingsMenuState(defs(), local, outbox);
        s.snapshot(connected, canEdit, connected ? serverDefaults() : Map.of(), Set.of());
        return s;
    }

    private static void select(SettingsMenuState s, SettingsCategory category, String key) {
        s.selectCategory(s.categories().indexOf(category));
        int row = s.rows().stream().map(SettingDef::key).toList().indexOf(key);
        assertTrue(row >= 0, key + " 不在 " + category);
        s.focusRow(row);
    }

    @Test
    @DisplayName("签：六组都有行、都画（动脑替身与大模型两组也露面了），本机在最前")
    void onlyCategoriesWithRows() {
        SettingsMenuState s = state(true, true);
        assertEquals(List.of(SettingsCategory.LOCAL, SettingsCategory.WINDOWS, SettingsCategory.PACING,
                SettingsCategory.STAND_INS, SettingsCategory.SMART, SettingsCategory.LLM), s.categories());
        // 正向对照：一组没有行就没有签（判据不是把枚举原样抄了一遍）
        List<SettingDef> withoutSmart = defs().stream().filter(d -> d.category() != SettingsCategory.SMART).toList();
        assertFalse(new SettingsMenuState(withoutSmart, local, outbox).categories().contains(SettingsCategory.SMART));
    }

    @Test
    @DisplayName("存盘包只含改过的键：改了又改回去的不发，没改的不发")
    void onlyChangedKeysAreSent() {
        SettingsMenuState s = state(true, true);
        select(s, SettingsCategory.WINDOWS, ServerSettingsTable.ACTION);
        assertTrue(s.step(+1, false));                       // 60 → 61
        assertTrue(s.changed(s.focused()));
        assertTrue(s.step(-1, false));                       // 61 → 60：改回去了
        assertFalse(s.changed(s.focused()), "改回服务端此刻的值：不算改动（不该挂「已改，未保存」）");
        select(s, SettingsCategory.WINDOWS, ServerSettingsTable.HELM);
        assertTrue(s.step(+1, true));                        // 20 → 30
        select(s, SettingsCategory.STAND_INS, ServerSettingsTable.FILL_SEATS);
        assertTrue(s.activate());                            // false → true
        assertTrue(s.changed(s.focused()));
        assertEquals(SettingsMenuState.SaveResult.SENT, s.save());
        assertEquals(List.of(Map.of(ServerSettingsTable.HELM, "30", ServerSettingsTable.FILL_SEATS, "true")), outbox.saves);
        assertFalse(s.hasPending());
        // 服务端回了新快照（值已经是新的）：「已发出」变成「已保存」
        Map<String, String> after = serverDefaults();
        after.put(ServerSettingsTable.HELM, "30");
        after.put(ServerSettingsTable.FILL_SEATS, "true");
        s.snapshot(true, true, after, Set.of());
        assertEquals(SettingsMenuState.Notice.SAVED, s.notice());
        assertEquals(SettingsMenuState.SaveResult.NOTHING, s.save(), "没有改动时一个包都不发");
        assertEquals(1, outbox.saves.size());
    }

    @Test
    @DisplayName("只读（非管理员）与没进存档：服务端那几组改不动、S 一个包都不发")
    void readOnlyAndOfflineSendNothing() {
        for (boolean connected : new boolean[]{true, false}) {
            SettingsMenuState s = state(connected, false);
            assertEquals(connected ? SettingsMenuState.Access.READ_ONLY : SettingsMenuState.Access.OFFLINE, s.access());
            select(s, SettingsCategory.WINDOWS, ServerSettingsTable.ACTION);
            assertFalse(s.editable(s.focused()));
            assertFalse(s.step(+1, false));
            assertFalse(s.activate());
            assertFalse(s.restoreDefault());
            assertFalse(s.setFraction(s.focused(), 1.0));
            assertEquals(SettingsMenuState.SaveResult.NOT_ALLOWED, s.save());
        }
        assertEquals(List.of(), outbox.saves);
        assertEquals(List.of(), outbox.secrets);
        SettingsMenuState offline = state(false, false);
        select(offline, SettingsCategory.WINDOWS, ServerSettingsTable.ACTION);
        assertEquals(SettingsMenuState.Notice.OFFLINE, offline.notice(), "没进存档：说「进存档后可改」");
        assertTrue(offline.display(offline.focused()).isEmpty(), "没进存档时服务端那几组没有值可显示（不拿默认值冒充）");
    }

    @Test
    @DisplayName("本机那一组：改了当场经 ClientPrefs 生效、存盘，不攒、不发包；没进存档也改得了")
    void localEditsApplyImmediately() {
        SettingsMenuState s = state(false, false);
        select(s, SettingsCategory.LOCAL, LocalSettings.LEGEND);
        assertTrue(s.editable(s.focused()));
        assertTrue(s.activate());                            // 图例 true → false
        select(s, SettingsCategory.LOCAL, LocalSettings.THEME);
        assertTrue(s.step(+1, false));                       // light → dark
        select(s, SettingsCategory.LOCAL, LocalSettings.VANILLA_HUD);
        assertTrue(s.step(+1, false));                       // 关 → 开
        assertTrue(s.restoreDefault());                      // 开 → 默认（关）
        assertEquals(List.of(LocalSettings.LEGEND + "=false", LocalSettings.THEME + "=dark",
                LocalSettings.VANILLA_HUD + "=true", LocalSettings.VANILLA_HUD + "=false"), local.writes);
        assertFalse(s.hasPending(), "本机的改动不攒");
        assertTrue(s.requestClose(), "没有攒着的改动：Esc 直接关");
        assertEquals(List.of(), outbox.saves);
    }

    @Test
    @DisplayName("密钥：显示只有状态、输的时候只有圆点数，按 S 才经密钥包发出去；R 是清掉")
    void secretsNeverExposeAValue() {
        SettingsMenuState s = state(true, true);
        select(s, SettingsCategory.LLM, SECRET_KEY);
        SettingDef def = s.focused();
        assertEquals(SettingsMenuState.SecretState.UNSET, s.secretState(def));
        assertTrue(s.activate());                            // 开始输
        s.paste(TYPED + "\n");                               // 换行这种控制字符自然丢掉
        assertEquals("", s.editText(), "密钥输到一半也不给字");
        assertEquals(TYPED.length(), s.editMask());
        assertTrue(s.commitEdit());
        assertTrue(s.display(def).isEmpty(), "密钥没有「显示的值」");
        assertEquals(SettingsMenuState.SecretState.PENDING_SET, s.secretState(def));
        assertTrue(s.changed(def));
        assertEquals(List.of(), outbox.secrets, "按 S 之前一个包都不发");
        assertEquals(SettingsMenuState.SaveResult.SENT, s.save());
        assertEquals(List.of(SECRET_KEY + " 设"), outbox.secrets);
        assertEquals(TYPED, outbox.lastSecretValue, "发出去的就是输的那一串（一字不差）");
        assertEquals(List.of(), outbox.saves, "密钥不进普通的存盘包");
        // 服务端回快照：只说「设了」
        s.snapshot(true, true, serverDefaults(), Set.of(SECRET_KEY));
        assertEquals(SettingsMenuState.SecretState.SET, s.secretState(def));
        assertTrue(s.restoreDefault());
        assertEquals(SettingsMenuState.SecretState.PENDING_CLEAR, s.secretState(def));
        s.save();
        assertEquals(List.of(SECRET_KEY + " 设", SECRET_KEY + " 清"), outbox.secrets);
    }

    @Test
    @DisplayName("Esc：在输字先只退出输字；有改动先问一次（S 存 · Esc 不存）；问过再按就扔掉、关；问的时候按别的就不问了")
    void escapeWithPendingAsksOnce() {
        SettingsMenuState s = state(true, true);
        select(s, SettingsCategory.WINDOWS, ServerSettingsTable.THIRST);
        assertTrue(s.activate());                            // 数值开始输字
        assertFalse(s.requestClose(), "第一下 Esc 只退出输字");
        assertTrue(s.editing().isEmpty());
        assertTrue(s.step(+1, false));                       // 攒一个改动
        assertFalse(s.requestClose(), "有改动：先问");
        assertEquals(SettingsMenuState.Notice.CONFIRM_DISCARD, s.notice());
        s.moveFocus(+1);                                     // 问的时候按了别的：不问了
        assertFalse(s.confirming());
        assertFalse(s.requestClose(), "再按 Esc 又先问");
        assertTrue(s.requestClose(), "问过再按：扔掉、关");
        assertFalse(s.hasPending());
        assertEquals(List.of(), outbox.saves, "扔掉的改动一个都没发");

        SettingsMenuState t = state(true, true);
        select(t, SettingsCategory.WINDOWS, ServerSettingsTable.THIRST);
        t.step(+1, false);
        assertFalse(t.requestClose());
        assertEquals(SettingsMenuState.SaveResult.SENT, t.save(), "问的时候按 S：存");
        assertTrue(t.requestClose(), "存完再关不再问");
        assertEquals(List.of(Map.of(ServerSettingsTable.THIRST, "21")), outbox.saves);
    }

    @Test
    @DisplayName("输字：只收合规矩的；不合就标红留在输字里；被撤了管理员就把攒着的扔掉")
    void typingIsValidatedAndPermissionLossDropsPending() {
        SettingsMenuState s = state(true, true);
        select(s, SettingsCategory.WINDOWS, ServerSettingsTable.ACTION);
        assertTrue(s.activate());
        for (int i = 0; i < 3; i++) {
            s.backspace();
        }
        s.paste("9x");                                       // 字母不收；9 秒低于下限 10
        assertEquals("9", s.editText());
        assertTrue(s.commitEdit());
        assertTrue(s.editInvalid(), "越界：标红、留在输字里");
        s.backspace();
        s.paste("45");
        assertTrue(s.commitEdit());
        assertTrue(s.editing().isEmpty());
        assertEquals("45", s.display(s.focused()).orElseThrow());
        assertTrue(s.hasPending());
        s.snapshot(true, false, serverDefaults(), Set.of());  // 被 deop
        assertFalse(s.hasPending(), "存不出去的改动不留着骗人");
        assertEquals("60", s.display(s.focused()).orElseThrow());
    }

    @Test
    @DisplayName("数值：一步一格、夹在范围里；液位管点哪是哪（对齐步长）")
    void numbersStepAndClamp() {
        SettingsMenuState s = state(true, true);
        select(s, SettingsCategory.PACING, ServerSettingsTable.REVEAL_HOLD);     // 默认 3，范围 0–30
        for (int i = 0; i < 5; i++) {
            s.step(-1, false);
        }
        assertEquals("0", s.display(s.focused()).orElseThrow(), "夹在下限");
        assertTrue(s.setFraction(s.focused(), 0.5));
        assertEquals("15", s.display(s.focused()).orElseThrow());
        assertTrue(s.setFraction(s.focused(), 2.0));
        assertEquals("30", s.display(s.focused()).orElseThrow(), "夹在上限");
        SettingDef decimal = SettingDef.decimal(SettingsCategory.LLM, "llm.share", 0.55, 0.3, 1.0, 0.05,
                SettingDef.When.IMMEDIATE, "share");
        assertEquals("0.6", SettingsMenuState.clampAndFormat(decimal, new java.math.BigDecimal("0.58")));
        assertEquals("1", SettingsMenuState.clampAndFormat(decimal, new java.math.BigDecimal("1.3")));
        assertEquals("0.3", SettingsMenuState.clampAndFormat(decimal, new java.math.BigDecimal("-2")));
    }

    @Test
    @DisplayName("滚动：键盘挪到哪就把哪一行滚进视野；滚轮只滚、不把视野拽回焦点")
    void focusIsKeptInView() {
        SettingsMenuState s = state(true, true);
        s.selectCategory(s.categories().indexOf(SettingsCategory.WINDOWS));   // 14 行
        int visible = 4;
        for (int i = 0; i < 13; i++) {
            s.moveFocus(+1);
            int scroll = s.layout(visible);
            assertTrue(s.focusIndex() >= scroll && s.focusIndex() < scroll + visible,
                    "第 " + s.focusIndex() + " 行不在视野 " + scroll + "…" + (scroll + visible - 1) + " 里");
        }
        assertEquals(10, s.layout(visible), "最后一行在底：滚到 14 − 4");
        s.scrollBy(-5, visible);
        assertEquals(5, s.layout(visible), "滚轮只滚：不被拽回焦点");
        s.scrollBy(-99, visible);
        assertEquals(0, s.layout(visible), "夹在 0");
        for (int focus = 0; focus < 20; focus++) {
            for (int scroll = 0; scroll < 20; scroll++) {
                int kept = SettingsMenuState.keepVisible(focus, scroll, 5, 20);
                assertTrue(focus >= kept && focus < kept + 5 && kept >= 0 && kept <= 15);
            }
        }
    }

    @Test
    @DisplayName("被决策面顶掉：没存的改动留成草稿、下次接回并停在原来那一行；自己关掉的不留；密钥不留；"
            + "表里没有的 · 解析不了的 · 与服务端此刻相同的逐项丢掉；只读整份不接")
    void draftSurvivesBeingPushedAside() {
        SettingsMenuState s = state(true, true);
        select(s, SettingsCategory.WINDOWS, ServerSettingsTable.ACTION);
        assertTrue(s.step(+1, false));                       // 60 → 61
        select(s, SettingsCategory.LLM, SECRET_KEY);
        assertTrue(s.activate());
        s.paste(TYPED);
        assertTrue(s.commitEdit());                          // 密钥也攒着
        select(s, SettingsCategory.WINDOWS, ServerSettingsTable.HELM);
        assertTrue(s.step(+1, true));                        // 20 → 30，焦点停在舵手这一行
        int category = s.categoryIndex();
        int focus = s.focusIndex();

        SettingsMenuState.Draft draft = s.draft().orElseThrow(() -> new AssertionError("有没存的改动：该留草稿"));
        assertEquals(Map.of(ServerSettingsTable.ACTION, "61", ServerSettingsTable.HELM, "30"), draft.pending(),
                "草稿只有服务端那几组的值，密钥不在里面");
        assertFalse(draft.pending().containsKey(SECRET_KEY));

        SettingsMenuState next = state(true, true);
        next.resume(draft);
        assertEquals(Map.of(ServerSettingsTable.ACTION, "61", ServerSettingsTable.HELM, "30"), next.pendingChanges());
        assertEquals(category, next.categoryIndex(), "接回之后停在原来那一组");
        assertEquals(focus, next.focusIndex(), "接回之后停在原来那一行");
        assertTrue(next.changed(next.focused()), "接回的改动照样挂「已改，未保存」");
        assertEquals(List.of(), outbox.saves, "接回不等于存：一个包都没发");

        // 逐项核：表里没有的键、解析不了的值、与服务端此刻相同的值都不接
        Map<String, String> odd = new LinkedHashMap<>(draft.pending());
        odd.put("windows.no_such_key", "5");
        odd.put(ServerSettingsTable.THIRST, "abc");
        Map<String, String> moved = serverDefaults();
        moved.put(ServerSettingsTable.ACTION, "61");              // 别人刚好存了同一个值
        SettingsMenuState third = new SettingsMenuState(defs(), local, outbox);
        third.snapshot(true, true, moved, Set.of());
        third.resume(new SettingsMenuState.Draft(odd, category, focus));
        assertEquals(Map.of(ServerSettingsTable.HELM, "30"), third.pendingChanges());

        SettingsMenuState readOnly = state(true, false);
        readOnly.resume(draft);
        assertFalse(readOnly.hasPending(), "被撤了管理员：草稿整份不接");

        assertFalse(s.requestClose(), "有改动：先问");
        assertTrue(s.requestClose(), "问过再按：扔掉、关");
        assertTrue(s.draft().isEmpty(), "自己关掉的不留草稿");
    }
}
