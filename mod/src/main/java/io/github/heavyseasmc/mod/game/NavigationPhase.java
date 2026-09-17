package io.github.heavyseasmc.mod.game;

import io.github.heavyseasmc.engine.model.CharacterId;
import io.github.heavyseasmc.engine.navigation.NavigationCard;
import io.github.heavyseasmc.engine.play.Session;
import io.github.heavyseasmc.engine.state.Phase;
import io.github.heavyseasmc.mod.HeavySeasMod;
import io.github.heavyseasmc.mod.net.HelmActionC2S;
import io.github.heavyseasmc.mod.net.HelmAutoPickS2C;
import io.github.heavyseasmc.mod.state.GameComponent;
import io.github.heavyseasmc.mod.state.GameComponents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;

/**
 * 航海阶段的服务端一侧：开挑牌窗口、计时、超时代挑，然后交给 {@link GameFlow#navigate} 结算（ADR-0019）。
 *
 * <h2>规则不在这里</h2>
 * 舵手是谁、划船堆里有什么、挑中之后怎么算，全在 {@link Session} 里 —— 与 {@link ProvisionPhase} 同一条分工。
 * 按收件人裁剪在 {@code GameComponent#writeView}：划船堆的牌只进舵手那一包。
 *
 * <h2>三条路</h2>
 * <ul>
 *   <li>划船堆空，或者没有清醒的舵手 → 当场翻顶牌。<b>舵手一面不出现</b>（ADR-0018 §7.4）；</li>
 *   <li>替身当舵手、自动推进开着 → 当场挑第一张；</li>
 *   <li>其余（真人舵手；替身舵手而开关关着）→ 开 12 秒窗口，超时认当前高亮。</li>
 * </ul>
 *
 * <h2>服务端是计时的权威</h2>
 * 理由同补给箱：客户端自己算超时的话，改过的客户端可以永远不超时，整局就停在航海阶段。
 */
public final class NavigationPhase {

    /** 舵手挑牌的时限（用户 2026-09-15 定）。 */
    public static final long PICK_MILLIS = 12_000L;

    private static final Logger LOGGER = LoggerFactory.getLogger(HeavySeasMod.MOD_ID);

    private NavigationPhase() {
    }

    /** 进入航海阶段时由 {@link GameFlow#announceTurn} 调用。 */
    public static void begin(ServerWorld world, GameComponent component) {
        Session session = component.requireSession();
        if (session.navigationComplete() || component.helmDeadline() > 0) {
            return;                           // 这一回合已经结算过，或者窗口已经开着：别再开一次
        }
        if (session.weatherNavigationPending()) {
            GameFlow.broadcast(world, Text.translatable("heavyseas.game.weather_extra_navigation")
                    .formatted(Formatting.AQUA));
            LOGGER.info("狂风：标准航海前额外翻一张航海牌");
            GameFlow.navigateWeather(world, component);
            return;
        }
        if (session.currentWeather().map(card -> card.effect()
                == io.github.heavyseasmc.engine.weather.WeatherEffect.SKIP_NAVIGATION).orElse(false)) {
            session.skipNavigation();
            GameFlow.broadcast(world, Text.translatable("heavyseas.game.weather_skip_navigation")
                    .formatted(Formatting.AQUA));
            LOGGER.info("风平浪静：跳过航海阶段，照常结束一天并清标记");
            GameFlow.afterNavigation(world, component);
            return;
        }
        // 舵手握着指南针时，挑牌之前多抽一张进划船堆（设计决策 §8.1）。必须排在「能不能挑」之前 ——
        // ❗这一行原先没有：指南针在真实对局里从来不生效，而单测、出口验收、实拍全都是绿的（ADR-0021 §9）。
        int extra = session.prepareRowStack();
        if (extra > 0) {
            CharacterId helmsman = session.state().helmsman().orElseThrow();
            GameFlow.broadcast(world, Text.translatable("heavyseas.game.compass",
                    GameFlow.characterName(helmsman), extra).formatted(Formatting.GRAY));
            LOGGER.info("指南针：舵手 {} 挑牌之前多抽 {} 张进划船堆", helmsman.value(), extra);
        }
        if (!session.helmsmanMayPick()) {
            boolean nobodyRowed = session.table().rowStackIsEmpty();
            GameFlow.broadcast(world, Text.translatable(nobodyRowed
                    ? "heavyseas.game.top_card" : "heavyseas.game.top_card_no_helmsman"));
            LOGGER.info("航海：{}，翻顶牌", nobodyRowed ? "没人划船" : "没有清醒的舵手");
            GameFlow.navigate(world, component, null);
            return;
        }
        CharacterId helm = session.state().helmsman().orElseThrow();
        GameComponent.Occupant who = component.occupantOf(helm).orElseThrow();
        int stack = session.table().rowStack().size();
        GameFlow.broadcast(world, Text.translatable("heavyseas.game.helmsman_picks",
                GameFlow.characterName(helm), stack));
        if (who.isDummy() && component.dummyAutoplay()) {
            LOGGER.info("舵手（替身自动）：{} 从划船堆 {} 张里挑第 1 张", helm.value(), stack);
            resolve(world, component, 0);
            return;
        }
        component.setHelmDeadline(System.currentTimeMillis() + PICK_MILLIS);
        component.setHelmHighlight(0);        // 高亮一进界面就在第一张：它是「你的默认答案」
        LOGGER.info("舵手挑牌：{}（{}）· 划船堆 {} 张 · {} 秒", helm.value(), who.isDummy() ? "替身" : "真人",
                stack, PICK_MILLIS / 1000);
        GameComponents.sync(world);           // 划船堆的牌只进舵手那一包；其余人拿到的是张数与倒计时
    }

    /** 舵手在挑牌一面上的一下：移高亮，或者就执行这张。 */
    public static void onAction(ServerPlayerEntity player, HelmActionC2S action) {
        ServerWorld world = player.getServerWorld();
        GameComponent component = GameComponents.of(world);
        if (component.session().isEmpty() || component.helmDeadline() <= 0) {
            return;
        }
        Session session = component.requireSession();
        if (session.state().phase() != Phase.NAVIGATION) {
            return;
        }
        Optional<CharacterId> helm = session.state().helmsman();
        Optional<CharacterId> seat = component.seatOf(player.getUuid());
        // ❗只有舵手说了算。不校验的话，任何人都能替舵手挑牌 —— 那是全船最强的权力位。
        if (helm.isEmpty() || seat.isEmpty() || !seat.get().equals(helm.get())) {
            return;
        }
        if (action.index() < 0 || action.index() >= session.table().rowStack().size()) {
            return;                           // 越界的下标一律忽略：可能是包与状态擦肩而过
        }
        component.setHelmHighlight(action.index());
        if (action.commit()) {
            LOGGER.info("舵手（界面）：{} 挑了第 {} 张", helm.get().value(), action.index() + 1);
            resolve(world, component, action.index());
        }
    }

    /** 指令那条路（dev）：{@code /seas navigate <牌>} 在窗口里提前定。窗口开没开由指令层先查。 */
    public static void pickByCommand(ServerWorld world, GameComponent component, int index) {
        CharacterId helm = component.requireSession().state().helmsman().orElseThrow();
        LOGGER.info("舵手（指令）：{} 挑了第 {} 张", helm.value(), index + 1);
        resolve(world, component, index);
    }

    /** 每 tick 检查超时。**服务端超时，客户端不参与判定。** */
    public static void tick(MinecraftServer server) {
        for (ServerWorld world : server.getWorlds()) {
            GameComponent component = GameComponents.of(world);
            long deadline = component.helmDeadline();
            if (component.session().isEmpty() || deadline <= 0 || System.currentTimeMillis() < deadline) {
                continue;
            }
            Session session = component.requireSession();
            // 超时认当前高亮（不是随机）；舵手一次都没上报过（替身、离线）时就是第一张。
            int index = Math.min(component.helmHighlight(), Math.max(0, session.table().rowStack().size() - 1));
            CharacterId helm = session.state().helmsman().orElseThrow();
            LOGGER.info("舵手超时：替 {} 挑了第 {} 张（当前高亮）", helm.value(), index + 1);
            // ❗先告诉舵手「这张是替你挑的」，再结算。顺序不能反：结算那一刻就推投影，投影里没了划船堆，
            //   客户端就要关界面了 —— 通知落在它后面，就没有界面来播这一下「顿」（与补给箱同一个坑）。
            notifyAutoPick(world, component, helm, index);
            resolve(world, component, index);
        }
    }

    /** 只发给舵手本人，而且只在他是在线的真人时发：替身没有界面，离线的人也没有。 */
    private static void notifyAutoPick(ServerWorld world, GameComponent component, CharacterId helm, int index) {
        component.occupantOf(helm)
                .map(GameComponent.Occupant::player)
                .map(uuid -> world.getServer().getPlayerManager().getPlayer(uuid))
                .ifPresent(player -> ServerPlayNetworking.send(player, new HelmAutoPickS2C(index)));
    }

    private static void resolve(ServerWorld world, GameComponent component, int index) {
        List<NavigationCard> stack = component.requireSession().table().rowStack();
        NavigationCard pick = stack.get(Math.max(0, Math.min(index, stack.size() - 1)));
        component.clearHelm();
        GameFlow.navigate(world, component, pick);
    }
}
