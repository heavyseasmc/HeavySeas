package io.github.heavyseasmc.mod.client;

import net.minecraft.util.math.MathHelper;

/**
 * ADR-0018 那套 GUI 语言的<b>唯一一份</b>实现：三个语义色、六个动词的时长与距离。
 *
 * <h2>为什么必须只有一份</h2>
 * ADR-0018 §7.2 写着「同一动词在不同界面用不同时长，就又变回十种游戏了」。
 * 而「同一份东西复制两处、各自维护」在本仓库已经被运行结果证伪过 ——
 * 两份 textguard 漂移到新版补的洞旧版没有。数字抄进每个 Screen 会走同一条路：
 * 补给箱那一面调了 300ms，手牌那一面还是 260ms，谁都不会报错。
 *
 * <p>所以各面<b>不许自己写这些数</b>，一律从这里取。改一个动词的含义或手感，
 * 改这里一处，全部界面一起变 —— 这正是 ADR-0018 §8 把「像素值与缓动微调」
 * 划进「实现中打磨」那一列的前提。
 *
 * <h2>动画走墙钟，不走 tick</h2>
 * {@code Screen.render} 的 {@code delta} 是 partial tick，受服务端 tick 影响；
 * 而这些动效是<b>显示</b>的一部分，该跑在显示帧率上。所以入场进度直接拿
 * {@code System.currentTimeMillis()} 算，插值按<b>两帧之间真实过了多少毫秒</b>推，
 * 不按帧数 —— 按帧数的话同一个动作在 30fps 与 144fps 上快慢不同。
 */
public final class GuiLanguage {

    private GuiLanguage() {
    }

    // ---------------------------------------------------------------- 三个语义色

    /** 铜绿：正常 · 可选 · 安全。 */
    public static final int VERDIGRIS = 0xFF5E9C94;

    /** 朱砂：紧迫 · 伤害 · 不可逆。❗只给这三件事 —— 当强调色用，真紧迫时就喊不动了。 */
    public static final int CINNABAR = 0xFFC8573F;

    /** 金：你 · 轮到你。 */
    public static final int GOLD = 0xFFC9A227;

    // 中性色。取自已交付的卡面 SVG —— GUI 与牌面必须是同一个世界。
    /** 纸。正文与卡名。 */
    public static final int INK = 0xFFEAE0C6;
    /** 次要文字。 */
    public static final int MUTED = 0xFF7E8C86;
    /** 更弱的文字（还没轮到的人、已用完的格）。 */
    public static final int DIM = 0xFF55655F;
    /** 底槽：进度条与轨道没走到的那一段。 */
    public static final int GROUND = 0xFF2A3A34;

    /**
     * 罩在 {@code Screen.renderBackground} 那层模糊之上的底色（底 {@code #0B120F} 带透明度）。
     *
     * <p>不铺这一层的话，界面坐在一片灰蓝的 Minecraft 世界上，而牌面是暖纸色的 ——
     * 两个世界。铺上之后 GUI 与牌面才是同一个世界，这是 ADR-0018 §7.3 那句
     * 「GUI 与牌面必须是同一个世界」的落地处。
     */
    public static final int BACKDROP = 0xB80B120F;

    // ---------------------------------------------------------------- 卡面

    /** 卡面的比例基准 5:7。贴图实际按 2 倍烘（600×840），画的时候只看比例。 */
    public static final int CARD_W = 300;
    /** 见 {@link #CARD_W}。 */
    public static final int CARD_H = 420;

    /** 按宽度求卡面高度，保持比例。各面都用它，省得每处自己乘一遍算错。 */
    public static int cardHeight(int width) {
        return Math.round(width * (float) CARD_H / CARD_W);
    }

    /** 按高度求卡面宽度，保持比例。 */
    public static int cardWidth(int height) {
        return Math.round(height * (float) CARD_W / CARD_H);
    }

    // ---------------------------------------------------------------- 发 Deal

    /** 发：新东西到你面前。单张 300ms。 */
    public static final long DEAL_MS = 300L;
    /** 相邻两张错开 45ms —— 错开才读得出是「发」而不是「出现」。 */
    public static final long DEAL_STAGGER_MS = 45L;
    /** 从下方 26px 升起。 */
    public static final float DEAL_RISE = 26f;

    /**
     * 第 {@code index} 张的入场进度，0 → 1。
     *
     * @param index 在这一批里的序号（不是在整行里的下标）—— 错开按这个算
     * @return 0 表示还没轮到它，1 表示已经落位
     */
    public static float deal(long now, long dealtAt, int index) {
        long elapsed = now - dealtAt - index * DEAL_STAGGER_MS;
        if (elapsed <= 0L) {
            return 0f;
        }
        float p = MathHelper.clamp(elapsed / (float) DEAL_MS, 0f, 1f);
        return 1f - (1f - p) * (1f - p) * (1f - p);      // ease-out cubic ≈ cubic-bezier(.16,1,.3,1)
    }

    /** 入场时的缩放：配合升起，让「发」有从手边推出去的分量。 */
    public static float dealScale(float progress) {
        return 0.94f + 0.06f * progress;
    }

    // ---------------------------------------------------------------- 抬 Lift

    /** 抬：这个是当前选中。180ms 到位。 */
    public static final long LIFT_MS = 180L;
    /** 抬起 9px。 */
    public static final float LIFT_PX = 9f;

    /**
     * 向目标插值，<b>按真实经过的毫秒</b>而不是按帧。
     *
     * <p>❗这是 ADR-0018 §7.2「抬」那一条里「插值而非跳变」的实现。直接赋值会让悬停显得很硬；
     * 而按帧插值（{@code lerp(delta * k, …)}）在高刷新率屏幕上会明显更快 ——
     * 同一个动作在两台机器上不一样快，就不是同一门语言了。
     *
     * @param dtMs 距上一帧过了多少毫秒
     */
    public static float approach(float current, float target, long dtMs) {
        if (dtMs <= 0L) {
            return current;
        }
        // 时间常数取 LIFT_MS/3：一个 LIFT_MS 之后走完约 95%，肉眼即「到位」。
        float k = 1f - (float) Math.exp(-dtMs / (LIFT_MS / 3f));
        return MathHelper.lerp(MathHelper.clamp(k, 0f, 1f), current, target);
    }

    /**
     * 「抬」的一次性进度，0 → 1：选中<b>一下子</b>换成了另一个时用（比如手牌大图换了一张）。
     * 持续跟着目标走的用 {@link #approach}。
     *
     * @param startedAt 换的那一刻；0 表示还没换过，直接返回 1（已到位）
     */
    public static float lift(long now, long startedAt) {
        if (startedAt <= 0L) {
            return 1f;
        }
        float p = MathHelper.clamp((now - startedAt) / (float) LIFT_MS, 0f, 1f);
        return 1f - (1f - p) * (1f - p) * (1f - p);      // ease-out，近似 cubic-bezier(.2,.8,.3,1)
    }

    // ---------------------------------------------------------------- 飞 Fly

    /** 飞：<b>归属变了</b>（牌换了主人）。520ms。 */
    public static final long FLY_MS = 520L;
    /** 弧顶抬 54px —— 直线会读成「移动」，弧线才读得出是「交出去」。 */
    public static final float FLY_ARC = 54f;

    // ---------------------------------------------------------------- 滑 Slide

    /** 滑：轮次推进。550ms，带轻微过冲。 */
    public static final long SLIDE_MS = 550L;

    // ---------------------------------------------------------------- 翻 Flip

    /** 翻：暗牌变明牌。全局唯一的「信息状态改变」，400ms，中点换面。 */
    public static final long FLIP_MS = 400L;

    // ---------------------------------------------------------------- 顿 Snap

    /** 顿：<b>系统替你做的</b>。180ms，带过冲 —— 必须与手动看得出不同。 */
    public static final long SNAP_MS = 180L;
}
