package io.github.heavyseasmc.mod.config;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 密钥放哪（ADR-0096 §8 · ADR-0099 D6）：<b>只在服务端</b>，不进 FCAP 的 SERVER 那份（那一份进服时整份发给每个客户端），
 * 也不进快照包。菜单里只写不读：客户端能「设一个新值」或「清掉」，永远拿不到存着的值。
 *
 * <p>读它的只有服务端自己（大模型那一路发请求时取密钥，第三刀接）。
 */
public interface SecretStore {

    /** 这一项设了没有（快照里只给这个）。 */
    boolean isSet(String key);

    /** 存着的值 —— <b>只给服务端自己用</b>，不许经任何包发出去、不许进日志。 */
    Optional<String> get(String key);

    /**
     * 设一个新值；空串 = 清掉。
     *
     * @throws RuntimeException 写盘失败（调用方说一句、这一下不算数）
     */
    void put(String key, String value);

    /**
     * 一次设几项（空串 = 清掉）：密钥与它绑的地址要一起落盘（审查 2026-10-07 L1），不能只落一半。
     * 默认逐项 {@link #put}；存在文件里的那一种覆写成一次原子写。
     */
    default void putAll(Map<String, String> entries) {
        entries.forEach(this::put);
    }

    /**
     * 存储读坏了（文件在、读不出来），这一次运行里不写它（审查 2026-10-07 C3：写回去会把原文件整份盖掉，
     * 而它多半只是被人用记事本加了一行备注）。{@link #put} 抛这个；消息里只有路径，没有值。
     */
    final class Unreadable extends IllegalStateException {
        public Unreadable(String message) {
            super(message);
        }
    }

    /** 只在内存里的那种：单测与「文件开不了」时用。 */
    static SecretStore inMemory() {
        Map<String, String> values = new ConcurrentHashMap<>();
        return new SecretStore() {
            @Override
            public boolean isSet(String key) {
                return values.containsKey(key);
            }

            @Override
            public Optional<String> get(String key) {
                return Optional.ofNullable(values.get(key));
            }

            @Override
            public void put(String key, String value) {
                if (value == null || value.isEmpty()) {
                    values.remove(key);
                } else {
                    values.put(key, value);
                }
            }

            @Override
            public String toString() {
                return "SecretStore[内存，" + values.size() + " 项]";   // ❗不带值
            }
        };
    }
}
