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

    /** 纸：画出来的牌面的底色。与 {@link #INK} 是同一个颜色 —— 深底上的正文用的就是纸色，所以引用它，不另写一遍。 */
    public static final int PAPER = INK;
    /** 墨 {@code #241E1A}：印在纸色牌面上的字（ADR-0018 §7.3）。 */
    public static final int CARD_INK = 0xFF241E1A;

    /**
     * 罩在 {@code Screen.renderBackground} 那层模糊之上的底色（底 {@code #0B120F} 带透明度）。
     *
     * <p>不铺这一层的话，界面坐在一片灰蓝的 Minecraft 世界上，而牌面是暖纸色的 ——
     * 两个世界。铺上之后 GUI 与牌面才是同一个世界，这是 ADR-0018 §7.3 那句
     * 「GUI 与牌面必须是同一个世界」的落地处。
     *
     * <p>❗透明度是用户 2026-09-15 从实测四档里定的 {@code 0xE4}。原先的 {@code 0xB8} 下，
     * 界面尺寸一大，Minecraft 自带的聊天就从这层后面透出来、字字可读 —— 而界面刚弹出时
     * 正是开局播报刷屏的时候。{@code 0xE4} 只剩淡影。底色在卡面之下，加深它不影响卡面。
     */
    public static final int BACKDROP = 0xE40B120F;

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

    // ---------------------------------------------------------------- 时间：倒计时

    /**
     * 倒计时从哪一刻起变朱砂。
     *
     * <p>原先写死剩 3 秒。可 2 张牌的倒计时一共只有 4 秒 —— 真实客户端上看，它出现 1 秒就红了，
     * 红了四分之三的时间。朱砂只给紧迫（ADR-0018 §7.3），一直红着就喊不动了。
     * 所以按总长的比例算、夹在 1 到 3 秒之间。比例可调（§8 右列）；「只在最后一段才红」不可调。
     *
     * <p>放在这里而不是某一面里：补给箱与舵手挑牌两面都有倒计时，「什么时候算紧迫」是同一句话。
     */
    public static long urgencyThreshold(long totalMs) {
        return Math.min(3000L, Math.max(1000L, Math.round(totalMs * 0.35)));
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

    /** 「飞」的缓动：{@code cubic-bezier(.3,.8,.25,1)}。 */
    private static final float FLY_X1 = .3f;
    private static final float FLY_Y1 = .8f;
    private static final float FLY_X2 = .25f;
    private static final float FLY_Y2 = 1f;

    /**
     * 「飞」走到哪了，0 → 1（已过缓动）。
     *
     * @param startedAt 起飞的时刻；{@code <= 0} 表示没在飞，返回 0（还在原位）
     */
    public static float fly(long now, long startedAt) {
        if (startedAt <= 0L) {
            return 0f;
        }
        float x = MathHelper.clamp((now - startedAt) / (float) FLY_MS, 0f, 1f);
        return cubicBezier(x, FLY_X1, FLY_Y1, FLY_X2, FLY_Y2);
    }

    /** 还在飞吗。飞完之前界面不许收：牌离开你手里的那一下不能被截断。 */
    public static boolean flying(long now, long startedAt) {
        return startedAt > 0L && now - startedAt < FLY_MS;
    }

    /** 飞行途中弧线额外抬起多少（GUI 单位，正数向上）：起点与终点为 0，正中是 {@link #FLY_ARC}。 */
    public static float flyArc(float progress) {
        return FLY_ARC * 4f * progress * (1f - progress);
    }

    // ---------------------------------------------------------------- 滑 Slide

    /** 滑：轮次推进。550ms，带轻微过冲。 */
    public static final long SLIDE_MS = 550L;

    /**
     * 「滑」的缓动：{@code cubic-bezier(.34,1.3,.5,1)}。y1 = 1.3 冲出 1 —— 越过终点一点再回来，这就是「过冲」。
     *
     * <p>❗数值照交互稿<b>实跑</b>的那一份核对过（{@code slide(el, dx)} 直接用 {@code M.slide.ms}，没有再乘什么），
     * 不重蹈「顿」印 180、跑 396 那一次。
     */
    private static final float SLIDE_X1 = .34f;
    private static final float SLIDE_Y1 = 1.3f;
    private static final float SLIDE_X2 = .5f;
    private static final float SLIDE_Y2 = 1f;

    /**
     * 「滑」走到哪了：0 → 1，<b>中途会略超过 1</b>（过冲）再落回 1。乘上位移就是这一帧该在的位置。
     *
     * @param startedAt 起点；{@code <= 0} 表示没在滑，返回 1（已到位）
     */
    public static float slide(long now, long startedAt) {
        if (startedAt <= 0L) {
            return 1f;
        }
        float x = MathHelper.clamp((now - startedAt) / (float) SLIDE_MS, 0f, 1f);
        return cubicBezier(x, SLIDE_X1, SLIDE_Y1, SLIDE_X2, SLIDE_Y2);
    }

    /** 还在滑吗。 */
    public static boolean sliding(long now, long startedAt) {
        return startedAt > 0L && now - startedAt < SLIDE_MS;
    }

    // ---------------------------------------------------------------- 翻 Flip

    /** 翻：暗牌变明牌。全局唯一的「信息状态改变」，400ms，中点换面。 */
    public static final long FLIP_MS = 400L;

    /** 胜者揭牌：先蓄势、再翻转、最后回弹，整段 1.4 秒。 */
    public static final long WINNER_FLIP_MS = 1400L;

    /** 「翻」的缓动：CSS 的 {@code ease-in-out}，即 {@code cubic-bezier(.42,0,.58,1)}（交互稿的 {@code M.flip.ease}）。 */
    private static final float FLIP_X1 = .42f;
    private static final float FLIP_Y1 = 0f;
    private static final float FLIP_X2 = .58f;
    private static final float FLIP_Y2 = 1f;

    /**
     * 「翻」此刻的横向缩放：rotateY 0° → 90° → 0° 投影到屏幕上的宽度 |cos θ|。
     *
     * <p>GUI 里没有真正的三维旋转，绕竖轴转一张牌在正面看就是宽度收窄再展开 —— 到中点正好侧过来（宽度 0），
     * 就在那一刻换面。下限夹在一个很小的正数：宽度真为 0 的矩阵在有的驱动上会画出一条闪烁的竖线。
     *
     * @param startedAt 起点；{@code <= 0} 表示没在翻，返回 1
     */
    public static float flipScaleX(long now, long startedAt) {
        if (startedAt <= 0L) {
            return 1f;
        }
        float x = MathHelper.clamp((now - startedAt) / (float) FLIP_MS, 0f, 1f);
        float eased = cubicBezier(x, FLIP_X1, FLIP_Y1, FLIP_X2, FLIP_Y2);
        float quarter = eased < .5f ? eased / .5f : (1f - eased) / .5f;        // 0 → 1 → 0
        return Math.max(0.02f, (float) Math.abs(Math.cos(quarter * Math.PI / 2)));
    }

    /**
     * 翻到这一刻，露出来的是不是正面。
     *
     * <p>❗按<b>墙钟的中点</b>换面（稿子里是 {@code setTimeout(after, M.flip.ms / 2)}），不按缓动后的进度 ——
     * ease-in-out 两头对称，两者恰好落在同一刻；换一条不对称的缓动时，照这里写的才仍与稿子一致。
     */
    public static boolean flipShowsFront(long now, long startedAt) {
        return startedAt > 0L && now - startedAt >= FLIP_MS / 2;
    }

    /**
     * 胜者揭牌的横向缩放。前 18% 向外蓄势，中间 64% 翻面，最后 18% 从 1.08 倍收回原尺寸。
     * 这不是把普通翻牌简单放慢：胜者会先展开再回弹，连续并列时每个人都能独立走完整段。
     */
    public static float winnerFlipScaleX(long now, long startedAt) {
        if (startedAt <= 0L) {
            return 1f;
        }
        float x = MathHelper.clamp((now - startedAt) / (float) WINNER_FLIP_MS, 0f, 1f);
        if (x < .18f) {
            return 1f + .08f * smoothStep(x / .18f);
        }
        if (x > .82f) {
            return 1f + .08f * (1f - smoothStep((x - .82f) / .18f));
        }
        float turn = (x - .18f) / .64f;
        float eased = cubicBezier(turn, FLIP_X1, FLIP_Y1, FLIP_X2, FLIP_Y2);
        float quarter = eased < .5f ? eased / .5f : (1f - eased) / .5f;
        return Math.max(0.02f, 1.08f * (float) Math.abs(Math.cos(quarter * Math.PI / 2)));
    }

    /** 胜者揭牌在整段动画中点换到正面。 */
    public static boolean winnerFlipShowsFront(long now, long startedAt) {
        return startedAt > 0L && now - startedAt >= WINNER_FLIP_MS / 2;
    }

    private static float smoothStep(float x) {
        float clamped = MathHelper.clamp(x, 0f, 1f);
        return clamped * clamped * (3f - 2f * clamped);
    }

    // ---------------------------------------------------------------- 顿 Snap

    /**
     * 顿：<b>系统替你做的</b>。带过冲 —— 必须与手动看得出不同。
     *
     * <h2>为什么是 396 而不是 ADR-0018 §7.2 写的 180</h2>
     * 交互稿里参数表记的是 {@code snap: { ms: 180 }}，可 {@code snap()} 传给动画的是
     * {@code M.snap.ms * 2.2}。<b>用户看过并说「就定这版」的是跑出来那个</b>，
     * 所以实现按 396ms —— 六个动词里只有它这样，其余五个印的与跑的一致。
     *
     * <p>ADR 是快照不回头改（文档纪律），这条记在 CURRENT_STATUS 的 O20 与
     * ENGINEERING 的证伪表里：<b>同源保的是参数，不是数值</b>。
     */
    public static final long SNAP_MS = 396L;

    /** 「顿」的缓动：{@code cubic-bezier(.6,-.3,.4,1.3)}，两头都冲出 [0,1]。 */
    private static final float SNAP_X1 = .6f;
    private static final float SNAP_Y1 = -.3f;
    private static final float SNAP_X2 = .4f;
    private static final float SNAP_Y2 = 1.3f;

    /** 关键帧：进度 → 上抬像素（负号向上）。与交互稿逐帧相同。 */
    private static final float[] SNAP_STOPS = {0f, .45f, .70f, 1f};
    private static final float[] SNAP_RISE = {0f, -6f, 2f, 0f};
    private static final float[] SNAP_SCALE = {1f, 1.07f, .98f, 1f};

    /** 「顿」峰值时最多抬起多少（GUI 单位，正数）。 */
    public static final float SNAP_PEAK_RISE;
    /** 「顿」峰值时的最大缩放。卡绕底边缩放，所以它同时决定卡顶要多留多少空。 */
    public static final float SNAP_PEAK_SCALE;

    static {
        // ❗从关键帧现算，不另写两个常量。今天刚记进证伪表：同源保的是参数不是数值 ——
        //   把 6 和 1.07 再抄一遍，改了关键帧而版面不跟着改，卡就会压上座位轨而没人报错。
        //   缓动两头会冲出 [0,1]，外推出的值不会超过这两个峰（外推段都朝静止方向走）。
        float rise = 0f;
        float scale = 1f;
        for (float v : SNAP_RISE) {
            rise = Math.max(rise, -v);
        }
        for (float v : SNAP_SCALE) {
            scale = Math.max(scale, v);
        }
        SNAP_PEAK_RISE = rise;
        SNAP_PEAK_SCALE = scale;
    }

    /**
     * 「顿」走到哪了，0 → 1（线性，未过缓动）。
     *
     * @param startedAt 起点；{@code <= 0} 表示没在播，返回 1（已结束）
     */
    public static float snap(long now, long startedAt) {
        if (startedAt <= 0L) {
            return 1f;
        }
        return MathHelper.clamp((now - startedAt) / (float) SNAP_MS, 0f, 1f);
    }

    /** 「顿」此刻的上抬量（GUI 单位，负号向上）。 */
    public static float snapRise(float progress) {
        return sampleSnap(SNAP_RISE, cubicBezier(progress, SNAP_X1, SNAP_Y1, SNAP_X2, SNAP_Y2));
    }

    /** 「顿」此刻的缩放。 */
    public static float snapScale(float progress) {
        return sampleSnap(SNAP_SCALE, cubicBezier(progress, SNAP_X1, SNAP_Y1, SNAP_X2, SNAP_Y2));
    }

    /**
     * 按缓动后的进度取关键帧。
     *
     * <p>❗缓动后的进度会<b>冲出 [0,1]</b>（y1 = -.3、y2 = 1.3 就是为此写的），
     * 这时按最近的那一段<b>外推</b>而不是夹住 —— 夹住等于把过冲抹掉，
     * 而过冲正是「顿」与「抬」唯一看得出的区别。
     */
    private static float sampleSnap(float[] values, float eased) {
        int i = 0;
        while (i < SNAP_STOPS.length - 2 && eased > SNAP_STOPS[i + 1]) {
            i++;
        }
        float span = SNAP_STOPS[i + 1] - SNAP_STOPS[i];
        float t = (eased - SNAP_STOPS[i]) / span;              // 越界时 t 自然落在 [0,1] 之外
        return values[i] + (values[i + 1] - values[i]) * t;
    }

    /**
     * 三次贝塞尔缓动：给横轴进度，求纵轴。「顿」与「飞」共用。
     *
     * <p>CSS 的 {@code cubic-bezier(x1,y1,x2,y2)} 定的是一条参数曲线，横轴不是参数本身 ——
     * 要先按 x 解出参数 t，再拿 t 求 y。用牛顿迭代解；导数太小时退回二分，
     * 免得在曲线平坦处除出一个巨大的步长。
     */
    private static float cubicBezier(float x, float x1, float y1, float x2, float y2) {
        if (x <= 0f) {
            return 0f;
        }
        if (x >= 1f) {
            return 1f;
        }
        float t = x;
        for (int i = 0; i < 8; i++) {
            float dx = bezier(t, x1, x2) - x;
            if (Math.abs(dx) < 1e-5f) {
                break;
            }
            float slope = bezierSlope(t, x1, x2);
            if (Math.abs(slope) < 1e-5f) {
                break;
            }
            t = MathHelper.clamp(t - dx / slope, 0f, 1f);
        }
        return bezier(t, y1, y2);
    }

    /** 首末控制点固定在 0 与 1 的三次贝塞尔。 */
    private static float bezier(float t, float a, float b) {
        float u = 1f - t;
        return 3f * u * u * t * a + 3f * u * t * t * b + t * t * t;
    }

    private static float bezierSlope(float t, float a, float b) {
        float u = 1f - t;
        return 3f * u * u * a + 6f * u * t * (b - a) + 3f * t * t * (1f - b);
    }
}
