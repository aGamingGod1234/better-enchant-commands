package com.agaminggod.betterenchantcommands.command;

import com.agaminggod.betterenchantcommands.BetterEnchantCommands;
import com.agaminggod.betterenchantcommands.audit.AuditLogger;
import com.agaminggod.betterenchantcommands.compat.MinecraftCompatibility;
import com.agaminggod.betterenchantcommands.permission.PermissionHelper;
import com.agaminggod.betterenchantcommands.undo.UndoManager;
import com.agaminggod.betterenchantcommands.util.EnchantmentCompat;
import com.agaminggod.betterenchantcommands.util.Messages;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.ResourceArgument;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;

public final class UnenchantCommand {
    private static final String COMMAND_NAME = "unenchant";
    private static final String TARGETS_ARGUMENT = "targets";
    private static final String ENCHANTMENT_ARGUMENT = "enchantment";
    private static final int REQUIRED_PERMISSION_LEVEL = 2;
    private static final int MAX_DISPLAYED_FAILURES = 10;

    private UnenchantCommand() {
    }

    public static void register(
        final CommandDispatcher<CommandSourceStack> dispatcher,
        final CommandBuildContext buildContext
    ) {
        dispatcher.register(
            Commands.literal(COMMAND_NAME)
                .requires(source -> PermissionHelper.check(source, PermissionHelper.NODE_UNENCHANT, REQUIRED_PERMISSION_LEVEL))
                .then(Commands.argument(TARGETS_ARGUMENT, EntityArgument.players())
                    .executes(context -> execute(context, null))
                    .then(Commands.argument(ENCHANTMENT_ARGUMENT, ResourceArgument.resource(buildContext, Registries.ENCHANTMENT))
                        .executes(context -> execute(context,
                            ResourceArgument.getEnchantment(context, ENCHANTMENT_ARGUMENT)))
                    )
                )
        );
    }

    private static int execute(
        final CommandContext<CommandSourceStack> context,
        final Holder<Enchantment> specificEnchantment
    ) {
        final CommandSourceStack source = context.getSource();
        try {
            final Collection<ServerPlayer> targets = EntityArgument.getPlayers(context, TARGETS_ARGUMENT);
            final String targetId = specificEnchantment == null
                ? null
                : EnchantmentCompat.shortId(specificEnchantment);

            final UndoManager.Snapshot snapshot = UndoManager.beginMainHandSnapshot(
                "/unenchant " + (targetId == null ? "all" : targetId));

            int successfulTargets = 0;
            final List<String> failedTargets = new ArrayList<>();

            for (ServerPlayer target : targets) {
                final ItemStack stack = target.getMainHandItem();
                if (stack.isEmpty()) {
                    failedTargets.add(target.getScoreboardName() + " (not holding an item)");
                    continue;
                }

                final ItemEnchantments current = MinecraftCompatibility.getComponentOrDefault(
                    stack, DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY);

                if (current.isEmpty()) {
                    failedTargets.add(target.getScoreboardName() + " (item has no enchantments)");
                    continue;
                }

                snapshot.record(target, stack);

                if (specificEnchantment == null) {
                    stack.set(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY);
                    final String targetName = target.getScoreboardName();
                    final String itemName = stack.getHoverName().getString();
                    source.sendSuccess(() -> Messages.success("success.unenchant_all",
                        "Removed all enchantments from %s's %s", targetName, itemName), true);
                    successfulTargets++;
                } else {
                    if (current.getLevel(specificEnchantment) <= 0) {
                        failedTargets.add(target.getScoreboardName() + " (item doesn't have " + targetId + ")");
                        continue;
                    }

                    // Rebuild enchantments excluding the one to remove — safer than
                    // relying on Mutable.set(holder, 0) semantics across versions.
                    final ItemEnchantments.Mutable mutable = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
                    for (Holder<Enchantment> holder : current.keySet()) {
                        if (holder.equals(specificEnchantment)) {
                            continue;
                        }
                        mutable.set(holder, current.getLevel(holder));
                    }
                    stack.set(DataComponents.ENCHANTMENTS, mutable.toImmutable());
                    final String targetName = target.getScoreboardName();
                    source.sendSuccess(() -> Messages.success("success.unenchant_one",
                        "Removed %s from %s's held item", targetId, targetName), true);
                    successfulTargets++;
                }
            }

            if (successfulTargets > 0) {
                UndoManager.commit(source, snapshot);
                AuditLogger.logUnenchant(source, targets, targetId);
            }

            if (!failedTargets.isEmpty()) {
                source.sendFailure(Component.literal(formatFailedTargets(failedTargets)).withStyle(ChatFormatting.RED));
            }

            return successfulTargets;
        } catch (CommandSyntaxException exception) {
            source.sendFailure(Component.literal(exception.getMessage()).withStyle(ChatFormatting.RED));
            return 0;
        } catch (RuntimeException exception) {
            source.sendFailure(Messages.error("error.internal",
                "An internal error occurred while running /unenchant. Check server logs."));
            BetterEnchantCommands.LOGGER.error("Unhandled /unenchant error: {}", exception.getMessage(), exception);
            return 0;
        }
    }

    private static String formatFailedTargets(final List<String> failedTargets) {
        if (failedTargets.size() <= MAX_DISPLAYED_FAILURES) {
            return "Failed targets: " + String.join(", ", failedTargets);
        }

        final List<String> displayed = failedTargets.subList(0, MAX_DISPLAYED_FAILURES);
        final int remaining = failedTargets.size() - MAX_DISPLAYED_FAILURES;
        return "Failed targets: " + String.join(", ", displayed) + " (and " + remaining + " more)";
    }
}
