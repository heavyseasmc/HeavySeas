package io.github.heavyseasmc.mod.command;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.command.CommandOutput;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec2f;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SeasCommandRegistrationTest {

    @Test
    void ordinaryPlayersCannotSeeOrExecuteTheCommandTree() {
        for (boolean development : new boolean[]{false, true}) {
            CommandDispatcher<ServerCommandSource> dispatcher = new CommandDispatcher<>();
            SeasCommand.register(dispatcher, development);
            var root = dispatcher.getRoot().getChild("seas");
            for (int level = 0; level <= 4; level++) {
                var source = new ServerCommandSource(CommandOutput.DUMMY, Vec3d.ZERO, Vec2f.ZERO,
                        null, level, "test", Text.literal("test"), null, null);
                if (level < 2) {
                    assertFalse(root.canUse(source));
                } else {
                    assertTrue(root.canUse(source));
                }
            }
        }
    }

    @Test
    void productionCommandTreeDoesNotExposeDevelopmentFixtures() {
        CommandDispatcher<ServerCommandSource> dispatcher = new CommandDispatcher<>();
        SeasCommand.register(dispatcher, false);

        assertNull(dispatcher.getRoot().getChild("seas").getChild("dev"));
    }

    @Test
    void developmentCommandTreeContainsRosterAndWeatherFixtures() {
        CommandDispatcher<ServerCommandSource> dispatcher = new CommandDispatcher<>();
        SeasCommand.register(dispatcher, true);

        var dev = dispatcher.getRoot().getChild("seas").getChild("dev");
        assertNotNull(dev);
        assertNotNull(dev.getChild("roster"));
        assertNotNull(dev.getChild("weather"));
    }
}
