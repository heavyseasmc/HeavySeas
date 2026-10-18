package io.github.heavyseasmc.mod.state;

import io.github.heavyseasmc.engine.model.CharacterId;
import io.github.heavyseasmc.engine.play.Contest;
import io.github.heavyseasmc.engine.play.Session;
import io.github.heavyseasmc.mod.net.ExperienceProtocol;
import io.netty.buffer.Unpooled;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.text.Text;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 协议版本（{@link ExperienceProtocol#VERSION}）与包结构绑在一起：结构变了、版本没升，这里红。
 *
 * <h2>指纹由两半拼成</h2>
 * <ul>
 *   <li><b>自定义包</b>：{@code mod.net} 里每一个 {@link CustomPayload} 记录的字段名与类型（递归进嵌套的记录），
 *       以及包里按编号传的枚举的常量表（编号就是下标，加一个常量、换个次序都改变线上含义）；</li>
 *   <li><b>对局投影</b>（CCA 同步包里手写的那一段，{@code GameComponent#writeView} 与 {@code TableView#write}）：
 *       拿几份固定的样本局面经真实的写法写一遍，记下每一笔写的是什么类型（不记值），枚举连常量表一起记。
 *       样本走不到的分支不在指纹里 —— 所以样本挑的是有一场在押武器、有播报、有手牌、有终局计分的局面，另加一份「没有对局」。</li>
 * </ul>
 *
 * <p>红了怎么办：确认这是有意的线上改动，就把 {@code ExperienceProtocol.VERSION} 加一，并在 {@link #KNOWN} 里登记新指纹
 * （失败信息里打印了它与完整的结构清单）。只改了样本、没改线上，指纹也会变 —— 那就只更新登记，不升版本。
 */
final class ProtocolFingerprintTest {

    /** 每个版本号对应的包结构指纹。 */
    private static final Map<Integer, String> KNOWN = Map.of(
            2, "2a844c476170e0b0",
            3, "449fe702ab892620",    // 投影公开那一半多了座位轨标记（HudView.Rail：在等哪几座 · 各自的钟 · 补给箱在谁手上）
            4, "440efae3dafe09d8",    // 「上一局」快照带上被移出游戏的人（ExperienceView.Result#removed）
            5, "0a67c78db2da4668",    // 北辰号的电梯（LiftFloorsS2C 楼层表 · LiftTravelC2S 去第几层）
            6, "9ce6ea3878bc9129");   // 电梯出发（LiftRideS2C：这一趟走多少 tick，客户端渐暗轻晃）

    private static final CharacterId MATE = CharacterId.of("mate");
    private static final CharacterId KID = CharacterId.of("kid");

    @BeforeAll
    static void bootstrap() {
        MinecraftTestBootstrap.initialize();
    }

    @Test
    @DisplayName("包结构指纹与协议版本登记的一致：结构变了没升版本就红")
    void protocolVersionMatchesThePacketStructure() throws Exception {
        String schema = schema();
        String fingerprint = sha256(schema);
        assertTrue(schema.contains("CardActionC2S") && schema.contains("projection:active"),
                "正向对照：结构清单里得真有包与投影，否则「没扫到」与「没变」长得一样");
        assertEquals(KNOWN.get(ExperienceProtocol.VERSION), fingerprint,
                "包结构的指纹变了而协议版本还是 " + ExperienceProtocol.VERSION
                        + "：有意改线上就把 VERSION 加一并登记这个指纹；只改了样本就只更新登记。\n结构清单：\n" + schema);
    }

    /** 整份结构清单（可读的那一份；指纹是它的摘要）。 */
    static String schema() throws Exception {
        GameComponent.clearExperienceMemory();     // 别的用例留下的「最近一局」会多写一段，样本就不固定了
        StringBuilder out = new StringBuilder();
        for (Class<?> payload : payloadClasses()) {
            out.append("payload ").append(payload.getSimpleName()).append(": ");
            out.append(describe(payload, new LinkedHashSet<>()));
            for (Class<?> nested : payload.getDeclaredClasses()) {
                if (nested.isEnum()) {
                    out.append(" ").append(describe(nested, new LinkedHashSet<>()));
                }
            }
            out.append('\n');
        }
        out.append("projection:active ").append(tape(activeSample())).append('\n');
        out.append("projection:scores ").append(tape(scoresSample())).append('\n');
        out.append("projection:idle ").append(tape(idleSample())).append('\n');
        for (Class<?> record : List.of(HudView.class, ExperienceView.class, TableView.class)) {
            out.append("record ").append(describe(record, new LinkedHashSet<>())).append('\n');
        }
        return out.toString();
    }

    // ---------------------------------------------------------------- 自定义包

    private static List<Class<?>> payloadClasses() throws IOException, URISyntaxException, ClassNotFoundException {
        String pkg = ExperienceProtocol.class.getPackageName();
        URL here = ExperienceProtocol.class.getResource(ExperienceProtocol.class.getSimpleName() + ".class");
        if (here == null) {
            throw new AssertionError("找不到 ExperienceProtocol.class 本身");
        }
        Set<String> names = new TreeSet<>();
        if (here.getProtocol().equals("jar")) {
            String path = here.getPath();
            String jar = path.substring("file:".length(), path.indexOf('!'));
            try (JarFile file = new JarFile(Path.of(new java.net.URI("file:" + jar)).toFile())) {
                String prefix = pkg.replace('.', '/') + "/";
                file.stream().map(e -> e.getName())
                        .filter(n -> n.startsWith(prefix) && n.endsWith(".class") && n.indexOf('/', prefix.length()) < 0)
                        .forEach(n -> names.add(n.substring(prefix.length(), n.length() - ".class".length())));
            }
        } else {
            try (Stream<Path> files = Files.list(Path.of(here.toURI()).getParent())) {
                files.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".class"))
                        .forEach(n -> names.add(n.substring(0, n.length() - ".class".length())));
            }
        }
        List<Class<?>> payloads = new ArrayList<>();
        for (String name : names) {
            Class<?> type = Class.forName(pkg + "." + name, false, ExperienceProtocol.class.getClassLoader());
            if (CustomPayload.class.isAssignableFrom(type) && !type.isInterface()) {
                payloads.add(type);
            }
        }
        assertTrue(payloads.size() >= 20, "正向对照：只扫到 " + payloads.size() + " 个自定义包，扫描的目录不对");
        return payloads;
    }

    /** 记录：字段名与类型，递归进本项目的记录与枚举；枚举：常量表。 */
    private static String describe(Class<?> type, Set<Class<?>> seen) {
        if (!seen.add(type)) {
            return type.getSimpleName();
        }
        if (type.isEnum()) {
            return type.getSimpleName() + Arrays.stream(type.getEnumConstants()).map(Object::toString)
                    .collect(Collectors.joining(",", "[", "]"));
        }
        if (!type.isRecord()) {
            return type.getSimpleName();
        }
        List<String> parts = new ArrayList<>();
        for (RecordComponent c : type.getRecordComponents()) {
            parts.add(c.getName() + ":" + describe(c.getGenericType(), seen));
        }
        return type.getSimpleName() + "(" + String.join(",", parts) + ")";
    }

    private static String describe(Type type, Set<Class<?>> seen) {
        if (type instanceof ParameterizedType p) {
            return describe((Class<?>) p.getRawType(), seen) + Arrays.stream(p.getActualTypeArguments())
                    .map(a -> describe(a, seen)).collect(Collectors.joining(",", "<", ">"));
        }
        if (type instanceof Class<?> c && (c.getName().startsWith("io.github.heavyseasmc."))) {
            return describe(c, seen);
        }
        return type.getTypeName();
    }

    // ---------------------------------------------------------------- 对局投影

    /** 有一场在押武器、有播报与私信、手里有牌的局面（ExperienceProjectionTest 的同一类搭法）。 */
    private static GameComponent activeSample() {
        GameComponent c = EndgameProjectionTest.component(UUID.nameUUIDFromBytes(new byte[]{1}),
                EndgameProgress.Stage.ARRIVAL, 0);
        Session s = c.requireSession();
        c.setEndgame(null);
        s.dealFromPile(MATE, "coin");
        s.advancePhase();
        s.declare(MATE, Contest.Kind.SWAP, KID);
        s.consent(true);
        s.closeStances();
        c.openContestWindow(20_000);
        c.notify(Text.literal("public"));
        c.privateEvent(MATE, "gift", ExperienceView.Source.PLAYER, Text.literal("private"));
        return c;
    }

    private static GameComponent scoresSample() {
        return EndgameProjectionTest.component(UUID.nameUUIDFromBytes(new byte[]{1}), EndgameProgress.Stage.SCORES, 2);
    }

    private static GameComponent idleSample() {
        return new GameComponent(null);
    }

    private static String tape(GameComponent c) {
        Tape tape = new Tape();
        try {
            c.writeViewFor(tape, UUID.nameUUIDFromBytes(new byte[]{1}));
            TableView.of(c.session().orElse(null), c.seatOf(UUID.nameUUIDFromBytes(new byte[]{1})), 0L).write(tape);
            return String.join(" ", tape.calls);
        } finally {
            tape.release();
        }
    }

    /** 只记每一笔写的类型、不记值的缓冲区。嵌套调用（写枚举走写变长整数）只记最外一层。 */
    private static final class Tape extends RegistryByteBuf {
        final List<String> calls = new ArrayList<>();
        private int depth;

        Tape() {
            super(Unpooled.buffer(), DynamicRegistryManager.EMPTY);
        }

        private void mark(String what) {
            if (depth == 0) {
                calls.add(what);
            }
        }

        @Override
        public PacketByteBuf writeVarInt(int value) {
            mark("I");
            depth++;
            try {
                return super.writeVarInt(value);
            } finally {
                depth--;
            }
        }

        @Override
        public PacketByteBuf writeVarLong(long value) {
            mark("J");
            depth++;
            try {
                return super.writeVarLong(value);
            } finally {
                depth--;
            }
        }

        @Override
        public PacketByteBuf writeString(String value) {
            mark("S");
            depth++;
            try {
                return super.writeString(value);
            } finally {
                depth--;
            }
        }

        @Override
        public PacketByteBuf writeBoolean(boolean value) {
            mark("Z");
            depth++;
            try {
                return super.writeBoolean(value);
            } finally {
                depth--;
            }
        }

        @Override
        public PacketByteBuf writeInt(int value) {
            mark("i");
            depth++;
            try {
                return super.writeInt(value);
            } finally {
                depth--;
            }
        }

        @Override
        public PacketByteBuf writeLong(long value) {
            mark("j");
            depth++;
            try {
                return super.writeLong(value);
            } finally {
                depth--;
            }
        }

        @Override
        public PacketByteBuf writeUuid(UUID value) {
            mark("U");
            depth++;
            try {
                return super.writeUuid(value);
            } finally {
                depth--;
            }
        }

        @Override
        public PacketByteBuf writeEnumConstant(Enum<?> value) {
            mark("E:" + describe(value.getDeclaringClass(), new LinkedHashSet<>()));
            depth++;
            try {
                return super.writeEnumConstant(value);
            } finally {
                depth--;
            }
        }

        @Override
        public PacketByteBuf writeByte(int value) {
            mark("B");
            depth++;
            try {
                return super.writeByte(value);
            } finally {
                depth--;
            }
        }

        @Override
        public PacketByteBuf writeFloat(float value) {
            mark("F");
            depth++;
            try {
                return super.writeFloat(value);
            } finally {
                depth--;
            }
        }

        @Override
        public PacketByteBuf writeDouble(double value) {
            mark("D");
            depth++;
            try {
                return super.writeDouble(value);
            } finally {
                depth--;
            }
        }
    }

    private static String sha256(String text) throws NoSuchAlgorithmException {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest).substring(0, 16);
    }
}
