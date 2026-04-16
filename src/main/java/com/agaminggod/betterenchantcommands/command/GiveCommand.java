package com.agaminggod.betterenchantcommands.command;

import com.agaminggod.betterenchantcommands.BetterEnchantCommands;
import com.agaminggod.betterenchantcommands.audit.AuditLogger;
import com.agaminggod.betterenchantcommands.compat.MinecraftCompatibility;
import com.agaminggod.betterenchantcommands.config.BetterEnchantConfig;
import com.agaminggod.betterenchantcommands.confirmation.ConfirmationManager;
import com.agaminggod.betterenchantcommands.permission.PermissionHelper;
import com.agaminggod.betterenchantcommands.util.EnchantmentCompat;
import com.agaminggod.betterenchantcommands.util.EnchantmentParser;
import com.agaminggod.betterenchantcommands.util.EnchantmentParser.ParseResult;
import com.agaminggod.betterenchantcommands.util.EnchantmentParser.ParsedEnchantment;
import com.agaminggod.betterenchantcommands.util.Messages;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.item.ItemArgument;
import net.minecraft.commands.arguments.item.ItemInput;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;

public final class GiveCommand {
    private static final String COMMAND_NAME = "give";
    private static final String TARGETS_ARGUMENT = "targets";
    private static final String ITEM_ARGUMENT = "item";
    private static final String COUNT_ARGUMENT = "count";
    private static final String ENCHANTMENTS_ARGUMENT = "enchantments";
    private static final int DEFAULT_COUNT = 1;
    private static final int MIN_COUNT = 1;
    private static final int MAX_COUNT = 6400;
    private static final int REQUIRED_PERMISSION_LEVEL = 2;
    private static final int MAX_DISPLAYED_FAILURES = 10;
    private static final int MAX_SUGGESTIONS = 50;

    private static final SuggestionProvider<CommandSourceStack> ENCHANTMENT_STRING_SUGGESTIONS =
        (context, builder) -> buildEnchantmentStringSuggestions(context, builder);

    private GiveCommand() {
    }

    public static void register(
        final CommandDispatcher<CommandSourceStack> dispatcher,
        final CommandBuildContext buildContext
    ) {
        dispatcher.register(
            Commands.literal(COMMAND_NAME)
                .requires(source -> PermissionHelper.check(source, PermissionHelper.NODE_GIVE, REQUIRED_PERMISSION_LEVEL))
                .then(Commands.argument(TARGETS_ARGUMENT, EntityArgument.players())
                    .then(Commands.argument(ITEM_ARGUMENT, ItemArgument.item(buildContext))
                        .executes(context -> execute(context, DEFAULT_COUNT, null))
                        .then(Commands.argument(COUNT_ARGUMENT, IntegerArgumentType.integer(MIN_COUNT, MAX_COUNT))
                            .executes(context -> execute(context, IntegerArgumentType.getInteger(context, COUNT_ARGUMENT), null))
                            .then(Commands.argument(ENCHANTMENTS_ARGUMENT, StringArgumentType.greedyString())
                                .suggests(ENCHANTMENT_STRING_SUGGESTIONS)
                                .executes(context -> execute(
                                    context,
                                    IntegerArgumentType.getInteger(context, COUNT_ARGUMENT),
                                    StringArgumentType.getString(context, ENCHANTMENTS_ARGUMENT)
                                ))
                            )
                        )
                        .then(Commands.argument(ENCHANTMENTS_ARGUMENT, StringArgumentType.greedyString())
                            .suggests(ENCHANTMENT_STRING_SUGGESTIONS)
                            .executes(context -> execute(
                                context,
                                DEFAULT_COUNT,
                                StringArgumentType.getString(context, ENCHANTMENTS_ARGUMENT)
                            ))
                        )
                    )
                )
        );
    }

    private static int execute(
        final CommandContext<CommandSourceStack> context,
        final int count,
        final String enchantmentsString
    ) {
        final CommandSourceStack source = context.getSource();

        try {
            if (count < MIN_COUNT || count > MAX_COUNT) {
                source.sendFailure(Messages.error("error.count_out_of_range",
                    "Count must be between %d and %d.", MIN_COUNT, MAX_COUNT));
                return 0;
            }

            final ItemInput itemInput = ItemArgument.getItem(context, ITEM_ARGUMENT);
            final Collection<ServerPlayer> targets = EntityArgument.getPlayers(context, TARGETS_ARGUMENT);
            final Map<Holder<Enchantment>, Integer> resolvedEnchantments = resolveEnchantments(source, enchantmentsString);

            if (resolvedEnchantments == null) {
                return 0;
            }

            if (targets.size() > BetterEnchantConfig.confirmationThreshold()) {
                final Collection<ServerPlayer> capturedTargets = List.copyOf(targets);
                final Map<Holder<Enchantment>, Integer> capturedEnch = Map.copyOf(resolvedEnchantments);
                final ConfirmationManager.PendingOperation pending = ConfirmationManager.store(source,
                    "/give on " + capturedTargets.size() + " targets",
                    () -> distributeItems(source, capturedTargets, itemInput, count, capturedEnch));
                source.sendFailure(Messages.error("error.confirmation_required",
                    "This would affect %d targets. Run /enchants confirm %s within 30s or the request will expire.",
                    capturedTargets.size(), pending.token()));
                return 0;
            }

            return distributeItems(source, targets, itemInput, count, resolvedEnchantments);
        } catch (CommandSyntaxException exception) {
            source.sendFailure(Component.literal(exception.getMessage()).withStyle(ChatFormatting.RED));
            return 0;
        } catch (RuntimeException exception) {
            source.sendFailure(Messages.error("error.internal",
                "An internal error occurred while running /give. Check server logs."));
            BetterEnchantCommands.LOGGER.error("Unhandled /give error: {}", exception.getMessage(), exception);
            return 0;
        }
    }

    static int distributeItems(
        final CommandSourceStack source,
        final Collection<ServerPlayer> targets,
        final ItemInput itemInput,
        final int count,
        final Map<Holder<Enchantment>, Integer> resolvedEnchantments
    ) {
        if (!BetterEnchantConfig.allowAllEnchantments() && !resolvedEnchantments.isEmpty()) {
            final ItemStack sample;
            try {
                sample = itemInput.createItemStack(1, false);
            } catch (CommandSyntaxException exception) {
                source.sendFailure(Messages.error("error.syntax", "%s", exception.getMessage()));
                return 0;
            }
            final String itemName = sample.getHoverName().getString();
            for (Map.Entry<Holder<Enchantment>, Integer> entry : resolvedEnchantments.entrySet()) {
                if (!EnchantmentCompat.isCompatible(sample, entry.getKey())) {
                    final String id = EnchantmentCompat.shortId(entry.getKey());
                    source.sendFailure(Messages.error("error.incompatible",
                        "You cannot enchant %s with %s", itemName, id));
                    return 0;
                }
            }
        }

        int successfulTargets = 0;
        final List<String> failedTargets = new ArrayList<>();

        for (ServerPlayer target : targets) {
            try {
                final ItemStack stack = itemInput.createItemStack(count, false);
                applyEnchantmentsToStack(stack, resolvedEnchantments);
                final boolean added = target.getInventory().add(stack);

                if (!added && !stack.isEmpty()) {
                    target.drop(stack, false);
                }

                successfulTargets++;
                final String itemName = stack.getHoverName().getString();
                final String targetName = target.getScoreboardName();
                final int enchCount = resolvedEnchantments.size();
                source.sendSuccess(() -> enchCount == 0
                    ? Messages.success("success.give",
                        "Gave %d [%s] to %s", count, itemName, targetName)
                    : Messages.success("success.give_enchanted",
                        "Gave %d [%s] to %s with %d enchantment(s)",
                        count, itemName, targetName, enchCount), true);
            } catch (CommandSyntaxException exception) {
                failedTargets.add(target.getScoreboardName() + " (syntax error)");
                BetterEnchantCommands.LOGGER.error("Syntax error giving item to {}: {}",
                    target.getScoreboardName(), exception.getMessage());
            } catch (IllegalStateException exception) {
                failedTargets.add(target.getScoreboardName() + " (compatibility error)");
                BetterEnchantCommands.LOGGER.error("Compatibility error giving item to {}",
                    target.getScoreboardName(), exception);
            } catch (RuntimeException exception) {
                failedTargets.add(target.getScoreboardName() + " (internal error)");
                BetterEnchantCommands.LOGGER.error("Failed to give item to {}: {}",
                    target.getScoreboardName(), exception.getMessage());
            }
        }

        if (successfulTargets > 0) {
            AuditLogger.logGive(source, targets, itemName(itemInput), count, resolvedEnchantments);
        }

        if (!failedTargets.isEmpty()) {
            source.sendFailure(Component.literal(formatFailedTargets(failedTargets)).withStyle(ChatFormatting.RED));
        }

        return successfulTargets;
    }

    private static String itemName(final ItemInput itemInput) {
        try {
            return itemInput.createItemStack(1, false).getItem().toString();
        } catch (CommandSyntaxException | RuntimeException exception) {
            return "unknown";
        }
    }

    static Map<Holder<Enchantment>, Integer> resolveEnchantments(
        final CommandSourceStack source,
        final String enchantmentsString
    ) {
        if (enchantmentsString == null || enchantmentsString.isBlank()) {
            return Map.of();
        }

        final ParseResult parseResult = EnchantmentParser.parse(enchantmentsString);
        if (!parseResult.successful()) {
            source.sendFailure(Component.literal(parseResult.errorMessage()).withStyle(ChatFormatting.RED));
            return null;
        }

        if (source.getServer() == null) {
            source.sendFailure(Messages.error("error.server_unavailable", "Server is unavailable."));
            return null;
        }

        final Registry<Enchantment> enchantmentRegistry = source.getServer().registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
        final Map<Holder<Enchantment>, Integer> resolved = new LinkedHashMap<>();
        final List<String> unknownEnchantments = new ArrayList<>();

        for (ParsedEnchantment parsed : parseResult.enchantments()) {
            final Holder<Enchantment> holder = MinecraftCompatibility.findRegistryHolderById(enchantmentRegistry, parsed.id());
            if (holder == null) {
                unknownEnchantments.add(parsed.id().toString());
                continue;
            }

            resolved.put(holder, parsed.level());
        }

        if (!unknownEnchantments.isEmpty()) {
            source.sendFailure(Component.literal(formatUnknownEnchantments(unknownEnchantments))
                .withStyle(ChatFormatting.RED));
            return null;
        }

        return resolved;
    }

    static void applyEnchantmentsToStack(
        final ItemStack stack,
        final Map<Holder<Enchantment>, Integer> enchantments
    ) {
        if (enchantments.isEmpty()) {
            return;
        }

        final ItemEnchantments.Mutable mutableEnchantments = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
        for (Map.Entry<Holder<Enchantment>, Integer> entry : enchantments.entrySet()) {
            mutableEnchantments.set(entry.getKey(), entry.getValue());
        }

        stack.set(DataComponents.ENCHANTMENTS, mutableEnchantments.toImmutable());
    }

    private static String formatFailedTargets(final List<String> failedTargets) {
        if (failedTargets.size() <= MAX_DISPLAYED_FAILURES) {
            return "Failed targets: " + String.join(", ", failedTargets);
        }

        final List<String> displayed = failedTargets.subList(0, MAX_DISPLAYED_FAILURES);
        final int remaining = failedTargets.size() - MAX_DISPLAYED_FAILURES;
        return "Failed targets: " + String.join(", ", displayed) + " (and " + remaining + " more)";
    }

    private static String formatUnknownEnchantments(final List<String> unknownEnchantments) {
        if (unknownEnchantments.size() <= MAX_DISPLAYED_FAILURES) {
            return "Unknown enchantment(s): " + String.join(", ", unknownEnchantments);
        }

        final List<String> displayed = unknownEnchantments.subList(0, MAX_DISPLAYED_FAILURES);
        final int remaining = unknownEnchantments.size() - MAX_DISPLAYED_FAILURES;
        return "Unknown enchantment(s): " + String.join(", ", displayed) + " (and " + remaining + " more)";
    }

    private static CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> buildEnchantmentStringSuggestions(
        final CommandContext<CommandSourceStack> context,
        final SuggestionsBuilder builder
    ) {
        try {
            final CommandSourceStack source = context.getSource();
            final String remainingLower = builder.getRemainingLowerCase();

            if (!remainingLower.startsWith(EnchantmentParser.ENCHANTMENTS_PREFIX)) {
                if (EnchantmentParser.ENCHANTMENTS_PREFIX.startsWith(remainingLower)) {
                    builder.suggest(EnchantmentParser.ENCHANTMENTS_PREFIX);
                }
                return builder.buildFuture();
            }

            if (source.getServer() == null) {
                return builder.buildFuture();
            }

            final Registry<Enchantment> registry = source.getServer().registryAccess().lookupOrThrow(Registries.ENCHANTMENT);

            final ItemStack contextStack = tryGetContextItem(context);

            final String input = builder.getRemaining();
            final int commaIndex = input.lastIndexOf(',');
            final String prefix = commaIndex >= 0
                ? input.substring(0, commaIndex + 1)
                : EnchantmentParser.ENCHANTMENTS_PREFIX;
            final String token = commaIndex >= 0
                ? input.substring(commaIndex + 1).trim().toLowerCase(Locale.ROOT)
                : input.substring(EnchantmentParser.ENCHANTMENTS_PREFIX.length()).trim().toLowerCase(Locale.ROOT);

            int emitted = 0;
            for (Identifier id : registry.keySet()) {
                if (emitted >= MAX_SUGGESTIONS) {
                    break;
                }

                // Item-type filtering: if we know the target item and config is in strict
                // mode, only suggest enchantments that can actually be applied.
                if (contextStack != null && !BetterEnchantConfig.allowAllEnchantments()) {
                    final Holder<Enchantment> holder = MinecraftCompatibility.findRegistryHolderById(registry, id);
                    if (holder != null && !EnchantmentCompat.isCompatible(contextStack, holder)) {
                        continue;
                    }
                }

                final String fullId = id.toString();
                final boolean isMinecraft = id.getNamespace().equals("minecraft");
                final String shortId = isMinecraft ? id.getPath() : fullId;

                if (token.isEmpty() || fullId.startsWith(token)) {
                    builder.suggest(prefix + fullId + ":" + EnchantmentParser.MIN_LEVEL);
                    emitted++;
                }

                if (emitted < MAX_SUGGESTIONS
                    && !shortId.equals(fullId)
                    && (token.isEmpty() || shortId.startsWith(token))) {
                    builder.suggest(prefix + shortId + ":" + EnchantmentParser.MIN_LEVEL);
                    emitted++;
                }
            }
        } catch (Exception exception) {
            BetterEnchantCommands.LOGGER.debug("Failed to build /give enchantment suggestions", exception);
        }

        return builder.buildFuture();
    }

    private static ItemStack tryGetContextItem(final CommandContext<CommandSourceStack> context) {
        try {
            final ItemInput itemInput = ItemArgument.getItem(context, ITEM_ARGUMENT);
            return itemInput.createItemStack(1, false);
        } catch (CommandSyntaxException | RuntimeException exception) {
            // RuntimeException covers IllegalArgumentException and other unchecked
            // parsing failures while the suggestion provider is being populated.
            return null;
        }
    }
}
