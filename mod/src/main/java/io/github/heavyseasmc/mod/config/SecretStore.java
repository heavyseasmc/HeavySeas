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
