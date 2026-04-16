package com.agaminggod.betterenchantcommands.command;

import com.agaminggod.betterenchantcommands.BetterEnchantCommands;
import com.agaminggod.betterenchantcommands.audit.AuditLogger;
import com.agaminggod.betterenchantcommands.compat.MinecraftCompatibility;
import com.agaminggod.betterenchantcommands.config.BetterEnchantConfig;
import com.agaminggod.betterenchantcommands.permission.PermissionHelper;
import com.agaminggod.betterenchantcommands.util.EnchantmentCompat;
import com.agaminggod.betterenchantcommands.util.Messages;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.item.ItemArgument;
import net.minecraft.commands.arguments.item.ItemInput;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;

public final class RepairCommand {
    private static final String COMMAND_NAME = "repair";
    private static final String TARGETS_ARGUMENT = "targets";
    private static final String ITEM_ARGUMENT = "item";
    private static final String COUNT_ARGUMENT = "count";
    private static final int DEFAULT_COUNT = 1;
    private static final int MIN_COUNT = 1;
    private static final int MAX_COUNT = 6400;
    private static final int REQUIRED_PERMISSION_LEVEL = 2;
    private static final Identifier MENDING_ID = Identifier.parse("minecraft:mending");
    private static final Identifier UNBREAKING_ID = Identifier.parse("minecraft:unbreaking");

    // Holder lookups go through a reflective compat resolver; the enchantment
    // registry is stable for the server lifetime, so we cache per registry instance
    // and invalidate automatically when a new registry (new server session) appears.
    private static volatile Registry<Enchantment> cachedRegistry;
    private static volatile Holder<Enchantment> cachedMending;
    private static volatile Holder<Enchantment> cachedUnbreaking;

    private RepairCommand() {
    }

    private static Holder<Enchantment> mendingHolder(final Registry<Enchantment> registry) {
        refreshHolderCache(registry);
        return cachedMending;
    }

    private static Holder<Enchantment> unbreakingHolder(final Registry<Enchantment> registry) {
        refreshHolderCache(registry);
        return cachedUnbreaking;
    }

    private static void refreshHolderCache(final Registry<Enchantment> registry) {
        if (cachedRegistry != registry) {
            synchronized (RepairCommand.class) {
                if (cachedRegistry != registry) {
                    cachedMending = MinecraftCompatibility.findRegistryHolderById(registry, MENDING_ID);
                    cachedUnbreaking = MinecraftCompatibility.findRegistryHolderById(registry, UNBREAKING_ID);
                    cachedRegistry = registry;
                }
            }
        }
    }

    public static void register(
        final CommandDispatcher<CommandSourceStack> dispatcher,
        final CommandBuildContext buildContext
    ) {
        dispatcher.register(
            Commands.literal(COMMAND_NAME)
                .requires(source -> PermissionHelper.check(source, PermissionHelper.NODE_REPAIR, REQUIRED_PERMISSION_LEVEL))
                .then(Commands.argument(TARGETS_ARGUMENT, EntityArgument.players())
                    .then(Commands.argument(ITEM_ARGUMENT, ItemArgument.item(buildContext))
                        .executes(context -> execute(context, DEFAULT_COUNT))
                        .then(Commands.argument(COUNT_ARGUMENT, IntegerArgumentType.integer(MIN_COUNT, MAX_COUNT))
                            .executes(context -> execute(context, IntegerArgumentType.getInteger(context, COUNT_ARGUMENT)))
                        )
                    )
                )
        );
    }

    private static int execute(final CommandContext<CommandSourceStack> context, final int count) {
        final CommandSourceStack source = context.getSource();
        try {
            if (source.getServer() == null) {
                source.sendFailure(Messages.error("error.server_unavailable", "Server is unavailable."));
                return 0;
            }

            final ItemInput itemInput = ItemArgument.getItem(context, ITEM_ARGUMENT);
            final var targets = EntityArgument.getPlayers(context, TARGETS_ARGUMENT);

            final Registry<Enchantment> registry = source.getServer().registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
            final Holder<Enchantment> mending = mendingHolder(registry);
            final Holder<Enchantment> unbreaking = unbreakingHolder(registry);

            if (mending == null) {
                source.sendFailure(Messages.error("error.missing_enchantment",
                    "Missing required enchantment: %s", MENDING_ID));
                return 0;
            }

            final Map<Holder<Enchantment>, Integer> enchantments = new LinkedHashMap<>();
            final ItemStack sample = itemInput.createItemStack(1, false);
            if (EnchantmentCompat.isCompatible(sample, mending) || BetterEnchantConfig.allowAllEnchantments()) {
                enchantments.put(mending, 1);
            } else {
                source.sendFailure(Messages.error("error.incompatible",
                    "You cannot enchant %s with %s",
                    sample.getHoverName().getString(), MENDING_ID));
                return 0;
            }
            if (unbreaking != null
                && (EnchantmentCompat.isCompatible(sample, unbreaking) || BetterEnchantConfig.allowAllEnchantments())) {
                enchantments.put(unbreaking, 3);
            }

            int successfulTargets = 0;
            for (ServerPlayer target : targets) {
                final ItemStack stack = itemInput.createItemStack(count, false);
                GiveCommand.applyEnchantmentsToStack(stack, enchantments);
                final boolean added = target.getInventory().add(stack);
                if (!added && !stack.isEmpty()) {
                    target.drop(stack, false);
                }
                successfulTargets++;
                final String itemName = stack.getHoverName().getString();
                final String targetName = target.getScoreboardName();
                source.sendSuccess(() -> Messages.success("success.repair",
                    "Gave %d [%s] to %s (mending applied)",
                    count, itemName, targetName), true);
            }

            if (successfulTargets > 0) {
                AuditLogger.logGive(source, targets,
                    sample.getItem().toString(), count, enchantments);
            }
            return successfulTargets;
        } catch (CommandSyntaxException exception) {
            source.sendFailure(Messages.error("error.syntax", "%s", exception.getMessage()));
            return 0;
        } catch (RuntimeException exception) {
            source.sendFailure(Messages.error("error.internal",
                "An internal error occurred while running /repair."));
            BetterEnchantCommands.LOGGER.error("Unhandled /repair error: {}", exception.getMessage(), exception);
            return 0;
        }
    }
}
