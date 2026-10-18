package io.github.heavyseasmc.mod.ui;

import io.github.heavyseasmc.engine.state.Condition;

/**
 * 头像上盖哪一档伤势裂纹（ADR-0048 §3 当时缺的那张覆盖贴图；木刻素材用户 2026-10-13 选定 R2「粗凿圆环」，朱砂着色）。
 *
 * <p>取档只读引擎给的两样：{@link Condition}（由「伤害 vs 体型」推导，见 {@link Condition#onBoat}）与剩余体力
 * {@code health = 体型 − 伤害}。全是公开信息（决策 ③），座位轨与状态牌用同一张表：
 *
 * <table>
 *   <tr><th>引擎状态</th><th>档</th><th>为什么</th></tr>
 *   <tr><td>清醒 · 没受伤（health ≥ 体型）</td><td>不盖</td><td></td></tr>
 *   <tr><td>清醒 · health ≥ 2 · 伤害 × 2 &lt; 体型</td><td>轻伤 {@link HudPart#CRACK_LIGHT}</td><td>伤了不到一半</td></tr>
 *   <tr><td>清醒 · health ≥ 2 · 伤害 × 2 ≥ 体型</td><td>重伤 {@link HudPart#CRACK_HEAVY}</td><td>伤了一半或更多</td></tr>
 *   <tr><td>清醒 · health = 1</td><td>濒死 {@link HudPart#CRACK_DYING}</td><td>再受 1 点就倒；在水里没有救生圈，
 *       「伤害 = 体型」就是死（{@link Condition#inWater}）—— 离死只差一下</td></tr>
 *   <tr><td>昏迷（health = 0，伤害 = 体型）</td><td>昏迷 {@link HudPart#CRACK_OUT}</td><td>一圈均匀的碎纹：是一种状态，
 *       不是更重的一档；另有压暗与「昏」签</td></tr>
 *   <tr><td>死亡 · 落海移出</td><td>不盖</td><td>深色罩加叉 / 波纹已经说了，再盖裂纹只是噪点</td></tr>
 * </table>
 *
 * <p>❗「濒死」排在「昏迷」之后，是因为素材四格的先后（轻 · 重 · 昏 · 濒死，濒死那一格最重），不是说昏迷比濒死轻 ——
 * 昏迷的人再受一点同样会死；他头像上的压暗与「昏」签已经比裂纹更重。这张表是我方的倾向，等用户实看再定。
 */
public final class HurtCrack {

    private HurtCrack() {
    }

    /** 这一座盖哪一档；不盖返回 {@code null}。 */
    public static HudPart of(Condition condition, int health, int size) {
        if (condition == null || size < 1) {
            return null;
        }
        return switch (condition) {
            case DEAD -> null;
            case UNCONSCIOUS -> HudPart.CRACK_OUT;
            case CONSCIOUS -> {
                int damage = size - health;
                if (damage <= 0) {
                    yield null;
                }
                if (health <= 1) {
                    yield HudPart.CRACK_DYING;
                }
                yield 2 * damage >= size ? HudPart.CRACK_HEAVY : HudPart.CRACK_LIGHT;
            }
        };
    }
}
