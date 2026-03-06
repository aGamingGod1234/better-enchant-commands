package com.agaminggod.betterenchantcommands.command;

import com.agaminggod.betterenchantcommands.BetterEnchantCommands;
import com.agaminggod.betterenchantcommands.util.EnchantmentParser;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.Suggestions;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.ResourceArgument;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;

public final class EnchantCommand {
    private static final int DEFAULT_LEVEL = 1;
    private static final String COMMAND_NAME = "enchant";
    private static final String TARGETS_ARGUMENT = "targets";
    private static final String ENCHANTMENT_ARGUMENT = "enchantment";
    private static final String LEVEL_ARGUMENT = "level";
    private static final int REQUIRED_PERMISSION_LEVEL = 2;

    private EnchantCommand() {
    }

    public static void register(
        final CommandDispatcher<CommandSourceStack> dispatcher,
        final CommandBuildContext buildContext
    ) {
        dispatcher.register(
            Commands.literal(COMMAND_NAME)
                .requires(source -> source.hasPermission(REQUIRED_PERMISSION_LEVEL))
                .then(Commands.argument(TARGETS_ARGUMENT, EntityArgument.players())
                    .then(Commands.argument(ENCHANTMENT_ARGUMENT, ResourceArgument.resource(buildContext, Registries.ENCHANTMENT))
                        .executes(context -> execute(context, DEFAULT_LEVEL))
                        .then(Commands.argument(
                            LEVEL_ARGUMENT,
                            IntegerArgumentType.integer(EnchantmentParser.MIN_LEVEL, EnchantmentParser.MAX_LEVEL)
                        ).executes(context -> execute(context, IntegerArgumentType.getInteger(context, LEVEL_ARGUMENT)))
                        )
                    )
                )
        );
    }

    private static int execute(final CommandContext<CommandSourceStack> context, final int level) {
        final CommandSourceStack source = context.getSource();

        try {
            if (level < EnchantmentParser.MIN_LEVEL || level > EnchantmentParser.MAX_LEVEL) {
                source.sendFailure(Component.literal("Level must be between " + EnchantmentParser.MIN_LEVEL + " and "
                    + EnchantmentParser.MAX_LEVEL + ".").withStyle(ChatFormatting.RED));
                return 0;
            }

            final Collection<ServerPlayer> targets = EntityArgument.getPlayers(context, TARGETS_ARGUMENT);
            final Holder.Reference<Enchantment> enchantmentHolder = ResourceArgument.getEnchantment(context, ENCHANTMENT_ARGUMENT);
            final String enchantmentId = enchantmentHolder.unwrapKey()
                .map(key -> key.location().toString())
                .orElse("unknown");

            int successfulTargets = 0;
            final List<String> failedTargets = new ArrayList<>();

            for (ServerPlayer target : targets) {
                try {
                    final ItemStack stack = target.getMainHandItem();
                    if (stack.isEmpty()) {
                        failedTargets.add(target.getScoreboardName() + " (not holding an item)");
                        continue;
                    }

                    final ItemEnchantments existingEnchantments = stack.get(DataComponents.ENCHANTMENTS);
                    final ItemEnchantments current = existingEnchantments == null
                        ? ItemEnchantments.EMPTY
                        : existingEnchantments;
                    final ItemEnchantments.Mutable mutable = new ItemEnchantments.Mutable(current);
                    mutable.set(enchantmentHolder, level);
                    stack.set(DataComponents.ENCHANTMENTS, mutable.toImmutable());

                    successfulTargets++;
                    final String successMessage = "Applied " + enchantmentId + " " + level + " to "
                        + stack.getHoverName().getString() + " for " + target.getScoreboardName();
                    source.sendSuccess(() -> Component.literal(successMessage), true);
                } catch (Exception exception) {
                    failedTargets.add(target.getScoreboardName() + " (internal error)");
                    BetterEnchantCommands.LOGGER.error("Failed to apply enchantment to {}", target.getScoreboardName(), exception);
                }
            }

            if (!failedTargets.isEmpty()) {
                source.sendFailure(Component.literal(
                    "Failed targets: " + String.join(", ", failedTargets)
                ).withStyle(ChatFormatting.RED));
            }

            return successfulTargets;
        } catch (Exception exception) {
            source.sendFailure(
                Component.literal("An internal error occurred while running /enchant. Check server logs.")
                    .withStyle(ChatFormatting.RED)
            );
            BetterEnchantCommands.LOGGER.error("Unhandled /enchant error", exception);
            return 0;
        }
    }
}

