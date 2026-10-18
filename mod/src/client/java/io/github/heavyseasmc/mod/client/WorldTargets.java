package io.github.heavyseasmc.mod.client;

import io.github.heavyseasmc.mod.state.GameComponents;
import io.github.heavyseasmc.mod.state.HudView;
import io.github.heavyseasmc.mod.state.TableView;
import io.github.heavyseasmc.mod.world.StandInEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 世界里「船上能指的人」与「指着谁」—— 世界右键（{@link WorldInteraction}）与指定模式（{@link DesignationAim}）共用这一份。
 *
 * <p>原先两边各写一套：认身体、量距离、按方向挑人，世界右键那一套还每 tick 把世界里全部实体逐座翻一遍（2026-10-13 验收）。
 * 现在认身体只走两条现成的路：真人按占座玩家的 UUID 在玩家表里认，替身按角色在玩家周围一个框里认（按区块索引取，不翻全世界）。
 *
 * <p>只认公开的东西：座位表里的人（不含自己与已移出的），身体在这个世界里、离得不远。
 */
final class WorldTargets {

    /** 一个能指的人：角色 · 第几座（0 起）· 他的身体。 */
    record Person(String character, int seat, Entity body) {
    }

    /** 多远以内算船上（格）：一条艇八座、座距 2。 */
    static final double MAX_DIST = 24.0;

    private WorldTargets() {
    }

    /** 船上能指的人，按座位次序（船头到船尾）。 */
    static List<Person> collect(MinecraftClient client, HudView view) {
        List<Person> result = new ArrayList<>();
        if (client.world == null || client.player == null) {
            return result;
        }
        List<TableView.Seat> seats = GameComponents.of(client.world).tableView().seats();
        Map<String, Entity> players = new HashMap<>();
        for (PlayerEntity p : client.world.getPlayers()) {
            players.put(p.getUuidAsString(), p);
        }
        Map<String, Entity> standIns = new HashMap<>();
        Box near = client.player.getBoundingBox().expand(MAX_DIST);
        for (StandInEntity s : client.world.getEntitiesByClass(StandInEntity.class, near, e -> true)) {
            standIns.put(s.character(), s);
            standIns.put(s.getUuidAsString(), s);
        }
        for (int i = 0; i < seats.size(); i++) {
            TableView.Seat seat = seats.get(i);
            if (seat.removed() || seat.id().equals(view.character())) {
                continue;
            }
            Entity body = seat.occupant().isEmpty() ? standIns.get(seat.id())
                    : players.getOrDefault(seat.occupant(), standIns.get(seat.occupant()));
            if (body != null && body.squaredDistanceTo(client.player) <= MAX_DIST * MAX_DIST) {
                result.add(new Person(seat.id(), i, body));
            }
        }
        return result;
    }

    /**
     * 准星<b>直接压在</b>谁的身体上（用户 2026-10-13：「世界右键只在准星压在身体上才弹」）；没有就是 {@code null}。
     *
     * <p>不再看「大概方向」：原先 12° 以内都算指着，坐成一排时随便一看就弹出菜单。
     * 被方块挡着的不算（准星先打到的方块比身体近）。射线自己量到 {@link #MAX_DIST}，不受交互距离限制 ——
     * 坐成一排时后面那几位本来就在交互距离之外（ADR-0097 §1 第 2 条）。
     */
    static Person underCrosshair(MinecraftClient client, List<Person> people) {
        if (client.player == null) {
            return null;
        }
        Vec3d eye = client.player.getCameraPosVec(1f);
        Vec3d look = client.player.getRotationVec(1f);
        Vec3d end = eye.add(look.multiply(MAX_DIST));
        Person hit = null;
        double nearest = Double.POSITIVE_INFINITY;
        for (Person person : people) {
            var at = person.body().getBoundingBox().raycast(eye, end);
            if (at.isPresent() && eye.squaredDistanceTo(at.get()) < nearest) {
                nearest = eye.squaredDistanceTo(at.get());
                hit = person;
            }
        }
        HitResult blocked = client.crosshairTarget;
        if (hit != null && blocked != null && blocked.getType() == HitResult.Type.BLOCK
                && eye.squaredDistanceTo(blocked.getPos()) < nearest) {
            return null;
        }
        return hit;
    }

    /** 每个人（身体中点）相对视线的水平夹角，度，右为正 —— 换人只在画面里的人之间换（{@code WorldTargetRules#cycleOnScreen}）。 */
    static double[] bearings(MinecraftClient client, List<Person> people) {
        double[] out = new double[people.size()];
        if (client.player == null) {
            java.util.Arrays.fill(out, 180);
            return out;
        }
        Vec3d eye = client.player.getCameraPosVec(1f);
        float yaw = client.player.getYaw();
        for (int i = 0; i < people.size(); i++) {
            Vec3d to = people.get(i).body().getBoundingBox().getCenter().subtract(eye);
            double toward = Math.toDegrees(Math.atan2(-to.x, to.z));    // 与实体朝向同一套：0 = 南，往右转变大
            out[i] = net.minecraft.util.math.MathHelper.wrapDegrees(toward - yaw);
        }
        return out;
    }

    /** 画面水平视角的一半（度）：由视野设置（竖直）与窗口宽高比算，留一点边，贴着画面边沿的人不算看得见。 */
    static double halfFov(MinecraftClient client) {
        double vertical = Math.toRadians(client.options.getFov().getValue());
        var window = client.getWindow();
        double aspect = window.getFramebufferHeight() <= 0 ? 16.0 / 9.0
                : (double) window.getFramebufferWidth() / window.getFramebufferHeight();
        return Math.toDegrees(Math.atan(Math.tan(vertical / 2) * aspect)) * 0.9;
    }

    /** 与准星方向夹角最小的那一个（看身体的中点）在 {@code people} 里排第几；一个都没有是 -1。指定模式用它挑默认的人。 */
    static int nearestByAngle(MinecraftClient client, List<Person> people) {
        if (client.player == null) {
            return -1;
        }
        Vec3d eye = client.player.getCameraPosVec(1f);
        Vec3d look = client.player.getRotationVec(1f);
        int best = -1;
        double bestDot = -2;
        for (int i = 0; i < people.size(); i++) {
            Vec3d to = people.get(i).body().getBoundingBox().getCenter().subtract(eye);
            double len = to.length();
            if (len < 1e-3) {
                continue;
            }
            double dot = to.multiply(1 / len).dotProduct(look);
            if (dot > bestDot) {
                bestDot = dot;
                best = i;
            }
        }
        return best;
    }
}
