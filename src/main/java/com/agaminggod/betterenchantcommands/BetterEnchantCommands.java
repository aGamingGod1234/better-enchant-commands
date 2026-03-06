package com.agaminggod.betterenchantcommands;

import com.agaminggod.betterenchantcommands.command.EnchantCommand;
import com.agaminggod.betterenchantcommands.command.GiveCommand;
import com.agaminggod.betterenchantcommands.verification.InGameStressVerifier;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class BetterEnchantCommands implements ModInitializer {
    public static final String MOD_ID = "better-enchant-commands";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private static final String STRESS_TEST_PROPERTY = "betterenchantcommands.stressTest";

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, environment) -> {
            EnchantCommand.register(dispatcher, buildContext);
            GiveCommand.register(dispatcher, buildContext);
        });

        if (Boolean.getBoolean(STRESS_TEST_PROPERTY)) {
            LOGGER.info("Better Enchant Commands in-game stress verification enabled via -D{}", STRESS_TEST_PROPERTY);
            ServerLifecycleEvents.SERVER_STARTED.register(InGameStressVerifier::run);
        }

        LOGGER.info("Better Enchant Commands initialized");
    }
}

