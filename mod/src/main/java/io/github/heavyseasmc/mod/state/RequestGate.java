package io.github.heavyseasmc.mod.state;

import io.github.heavyseasmc.mod.net.RequestStamp;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 一个意图只认一次：每人一个「已收到的最大编号」（Minecraft 同一条连接上的包按序到达）。
 *
 * <h2>它防什么、不防什么</h2>
 * <ul>
 *   <li>防：同一个意图被发了两次（双击、按键连发、同一份投影下重复确认）—— 客户端对同一个意图复用同一个编号
 *       （{@code IntentNonces}），第二包在这里判成 {@link Verdict#DUPLICATE}，不再动一张牌；</li>
 *   <li>防：上一局、上一条连接、上一扇窗（医疗箱目标、表态那一场……）留下的请求落进这一扇 —— 判 {@link Verdict#STALE}；</li>
 *   <li>不防：看到新投影之后又点了一次。那是新意图，换新编号，照常过；牌还在不在由规则守卫另问。</li>
 * </ul>
 * 编号只是身份，从不替规则放行：过了这一道照样要过引擎与 {@code CardAvailability}。
 */
public final class RequestGate {
    public enum Verdict { NEW, DUPLICATE, STALE }

    /** 场景与连接之间的分隔：{@code <对局/回合/阶段/这一场/医疗箱>#c<连接号>}。 */
    static final String CONNECTION_MARK = "#c";

    private final Map<UUID, Long> accepted = new HashMap<>();

    /**
     * @param anyWindow 这件事的意思不随窗口变（喝酒、亮牌、送牌）：只核对局与连接，不核对回合 / 阶段 / 这一场。
     *                  否则阶段切换那一瞬间发出的一口合法的酒，会因为场景串对不上被判过期
     */
    public Verdict admit(UUID player, RequestStamp stamp, String match, String context, boolean anyWindow) {
        if (!stamp.present() || !stamp.matchId().equals(match)) {
            return Verdict.STALE;
        }
        boolean sameScene = anyWindow
                ? connectionOf(stamp.context()).equals(connectionOf(context))
                : stamp.context().equals(context);
        if (!sameScene) {
            return Verdict.STALE;
        }
        if (stamp.nonce() <= accepted.getOrDefault(player, 0L)) {
            return Verdict.DUPLICATE;
        }
        accepted.put(player, stamp.nonce());
        return Verdict.NEW;
    }

    public Verdict admit(UUID player, RequestStamp stamp, String match, String context) {
        return admit(player, stamp, match, context, false);
    }

    private static String connectionOf(String context) {
        int mark = context.lastIndexOf(CONNECTION_MARK);
        return mark < 0 ? "" : context.substring(mark);
    }

    public void reconnect(UUID player) { accepted.remove(player); }
    public void clear() { accepted.clear(); }
}
