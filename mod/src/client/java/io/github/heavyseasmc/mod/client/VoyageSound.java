package io.github.heavyseasmc.mod.client;

import com.google.gson.JsonParser;
import io.github.heavyseasmc.engine.state.Condition;
import io.github.heavyseasmc.mod.audio.AudioGate;
import io.github.heavyseasmc.mod.audio.AudioInbox;
import io.github.heavyseasmc.mod.net.AudioCueS2C;
import io.github.heavyseasmc.mod.state.GameComponents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.MovingSoundInstance;
import net.minecraft.client.sound.SoundInstance;
import net.minecraft.resource.ResourceManager;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Client-owned, bounded playback. No recorded speech backlog and no gameplay random draws. */
final class VoyageSound {
    private static final Logger LOGGER = LoggerFactory.getLogger("heavyseas");
    private static final AudioGate GATE = new AudioGate();
    private static final Map<String, Clip> CLIPS = new HashMap<>();
    private static final Set<String> REPORTED = new HashSet<>();
    private static final List<Playing> PLAYING = new ArrayList<>();
    private static final AudioInbox INCOMING = new AudioInbox();
    private static ResourceManager resources;
    private static Object world;
    private static String epoch = "";
    private static boolean hadTurn;
    private static long localSequence;

    private record Clip(String path, long durationMs) { }

    private VoyageSound() { }

    static void receive(AudioCueS2C cue) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null || !client.world.getRegistryKey().getValue().toString().equals(cue.dimension())) {
            return;
        }
        // A semantic event can arrive just before its state projection. This is NOT a speech queue.
        INCOMING.offer(new AudioInbox.Event(cue.dimension(), cue.epoch(), cue.sequence(), cue.cue(), cue.speaker()),
                Util.getMeasuringTimeMs());
    }

    static void tick(MinecraftClient client) {
        long now = Util.getMeasuringTimeMs();
        if (world != client.world) {
            clearPlayback(client);
            world = client.world;
            epoch = "";
        }
        if (client.world == null) {
            INCOMING.clear();
            return;
        }
        // Also prune UI sounds used outside a voyage; the inactive path returns below.
        cleanup(client, now);
        var component = GameComponents.of(client.world);
        var view = component.hudView();
        String dimension = client.world.getRegistryKey().getValue().toString();
        if (!view.active()) {
            if (!epoch.isEmpty()) {
                clearPlayback(client);
            }
            epoch = "";
            INCOMING.drain(dimension, null, false, now);
            return;
        }
        String current = component.notificationEpoch().toString();
        boolean fresh = !epoch.equals(current);
        if (fresh) {
            clearPlayback(client);
            epoch = current;
            hadTurn = view.myTurnToAct(); // First snapshot is a baseline, not a replay of the turn.
        }
        if (!fresh && view.myTurnToAct() && !hadTurn) {
            play("turn_chime", AudioGate.Lane.ALERT, SoundCategory.MASTER, SoundEvents.BLOCK_NOTE_BLOCK_BELL.value(),
                    0.38f, "turn:" + (++localSequence));
        }
        hadTurn = view.myTurnToAct();
        for (var cue : INCOMING.drain(dimension, epoch, true, now)) {
            dispatch(cue);
        }
        if ((!ClientPrefs.characterSpeech() || ClientPrefs.negotiationAudio())
                && PLAYING.stream().anyMatch(p -> p.lane == AudioGate.Lane.VOICE)) {
            PLAYING.stream().filter(p -> p.lane == AudioGate.Lane.VOICE).forEach(p -> {
                p.done = true;
                client.getSoundManager().stop(p);
            });
            GATE.voiceFinished();
        }
    }

    private static void cleanup(MinecraftClient client, long now) {
        // Stop old instances before dispatch, not after a replacement voice has started.
        PLAYING.removeIf(sound -> {
            boolean completed = now >= sound.started + 500 && !client.getSoundManager().isPlaying(sound);
            if (sound.done || now >= sound.until || completed) {
                client.getSoundManager().stop(sound);
                if (sound.lane == AudioGate.Lane.VOICE) {
                    GATE.voiceFinished();
                }
                return true;
            }
            return false;
        });
    }

    private static void dispatch(AudioInbox.Event cue) {
        String key = cue.epoch() + ":" + cue.sequence();
        var view = GameComponents.of(MinecraftClient.getInstance().world).hudView();
        // A fast simulation can already have finished the event by the time its packet is applied.
        if (view.endgame().active() && cue.cue() != io.github.heavyseasmc.mod.audio.AudioCue.GULL_CALL
                && cue.cue() != io.github.heavyseasmc.mod.audio.AudioCue.GULL_DEPART) {
            return;
        }
        switch (cue.cue()) {
            case FIGHT_START -> {
                play("fight_start", AudioGate.Lane.EVENT, SoundCategory.PLAYERS, SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP, 0.5f, key);
                if (view.contest().active()) {
                    speech(cue.speaker(), "refuse", key);
                }
            }
            case FIGHT_CLASH -> play("fight_clash", AudioGate.Lane.EVENT, SoundCategory.PLAYERS,
                    SoundEvents.ENTITY_PLAYER_ATTACK_STRONG, 0.5f, key);
            case DRINK -> play("drink_gulp", AudioGate.Lane.EVENT, SoundCategory.PLAYERS,
                    SoundEvents.ENTITY_GENERIC_DRINK, 0.4f, key);
            case THIRST -> speech(cue.speaker(), "thirst", key);
            case OVERBOARD -> {
                play("overboard_fall", AudioGate.Lane.EVENT, SoundCategory.PLAYERS,
                        SoundEvents.ENTITY_PLAYER_SPLASH_HIGH_SPEED, 0.6f, key);
                speech(cue.speaker(), "overboard", key);
            }
            case GULL_CALL -> play("gull_call", AudioGate.Lane.AMBIENT, SoundCategory.AMBIENT,
                    SoundEvents.ENTITY_PARROT_AMBIENT, 0.28f, key);
            case GULL_DEPART -> play("gull_flock_depart", AudioGate.Lane.EVENT, SoundCategory.AMBIENT,
                    SoundEvents.ENTITY_PARROT_FLY, 0.4f, key);
        }
    }

    private static void speech(String speaker, String suffix, String key) {
        if (!ClientPrefs.characterSpeech() || ClientPrefs.negotiationAudio() || !speaker.matches("[a-z_]{1,24}")) {
            return;
        }
        var component = GameComponents.of(MinecraftClient.getInstance().world);
        boolean conscious = component.tableView().seats().stream().anyMatch(seat -> seat.id().equals(speaker)
                && !seat.removed() && !seat.offline() && seat.condition() == Condition.CONSCIOUS);
        if (!conscious || component.hudView().endgame().active()) {
            return;
        }
        // Public recipient scope was enforced on the server; no local hand-derived choice is made here.
        String id = "bark_" + speaker + "_" + suffix;
        loadClips();
        String language = MinecraftClient.getInstance().getLanguageManager().getLanguage();
        if (language.startsWith("zh_")) {
            if (available(id + "_zh")) {
                id += "_zh";
            } else {
                reportOnce("language:" + id, "Audio language fallback to approved English: " + id);
            }
        }
        play(id, AudioGate.Lane.VOICE, SoundCategory.VOICE, null, 0.7f, key + ":voice");
    }

    static boolean ui(String id, SoundEvent fallback, float volume) {
        return play(id, AudioGate.Lane.UI, SoundCategory.MASTER, fallback, volume, "local:" + (++localSequence));
    }

    /**
     * 本机的决策提示（轮到你 · 快超时，{@code AccessibilityFeedback}）：走提醒那一条通道，受总音量与闸门管 ——
     * 不另起一个音量设置（2026-10-13：试合入另加的「界面音量」与绕过闸门的直放已收掉）。
     */
    static boolean alert(String id, SoundEvent fallback, float volume) {
        return play(id, AudioGate.Lane.ALERT, SoundCategory.MASTER, fallback, volume, "alert:" + (++localSequence));
    }

    /** 这一类声音此刻发得出来吗：本模组总音量、Minecraft 的主音量与这一类音量都不为 0。为 0 时调用方连排都不排。 */
    static boolean audible(SoundCategory category) {
        MinecraftClient client = MinecraftClient.getInstance();
        return client != null && client.options != null && ClientPrefs.audioVolume() > 0
                && client.options.getSoundVolume(SoundCategory.MASTER) > 0
                && client.options.getSoundVolume(category) > 0;
    }

    static void removed() {
        play("taken_by_sea", AudioGate.Lane.EVENT, SoundCategory.PLAYERS,
                SoundEvents.ENTITY_PLAYER_SPLASH_HIGH_SPEED, 0.65f, "removed:" + (++localSequence));
    }

    static void cancelUi() {
        MinecraftClient client = MinecraftClient.getInstance();
        PLAYING.stream().filter(p -> p.lane == AudioGate.Lane.UI).forEach(p -> {
            p.done = true;
            client.getSoundManager().stop(p);
        });
    }

    private static boolean play(String id, AudioGate.Lane lane, SoundCategory category,
                                SoundEvent fallback, float volume, String key) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null || ClientPrefs.audioVolume() <= 0
                || client.options.getSoundVolume(SoundCategory.MASTER) <= 0
                || client.options.getSoundVolume(category) <= 0) {
            return false;
        }
        loadClips();
        Clip clip = available(id) ? CLIPS.get(id) : null;
        Identifier event = clip == null ? (fallback == null ? null : fallback.getId()) : Identifier.of("heavyseas", id);
        if (clip == null) {
            reportOnce("missing:" + id, "Audio resource fallback: " + id + (fallback == null ? " -> silent" : " -> " + fallback.getId()));
        }
        if (event == null) {
            return false;
        }
        long duration = clip == null ? 1000L : clip.durationMs();
        if (lane == AudioGate.Lane.VOICE) {
            duration = Math.clamp(duration, 200L, 30_000L);
        }
        long now = Util.getMeasuringTimeMs();
        // Do not admit another voice until the actual sound has finished, even if metadata was short.
        if (lane == AudioGate.Lane.VOICE && PLAYING.stream().anyMatch(p -> p.lane == lane && !p.done && now < p.until)) {
            return false;
        }
        long sameLane = PLAYING.stream().filter(p -> !p.done && p.lane == lane && now < p.until).count();
        int limit = lane == AudioGate.Lane.UI ? 2 : 3;
        if (lane != AudioGate.Lane.VOICE && sameLane >= limit) {
            // Leave existing tails intact rather than stacking or cutting approved transients.
            return false;
        }
        if (!GATE.admit(new AudioGate.Request(id, lane, key, now + 250, 50, duration), now)) {
            LOGGER.debug("Audio suppressed: {} ({})", id, lane);
            return false;
        }
        Playing sound = new Playing(event, category, lane, volume, now + duration + 100);
        PLAYING.add(sound);
        client.getSoundManager().play(sound);
        LOGGER.debug("Audio played: {} ({})", id, lane);
        return true;
    }

    private static boolean available(String id) {
        Clip clip = CLIPS.get(id);
        var sounds = MinecraftClient.getInstance().getSoundManager().get(Identifier.of("heavyseas", id));
        return clip != null && sounds != null && sounds.getWeight() > 0
                && resources.getResource(Identifier.of("heavyseas", "sounds/" + clip.path() + ".ogg")).isPresent();
    }

    private static void loadClips() {
        ResourceManager manager = MinecraftClient.getInstance().getResourceManager();
        if (resources == manager) {
            return;
        }
        resources = manager;
        CLIPS.clear();
        var index = manager.getResource(Identifier.of("heavyseas", "audio-index.json"));
        if (index.isEmpty()) {
            reportOnce("pack", "Private audio pack absent; public sound fallbacks are active");
            return;
        }
        try (var reader = new InputStreamReader(index.get().getInputStream(), StandardCharsets.UTF_8)) {
            var json = JsonParser.parseReader(reader).getAsJsonObject();
            for (var entry : json.entrySet()) {
                var value = entry.getValue().getAsJsonObject();
                String path = value.get("path").getAsString();
                long duration = value.get("durationMs").getAsLong();
                if (!entry.getKey().matches("[a-z0-9_]+") || !path.matches("[a-z0-9_]+/[a-z0-9_]+")
                        || duration < 1 || duration > 120_000) {
                    throw new IllegalArgumentException("Invalid audio index entry: " + entry.getKey());
                }
                CLIPS.put(entry.getKey(), new Clip(path, duration));
            }
            LOGGER.debug("Private audio index loaded: {} clips", CLIPS.size());
        } catch (Exception ex) {
            CLIPS.clear();
            LOGGER.warn("Private audio index invalid; public fallbacks remain active", ex);
        }
    }

    static void reset() {
        clearPlayback(MinecraftClient.getInstance());
        INCOMING.clear();
        epoch = "";
        world = null;
    }

    static void resourcesReloaded() {
        reset();
        resources = null;
        CLIPS.clear();
        REPORTED.clear();
    }

    private static void clearPlayback(MinecraftClient client) {
        GuiSound.cancel();
        PLAYING.forEach(client.getSoundManager()::stop);
        PLAYING.clear();
        GATE.reset();
        hadTurn = false;
    }

    private static void reportOnce(String key, String text) {
        if (REPORTED.add(key)) {
            LOGGER.debug(text);
        }
    }

    private static final class Playing extends MovingSoundInstance {
        private final AudioGate.Lane lane;
        private final float baseVolume;
        private final long until;
        private final long started;
        private float envelope = 1f;
        private boolean done;

        Playing(Identifier id, SoundCategory category, AudioGate.Lane lane, float volume, long until) {
            super(SoundEvent.of(id), category, SoundInstance.createRandom());
            this.lane = lane;
            this.baseVolume = volume;
            this.until = until;
            this.started = Util.getMeasuringTimeMs();
            this.relative = true;
            this.attenuationType = AttenuationType.NONE;
            this.pitch = 1f;
            boolean voice = PLAYING.stream().anyMatch(p -> p.lane == AudioGate.Lane.VOICE && !p.done
                    && Util.getMeasuringTimeMs() < p.until);
            envelope = voice && (lane == AudioGate.Lane.UI || lane == AudioGate.Lane.AMBIENT) ? 0.45f : 1f;
            if (ClientPrefs.negotiationAudio() && lane == AudioGate.Lane.AMBIENT) {
                envelope *= 0.5f;
            }
            this.volume = volume * ClientPrefs.audioVolume() * envelope;
        }

        @Override
        public void tick() {
            long now = Util.getMeasuringTimeMs();
            if (done || now >= until) {
                setDone();
                done = true;
                return;
            }
            boolean voice = PLAYING.stream().anyMatch(p -> p.lane == AudioGate.Lane.VOICE && !p.done && now < p.until);
            float target = voice && (lane == AudioGate.Lane.UI || lane == AudioGate.Lane.AMBIENT) ? 0.45f : 1f;
            if (ClientPrefs.negotiationAudio() && lane == AudioGate.Lane.AMBIENT) {
                target *= 0.5f;
            }
            envelope += (target - envelope) * (target < envelope ? 0.5f : 0.15f);
            volume = baseVolume * ClientPrefs.audioVolume() * envelope;
        }
    }
}