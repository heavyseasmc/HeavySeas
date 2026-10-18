package io.github.heavyseasmc.mod.audio;

import io.github.heavyseasmc.mod.net.AudioCueS2C;
import io.netty.buffer.Unpooled;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.registry.DynamicRegistryManager;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AudioCueS2CTest {
    private static final String EPOCH = "01234567-89ab-cdef-0123-456789abcdef";

    @Test
    void everyCueRoundTripsWithEmptyAndPresentSpeaker() {
        for (AudioCue cue : AudioCue.values()) {
            roundTrip(new AudioCueS2C("heavyseas:mist_sea", EPOCH, 1, cue, ""));
            roundTrip(new AudioCueS2C("heavyseas:mist_sea", EPOCH, 1L << 40, cue, "captain"));
        }
    }

    @Test
    void exactStringBoundsAndLargestSequenceRoundTrip() {
        roundTrip(new AudioCueS2C("d".repeat(256), "e".repeat(36), Long.MAX_VALUE,
                AudioCue.OVERBOARD, "s".repeat(32)));
    }

    @Test
    void boundsAreCheckedBeforeAsynchronousEncoding() {
        assertThrows(IllegalArgumentException.class, () -> new AudioCueS2C(
                "d".repeat(257), EPOCH, 1, AudioCue.DRINK, ""));
        assertThrows(IllegalArgumentException.class, () -> new AudioCueS2C(
                "heavyseas:mist_sea", "e".repeat(37), 1, AudioCue.DRINK, ""));
        assertThrows(IllegalArgumentException.class, () -> new AudioCueS2C(
                "heavyseas:mist_sea", EPOCH, 1, AudioCue.DRINK, "s".repeat(33)));
        assertThrows(IllegalArgumentException.class, () -> new AudioCueS2C(
                "heavyseas:mist_sea", EPOCH, -1, AudioCue.DRINK, ""));
    }

    @Test
    void decoderRejectsOversizedFieldsFromTheWire() {
        for (int field = 0; field < 3; field++) {
            RegistryByteBuf buf = buffer();
            try {
                buf.writeString(field == 0 ? "d".repeat(257) : "heavyseas:mist_sea");
                buf.writeString(field == 1 ? "e".repeat(37) : EPOCH);
                buf.writeVarLong(1);
                buf.writeEnumConstant(AudioCue.DRINK);
                buf.writeString(field == 2 ? "s".repeat(33) : "");
                assertThrows(io.netty.handler.codec.DecoderException.class, () -> AudioCueS2C.CODEC.decode(buf));
            } finally {
                buf.release();
            }
        }
    }

    @Test
    void wireOrderIsDimensionEpochVarLongEnumSpeaker() {
        RegistryByteBuf buf = buffer();
        try {
            AudioCueS2C.CODEC.encode(buf, new AudioCueS2C("heavyseas:mist_sea", EPOCH,
                    301, AudioCue.THIRST, "captain"));
            assertEquals("heavyseas:mist_sea", buf.readString(256));
            assertEquals(EPOCH, buf.readString(36));
            assertEquals(301, buf.readVarLong());
            assertEquals(AudioCue.THIRST, buf.readEnumConstant(AudioCue.class));
            assertEquals("captain", buf.readString(32));
            assertFalse(buf.isReadable());
        } finally {
            buf.release();
        }
    }

    private static void roundTrip(AudioCueS2C payload) {
        RegistryByteBuf buf = buffer();
        try {
            AudioCueS2C.CODEC.encode(buf, payload);
            assertEquals(payload, AudioCueS2C.CODEC.decode(buf));
            assertFalse(buf.isReadable());
        } finally {
            buf.release();
        }
    }

    private static RegistryByteBuf buffer() {
        return new RegistryByteBuf(Unpooled.buffer(), DynamicRegistryManager.EMPTY);
    }
}