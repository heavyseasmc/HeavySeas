package io.github.heavyseasmc.mod.game;

import io.github.heavyseasmc.engine.model.CharacterId;
import io.github.heavyseasmc.engine.seat.HeuristicSeatPolicy;
import io.github.heavyseasmc.engine.seat.SeatPolicy;
import io.github.heavyseasmc.engine.seat.SeatPolicySettings;
import io.github.heavyseasmc.mod.config.ServerSettings;
import io.github.heavyseasmc.mod.config.ServerSettingsTable;
import io.github.heavyseasmc.mod.config.TestSettings;
import io.github.heavyseasmc.mod.llm.LlmConfig;
import io.github.heavyseasmc.mod.llm.LlmHooks;
import io.github.heavyseasmc.mod.state.GameComponent;
import io.github.heavyseasmc.mod.state.StandInMind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 替身读设置的接缝（{@link StandInSettings}）读的就是设置菜单存着的那一份（cut 3b）：动脑的旋钮下一局换，
 * 问大模型最多等多久存了就换；{@code /seas dummy} 那一行按存着的说，密钥只说取自哪里。
 */
final class StandInSettingsTest {

    private static final String KEY = "sk-status-0123456789-not-real";

    /** 与 StandInMinds 里那一份同一条读法（旋钮取自接缝），策略用第一层（单测里没有数据，造不了第二层）。 */
    private static final StandInThinker.Policies POLICIES = new StandInThinker.Policies() {
        @Override
        public SeatPolicySettings settings() {
            return StandInSettings.smart();
        }

        @Override
        public SeatPolicy smart(SeatPolicySettings settings, long seed) {
            return new HeuristicSeatPolicy(settings);
        }

        @Override
        public SeatPolicy quick(SeatPolicySettings settings) {
            return new HeuristicSeatPolicy(settings);
        }
    };

    @Test
    @DisplayName("存了新的旋钮：接缝当场读得到，开着的那一局照旧，下一局用新的；思考上限跟着时限走；最多等多久存了就换")
    void seamFollowsTheSavedSettings() throws Exception {
        try (TestSettings t = TestSettings.defaults(); AutoCloseable use = ServerSettings.useForTests(t.settings())) {
            assertEquals(SeatPolicySettings.DEFAULTS, StandInSettings.smart());
            assertEquals(StandInSettings.LLM_DECISION_CAP_MS, StandInSettings.llmDecisionCapMs());
            Object running = new Object();
            StandInThinker.Seat before = StandInThinker.seat(running, 1L, CharacterId.of("captain"), 0, POLICIES);
            assertEquals(SeatPolicySettings.DEFAULTS, before.settings());

            Map<String, String> changes = new LinkedHashMap<>();
            changes.put(ServerSettingsTable.SMART_ROLLOUTS, "64");
            changes.put(ServerSettingsTable.SMART_MILLIS, "1000");
            changes.put(ServerSettingsTable.LLM_DECISION_CAP, "15000");
            assertTrue(t.settings().save(true, changes).accepted());

            assertEquals(64, StandInSettings.smart().rollouts(), "接缝没读到存着的那一份");
            assertEquals(15_000L, StandInSettings.llmDecisionCapMs(), "最多等多久存了没生效");
            StandInThinker.Seat sameGame = StandInThinker.seat(running, 1L, CharacterId.of("kid"), 1, POLICIES);
            assertEquals(SeatPolicySettings.DEFAULTS, sameGame.settings(), "开着的那一局中途换了旋钮");
            StandInThinker.Seat nextGame = StandInThinker.seat(new Object(), 1L, CharacterId.of("captain"), 0, POLICIES);
            assertEquals(64, nextGame.settings().rollouts(), "下一局没用上新的旋钮");
            assertEquals(1000, nextGame.settings().millisPerDecision());
            assertEquals(StandInSettings.SMART_THINK_CAP_MS + StandInSettings.SEARCH_QUEUE_FACTOR * 1000L,
                    StandInSettings.smartThinkCapMs(nextGame.settings()), "思考上限没跟着新的时限走");
        }
    }

    @Test
    @DisplayName("/seas dummy 那一行：动脑那一层按存着的说；大模型那一种说密钥取自哪里，不写密钥")
    void dummyStatusNamesTheKeySourceNotTheKey() throws Exception {
        GameComponent component = new GameComponent(null);
        component.setDummyMind(StandInMind.LLM);
        String line;
        try (TestSettings t = TestSettings.defaults(); AutoCloseable use = ServerSettings.useForTests(t.settings())) {
            Map<String, String> changes = new LinkedHashMap<>();
            changes.put(ServerSettingsTable.LLM_ENABLED, "true");
            changes.put(ServerSettingsTable.LLM_BASE_URL, "https://llm.example/v1");
            changes.put(ServerSettingsTable.LLM_MODEL, "test-model");
            changes.put(ServerSettingsTable.SMART_ROLLOUTS, "64");
            assertTrue(t.settings().save(true, changes).accepted());
            assertTrue(t.settings().setSecret(true, ServerSettingsTable.LLM_API_KEY, KEY, "https://llm.example/v1").accepted());
            LlmHooks.reload();                         // 设置菜单存的密钥压过环境变量：与这台机器的环境无关
            line = StandInMinds.status(component);
        } finally {
            LlmHooks.reload();                         // 单测换进来的设置已经换回去：接入层回到关着
        }
        assertTrue(line.contains("密钥：" + LlmConfig.KEY_SOURCE_STORE), line);
        assertTrue(line.contains("开（test-model）"), line);
        assertTrue(line.contains("每步推演 64 局"), line);
        assertFalse(line.contains(KEY), "密钥进了 /seas dummy 那一行：" + line);
        assertFalse(LlmHooks.service().enabled());
    }
}
