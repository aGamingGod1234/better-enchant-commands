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
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.CommandNode;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class BetterEnchantCommands implements ModInitializer {
    public static final String MOD_ID = "better-enchant-commands";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private static final String STRESS_TEST_PROPERTY = "betterenchantcommands.stressTest";
    private static final List<String> MOD_COMMANDS = List.of(
        "enchant",
        "give",
        "unenchant",
        "enchantinfo",
        "enchantlist",
        "enchantpreset",
        "repair",
        "enchants"
    );
    private static CommandBuildContext latestBuildContext;

    @Override
    public void onInitialize() {
        BetterEnchantConfig.load();

        CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, environment) -> {
            latestBuildContext = buildContext;
            registerReplacementCommands(dispatcher, buildContext);
        });

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            if (latestBuildContext != null) {
                registerReplacementCommands(server.getCommands().getDispatcher(), latestBuildContext);
            }
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> UndoManager.clear());
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
            UndoManager.forgetOwner(handler.player.getUUID()));

        if (Boolean.getBoolean(STRESS_TEST_PROPERTY)) {
            LOGGER.info("Better Enchant Commands in-game stress verification enabled via -D{}", STRESS_TEST_PROPERTY);
            ServerLifecycleEvents.SERVER_STARTED.register(InGameStressVerifier::run);
        }

        LOGGER.info("Better Enchant Commands initialized (LuckPerms support: {})", PermissionHelper.isLuckPermsAvailable());
    }

    private static void registerReplacementCommands(
        final CommandDispatcher<CommandSourceStack> dispatcher,
        final CommandBuildContext buildContext
    ) {
        for (String commandName : MOD_COMMANDS) {
            replaceRootCommand(dispatcher, commandName);
        }

        EnchantCommand.register(dispatcher, buildContext);
        GiveCommand.register(dispatcher, buildContext);
        UnenchantCommand.register(dispatcher, buildContext);
        EnchantInfoCommand.register(dispatcher, buildContext);
        EnchantListCommand.register(dispatcher, buildContext);
        EnchantPresetCommand.register(dispatcher, buildContext);
        RepairCommand.register(dispatcher, buildContext);
        EnchantsCommand.register(dispatcher, buildContext);
    }

    private static void replaceRootCommand(final CommandDispatcher<CommandSourceStack> dispatcher, final String commandName) {
        try {
            final CommandNode<CommandSourceStack> root = dispatcher.getRoot();
            for (String fieldName : List.of("children", "literals", "arguments")) {
                final Field field = CommandNode.class.getDeclaredField(fieldName);
                field.setAccessible(true);
                final Object value = field.get(root);
                if (value instanceof Map<?, ?> commandMap) {
                    commandMap.remove(commandName);
                }
            }
        } catch (ReflectiveOperationException exception) {
            LOGGER.warn("Unable to replace vanilla /{} command cleanly; command tree will be merged", commandName, exception);
        }
    }
}
