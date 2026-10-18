package io.github.heavyseasmc.mod.client;

import io.github.heavyseasmc.mod.net.CardActionC2S;
import io.github.heavyseasmc.mod.net.RequestStamp;
import io.github.heavyseasmc.mod.net.UseProvisionC2S;
import io.github.heavyseasmc.mod.state.GameComponents;
import io.github.heavyseasmc.mod.state.HudView;
import io.github.heavyseasmc.mod.ui.IntentNonces;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

/**
 * 一个意图一个编号（{@link IntentNonces}）：同一份投影下重复发出的同一个请求复用编号，服务端只认第一包。
 * 看到新投影之后再点算新意图。编号只是身份，服务端照样重问规则。
 */
final class CardRequests {
    private static final IntentNonces NONCES = new IntentNonces();

    private CardRequests() { }

    private static RequestStamp stampFor(Object intent) {
        var client = MinecraftClient.getInstance();
        if (client.world == null) return null;
        HudView view = GameComponents.of(client.world).hudView();
        var experience = view.experience();
        if (experience.matchId().isEmpty() || experience.commandContext().isEmpty()) {
            ActionBarEcho.record(Text.translatable("heavyseas.ux.reject.window_closed"));
            return null;
        }
        return NONCES.stamp(view, intent, experience.matchId(), experience.commandContext());
    }

    static void send(CardActionC2S packet) {
        RequestStamp stamp = stampFor(packet.withStamp(RequestStamp.EMPTY));
        if (stamp != null) ClientPlayNetworking.send(packet.withStamp(stamp));
    }

    static void send(UseProvisionC2S packet) {
        RequestStamp stamp = stampFor(packet.withStamp(RequestStamp.EMPTY));
        if (stamp != null) ClientPlayNetworking.send(packet.withStamp(stamp));
    }
}
