package io.github.heavyseasmc.mod.client;

import io.github.heavyseasmc.mod.net.RosterConfigS2C;
import io.github.heavyseasmc.mod.net.StartVoyageC2S;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 大厅房主阵容面板：三套预设只是快捷键，八个角色都可自由勾选。 */
public final class RosterScreen extends Screen {
    private final RosterConfigS2C config;
    private final Set<String> selected = new LinkedHashSet<>();
    private final Map<String, ButtonWidget> characterButtons = new LinkedHashMap<>();
    private ButtonWidget start;

    public RosterScreen(RosterConfigS2C config) {
        super(Text.translatable("heavyseas.roster.title"));
        this.config = config;
        selected.addAll(preset(config.players()));
    }

    @Override
    protected void init() {
        characterButtons.clear();
        int buttonW = Math.min(160, (width - 48) / 2);
        int left = (width - buttonW * 2 - 8) / 2;
        int top = 48;
        for (int i = 0; i < config.characters().size(); i++) {
            String id = config.characters().get(i);
            int x = left + (i % 2) * (buttonW + 8);
            int y = top + (i / 2) * 24;
            ButtonWidget button = ButtonWidget.builder(label(id), ignored -> toggle(id))
                    .dimensions(x, y, buttonW, 20).build();
            characterButtons.put(id, addDrawableChild(button));
        }
        int presetsY = top + 4 * 24 + 8;
        List<Text> presetLabels = List.of(
                Text.translatable("heavyseas.roster.preset", 6),
                Text.translatable("heavyseas.roster.preset", 7),
                Text.translatable("heavyseas.roster.preset", 8));
        int widestPreset = presetLabels.stream().mapToInt(textRenderer::getWidth).max().orElse(0);
        int small = Math.min(widestPreset + 20, (width - 16) / 3);
        int presetsLeft = (width - small * 3 - 8) / 2;
        addDrawableChild(ButtonWidget.builder(presetLabels.get(0),
                ignored -> applyPreset(config.preset6())).dimensions(presetsLeft, presetsY, small, 20).build());
        addDrawableChild(ButtonWidget.builder(presetLabels.get(1),
                ignored -> applyPreset(config.preset7())).dimensions(presetsLeft + small + 4, presetsY, small, 20).build());
        addDrawableChild(ButtonWidget.builder(presetLabels.get(2),
                ignored -> applyPreset(config.preset8())).dimensions(presetsLeft + 2 * (small + 4), presetsY, small, 20).build());
        int actionsY = presetsY + 28;
        start = addDrawableChild(ButtonWidget.builder(Text.translatable("heavyseas.roster.start"), ignored -> start())
                .dimensions(width / 2 - 104, actionsY, 100, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.translatable("gui.cancel"), ignored -> close())
                .dimensions(width / 2 + 4, actionsY, 100, 20).build());
        refresh();
    }

    private void toggle(String id) {
        if (!selected.remove(id)) {
            selected.add(id);
        }
        refresh();
    }

    private void applyPreset(List<String> ids) {
        selected.clear();
        selected.addAll(ids);
        refresh();
    }

    private List<String> preset(int players) {
        return switch (players) {
            case 6 -> config.preset6();
            case 7 -> config.preset7();
            case 8 -> config.preset8();
            default -> List.of();
        };
    }

    private void refresh() {
        characterButtons.forEach((id, button) -> button.setMessage(label(id)));
        if (start != null) {
            start.active = selected.size() == config.players() && selected.size() >= 6 && selected.size() <= 8;
        }
    }

    private Text label(String id) {
        Text name = Text.translatable("heavyseas.character." + id);
        return selected.contains(id) ? Text.literal("✓ ").append(name) : name;
    }

    private void start() {
        if (!start.active) {
            return;
        }
        List<String> ordered = config.characters().stream().filter(selected::contains).toList();
        ClientPlayNetworking.send(new StartVoyageC2S(config.anchor(), ordered));
        close();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        // 原版 Screen 会先画背景再画控件；标题必须最后画，否则真客户端上会被背景的
        // 模糊 pass 一起处理，按钮清楚而标题看不清。不要另调 renderBackground，会重复模糊整帧。
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 16, 0xFFFFFF);
        context.drawCenteredTextWithShadow(textRenderer,
                Text.translatable("heavyseas.roster.detail", config.players(), selected.size()),
                width / 2, 30, 0xA0A0A0);
    }
}
