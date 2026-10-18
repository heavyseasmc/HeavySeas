package io.github.heavyseasmc.mod.ui;

/**
 * 望远镜柜右键进入的望远镜视角（ADR-0109 §2 ③）的纯规则：拉近多少、灵敏度跟着降多少、暗角多大、什么时候必须退出。
 * 不碰 Minecraft 客户端类，单测直接对它（{@code TelescopeSightTest}）；画与接按键在客户端的 {@code TelescopeEyepiece}。
 *
 * <h2>为什么「什么时候退出」要单独成一张表</h2>
 * 用户 2026-10-17：「再按一下或 Shift 退出；切走界面、下船、开局都要退得出来」。视角是客户端自己的状态，服务端不知道它，
 * 没有任何一个包会来「收」它 —— 漏一种情况，人就被困在拉近的画面里（拉近时灵敏度也低，连转身都难）。
 * 所以每 tick 把此刻的处境拍成一帧（{@link Frame}），按这张表逐条问一遍，第一条成立的就是退出理由。
 */
public final class TelescopeSight {

    /**
     * 拉近后视野是原来的几分之几：1/5。游戏自带的望远镜是 1/10（70° → 7°），在船上看海面太窄 ——
     * 船在雾里，看得远的只有一圈海面与远处的灯，1/5（70° → 14°，约 5.7 倍）既看得出「拉近了」，又找得到要看的东西。
     */
    public static final double ZOOM = 0.2;
    /** 每 tick 往目标走剩下的这么多（同游戏自带的视野缓动 0.5）：约 4 tick（0.2 秒）基本到位，不是一下跳过去。 */
    public static final double EASE = 0.5;
    /** 走到离目标不到这么多就当到了（不然半衰永远到不了 0，退出后暗角会一直剩一丝）。 */
    static final double SNAP = 0.01;
    /** 离柜子（眼睛到柜子那一格正中）多远就算走开了：2.5 格。站在瞭望台上任何一处看柜子都在 2 格以内。 */
    public static final double LEAVE_DISTANCE = 2.5;
    /** 隔得远右键进来的人（交互距离 4.5 格）：以进来时的距离再宽这么多为界，免得一进来就判「走开了」。 */
    public static final double LEAVE_SLACK = 0.75;
    /** 一 tick 里挪了这么多格就是被传送了（走、跑、掉落都到不了：最快的掉落一 tick 不到 4 格）。 */
    public static final double TELEPORT_STEP = 4.0;

    /** 暗角：清晰的圆的半径（屏幕短边的几分之几），往外渐暗到 {@link #RIM_INNER}、到 {@link #RIM_OUTER} 全黑。 */
    public static final float CLEAR_RADIUS = 0.40f;
    public static final float RIM_INNER = 0.47f;
    public static final float RIM_OUTER = 0.50f;
    /** 渐暗那一圈到 {@link #RIM_INNER} 时有多黑（0–1）；再往外到全黑。 */
    public static final float RIM_INNER_ALPHA = 0.85f;

    private TelescopeSight() {
    }

    /** 为什么退出了。日志与单测用；声音接口点也按它分（自己按的 / 被打断的）。 */
    public enum Exit {
        /** 再按一下使用键（右键）。 */
        USE_KEY,
        /** 按潜行（Shift）。 */
        SNEAK,
        /** 开了任何一面界面（游戏菜单 · 聊天 · 背包 · 对局各面 · 死亡界面……）。 */
        SCREEN,
        /** 走开了（离柜子超过 {@link #leaveLimit}）。 */
        LEFT,
        /** 一 tick 里挪了 {@link #TELEPORT_STEP} 格以上：被传送了（开局被送到艇上 · 魔镜 · 指令）。 */
        TELEPORTED,
        /** 世界换了：换维度、重生之后客户端换了一个世界对象。 */
        WORLD_CHANGED,
        /** 死了，或客户端换了一个玩家对象（重生）。 */
        DIED,
        /** 断线 / 退出世界：客户端没有世界或没有玩家了。 */
        DISCONNECTED,
        /** 这个人进了一局（HUD 有对局了）：开局那一刻。 */
        VOYAGE,
        /** 柜子没了（被拆 · 换船重摆）。 */
        CABINET_GONE,
        /** 换了人称（F5）：望远镜视角只在第一人称里成立（进来时已切到第一人称，再按 F5 就是要出去）。 */
        PERSPECTIVE
    }

    /**
     * 此刻的处境，一 tick 一帧。
     *
     * @param connected    客户端有世界、有玩家
     * @param sameWorld    世界对象还是进来时那一个
     * @param samePlayer   玩家对象还是进来时那一个（重生会换）
     * @param alive        玩家活着
     * @param screenOpen   有界面开着
     * @param sneaking     潜行键按着
     * @param inVoyage     这个人的 HUD 里有一局
     * @param cabinetThere 柜子那一格还是望远镜柜
     * @param distance     眼睛到柜子那一格正中多远（格）
     * @param step         这一 tick 比上一 tick 挪了多远（格）
     * @param firstPerson  此刻是第一人称
     */
    public record Frame(boolean connected, boolean sameWorld, boolean samePlayer, boolean alive, boolean screenOpen,
                        boolean sneaking, boolean inVoyage, boolean cabinetThere, double distance, double step,
                        boolean firstPerson) {
    }

    /** 能不能进：潜行着（那一下右键本该是别的意思）、开着界面、在对局里都不进。 */
    public static boolean canEnter(boolean sneaking, boolean screenOpen, boolean inVoyage) {
        return !sneaking && !screenOpen && !inVoyage;
    }

    /** 走开的界线：{@link #LEAVE_DISTANCE}，或者进来时的距离再宽 {@link #LEAVE_SLACK}，取大的。 */
    public static double leaveLimit(double entryDistance) {
        return Math.max(LEAVE_DISTANCE, entryDistance + LEAVE_SLACK);
    }

    /**
     * 这一帧该不该退出：按下面的次序第一条成立的就是理由，都不成立是 {@code null}。
     * 次序只影响记下来的理由（几条同时成立时报最根本的那一条：断线先于换世界先于死亡……），不影响退不退。
     */
    public static Exit exit(Frame f, double limit) {
        if (!f.connected()) {
            return Exit.DISCONNECTED;
        }
        if (!f.sameWorld()) {
            return Exit.WORLD_CHANGED;
        }
        if (!f.samePlayer() || !f.alive()) {
            return Exit.DIED;
        }
        if (f.inVoyage()) {
            return Exit.VOYAGE;
        }
        if (f.screenOpen()) {
            return Exit.SCREEN;
        }
        if (f.sneaking()) {
            return Exit.SNEAK;
        }
        if (!f.firstPerson()) {
            return Exit.PERSPECTIVE;
        }
        if (!f.cabinetThere()) {
            return Exit.CABINET_GONE;
        }
        if (f.step() > TELEPORT_STEP) {
            return Exit.TELEPORTED;
        }
        if (f.distance() > limit) {
            return Exit.LEFT;
        }
        return null;
    }

    /** 拉近的进度（0 = 没拉近，1 = 拉到底）往目标走一 tick。 */
    public static double approach(double progress, boolean active) {
        double target = active ? 1 : 0;
        double next = progress + (target - progress) * EASE;
        return Math.abs(target - next) < SNAP ? target : next;
    }

    /** 进度 {@code progress} 时视野乘多少：1 → {@link #ZOOM}。 */
    public static double fovFactor(double progress) {
        double p = Math.max(0, Math.min(1, progress));
        return 1 + (ZOOM - 1) * p;
    }

    /**
     * 视野乘了 {@code factor} 之后，鼠标灵敏度乘多少：让同样一下鼠标在屏幕上挪的距离不变。
     * 屏幕上一个像素对应的角度正比于 {@code tan(半视角)}，所以按两个视角的半角正切之比 —— 70° 拉到 14° 时约 0.175。
     * （游戏自带的望远镜是固定的 1/8，比这个比例略快一点；拉得更近时差得更多。）
     */
    public static double sensitivityScale(double baseFovDegrees, double factor) {
        double base = Math.toRadians(Math.max(1, Math.min(179, baseFovDegrees))) / 2;
        double zoomed = base * Math.max(0, Math.min(1, factor));
        return Math.tan(zoomed) / Math.tan(base);
    }

    /**
     * 使用键（右键）的闸：进来的那一下右键按着不放时，游戏每 4 tick 会再「用」一次 —— 不拦就当场又退出去；
     * 右键退出之后还按着，4 tick 后又「用」到柜子上 —— 不拦就又进来了。所以：进来之后、退出之后都要先松开一次右键。
     */
    public static final class UseGate {
        private boolean armed;
        private boolean waitRelease;

        /** 进来了：这一下右键还按着，松开之前不算「再按一下」。 */
        public void entered() {
            armed = false;
            waitRelease = false;
        }

        /** 被别的理由退出了（潜行 · 界面 · 走开……）：右键照常。 */
        public void exitedOtherwise() {
            armed = false;
            waitRelease = false;
        }

        /** 每 tick 报一次右键此刻按没按着。 */
        public void tick(boolean usePressed, boolean active) {
            if (!usePressed) {
                waitRelease = false;
                if (active) {
                    armed = true;
                }
            }
        }

        /** 游戏要「用」一次（{@code doItemUse}）时问：{@link Verdict#EXIT} 退出并吞掉、{@link Verdict#SWALLOW} 只吞掉、{@link Verdict#PASS} 放行。 */
        public Verdict use(boolean active) {
            if (active) {
                if (armed) {
                    armed = false;
                    waitRelease = true;
                    return Verdict.EXIT;
                }
                return Verdict.SWALLOW;
            }
            return waitRelease ? Verdict.SWALLOW : Verdict.PASS;
        }
    }

    /** {@link UseGate#use} 的回答。 */
    public enum Verdict {
        PASS, SWALLOW, EXIT
    }
}
