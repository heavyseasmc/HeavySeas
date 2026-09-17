package io.github.heavyseasmc.mod.command;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.server.command.ServerCommandSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class SeasCommandRegistrationTest {

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
