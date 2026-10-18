package io.github.heavyseasmc.mod.audio;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class BundledAudioTest {
    @Test
    void integratedResourcesHaveExactFilesHashesMappingsAndBothSubtitles() throws Exception {
        Path root = Path.of("src/main/resources/assets/heavyseas");
        var index = JsonParser.parseString(Files.readString(root.resolve("audio-index.json"))).getAsJsonObject();
        var sounds = JsonParser.parseString(Files.readString(root.resolve("sounds.json"))).getAsJsonObject();
        Set<String> expected = new HashSet<>(Set.of("card_deal", "card_flip", "card_snap", "turn_chime",
                "fight_start", "fight_clash", "drink_gulp", "overboard_fall", "taken_by_sea", "gull_call", "gull_flock_depart"));
        for (String role : new String[]{"jeweler", "collector", "captain", "mate", "hostess", "sailor", "doctor", "kid"}) {
            for (String suffix : new String[]{"refuse", "thirst", "overboard"}) {
                expected.add("bark_" + role + "_" + suffix);
            }
        }
        assertEquals(expected, index.keySet());
        assertEquals(expected, sounds.keySet());
        Set<Path> files = new HashSet<>();
        for (String id : expected) {
            var clip = index.getAsJsonObject(id);
            String relative = clip.get("path").getAsString();
            assertTrue(relative.matches("[a-z0-9_]+/" + id));
            Path file = root.resolve("sounds/" + relative + ".ogg");
            files.add(file);
            byte[] bytes = Files.readAllBytes(file);
            assertTrue(bytes.length > 4);
            assertArrayEquals(new byte[]{'O', 'g', 'g', 'S'}, java.util.Arrays.copyOf(bytes, 4));
            assertEquals(clip.get("sha256").getAsString(), HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)), id);
            assertTrue(clip.get("durationMs").getAsLong() > 0);
            var event = sounds.getAsJsonObject(id);
            assertEquals("subtitles.heavyseas." + id, event.get("subtitle").getAsString());
            assertEquals(1, event.getAsJsonArray("sounds").size());
            assertEquals("heavyseas:" + relative, event.getAsJsonArray("sounds").get(0).getAsJsonObject().get("name").getAsString());
        }
        try (var walk = Files.walk(root.resolve("sounds"))) {
            assertEquals(files, walk.filter(Files::isRegularFile).collect(java.util.stream.Collectors.toSet()));
        }
        for (String locale : new String[]{"en_us", "zh_cn"}) {
            var lang = JsonParser.parseString(Files.readString(root.resolve("lang/" + locale + ".json"))).getAsJsonObject();
            for (String id : expected) {
                assertFalse(lang.get("subtitles.heavyseas." + id).getAsString().isBlank());
            }
        }
    }
}