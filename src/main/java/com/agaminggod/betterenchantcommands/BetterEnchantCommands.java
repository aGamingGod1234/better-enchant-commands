package com.agaminggod.betterenchantcommands;

import com.agaminggod.betterenchantcommands.command.EnchantCommand;
import com.agaminggod.betterenchantcommands.command.EnchantInfoCommand;
import com.agaminggod.betterenchantcommands.command.EnchantListCommand;
import com.agaminggod.betterenchantcommands.command.EnchantPresetCommand;
import com.agaminggod.betterenchantcommands.command.EnchantsCommand;
import com.agaminggod.betterenchantcommands.command.GiveCommand;
import com.agaminggod.betterenchantcommands.command.RepairCommand;
import com.agaminggod.betterenchantcommands.command.UnenchantCommand;
import com.agaminggod.betterenchantcommands.config.BetterEnchantConfig;
import com.agaminggod.betterenchantcommands.permission.PermissionHelper;
import com.agaminggod.betterenchantcommands.undo.UndoManager;
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
        BetterEnchantConfig.load();

        CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, environment) -> {
            EnchantCommand.register(dispatcher, buildContext);
            GiveCommand.register(dispatcher, buildContext);
            UnenchantCommand.register(dispatcher, buildContext);
            EnchantInfoCommand.register(dispatcher, buildContext);
            EnchantListCommand.register(dispatcher, buildContext);
            EnchantPresetCommand.register(dispatcher, buildContext);
            RepairCommand.register(dispatcher, buildContext);
            EnchantsCommand.register(dispatcher, buildContext);
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> UndoManager.clear());

        if (Boolean.getBoolean(STRESS_TEST_PROPERTY)) {
            LOGGER.info("Better Enchant Commands in-game stress verification enabled via -D{}", STRESS_TEST_PROPERTY);
            ServerLifecycleEvents.SERVER_STARTED.register(InGameStressVerifier::run);
        }

        LOGGER.info("Better Enchant Commands initialized (LuckPerms support: {})", PermissionHelper.isLuckPermsAvailable());
    }
}
