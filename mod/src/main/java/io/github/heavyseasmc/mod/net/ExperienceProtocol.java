package io.github.heavyseasmc.mod.net;

import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/**
 * Checked during login, before recipient projections containing the newer fields can be decoded.
 *
 * <p>❗版本号与包结构绑在一起：{@code ProtocolFingerprintTest} 对自定义包与对局投影的结构算一个指纹，
 * 每个版本号登记一个。改了线上结构就加一，并在那里登记新指纹。
 * 2：播报与私信并到增量通道（不再随投影整份重发）、可用性只在变了时发。
 * 3：投影公开那一半加座位轨标记（{@code HudView.Rail}：在等哪几座 · 各自的钟 · 补给箱在谁手上）。
 * 4：「上一局」快照带上那一局被移出游戏的人（{@code ExperienceView.Result#removed}），计分面板回看时照样淡化。
 * 5：北辰号的电梯（{@code LiftFloorsS2C} 楼层表 · {@code LiftTravelC2S} 去第几层）。
 * 6：电梯出发（{@code LiftRideS2C}：这一趟走多少 tick，客户端照它渐暗、轻晃）。
 */
public final class ExperienceProtocol {
    public static final int VERSION = 6;
    public static final Identifier CHANNEL = Identifier.of("heavyseas", "experience_protocol");
    private ExperienceProtocol() { }

    public static Text mismatch() {
        return Text.translatableWithFallback("heavyseas.protocol.mismatch",
                "Heavy Seas: client and server versions do not match. Update both before joining.");
    }
}
