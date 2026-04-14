package com.agaminggod.betterenchantcommands.command;

import com.agaminggod.betterenchantcommands.BetterEnchantCommands;
import com.agaminggod.betterenchantcommands.audit.AuditLogger;
import com.agaminggod.betterenchantcommands.compat.MinecraftCompatibility;
import com.agaminggod.betterenchantcommands.config.BetterEnchantConfig;
import com.agaminggod.betterenchantcommands.confirmation.ConfirmationManager;
import com.agaminggod.betterenchantcommands.permission.PermissionHelper;
import com.agaminggod.betterenchantcommands.undo.UndoManager;
import com.agaminggod.betterenchantcommands.util.EnchantmentCompat;
import com.agaminggod.betterenchantcommands.util.EnchantmentParser;
import com.agaminggod.betterenchantcommands.util.Messages;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
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

public final class EnchantCommand {
    private static final int DEFAULT_LEVEL = 1;
    private static final String COMMAND_NAME = "enchant";
    private static final String TARGETS_ARGUMENT = "targets";
    private static final String ENCHANTMENT_ARGUMENT = "enchantment";
    private static final String LEVEL_ARGUMENT = "level";
    private static final int REQUIRED_PERMISSION_LEVEL = 2;
    private static final int MAX_DISPLAYED_FAILURES = 10;
    private static final int[] LEVEL_SUGGESTIONS = {1, 5, 10, 32, 64, 128, 255};

    private static final SuggestionProvider<CommandSourceStack> LEVEL_SUGGESTION_PROVIDER = (context, builder) -> {
        final String remaining = builder.getRemainingLowerCase();
        for (int candidate : LEVEL_SUGGESTIONS) {
            final String text = Integer.toString(candidate);
            if (remaining.isEmpty() || text.startsWith(remaining)) {
                builder.suggest(text);
            }
        }
        return builder.buildFuture();
    };

    private EnchantCommand() {
    }

    public static void register(
        final CommandDispatcher<CommandSourceStack> dispatcher,
        final CommandBuildContext buildContext
    ) {
        dispatcher.register(
            Commands.literal(COMMAND_NAME)
                .requires(source -> PermissionHelper.check(source, PermissionHelper.NODE_ENCHANT, REQUIRED_PERMISSION_LEVEL))
                .then(Commands.literal("list")
                    .executes(EnchantCommand::executeList)
                )
                .then(Commands.argument(TARGETS_ARGUMENT, EntityArgument.players())
                    .then(Commands.argument(ENCHANTMENT_ARGUMENT, ResourceArgument.resource(buildContext, Registries.ENCHANTMENT))
                        .executes(context -> execute(context, DEFAULT_LEVEL))
                        .then(Commands.argument(
                            LEVEL_ARGUMENT,
                            IntegerArgumentType.integer(EnchantmentParser.MIN_LEVEL, EnchantmentParser.MAX_LEVEL)
                        )
                            .suggests(LEVEL_SUGGESTION_PROVIDER)
                            .executes(context -> execute(context, IntegerArgumentType.getInteger(context, LEVEL_ARGUMENT)))
                        )
                    )
                )
        );
    }

    private static int execute(final CommandContext<CommandSourceStack> context, final int level) {
        final CommandSourceStack source = context.getSource();

        try {
            final Collection<ServerPlayer> targets = EntityArgument.getPlayers(context, TARGETS_ARGUMENT);
            final Holder.Reference<Enchantment> enchantmentHolder = ResourceArgument.getEnchantment(context, ENCHANTMENT_ARGUMENT);
            final String enchantmentId = enchantmentHolder.unwrapKey()
                .map(key -> key.identifier().toString())
                .orElse("unknown");

            if (targets.size() > BetterEnchantConfig.confirmationThreshold()) {
                final Collection<ServerPlayer> capturedTargets = List.copyOf(targets);
                final ConfirmationManager.PendingOperation pending = ConfirmationManager.store(source,
                    "/enchant on " + capturedTargets.size() + " targets with " + enchantmentId + " " + level,
                    () -> applyEnchantment(source, capturedTargets, enchantmentHolder, enchantmentId, level));
                source.sendFailure(Messages.error("error.confirmation_required",
                    "This would affect %d targets. Run /enchants confirm %s within 30s or the request will expire.",
                    capturedTargets.size(), pending.token()));
                return 0;
            }

            return applyEnchantment(source, targets, enchantmentHolder, enchantmentId, level);
        } catch (CommandSyntaxException exception) {
            source.sendFailure(Messages.error("error.syntax", "%s", exception.getMessage()));
            return 0;
        } catch (RuntimeException exception) {
            source.sendFailure(Messages.error("error.internal",
                "An internal error occurred while running /enchant. Check server logs."));
            BetterEnchantCommands.LOGGER.error("Unhandled /enchant error: {}", exception.getMessage(), exception);
            return 0;
        }
    }

    static int applyEnchantment(
        final CommandSourceStack source,
        final Collection<ServerPlayer> targets,
        final Holder<Enchantment> enchantmentHolder,
        final String enchantmentId,
        final int level
    ) {
        int successfulTargets = 0;
        final List<String> failedTargets = new ArrayList<>();
        final UndoManager.Snapshot snapshot = UndoManager.beginMainHandSnapshot(
            "/enchant " + enchantmentId + " " + level);

        for (ServerPlayer target : targets) {
            try {
                final ItemStack stack = target.getMainHandItem();
                if (stack.isEmpty()) {
                    failedTargets.add(target.getScoreboardName() + " (not holding an item)");
                    continue;
                }

                if (!BetterEnchantConfig.allowAllEnchantments()
                    && !EnchantmentCompat.isCompatible(stack, enchantmentHolder)) {
                    source.sendFailure(Messages.error("error.incompatible",
                        "You cannot enchant %s with %s",
                        stack.getHoverName().getString(), enchantmentId));
                    failedTargets.add(target.getScoreboardName() + " (incompatible)");
                    continue;
                }

                snapshot.record(target, stack);

                final ItemEnchantments current = MinecraftCompatibility.getComponentOrDefault(
                    stack,
                    DataComponents.ENCHANTMENTS,
                    ItemEnchantments.EMPTY
                );
                final ItemEnchantments.Mutable mutable = new ItemEnchantments.Mutable(current);
                mutable.set(enchantmentHolder, level);
                stack.set(DataComponents.ENCHANTMENTS, mutable.toImmutable());

                successfulTargets++;
                final String targetName = target.getScoreboardName();
                final String itemName = stack.getHoverName().getString();
                source.sendSuccess(() -> Messages.success("success.enchant",
                    "Applied %s %d to %s for %s",
                    enchantmentId, level, itemName, targetName), true);
            } catch (IllegalStateException exception) {
                failedTargets.add(target.getScoreboardName() + " (compatibility error)");
                BetterEnchantCommands.LOGGER.error("Compatibility error applying enchantment to {}",
                    target.getScoreboardName(), exception);
            } catch (RuntimeException exception) {
                failedTargets.add(target.getScoreboardName() + " (internal error)");
                BetterEnchantCommands.LOGGER.error("Failed to apply enchantment to {}: {}",
                    target.getScoreboardName(), exception.getMessage());
            }
        }

        if (successfulTargets > 0) {
            UndoManager.commit(source, snapshot);
            AuditLogger.logEnchant(source, targets, enchantmentId, level);
        }

        if (!failedTargets.isEmpty()) {
            source.sendFailure(Component.literal(formatFailedTargets(failedTargets)).withStyle(ChatFormatting.RED));
        }

        return successfulTargets;
    }

    private static int executeList(final CommandContext<CommandSourceStack> context) {
        final CommandSourceStack source = context.getSource();
        final ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (CommandSyntaxException exception) {
            source.sendFailure(Messages.error("error.not_a_player",
                "This subcommand can only be used by a player."));
            return 0;
        }

        final ItemStack stack = player.getMainHandItem();
        if (stack.isEmpty()) {
            source.sendFailure(Messages.error("error.not_holding_item",
                "You are not holding an item."));
            return 0;
        }

        final ItemEnchantments enchantments = MinecraftCompatibility.getComponentOrDefault(
            stack, DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY);

        if (enchantments.isEmpty()) {
            source.sendSuccess(() -> Messages.info("list.empty",
                "%s has no enchantments.", stack.getHoverName().getString()), false);
            return 1;
        }

        final String itemName = stack.getHoverName().getString();
        source.sendSuccess(() -> Messages.accent("list.header",
            "Enchantments on %s:", itemName), false);

        int count = 0;
        for (Holder<Enchantment> holder : enchantments.keySet()) {
            final String id = EnchantmentCompat.shortId(holder);
            final int level = enchantments.getLevel(holder);
            source.sendSuccess(() -> Messages.info("list.entry", "  - %s (level %d)", id, level), false);
            count++;
        }
        return count;
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
