package io.github.heavyseasmc.mod.state;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.heavyseasmc.mod.data.FogTable;
import io.github.heavyseasmc.mod.data.VoyageLayout;
import io.github.heavyseasmc.mod.data.VoyageLayoutLoader;
import net.minecraft.util.Identifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 这一局的布局与雾表在开局时整份快照进组件（审查 2026-10-07 C6）。
 *
 * <p>❗原先每次都按布局 id 去场景数据里现取：对局中 {@code /reload} 拿掉这一局的布局，好几处每 tick 的计时就各抛一次、崩服。
 * 这里的场景数据<b>从没加载过</b>（单测里没有数据包重载）—— 等同于「现取一定取不到」：快照在就答得出，答不出就红。
 */
final class LayoutSnapshotTest {

    private static final Identifier DEFAULT = Identifier.of("heavyseas", "default");

    @Test
    @DisplayName("有开局快照时，雾表条目从快照取 —— 场景数据里没有这份布局也照样答得出")
    void fogComesFromTheSnapshot() throws IOException {
        VoyageLayout layout = VoyageLayoutLoader.parse("heavyseas:voyage/default.json",
                new StringReader(resource("/data/heavyseas/voyage/default.json")), DEFAULT);
        FogTable fog = FogTable.parse("heavyseas:fog/default.json",
                new StringReader(resource("/data/heavyseas/fog/default.json")), layout.fog(), weatherIds());
        GameComponent component = new GameComponent(null);
        component.setLayout(layout, fog);

        assertSame(layout, component.layout().orElseThrow(), "快照就是开局那一份");
        assertEquals(DEFAULT, component.layoutId().orElseThrow(), "id 跟着快照走");
        assertEquals(fog.entryFor("dense_fog"), component.fogFor("dense_fog"),
                "雾表条目没从快照取（单测里场景数据从没加载过，现取一定抛）");

        component.clearLayoutId();
        assertTrue(component.layout().isEmpty(), "收场景时快照一并清掉");
    }

    private static Set<String> weatherIds() throws IOException {
        JsonObject root = JsonParser.parseString(resource("/data/heavyseas/weather/default.json")).getAsJsonObject();
        Set<String> ids = new LinkedHashSet<>();
        root.getAsJsonArray("cards").forEach(card -> ids.add(card.getAsJsonObject().get("id").getAsString()));
        return ids;
    }

    private static String resource(String path) throws IOException {
        try (InputStream in = LayoutSnapshotTest.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("模组资源里没有 " + path + " —— 没在测，不是通过");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
