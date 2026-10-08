package io.github.heavyseasmc.mod.config;

import fuzs.forgeconfigapiport.fabric.api.neoforge.v4.NeoForgeConfigRegistry;
import fuzs.forgeconfigapiport.fabric.api.neoforge.v4.NeoForgeModConfigEvents;
import io.github.heavyseasmc.engine.seat.SeatPolicySettings;
import io.github.heavyseasmc.mod.HeavySeasMod;
import io.github.heavyseasmc.mod.game.GameTiming;
import io.github.heavyseasmc.mod.game.StandInSettings;
import io.github.heavyseasmc.mod.llm.LlmConfig;
import io.github.heavyseasmc.mod.state.GameComponent;
import io.github.heavyseasmc.mod.state.GameComponents;
import io.github.heavyseasmc.mod.state.StandInMind;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.WorldSavePath;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * 服务端设置（ADR-0099 D2 (a)）：交给 Forge Config API Port 的 SERVER 类型，文件 {@code heavyseas-server.toml}。
 *
 * <h2>文件只有一个主人</h2>
 * TLM 的「两边争」是因为它自己也写同一个文件（ADR-0099 §2.1）。这里<b>一律经 FCAP 自己的对象改值</b>：
 * {@code ConfigValue.set} + {@code ModConfigSpec.save()}，不手写文件、不另开读写口。FCAP 写盘是原子替换（REPLACE_ATOMIC）。
 *
 * <h2>文件在哪</h2>
 * FCAP 21.1.6 在起服那一刻（{@code SERVER_STARTING} 的前置阶段）加载 SERVER 配置：默认是全局的 {@code config/heavyseas-server.toml}；
 * 存档里若有 {@code serverconfig/heavyseas-server.toml}，那一份优先（FCAP 的 {@code ServerLifecycleHandler}）。
 * ❗所以它<b>不是</b>天然「按存档一份」—— 要某个存档单独一套，把文件放进那个存档的 {@code serverconfig/}。
 *
 * <h2>谁读、什么时候读</h2>
 * <ul>
 *   <li>时限：开局那一刻读一次（{@link #gameTiming()}），快照挂在组件上，一局之内不变（D8）。</li>
 *   <li>替身的起服默认（自动推进 · 怎么拿主意）：起服时读一次（D7 · ADR-0019：这一次运行里由指令说了算）。</li>
 *   <li>动脑替身的旋钮：每局第一次造替身的策略时读一次（{@link #seatPolicy()}），一局之内不变。</li>
 *   <li>大模型那一组：存了就当场换接入层（{@link #llmConfig} · {@link #onChange}）。</li>
 *   <li>替身补位：用到时现读。</li>
 * </ul>
 *
 * <h2>线程</h2>
 * FCAP 的文件监视在自己的线程上回调「重载」（ADR-0099 §8）。这里一律转回服务端线程再碰别的东西（{@link #onReloaded}）。
 */
public final class ServerSettings {

    private static final Logger LOGGER = LoggerFactory.getLogger(HeavySeasMod.MOD_ID);

    /** 向 FCAP 登记的那一份；{@link #register()} 之前是 {@code null}。 */
    private static ServerSettings registered;
    private static ModConfig registeredConfig;
    /** 此刻在跑的服务端（起服 → 停服）。重载回调靠它把事情转回服务端线程；客户端收到同步过来的那份时它是空的。 */
    private static volatile MinecraftServer running;

    private final ServerSettingsTable table;
    private final ModConfigSpec spec;
    /** 键 → FCAP 的值对象。只含 {@link ServerSettingsTable#synced()}。 */
    private final Map<String, ModConfigSpec.ConfigValue<?>> values;
    /** 密钥另放（{@link SecretStore}）：不进上面那份会同步给客户端的 spec。 */
    private final SecretStore secrets;

    /** 按表展开一份 FCAP spec。不登记 —— 单测直接用它（配一份内存里的配置）；密钥放内存。 */
    public ServerSettings(ServerSettingsTable table) {
        this(table, SecretStore.inMemory());
    }

    public ServerSettings(ServerSettingsTable table, SecretStore secrets) {
        this.table = Objects.requireNonNull(table, "table");
        this.secrets = Objects.requireNonNull(secrets, "secrets");
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        Map<String, ModConfigSpec.ConfigValue<?>> built = new LinkedHashMap<>();
        String section = null;
        for (SettingDef def : table.all()) {
            if (def.secret()) {
                // ❗密钥绝不进这一份：FCAP 进服时把 SERVER 那份文件整份发给每个客户端（ConfigSync.syncConfigs）
                continue;
            }
            List<String> path = Arrays.asList(def.key().split("\\."));
            if (path.size() != 2) {
                throw new IllegalArgumentException("设置的键要写成「段.名字」：" + def.key());
            }
            if (!path.get(0).equals(section)) {
                if (section != null) {
                    builder.pop();
                }
                section = path.get(0);
                String sectionComment = ServerSettingsTable.SECTIONS.get(section);
                if (sectionComment != null) {
                    builder.comment(sectionComment);
                }
                builder.push(section);
            }
            builder.comment(def.comment());
            String name = path.get(1);
            ModConfigSpec.ConfigValue<?> value = switch (def.kind()) {
                case SECONDS, INT -> builder.defineInRange(name, (int) (Integer) def.fallback(), (int) def.min(), (int) def.max());
                case DECIMAL -> builder.defineInRange(name, (double) (Double) def.fallback(), def.min(), def.max());
                case FLAG -> builder.define(name, (boolean) (Boolean) def.fallback());
                // ❗交一份可变的表：FCAP 拿 contains 核文件里的值，文件缺这一项时传进来的是 null，而 List.of 的 contains(null) 直接抛
                case CHOICE -> builder.defineInList(name, (String) def.fallback(), new java.util.ArrayList<>(def.choices()));
                // 文字：类型与长度由这一项自己的规矩核（FCAP 在读文件时按它纠正，不合的退回默认）
                case TEXT -> builder.define(name, (String) def.fallback(), raw -> def.check(raw).isEmpty());
            };
            built.put(def.key(), value);
        }
        if (section != null) {
            builder.pop();
        }
        this.spec = builder.build();
        this.values = java.util.Collections.unmodifiableMap(built);
    }

    public ModConfigSpec spec() {
        return spec;
    }

    public ServerSettingsTable table() {
        return table;
    }

    /** FCAP 把文件读进来了没有（起服之后才有；单测里由测试塞一份）。 */
    public boolean loaded() {
        return spec.isLoaded();
    }

    /**
     * 一项此刻的值（类型见 {@link SettingDef}）。读的是配置里存着的那个（不走 FCAP 的缓存）。
     * ❗密钥没有「此刻的值」可读 —— 那一类走 {@link #secretSet} / {@link #secret}。
     */
    public Object current(SettingDef def) {
        ModConfigSpec.ConfigValue<?> value = values.get(def.key());
        if (value == null) {
            throw new IllegalArgumentException("这一项不在同步的那份设置里：" + def.key());
        }
        Object raw = value.getRaw();
        return switch (def.kind()) {
            case SECONDS, INT -> ((Number) raw).intValue();
            case DECIMAL -> ((Number) raw).doubleValue();
            case CHOICE, TEXT -> String.valueOf(raw);
            case FLAG -> raw;
        };
    }

    /** 这一项密钥设了没有（快照里只给这个）。 */
    public boolean secretSet(SettingDef def) {
        return def.secret() && secrets.isSet(def.key());
    }

    /** 密钥的值 —— <b>只给服务端自己用</b>（大模型那一路发请求时取，第三刀接），不许经任何包发出去、不许进日志。 */
    public Optional<String> secret(String key) {
        return table.def(key).filter(SettingDef::secret).flatMap(def -> secrets.get(key));
    }

    /** 密钥绑的地址在密钥文件里的键：{@code <密钥的键>.origin}。不是表里的一项，快照里没有它。 */
    static final String ORIGIN_SUFFIX = ".origin";

    /**
     * 这项密钥设的时候绑的地址（{@link LlmConfig#origin} 的写法；审查 2026-10-07 L1）。没绑（旧版本存的、或者这项不绑）是空。
     * 只给服务端自己比对用。
     */
    public Optional<String> secretOrigin(String key) {
        return table.def(key).filter(SettingDef::secret).flatMap(def -> secrets.get(key + ORIGIN_SUFFIX));
    }

    /**
     * 客户端设一项密钥（ADR-0099 D6 · 只写不读）：再查权限 → 必须是表里登记的密钥 → 按这一项的规矩核 → 存。
     * 空串 = 清掉。<b>结果里没有值</b>；调用方的日志也只许写「谁设了 / 清了哪一项」。
     *
     * <h2>❗绑地址（审查 2026-10-07 L1）</h2>
     * 绑地址的那项密钥（{@link ServerSettingsTable#secretScope}）连同服务端<b>此刻</b>的地址一起存，大模型那一层只把它发往这个地址
     * （{@link LlmConfig#fromSettings}）。{@code scope} 是发包的人在菜单里看到的地址：同一次存盘里先发改地址的那一批、再发密钥，
     * 那一批若被拒，服务端此刻的地址还是旧的 —— 照旧绑上去，就把给新地址的密钥送到了旧地址。所以两边的「协议 + 主机 + 端口」对不上就拒。
     *
     * @param canEdit 发包的人此刻能不能改（{@link SettingsSync#canEdit}）
     * @param scope   这项密钥绑的那一项设置，发包的人以为的值（不绑的密钥不看它）
     */
    public Outcome setSecret(boolean canEdit, String key, String value, String scope) {
        if (!canEdit) {
            return Outcome.rejected("没有权限");
        }
        Optional<SettingDef> def = table.def(key).filter(SettingDef::secret);
        if (def.isEmpty()) {
            return Outcome.rejected("不是登记过的密钥");
        }
        SettingDef.Parsed parsed = def.get().parse(value == null ? "" : value);
        if (!parsed.ok()) {
            // ❗理由里不带值：SettingDef 的理由只在数值类型里回显输入，文字类型只说哪一条规矩不合
            return Outcome.rejected("不合规矩（" + def.get().kind() + "）");
        }
        String newValue = (String) parsed.value();
        Optional<SettingDef> bound = ServerSettingsTable.secretScope(key).flatMap(table::def);
        String origin = null;
        if (bound.isPresent() && !newValue.isEmpty()) {
            origin = LlmConfig.origin(String.valueOf(current(bound.get())));
            if (origin == null) {
                return Outcome.rejected("先存好接口地址（" + bound.get().key() + "）再设密钥：密钥只发往设它时的那个地址");
            }
            if (!origin.equals(LlmConfig.origin(scope))) {
                return Outcome.rejected("接口地址没存上，密钥没设（服务端此刻的地址是 " + origin + "）");
            }
        }
        return storeSecret(key, newValue, bound.isPresent(), origin);
    }

    /**
     * 迁移用（{@link SettingsMigration}）：不经菜单、不查权限，绑到给定的地址（旧文件自己写的那个，不是此刻设置里的 ——
     * 存档自带的 {@code serverconfig/} 可以把此刻的地址改成任何地方）。
     */
    Outcome importSecret(String key, String value, String origin) {
        Optional<SettingDef> def = table.def(key).filter(SettingDef::secret);
        if (def.isEmpty() || value == null || value.isEmpty() || !def.get().parse(value).ok()) {
            return Outcome.rejected("不是登记过的密钥，或者不合规矩");
        }
        boolean bound = ServerSettingsTable.secretScope(key).flatMap(table::def).isPresent();
        if (bound && origin == null) {
            return Outcome.rejected("没有可绑的地址");
        }
        return storeSecret(key, value, bound, origin);
    }

    /** 旧密钥被菜单/环境变量覆盖时仍保留一份恢复值；它不登记为设置，也永远不用于发请求。 */
    Outcome preserveLegacyKey(String value, String origin) {
        String recovery = "legacy_recovery.llm_api_key";
        try {
            Optional<String> previous = secrets.get(recovery);
            if (previous.isPresent() && !previous.get().equals(value)) {
                return Outcome.rejected("密钥文件里已有不同的旧密钥恢复项，未覆盖；请先核对旧文件");
            }
            secrets.putAll(Map.of(recovery, value, recovery + ORIGIN_SUFFIX, origin == null ? "" : origin));
            return Outcome.accepted(List.of(recovery));
        } catch (RuntimeException failure) {
            LOGGER.warn("旧密钥恢复项没有存成：{}", failure.getClass().getSimpleName());
            return Outcome.rejected("旧密钥恢复项写盘失败，旧文件留在原地");
        }
    }

    private Outcome storeSecret(String key, String value, boolean bound, String origin) {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put(key, value);
        if (bound) {
            entries.put(key + ORIGIN_SUFFIX, value.isEmpty() ? "" : origin);   // 清掉密钥时连绑的地址一起清
        }
        try {
            secrets.putAll(entries);
        } catch (SecretStore.Unreadable unreadable) {
            LOGGER.error("密钥 {} 没存：密钥文件读不出来，这一次运行里不写它（见起服时那一行）", key);
            return Outcome.rejected("密钥文件读坏了，没写（见服务端日志）");
        } catch (RuntimeException failure) {
            LOGGER.error("密钥 {} 没存上：{}", key, failure.getClass().getSimpleName());
            return Outcome.rejected("写盘失败");
        }
        return Outcome.accepted(List.of(key));
    }

    /** 快照：每一项此刻的值（字符串，不含密钥）。 */
    public LinkedHashMap<String, String> snapshot() {
        return table.snapshot(this::current);
    }

    /** 这一刻的时限。 */
    public GameTiming timing() {
        return table.timing(this::current);
    }

    /**
     * 客户端发来的一批改动（ADR-0099 D6）。
     *
     * <ol>
     *   <li>没有权限 → 拒（服务端再查一遍，不信客户端说的）；</li>
     *   <li>整批核对，一项不合法整批拒、一个值都不动（TLM 教训 A5）；</li>
     *   <li>经 FCAP 自己的对象改值、存盘；写盘失败就把内存里的值改回去 —— 不留「改了一半」。</li>
     * </ol>
     *
     * @param canEdit 发包的人此刻能不能改（{@link SettingsSync#canEdit}）
     */
    public Outcome save(boolean canEdit, Map<String, String> changes) {
        if (!canEdit) {
            return Outcome.rejected("没有权限（单人存档的主人 · 局域网房主 · 专用服务端上 2 级以上才能改）");
        }
        if (!loaded()) {
            return Outcome.rejected("服务端设置还没加载");
        }
        ServerSettingsTable.Batch batch = table.validate(changes);
        if (!batch.ok()) {
            return Outcome.rejected(batch.rejection());
        }
        // 审查 2026-10-07 R8：碰了「大模型」一组的一批，按改后的值整组合一遍；大模型那一层不收就整批拒、把理由回给菜单
        boolean touchesLlm = batch.values().keySet().stream()
                .anyMatch(k -> table.def(k).map(d -> d.category() == SettingsCategory.LLM).orElse(false));
        if (touchesLlm) {
            String problem = table.llmProblem(def -> batch.values().containsKey(def.key())
                    ? batch.values().get(def.key()) : current(def));
            if (problem != null) {
                return Outcome.rejected("「大模型」一组合不起来：" + problem);
            }
        }
        Map<String, Object> before = new LinkedHashMap<>();
        for (String key : batch.values().keySet()) {
            before.put(key, values.get(key).getRaw());
        }
        try {
            batch.values().forEach(this::set);
            spec.save();                      // 写盘 + FCAP 发一次「重载」（在本线程上）
        } catch (RuntimeException failure) {
            before.forEach(this::set);        // 写盘失败：内存里的值改回去，与文件保持一致
            LOGGER.error("服务端设置写盘失败，这一批没有生效", failure);
            return Outcome.rejected("写盘失败：" + failure);
        }
        return Outcome.accepted(batch.values().keySet().stream().toList());
    }

    @SuppressWarnings("unchecked")
    private void set(String key, Object value) {
        ((ModConfigSpec.ConfigValue<Object>) values.get(key)).set(value);
    }

    /** {@link #save} 的结果。 */
    public record Outcome(boolean accepted, List<String> keys, String rejection) {

        static Outcome accepted(List<String> keys) {
            return new Outcome(true, List.copyOf(keys), null);
        }

        static Outcome rejected(String why) {
            return new Outcome(false, List.of(), why);
        }
    }

    // ------------------------------------------------------------------ 登记与起服

    /** 本模组在 SERVER_STARTING 里排在 FCAP 读文件之前的那一段：问旧键（FCAP 读文件时会把不认识的键删掉）。 */
    private static final Identifier BEFORE_FCAP = Identifier.of(HeavySeasMod.MOD_ID, "settings_before_fcap");
    /** FCAP 读 SERVER 配置的那一段（它自己的 {@code ServerLifecycleHandler.BEFORE_PHASE}）。 */
    private static final Identifier FCAP_LOAD = Identifier.of("forgeconfigapiport", "before");

    /** 起服时、FCAP 读文件之前从那份文件里问出来的旧键；迁完就清。 */
    private static volatile SettingsMigration.Legacy legacy = SettingsMigration.Legacy.NONE;

    /**
     * 向 FCAP 登记（两端都登记：SERVER 配置只在起服时加载，客户端那一份用来接进服时同步过来的值）。
     * 在模组入口里调一次。
     */
    public static void register() {
        if (registered != null) {
            throw new IllegalStateException("服务端设置登记了两次");
        }
        // 密钥文件只在真有人设了密钥时才写（服务端那一侧）；客户端进程里这个对象从不写盘
        ServerSettings settings = new ServerSettings(ServerSettingsTable.DEFAULT,
                new FileSecretStore(FabricLoader.getInstance().getConfigDir()));
        registeredConfig = NeoForgeConfigRegistry.INSTANCE.register(HeavySeasMod.MOD_ID, ModConfig.Type.SERVER, settings.spec);
        registered = settings;
        NeoForgeModConfigEvents.loading(HeavySeasMod.MOD_ID).register(config -> {
            if (config.getSpec() == settings.spec) {
                LOGGER.info("服务端设置已加载：{}", where());
            }
        });
        NeoForgeModConfigEvents.reloading(HeavySeasMod.MOD_ID).register(config -> {
            if (config.getSpec() == settings.spec) {
                onReloaded();
            }
        });
        // ❗赶在 FCAP 读文件之前问旧键：它读的时候按 spec 纠正，表里没有的键（旧的随机开关）当场删掉（TLM 教训 A7）
        ServerLifecycleEvents.SERVER_STARTING.addPhaseOrdering(BEFORE_FCAP, FCAP_LOAD);
        ServerLifecycleEvents.SERVER_STARTING.register(BEFORE_FCAP, server -> legacy = SettingsMigration.readLegacy(
                SettingsMigration.effectiveFile(FabricLoader.getInstance().getConfigDir(),
                        server.getSavePath(WorldSavePath.ROOT).resolve("serverconfig"), registeredConfig.getFileName())));
        // FCAP 在 SERVER_STARTING 的前置阶段加载文件；这里排在默认阶段，读得到。迁移也在这里做（大模型那一层在 SERVER_STARTED 读）
        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            running = server;
            migrateOnStart();
        });
        ServerLifecycleEvents.SERVER_STARTED.register(ServerSettings::applyStandInDefaults);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> running = null);
    }

    /**
     * 起服时的两次一次性迁移（{@link SettingsMigration}）：旧的随机开关 → 替身的脑子；旧的 {@code llm.json} → 「大模型」一组。
     * 都经本类自己的存盘那条路写（FCAP 是文件唯一的主人）。
     */
    private static void migrateOnStart() {
        ServerSettings settings = registered;
        SettingsMigration.Legacy read = legacy;
        legacy = SettingsMigration.Legacy.NONE;
        if (settings == null || !settings.loaded()) {
            return;
        }
        SettingsMigration.migrateMind(read, settings).ifPresent(outcome -> {
            if (outcome.accepted()) {
                LOGGER.info("设置迁移：旧的 {} = true 改写成 {} = {}", ServerSettingsTable.LEGACY_RANDOM,
                        ServerSettingsTable.MIND, settings.snapshot().get(ServerSettingsTable.MIND));
            } else {
                LOGGER.warn("设置迁移：旧的随机开关没迁成（{}）", outcome.rejection());
            }
        });
        Path llmJson = FabricLoader.getInstance().getConfigDir().resolve("heavyseas").resolve("llm.json");
        SettingsMigration.LlmImport result = SettingsMigration.importLlmJson(llmJson, settings, System::getenv);
        if (result != null) {
            result.log(LOGGER, llmJson);
        }
    }

    /** 登记的那一份（发快照 · 存盘包要用）；没登记时抛 —— 那是入口漏了一行，不是「没有设置」。 */
    public static ServerSettings instance() {
        if (registered == null) {
            throw new IllegalStateException("服务端设置还没登记（HeavySeasMod.onInitialize 里漏了 ServerSettings.register()）");
        }
        return registered;
    }

    private static String where() {
        try {
            return registeredConfig.getFullPath().toString();
        } catch (RuntimeException e) {
            return registeredConfig.getFileName() + "（不是文件：进服时同步过来的那一份）";
        }
    }

    /**
     * 文件变了（菜单存盘 · 有人手改了文件被 FCAP 的监视线程读到）。❗可能在 FCAP 的监视线程上：一律转回服务端线程。
     * 客户端收到同步过来的那一份时也会走到这里 —— 那时没有在跑的服务端，什么都不做。
     */
    private static void onReloaded() {
        MinecraftServer server = running;
        if (server == null) {
            return;
        }
        server.execute(() -> {
            LOGGER.info("服务端设置变了（{}）：下一局按新的时限与动脑替身的旋钮；大模型那一组当场换；替身的两个起服默认下次起服才用",
                    where());
            SettingsSync.broadcastIfChanged(server);
            fireChanged();
        });
    }

    /** 设置或密钥变了之后要做的事（大模型接入层当场换一个服务）。只在服务端线程上调。 */
    private static final List<Runnable> CHANGE_LISTENERS = new CopyOnWriteArrayList<>();

    /**
     * 服务端设置变了（菜单存盘 · 手改文件被 FCAP 读到）或者设了 / 清了一项密钥之后，在服务端线程上调 {@code listener}。
     * 大模型接入层靠它「存了就生效」（{@code LlmHooks}）。
     */
    public static void onChange(Runnable listener) {
        CHANGE_LISTENERS.add(Objects.requireNonNull(listener, "listener"));
    }

    /** 告诉听着的人：设置或密钥变了。一个出错不拦后面的。 */
    static void fireChanged() {
        for (Runnable listener : CHANGE_LISTENERS) {
            try {
                listener.run();
            } catch (RuntimeException e) {
                LOGGER.error("服务端设置变了之后，有一项跟着改的事出错了", e);
            }
        }
    }

    /**
     * 起服时把替身的起服默认值摆上（ADR-0099 D7）：自动推进开不开、替身怎么拿主意（{@link ServerSettingsTable#MIND}）。
     * 各世界的组件都摆 —— 开关写在承载对局的那个世界上，而那个世界是哪一个由布局定（{@code /seas dummy auto} 写雾海，
     * 雾海没加载时写指令所在的世界）。这一次运行里 {@code /seas dummy …} 照旧说了算（ADR-0019）。
     */
    private static void applyStandInDefaults(MinecraftServer server) {
        ServerSettings settings = registered;
        if (settings == null || !settings.loaded()) {
            LOGGER.warn("服务端设置没加载，替身开关按代码里的默认值（自动推进 开 · 什么也不做）");
            return;
        }
        ServerSettingsTable t = settings.table;
        boolean autoplay = t.flag(settings::current, ServerSettingsTable.AUTOPLAY);
        StandInMind mind = t.mind(settings::current);
        for (ServerWorld world : server.getWorlds()) {
            GameComponent component = GameComponents.of(world);
            // 会动的脑子都是自动推进的一种（与 /seas dummy random|smart|llm on 同一条）
            component.setDummyAutoplay(autoplay || mind.acts());
            component.setDummyMind(mind);
        }
        LOGGER.info("替身起服默认（服务端设置）：自动推进 {} · 脑子 {}", autoplay || mind.acts() ? "开" : "关", mind.label());
        if (mind.acts() && t.flag(settings::current, ServerSettingsTable.UNTIMED_DEMO)) {
            // ADR-0097：正式局之所以不会变成不限时，原先只靠「随机默认关」。默认值进了设置之后，把后果说出来
            LOGGER.warn("替身默认会自己动（{}），且 demo.untimed_humans 开着：凡是既有真人也有替身的局，真人都不限时", mind.label());
        }
    }

    // ------------------------------------------------------------------ 替身与大模型读设置的口（StandInSettings · LlmHooks 用）

    /** 单测换进来的那一份（{@link #useForTests}）；平时是空。 */
    private static volatile ServerSettings testOverride;

    /** 此刻能读的那一份：登记过、而且 FCAP 已经把文件读进来了。没有就是 {@code null}。 */
    private static ServerSettings active() {
        ServerSettings s = testOverride != null ? testOverride : registered;
        return s != null && s.loaded() ? s : null;
    }

    /**
     * <b>单测用</b>：让下面几个静态的读口读这一份已经加载好的设置；返回的东西 {@code close} 时换回去。
     * 生产代码不调它（替身与大模型那一层只从登记的那一份读）。
     */
    public static AutoCloseable useForTests(ServerSettings settings) {
        testOverride = Objects.requireNonNull(settings, "settings");
        return () -> testOverride = null;
    }

    /**
     * 动脑替身的那一份旋钮（引擎的 {@link SeatPolicySettings}）。设置没加载、或者存着的值引擎不收（它改窄了范围、文件里还是老值）：
     * 用默认值并说一句 —— 不让一局开不起来。
     */
    public static SeatPolicySettings seatPolicy() {
        ServerSettings s = active();
        if (s == null) {
            return SeatPolicySettings.DEFAULTS;
        }
        try {
            return s.table.seatPolicy(s::current);
        } catch (IllegalArgumentException rejected) {
            LOGGER.warn("动脑替身的设置引擎不收（{}），这一局按默认值", rejected.getMessage());
            return SeatPolicySettings.DEFAULTS;
        }
    }

    /** 替身问大模型一个决定最多等多久（毫秒）；设置没加载时是代码里的默认值。 */
    public static long llmDecisionCapMs() {
        ServerSettings s = active();
        return s == null ? StandInSettings.LLM_DECISION_CAP_MS : s.table.llmDecisionCapMs(s::current);
    }

    /**
     * 大模型接入层的那一份设置：「大模型」一组的值，密钥先取设置菜单存的（服务端的密钥文件），没有再看环境变量。
     * <b>结果里的密钥只给接入层发请求用</b>；{@link LlmConfig.Loaded#keySource()} 只说取自哪里。
     * 密钥只在地址对得上时才带（审查 2026-10-07 L1，见 {@link LlmConfig#fromSettings}）。
     *
     * @param env 查环境变量（服务端传 {@code System::getenv}，单测传一张表）
     */
    public static LlmConfig.Loaded llmConfig(Function<String, String> env) {
        ServerSettings s = active();
        if (s == null) {
            return new LlmConfig.Loaded(LlmConfig.off(), null, "服务端设置还没加载，大模型替身关着",
                    LlmConfig.KEY_SOURCE_NONE);
        }
        return LlmConfig.fromSettings(s.table.llmDraft(s::current, s.secret(ServerSettingsTable.LLM_API_KEY).orElse(null)),
                s.secretOrigin(ServerSettingsTable.LLM_API_KEY).orElse(null), env);
    }

    /** 开局时取的时限，连同它取自哪里（进开局那一行日志：「改了设置」与「没改」要分得开）。 */
    public record TimingRead(GameTiming timing, String source) {
    }

    /**
     * 新开一局用的时限（ADR-0099 D8）。设置没加载（不该发生：开局一定在起服之后）就用写死的那一套，并在来源里说出来。
     */
    public static TimingRead gameTiming() {
        ServerSettings settings = registered;
        if (settings == null || !settings.loaded()) {
            LOGGER.warn("开局时服务端设置没加载，这一局用代码里的默认时限");
            return new TimingRead(GameTiming.DEFAULTS, "默认值：服务端设置没加载");
        }
        GameTiming timing = settings.timing();
        return new TimingRead(timing, timing.equals(GameTiming.DEFAULTS) ? "服务端设置 · 全是默认值" : "服务端设置");
    }

    /** 演习艇人不够 6 个时替身补位（{@code stand_ins.fill_empty_seats}）。用到时现读。 */
    public static boolean fillEmptySeats() {
        ServerSettings settings = registered;
        if (settings == null || !settings.loaded()) {
            LOGGER.warn("服务端设置没加载，替身补位按默认（关）");
            return false;
        }
        return settings.table.flag(settings::current, ServerSettingsTable.FILL_SEATS);
    }
}
