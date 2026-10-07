package io.github.heavyseasmc.mod.config;

/**
 * 别的包的单测要一份「已加载」的服务端设置时用：按表展开 FCAP 的 spec、补齐默认值、装上内存里的配置
 * （与 {@link ServerSettingsTest#loaded} 同一个顺序），密钥放给定的存储里。用完 {@link #close()}。
 */
public final class TestSettings implements AutoCloseable {

    private final ServerSettingsTest.Loaded file = new ServerSettingsTest.Loaded();
    private final ServerSettings settings;

    private TestSettings(ServerSettingsTable table, SecretStore secrets) {
        settings = new ServerSettings(table, secrets);
        settings.spec().correct(file.config);
        settings.spec().acceptConfig(file);
    }

    /** 默认表、密钥放内存。 */
    public static TestSettings defaults() {
        return new TestSettings(ServerSettingsTable.DEFAULT, SecretStore.inMemory());
    }

    public static TestSettings of(ServerSettingsTable table, SecretStore secrets) {
        return new TestSettings(table, secrets);
    }

    public ServerSettings settings() {
        return settings;
    }

    /** 这份「文件」存过几次盘。 */
    public int saves() {
        return file.saves;
    }

    /** 文件里某一段某一项此刻写着什么（看密钥有没有漏进会同步的那一份用）。 */
    public Object raw(String key) {
        return file.config.get(java.util.List.of(key.split("\\.")));
    }

    /** 整份配置写成的字（找密钥用）。 */
    public String dump() {
        return file.config.toString();
    }

    @Override
    public void close() {
        settings.spec().acceptConfig(null);
    }
}
