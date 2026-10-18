package io.github.heavyseasmc.mod.ui;

import io.github.heavyseasmc.mod.net.RequestStamp;

import java.util.Objects;

/**
 * 一个意图一个编号（客户端发请求时用）。
 *
 * <h2>什么算「同一个意图」</h2>
 * 同一份投影下（客户端还没收到服务端的下一帧）发出的<b>同一个请求</b>（同一种动作、同一张牌、同一个目标）：
 * 双击、按键连发、确认键被按了两下。它们复用同一个编号，服务端第二包判重复（{@code RequestGate}），不再动第二张牌。
 *
 * <p>❗此前每点一下就取一个新编号：双击就是两个新请求，手里两张水会被送走两张 —— 编号防不住它本该防的那一种。
 *
 * <h2>不防什么</h2>
 * 看到新投影之后再点一次，是新意图，换新编号（手里还有第二张水，他就是想再送一张）。
 * 这里用「投影换没换」划界，不用计时：服务端处理完第一包一定会推一帧，那一帧之前的重复都只可能是同一下。
 */
public final class IntentNonces {
    private long sequence;
    private Object lastProjection;
    private Object lastIntent;
    private RequestStamp lastStamp;

    /**
     * @param projection 此刻客户端手上的那一份投影（按对象身份比：收到新一帧就是另一个对象）
     * @param intent     不带编号的请求本身（按内容比）
     */
    public synchronized RequestStamp stamp(Object projection, Object intent, String match, String context) {
        Objects.requireNonNull(intent, "intent");
        if (lastStamp != null && projection == lastProjection && intent.equals(lastIntent)
                && lastStamp.matchId().equals(match) && lastStamp.context().equals(context)) {
            return lastStamp;
        }
        lastStamp = new RequestStamp(match, context, ++sequence);
        lastProjection = projection;
        lastIntent = intent;
        return lastStamp;
    }
}
