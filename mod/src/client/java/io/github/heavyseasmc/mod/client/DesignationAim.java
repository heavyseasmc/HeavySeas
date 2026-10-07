package io.github.heavyseasmc.mod.client;

import io.github.heavyseasmc.mod.HeavySeasMod;
import io.github.heavyseasmc.mod.net.DesignateC2S;
import io.github.heavyseasmc.mod.state.GameComponents;
import io.github.heavyseasmc.mod.state.HudView;
import io.github.heavyseasmc.mod.state.TableView;
import io.github.heavyseasmc.mod.world.StandInEntity;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 指定模式里「指着谁」（ADR-0095 F2，用户 2026-10-07 选「看大概方向 + 滚轮换人」）。
 *
 * <h2>为什么不靠准星点人</h2>
 * 大家在艇上坐成一排：准星射线先打到中间那个人，交互距离加多长都点不到后面的；按方向挑也分不开 ——
 * 从自己的座位看过去，前方的人几乎在同一个方向，只差一点高低（船尾两座相差不到 1°）。
 *
 * <h2>怎么选</h2>
 * 一进指定模式（或者头转了一大截）就挑与准星方向夹角最小的那个人；滚轮按座位次序前后换；右键确定。
 * 选中的那个人在自己屏幕上描一圈轮廓（{@code MinecraftClientMixin#hasOutline}），准星下面写「指着：谁（第几座）」。
 * 仍在世界里、发着光、全船看得见的 20 秒里选 —— 决策 ⑦ 的预告窗口不变，变的只是「怎么指」。
 *
 * <p>只做客户端那一半：挑谁、画什么。认不认由服务端（{@code DesignationPhase#onDesignate}），与右键实体同一份判据。
 */
public final class DesignationAim {

    private static final Logger LOGGER = LoggerFactory.getLogger(HeavySeasMod.MOD_ID);

    /** 头转了这么多（偏航 + 俯仰，度）就按方向重挑；小于它当作手抖，滚轮选的人不变。 */
    private static final float RETARGET_DEG = 8f;
    /** 多远以内算船上（格）：一条艇八座、座距 2。 */
    private static final double MAX_DIST = 24.0;

    /** 船上可以指的人，按座位次序（船头到船尾），不含自己与已移出的人。 */
    private static final List<Candidate> candidates = new ArrayList<>();
    private static int selected = -1;
    private static float aimYaw;
    private static float aimPitch;
    private static boolean active;

    private record Candidate(String character, int seat, Entity body) {
    }

    private DesignationAim() {
    }

    /** 每个客户端 tick 一次：不在指定模式里就清空；在的话重算候选、按需重挑。 */
    static void tick(MinecraftClient client) {
        if (client.world == null || client.player == null) {
            reset();
            return;
        }
        HudView view = GameComponents.of(client.world).hudView();
        if (!view.myDesignating()) {
            reset();
            return;
        }
        String previous = current() == null ? null : current().character();
        collect(client, view);
        if (candidates.isEmpty()) {
            selected = -1;
            return;
        }
        int keep = indexOf(previous);
        float yaw = client.player.getYaw();
        float pitch = client.player.getPitch();
        boolean turned = Math.abs(MathHelper.wrapDegrees(yaw - aimYaw)) + Math.abs(pitch - aimPitch) > RETARGET_DEG;
        if (!active || keep < 0 || turned) {
            selected = nearestByAngle(client);
            aimYaw = yaw;
            aimPitch = pitch;
        } else {
            selected = keep;
        }
        active = true;
    }

    /** 滚轮：按座位次序前后换一个（往上滚 = 往船头）。吃掉这一下就返回 {@code true}。 */
    public static boolean scroll(double vertical) {
        if (!active || candidates.isEmpty() || vertical == 0) {
            return false;
        }
        int step = vertical > 0 ? -1 : 1;
        selected = Math.floorMod(selected + step, candidates.size());
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            aimYaw = client.player.getYaw();     // 滚轮选的人不被下一 tick 的「按方向重挑」改回去
            aimPitch = client.player.getPitch();
        }
        return true;
    }

    /** 右键：把选中的那个人发给服务端。吃掉这一下（不再交给 Minecraft 的右键）就返回 {@code true}。 */
    public static boolean confirm() {
        Candidate c = current();
        if (!active) {
            return false;
        }
        if (c == null) {
            return true;                      // 指定模式里但一个人都没挑到：吃掉这一下，不让右键去碰别的东西
        }
        // 与语言无关的一行：客户端回归靠它判「这一下真的发出去了，选的是谁」
        LOGGER.info("指定模式（看着谁）：选了 {}（第 {} 座）", c.character(), c.seat() + 1);
        ClientPlayNetworking.send(new DesignateC2S(c.body().getUuid()));
        return true;
    }

    /** 选中的那具身体要不要描轮廓（{@code MinecraftClientMixin}）。 */
    public static boolean outlined(Entity entity) {
        Candidate c = current();
        return active && c != null && c.body() == entity;
    }

    /** 准星下面那两行：「指着：谁（第几座）」与按键。主画面 HUD 调。 */
    static void draw(DrawContext context, MinecraftClient client) {
        if (!active) {
            return;
        }
        int cx = context.getScaledWindowWidth() / 2;
        int y = context.getScaledWindowHeight() / 2 + 14;
        Candidate c = current();
        Text line = c == null ? Text.translatable("heavyseas.hud.aim_none")
                : Text.translatable("heavyseas.hud.aim", GameScreen.nameOf(c.character()), c.seat() + 1);
        int w = 220;
        GuiText.draw(context, line.getString(), cx - w / 2, y, w, GuiText.BODY, true,
                GuiLanguage.gold(), GuiText.Align.CENTER, 1);
        String act = HeavySeasClient.actKey() == null ? "G" : HeavySeasClient.actKey().getBoundKeyLocalizedText().getString();
        GuiText.draw(context, Text.translatable("heavyseas.hud.aim_keys", act).getString(), cx - w / 2,
                y + GuiText.lineHeight(GuiText.BODY, true) + 2, w, GuiText.BODY, false,
                GuiLanguage.Hud.alpha(0xFFFFFFFF, 0.85f), GuiText.Align.CENTER, 1);
    }

    private static Candidate current() {
        return selected >= 0 && selected < candidates.size() ? candidates.get(selected) : null;
    }

    private static int indexOf(String character) {
        for (int i = 0; i < candidates.size(); i++) {
            if (candidates.get(i).character().equals(character)) {
                return i;
            }
        }
        return -1;
    }

    /** 船上的人（按座位次序），身体在这个世界里、离得不太远。真人按占座的玩家认，替身按人形认。 */
    private static void collect(MinecraftClient client, HudView view) {
        candidates.clear();
        TableView table = GameComponents.of(client.world).tableView();
        List<TableView.Seat> seats = table.seats();
        for (int i = 0; i < seats.size(); i++) {
            TableView.Seat seat = seats.get(i);
            if (seat.removed() || seat.id().equals(view.character())) {
                continue;
            }
            Entity body = bodyOf(client, seat);
            if (body != null && body.squaredDistanceTo(client.player) <= MAX_DIST * MAX_DIST) {
                candidates.add(new Candidate(seat.id(), i, body));
            }
        }
    }

    private static Entity bodyOf(MinecraftClient client, TableView.Seat seat) {
        if (!seat.occupant().isEmpty()) {
            for (PlayerEntity p : client.world.getPlayers()) {
                if (p.getUuidAsString().equals(seat.occupant())) {
                    return p;
                }
            }
            return null;
        }
        for (Entity e : client.world.getEntities()) {
            if (e instanceof StandInEntity standIn && standIn.character().equals(seat.id())) {
                return e;
            }
        }
        return null;
    }

    /** 与准星方向夹角最小的那一个（看身体的中点）。 */
    private static int nearestByAngle(MinecraftClient client) {
        Vec3d eye = client.player.getCameraPosVec(1f);
        Vec3d look = client.player.getRotationVec(1f);
        int best = -1;
        double bestDot = -2;
        for (int i = 0; i < candidates.size(); i++) {
            Vec3d to = candidates.get(i).body().getBoundingBox().getCenter().subtract(eye);
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

    private static void reset() {
        candidates.clear();
        selected = -1;
        active = false;
    }
}
