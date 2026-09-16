package io.github.heavyseasmc.mod.world;

import io.github.heavyseasmc.engine.model.CharacterId;
import io.github.heavyseasmc.engine.model.Provision;
import io.github.heavyseasmc.engine.model.ProvisionEffect;
import io.github.heavyseasmc.engine.play.Session;
import io.github.heavyseasmc.engine.state.GameState;
import io.github.heavyseasmc.engine.state.SurvivorState;
import io.github.heavyseasmc.mod.HeavySeasMod;
import io.github.heavyseasmc.mod.game.GameFlow;
import io.github.heavyseasmc.mod.state.GameComponent;
import net.minecraft.scoreboard.ServerScoreboard;
import net.minecraft.scoreboard.Team;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 头顶信息条（决策 ⑥）：<b>所有亮出的牌走这里</b>。
 *
 * <h2>只写公开的东西</h2>
 * 决策 ⑥ 自己划了范围 ——「亮出的牌」。所以这里一个字都不涉及手牌内容、爱恨、押下的武器：
 * 面前那一区规则上本来就是公开的，手牌只公开<b>张数</b>，伤害公开。
 * 这与投影那一套是同一条线，只是换了个显示的地方。
 *
 * <h2>精度按博弈需要，不按好看</h2>
 * 决策 ⑥ 的那张表写得很死：<b>战斗总加值必须精确</b>（让人自己数模型，数错就会做出错误的站队决策）；
 * 能不能抗落水是布尔值；身上财宝只要<b>粗略量级</b>（决定抢不抢你）。
 * 水、医疗箱、血饵、指南针<b>根本不必显示</b> —— 它们亮出来的唯一理由是防小孩偷，不是给人看的。
 *
 * <h2>为什么用计分板队伍，不生一堆实体</h2>
 * 队伍的前后缀由 Minecraft 直接画在玩家名牌上：零新实体、零客户端代码、零投影改动。
 * 用悬浮文字实体的话，八个人就是八个要管生死的东西 —— 而座位那一刀刚教过：
 * <b>实体会进存档，忘了收不报错</b>。
 *
 * <p>❗但队伍本身也进存档（scoreboard.dat），所以退出路径同样要收：见 {@link #clear}。
 * 这是同一个形状的坑，只是换了个地方。
 */
public final class Nameplates {

    private static final Logger LOGGER = LoggerFactory.getLogger(HeavySeasMod.MOD_ID);

    /**
     * 队伍名前缀。
     *
     * <p>❗队伍名有 16 个字符的上限，而<b>超长就得截断，截断就会撞名</b> ——
     * 第一版用 {@code heavyseas_} 加玩家名，前缀就吃掉 10 个，只剩 6 个给名字：
     * 两个前六位相同的玩家会共用一个队伍，于是两块名牌显示同一份数据。实测截出来的是
     * {@code heavyseas_DemoPl} 与 {@code heavyseas_Deckha}，这一局侥幸没撞。
     * 改用<b>角色 id</b>：一局里天然唯一，最长的 {@code collector} 也只有 12 个字符。
     */
    /** 同名外部队伍或已有玩家队伍只报一次，避免每次组件同步都刷日志。 */
    private static final Set<String> REPORTED_CONFLICTS = new HashSet<>();

    private Nameplates() {
    }

    /**
     * 照现在的局面把每个人的名牌重写一遍。
     *
     * <p>与座位同一条：<b>这是投影，不是状态</b>。每次同步都走一遍，便宜且幂等。
     */
    public static void refresh(ServerWorld world, GameComponent component) {
        if (component.session().isEmpty()) {
            return;
        }
        Session session = component.requireSession();
        GameState g = session.state();
        ServerScoreboard board = world.getServer().getScoreboard();
        for (CharacterId id : g.bySeat()) {
            ServerPlayerEntity player = component.occupantOf(id)
                    .filter(o -> !o.isDummy())
                    .map(o -> world.getServer().getPlayerManager().getPlayer(o.player()))
                    .orElse(null);
            if (player == null) {
                continue;                     // 替身没有身体，也就没有名牌（ADR-0024 §7.6）
            }
            Team team = teamFor(board, player, id);
            if (team == null) {
                continue;                     // 外部队伍优先：名牌可以缺，权限/友伤/格式不能被我们改掉
            }
            // 前缀：他是谁。世界里此前完全看不出「那个人是大副」——名字是 MC 账号名。
            Text prefix = Text.empty().append(GameFlow.characterName(id)).append(Text.literal(" "))
                    .formatted(Formatting.GOLD);
            Text suffix = Text.literal(" ").append(bar(session, g, id, component));
            // ❗没变就不写。refresh 挂在每次同步上，而 setPrefix/setSuffix 每调一次就广播一个队伍更新包 ——
            //   一个回合几十次同步，等于几十个没有信息量的包。
            if (!prefix.equals(team.getPrefix()) || !suffix.equals(team.getSuffix())) {
                team.setPrefix(prefix);
                team.setSuffix(suffix);
                board.updateScoreboardTeam(team);
            }
        }
    }

    /**
     * 一个人的那一条。
     *
     * <p>顺序照决策 ⑥ 的那一行：伤害 · 战斗总加值 · 救生圈 · 财宝量级 · 手牌数。
     */
    private static Text bar(Session session, GameState g, CharacterId id, GameComponent component) {
        SurvivorState st = g.stateOf(id);
        int size = g.roster().get(id).size();
        Text out = Text.empty();
        out = out.copy().append(Text.translatable("heavyseas.plate.health", size - st.damage(), size)
                .formatted(st.damage() >= size ? Formatting.DARK_RED : Formatting.GRAY));

        int power = 0;
        int treasure = 0;
        boolean preserver = false;
        for (String card : st.front()) {
            Provision provision = session.provisions().get(card);
            power += provision.weaponPower();
            if (provision.category() == Provision.Category.TREASURE) {
                treasure++;
            }
            // ❗问**效果**不问 id：抗落水这件事由 PreventOverboardDamage 定义，
            //   写死一个物资 id 的话，将来多一张同效果的牌就会安静地漏掉（判据里的字面量是定时器）。
            if (provision.effect() instanceof ProvisionEffect.PreventOverboardDamage) {
                preserver = true;
            }
        }
        if (power > 0) {
            // ❗必须精确：让人从亮着的牌自己数，数错就会做出错误的站队决策（决策 ⑥）。
            out = out.copy().append(Text.translatable("heavyseas.plate.power", power)
                    .formatted(Formatting.RED));
        }
        if (preserver) {
            out = out.copy().append(Text.translatable("heavyseas.plate.preserver")
                    .formatted(Formatting.AQUA));
        }
        if (treasure > 0) {
            // 粗略量级就够：这一项只用来决定「抢不抢你」，而抢一次只拿走一张。
            out = out.copy().append(Text.translatable("heavyseas.plate.treasure", treasure)
                    .formatted(Formatting.YELLOW));
        }
        if (!st.hand().isEmpty()) {
            // 只给张数 —— 内容是隐藏信息，而张数本来就公开（决策 ⑫ 的可见性表）。
            out = out.copy().append(Text.translatable("heavyseas.plate.hand", st.hand().size())
                    .formatted(Formatting.GRAY));
        }
        if (component.designating().map(id::equals).orElse(false)) {
            // 「抢夺中」那一条（决策 ⑦）。小孩没有预告，所以那时候组件里根本不会记这一笔 —— 见 DesignationPhase。
            out = out.copy().append(Text.translatable("heavyseas.plate.designating")
                    .formatted(Formatting.RED));
        }
        return out;
    }

    private static Team teamFor(ServerScoreboard board, ServerPlayerEntity player, CharacterId id) {
        String name = NameplateTeamNames.teamName(id.value());
        Team team = board.getTeam(name);
        if (team != null && !NameplateTeamNames.owns(name, team.getDisplayName().getString())) {
            reportConflict(name, "同名队伍不属于 HeavySeas");
            return null;
        }
        if (team == null) {
            team = board.addTeam(name);
            team.setDisplayName(Text.literal(NameplateTeamNames.marker(name)));
            board.updateScoreboardTeam(team);
        }
        String holder = player.getNameForScoreboard();
        Team current = board.getScoreHolderTeam(holder);
        if (!NameplateTeamNames.mayJoin(name, current == null ? null : current.getName())) {
            reportConflict(holder, "玩家已经属于队伍 " + current.getName());
            return null;
        }
        // 同理：已经在队里就别再加一次，否则每次同步都会广播一遍成员变更。
        if (!team.getPlayerList().contains(holder)) {
            board.addScoreHolderToTeam(holder, team);
        }
        return team;
    }

    private static void reportConflict(String key, String detail) {
        if (REPORTED_CONFLICTS.add(key)) {
            LOGGER.warn("头顶信息条：{}；保留原队伍，不接管 {}", detail, key);
        }
    }

    /**
     * 收摊：把这一局建的队伍全删掉。
     *
     * <p>❗队伍进存档（scoreboard.dat）。不删的话，下一局、甚至下一次起服，
     * 名牌上还挂着上一局的体力与牌数 —— <b>而那与「刚更新过」长得一模一样</b>。
     * 与座位的孤儿是同一个形状的坑。
     */
    public static void clear(ServerWorld world) {
        ServerScoreboard board = world.getServer().getScoreboard();
        List<Team> ours = new ArrayList<>();
        for (Team team : board.getTeams()) {
            if (NameplateTeamNames.owns(team.getName(), team.getDisplayName().getString())) {
                ours.add(team);
            }
        }
        for (Team team : ours) {
            board.removeTeam(team);
        }
        if (!ours.isEmpty()) {
            // 0 不打：这一句只在真的删过东西时才有意义（与座位收摊同一个写法）。
            LOGGER.info("头顶信息条：收摊，删了 {} 个队伍", ours.size());
        }
        REPORTED_CONFLICTS.clear();
    }
}
