package io.github.heavyseasmc.mod.config;

import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.toml.TomlParser;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import io.github.heavyseasmc.mod.llm.LlmConfig;
import io.github.heavyseasmc.mod.llm.LlmConfigFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * 起服时的两次一次性迁移（cut 3b：设置菜单管全部设置）。
 *
 * <ol>
 *   <li><b>旧的随机开关</b>：{@code stand_ins.random = true} → {@code stand_ins.mind = random}。
 *       旧键不在表里，FCAP 读文件时按 spec 纠正会把它删掉（TLM 教训 A7），所以这一次读要在 FCAP 之前、自己读那份 toml（{@link #readLegacy}）；
 *       写还是经 FCAP（{@link ServerSettings#save}）。文件里已经写了新键的不动。</li>
 *   <li><b>旧的 {@code config/heavyseas/llm.json}</b>：逐项迁进「大模型」一组，<b>只填还是默认值的那几项</b>（菜单里已经改过的以菜单为准）；
 *       密钥进服务端的密钥文件（设置菜单里还没设、环境变量里也没有时 —— 原先环境变量就压过文件里的那一份）；
 *       然后把文件改名成 {@code llm.json.migrated}（被占了就 {@code .1}、{@code .2}……），<b>不删</b>；
 *       已迁入或安全保存在密钥文件恢复项后，才从旧文件抹掉 {@code apiKey} 并归档。
 *       配置值有独立完成标记；归档或密钥重试不再覆盖用户之后改回的默认值。
 *       整份读不了（不是 JSON）就什么都不迁、文件留在原地，并说一句：改好了下次起服再迁。密钥该迁却没写进去时也不改名。</li>
 * </ol>
 * 两件都是幂等的：配置已迁的标记与旧文件是否归档分开，完整归档后再起服什么都不做。
 */
final class SettingsMigration {

    private static final Logger LOGGER = LoggerFactory.getLogger("heavyseas");

    /** {@code llm.json} 迁完改成的名字。 */
    static final String MIGRATED_SUFFIX = ".migrated";

    private SettingsMigration() {
    }

    // ---------------------------------------------------------------- 旧的随机开关

    /**
     * FCAP 读文件之前问出来的旧值。
     *
     * @param random      旧的 {@code stand_ins.random}（没写、不是开关都是 {@code null}）
     * @param mindWritten 文件里已经有新键 {@code stand_ins.mind}
     */
    record Legacy(Boolean random, boolean mindWritten) {
        static final Legacy NONE = new Legacy(null, false);
    }

    /** FCAP 会读的那一份：存档里 {@code serverconfig/} 下有就是它（FCAP 的覆盖规则），否则是全局 {@code config/} 下的。 */
    static Path effectiveFile(Path configDir, Path serverConfigDir, String fileName) {
        Path override = serverConfigDir.resolve(fileName);
        return Files.isRegularFile(override) ? override : configDir.resolve(fileName);
    }

    /** 读那份 toml 里的旧键。文件不在、读不了、写坏了都当没有（读不了时说一句：那样就迁不成了）。 */
    static Legacy readLegacy(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return Legacy.NONE;
        }
        try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Config config = new TomlParser().parse(in);
            Object random = config.get(path(ServerSettingsTable.LEGACY_RANDOM));
            return new Legacy(random instanceof Boolean b ? b : null, config.contains(path(ServerSettingsTable.MIND)));
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("设置迁移：读不了 {}，旧的随机开关迁不了（{}）", file, e.toString());
            return Legacy.NONE;
        }
    }

    private static List<String> path(String key) {
        return List.of(key.split("\\."));
    }

    /** 旧的随机开关该迁成什么：开着、而且文件里还没有新键 → 随机；别的情况不动。 */
    static Optional<String> mindFromLegacy(Legacy legacy) {
        return Boolean.TRUE.equals(legacy.random()) && !legacy.mindWritten() ? Optional.of("random") : Optional.empty();
    }

    /**
     * 迁旧的随机开关：该迁（{@link #mindFromLegacy}）就经 FCAP 存成新键（{@link ServerSettings#save}）。
     *
     * @return 不用迁是空；迁了是存盘的结果（拒了也在里面，由调用方报）
     */
    static Optional<ServerSettings.Outcome> migrateMind(Legacy legacy, ServerSettings settings) {
        return mindFromLegacy(legacy).map(mind -> settings.save(true, Map.of(ServerSettingsTable.MIND, mind)));
    }

    // ---------------------------------------------------------------- 旧的 llm.json

    /**
     * 迁 {@code llm.json} 的结果。
     *
     * @param imported   填进设置的键
     * @param skipped    没填的键与为什么（菜单里已经改过 · 不合这一项的规矩 · 文件里那一项本身写错了）
     * @param keyNote    密钥那一项怎么了（迁进了密钥文件 · 没迁、为什么）；文件里没有密钥是 {@code null}。<b>不含密钥本身</b>
     * @param renamedTo  文件改成了什么名字；没改名（读不了 · 存不进去）是 {@code null}
     * @param problem    整份没迁的原因；迁了是 {@code null}
     */
    record LlmImport(List<String> imported, List<String> skipped, String keyNote, Path renamedTo, String problem) {

        void log(Logger logger, Path file) {
            if (problem != null && imported.isEmpty()) {
                logger.error("设置迁移：{} 没有迁进设置（{}），文件留在原地；改好了下次起服再迁", file, problem);
                return;
            }
            if (problem != null) {
                logger.error("设置迁移：{} 迁了一半：填了 {} 项 {}；{}", file, imported.size(), imported, problem);
                return;
            }
            logger.info("设置迁移：{} 迁进了「大模型」一组：填了 {} 项 {}{}{}；原文件改名为 {}", file, imported.size(), imported,
                    skipped.isEmpty() ? "" : "，没填 " + skipped, keyNote == null ? "" : "；" + keyNote,
                    renamedTo == null ? "（没改名）" : renamedTo.getFileName());
        }
    }

    /**
     * 迁一次。文件不在返回 {@code null}（什么都不用做）。
     *
     * @param env 查环境变量（服务端传 {@code System::getenv}）
     */
    static LlmImport importLlmJson(Path file, ServerSettings settings, Function<String, String> env) {
        LlmConfigFile.DraftRead read = LlmConfigFile.readDraft(file);
        if (read == null) {
            return null;
        }
        if (read.unreadable() != null) {
            return new LlmImport(List.of(), List.of(), null, null, read.unreadable());
        }
        LegacyMigrationMarker marker;
        try {
            marker = LegacyMigrationMarker.begin(file);
        } catch (IOException failure) {
            return new LlmImport(List.of(), List.of(), null, null, "不能安全记录迁移进度：" + failure.getMessage());
        }
        ServerSettingsTable table = settings.table();
        List<String> skipped = new ArrayList<>(read.problems());
        Map<String, String> changes = new LinkedHashMap<>();
        values(read.draft()).forEach((key, value) -> {
            if (marker.complete()) {
                return;
            }
            SettingDef def = table.def(key).orElseThrow();
            if (!settings.current(def).equals(def.fallback())) {
                skipped.add(key + "（设置里已经改过，以设置为准）");
                return;
            }
            Optional<String> bad = def.check(value);
            if (bad.isPresent()) {
                skipped.add(key + "（" + bad.get() + "）");
                return;
            }
            if (!value.equals(def.fallback())) {
                changes.put(key, def.format(value));
            }
        });
        if (!changes.isEmpty()) {
            ServerSettings.Outcome outcome = settings.save(true, changes);
            if (!outcome.accepted()) {
                try {
                    marker.rejected();
                } catch (IOException failure) {
                    return new LlmImport(List.of(), skipped, null, null,
                            "设置没保存，迁移待处理标记也未清掉：" + failure.getClass().getSimpleName());
                }
                return new LlmImport(List.of(), skipped, null, null, "存不进设置：" + outcome.rejection());
            }
        }
        try {
            marker.saved();
        } catch (IOException failure) {
            return new LlmImport(List.copyOf(changes.keySet()), skipped, null, null,
                    "设置已处理，完成标记没写成；保留 .values-importing 防止下次重填：" + failure.getClass().getSimpleName());
        }
        KeyImport key = importKey(read.draft(), settings, env);
        if (key.failed()) {
            // 审查 2026-10-07 R10：密钥写不进密钥文件时原先照样改名 —— 密钥从此只剩在 .migrated 里，以后也不会再迁
            return new LlmImport(List.copyOf(changes.keySet()), List.copyOf(skipped), key.note(), null,
                    "值迁进了设置，但" + key.note() + "：文件留在原地，下次起服再迁密钥");
        }
        String keyNote = key.note();
        String oldKey = read.draft().apiKey();
        if (oldKey != null && !oldKey.isBlank()) {
            if (!key.imported() && !settings.secret(ServerSettingsTable.LLM_API_KEY).filter(oldKey.strip()::equals).isPresent()) {
                ServerSettings.Outcome backup = settings.preserveLegacyKey(oldKey.strip(), LlmConfig.origin(url(read.draft().baseUrl())));
                if (!backup.accepted()) {
                    return new LlmImport(List.copyOf(changes.keySet()), skipped, keyNote, null, backup.rejection());
                }
                keyNote = (keyNote == null ? "" : keyNote + "；") + "旧值保留在服务端密钥文件的恢复项，不用于请求";
            }
            // 先确保密钥可恢复，再去掉旧配置的副本；改名失败重试时不会重复导入设置。
            String scrubNote = scrubKey(file);
            if (scrubNote != null) {
                FileSecretStore.restrict(file);
                return new LlmImport(List.copyOf(changes.keySet()), skipped, keyNote, null, scrubNote);
            }
        }
        Path renamed;
        try {
            renamed = rename(file);
        } catch (IOException e) {
            return new LlmImport(List.copyOf(changes.keySet()), skipped, keyNote, null,
                    "值已处理，但旧文件改不了名（" + e.getClass().getSimpleName() + "）：下次只重试归档，不重填设置");
        }
        FileSecretStore.restrict(renamed);
        return new LlmImport(List.copyOf(changes.keySet()), List.copyOf(skipped), keyNote, renamed, null);
    }

    /**
     * 密钥那一项迁得怎么样。
     *
     * @param note     一句说明（不含密钥）；文件里没有密钥是 {@code null}
     * @param imported 迁进了密钥文件
     * @param failed   该迁、却没写进去（这时文件不改名）
     */
    private record KeyImport(String note, boolean imported, boolean failed) {
    }

    /**
     * 密钥：设置菜单里还没设、环境变量里也没有时才迁进密钥文件，绑到 {@code llm.json} 自己写的地址（审查 2026-10-07 L1：
     * 不绑此刻设置里的 —— 存档自带的 {@code serverconfig/} 可以把此刻的地址改到任何地方；原先这把密钥也只发往 llm.json 的地址）。
     */
    private static KeyImport importKey(LlmConfig.Draft draft, ServerSettings settings, Function<String, String> env) {
        String key = draft.apiKey();
        if (key == null || key.isBlank()) {
            return new KeyImport(null, false, false);
        }
        SettingDef def = settings.table().def(ServerSettingsTable.LLM_API_KEY).orElseThrow();
        if (settings.secretSet(def)) {
            return new KeyImport("密钥：设置菜单里已经设了，llm.json 里的那一份没迁", false, false);
        }
        String envKey = env.apply(LlmConfig.KEY_ENV);
        if (envKey != null && !envKey.isBlank()) {
            return new KeyImport("密钥：环境变量 " + LlmConfig.KEY_ENV + " 里有（原先就是它压过 llm.json），llm.json 里的那一份没迁",
                    false, false);
        }
        String origin = LlmConfig.origin(url(draft.baseUrl()));
        if (origin == null) {
            return new KeyImport("密钥：llm.json 里没有可用的地址，没迁（密钥只发往设它时的那个地址）", false, false);
        }
        ServerSettings.Outcome outcome = settings.importSecret(ServerSettingsTable.LLM_API_KEY, key.strip(), origin);
        return outcome.accepted()
                ? new KeyImport("密钥：迁进了服务端的密钥文件（只发往 " + origin + "）", true, false)
                : new KeyImport("密钥没迁成（" + outcome.rejection() + "）", false, true);
    }

    /**
     * 密钥已经迁进了密钥文件：把改了名的那一份里的 {@code apiKey} 抹掉（审查 2026-10-07 R10：原先明文密钥原样留在
     * {@code llm.json.migrated} 里，换了、清了密钥之后它还在）。先写临时文件再替换。返回一句说明；抹掉了是 {@code null}。
     */
    static String scrubKey(Path migrated) {
        try {
            JsonElement root = JsonParser.parseString(Files.readString(migrated, StandardCharsets.UTF_8));
            if (!root.isJsonObject() || !root.getAsJsonObject().has("apiKey")) {
                return null;
            }
            root.getAsJsonObject().remove("apiKey");
            Path tmp = migrated.resolveSibling(migrated.getFileName() + ".tmp");
            Files.writeString(tmp, new GsonBuilder().setPrettyPrinting().create().toJson(root) + "\n", StandardCharsets.UTF_8);
            FileSecretStore.restrict(tmp);
            Files.move(tmp, migrated, StandardCopyOption.REPLACE_EXISTING);
            return null;
        } catch (IOException | RuntimeException e) {
            return migrated.getFileName() + " 里的密钥没抹掉（" + e.getClass().getSimpleName() + "）：请手动删掉其中的 apiKey";
        }
    }

    /** {@code llm.json} 里写了的每一项（密钥除外）→ 表里的键与值（类型与表那一项一样）。 */
    static Map<String, Object> values(LlmConfig.Draft d) {
        Map<String, Object> out = new LinkedHashMap<>();
        put(out, ServerSettingsTable.LLM_ENABLED, d.enabled());
        put(out, ServerSettingsTable.LLM_BASE_URL, url(d.baseUrl()));
        put(out, ServerSettingsTable.LLM_MODEL, strip(d.model()));
        put(out, ServerSettingsTable.LLM_REASONING_EFFORT, strip(d.reasoningEffort()));
        put(out, ServerSettingsTable.LLM_LANGUAGE, strip(d.language()));
        put(out, ServerSettingsTable.LLM_MAX_TOKENS, d.maxTokens());
        put(out, ServerSettingsTable.LLM_TEMPERATURE, d.temperature());
        put(out, ServerSettingsTable.LLM_MAX_ATTEMPTS, d.maxAttempts());
        put(out, ServerSettingsTable.LLM_ATTEMPT_TIMEOUT, d.attemptTimeoutMs());
        put(out, ServerSettingsTable.LLM_ATTEMPT_SHARE, d.attemptShare());
        put(out, ServerSettingsTable.LLM_MIN_ATTEMPT, d.minAttemptMs());
        put(out, ServerSettingsTable.LLM_BACKOFF_BASE, d.backoffBaseMs());
        put(out, ServerSettingsTable.LLM_BACKOFF_MAX, d.backoffMaxMs());
        put(out, ServerSettingsTable.LLM_DEADLINE_MARGIN, d.deadlineMarginMs());
        put(out, ServerSettingsTable.LLM_MAX_CONCURRENT, d.maxConcurrent());
        put(out, ServerSettingsTable.LLM_MAX_QUEUED, d.maxQueued());
        put(out, ServerSettingsTable.LLM_BREAKER_THRESHOLD, d.breakerThreshold());
        put(out, ServerSettingsTable.LLM_BREAKER_COOLDOWN, d.breakerCooldownMs());
        put(out, ServerSettingsTable.LLM_DEBUG_LOG, d.debugLog());
        return out;
    }

    private static void put(Map<String, Object> out, String key, Object value) {
        if (value != null) {
            out.put(key, value);
        }
    }

    /** 原先的读法（{@link LlmConfig} 的构造器）先去掉首尾空白再查；迁的时候也照这样，免得一个空格就整项迁不过来。 */
    private static String strip(String s) {
        return s == null ? null : s.strip();
    }

    /** 地址：去掉首尾空白与末尾的 {@code /}（原先的读法也这样）。 */
    private static String url(String s) {
        String out = strip(s);
        while (out != null && out.endsWith("/")) {
            out = out.substring(0, out.length() - 1);
        }
        return out;
    }

    /** 改名成 {@code llm.json.migrated}；被占了就 {@code .1}、{@code .2}……（不覆盖，更不删）。 */
    static Path rename(Path file) throws IOException {
        Path target = file.resolveSibling(file.getFileName() + MIGRATED_SUFFIX);
        for (int n = 1; ; n++) {
            try {
                return Files.move(file, target);
            } catch (FileAlreadyExistsException taken) {
                if (n > 999) {
                    throw taken;
                }
                target = file.resolveSibling(file.getFileName() + MIGRATED_SUFFIX + "." + n);
            }
        }
    }
}
