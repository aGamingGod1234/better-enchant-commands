package com.agaminggod.betterenchantcommands.command;

import com.agaminggod.betterenchantcommands.BetterEnchantCommands;
import com.agaminggod.betterenchantcommands.util.EnchantmentParser;
import com.agaminggod.betterenchantcommands.util.EnchantmentParser.ParseResult;
import com.agaminggod.betterenchantcommands.util.EnchantmentParser.ParsedEnchantment;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
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
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
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

    private static final SuggestionProvider<CommandSourceStack> ENCHANTMENT_STRING_SUGGESTIONS =
        (context, builder) -> buildEnchantmentStringSuggestions(context.getSource(), builder);

    private GiveCommand() {
    }

    public static void register(
        final CommandDispatcher<CommandSourceStack> dispatcher,
        final CommandBuildContext buildContext
    ) {
        dispatcher.register(
            Commands.literal(COMMAND_NAME)
                .requires(source -> source.hasPermission(REQUIRED_PERMISSION_LEVEL))
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
                source.sendFailure(
                    Component.literal("Count must be between " + MIN_COUNT + " and " + MAX_COUNT + ".")
                        .withStyle(ChatFormatting.RED)
                );
                return 0;
            }

            final ItemInput itemInput = ItemArgument.getItem(context, ITEM_ARGUMENT);
            final Collection<ServerPlayer> targets = EntityArgument.getPlayers(context, TARGETS_ARGUMENT);
            final Map<Holder<Enchantment>, Integer> resolvedEnchantments = resolveEnchantments(source, enchantmentsString);

            if (resolvedEnchantments == null) {
                return 0;
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
                    final String message = buildSuccessMessage(target, stack, count, resolvedEnchantments);
                    source.sendSuccess(() -> Component.literal(message), true);
                } catch (Exception exception) {
                    failedTargets.add(target.getScoreboardName() + " (internal error)");
                    BetterEnchantCommands.LOGGER.error("Failed to give item to {}", target.getScoreboardName(), exception);
                }
            }

            if (!failedTargets.isEmpty()) {
                source.sendFailure(Component.literal("Failed targets: " + String.join(", ", failedTargets))
                    .withStyle(ChatFormatting.RED));
            }

            return successfulTargets;
        } catch (Exception exception) {
            source.sendFailure(
                Component.literal("An internal error occurred while running /give. Check server logs.")
                    .withStyle(ChatFormatting.RED)
            );
            BetterEnchantCommands.LOGGER.error("Unhandled /give error", exception);
            return 0;
        }
    }

    private static Map<Holder<Enchantment>, Integer> resolveEnchantments(
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

        final Registry<Enchantment> enchantmentRegistry = source.getServer().registryAccess().registryOrThrow(Registries.ENCHANTMENT);
        final Map<Holder<Enchantment>, Integer> resolved = new LinkedHashMap<>();
        final List<String> unknownEnchantments = new ArrayList<>();

        for (ParsedEnchantment parsed : parseResult.enchantments()) {
            final ResourceKey<Enchantment> key = ResourceKey.create(Registries.ENCHANTMENT, parsed.id());
            final Optional<Holder.Reference<Enchantment>> holder = enchantmentRegistry.getHolder(key);

            if (holder.isEmpty()) {
                unknownEnchantments.add(parsed.id().toString());
                continue;
            }

            resolved.put(holder.get(), parsed.level());
        }

        if (!unknownEnchantments.isEmpty()) {
            source.sendFailure(Component.literal("Unknown enchantment(s): " + String.join(", ", unknownEnchantments))
                .withStyle(ChatFormatting.RED));
            return null;
        }

        return resolved;
    }

    private static void applyEnchantmentsToStack(
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

    private static String buildSuccessMessage(
        final ServerPlayer target,
        final ItemStack stack,
        final int count,
        final Map<Holder<Enchantment>, Integer> enchantments
    ) {
        final String baseMessage = "Gave " + count + " [" + stack.getHoverName().getString() + "] to " + target.getScoreboardName();
        if (enchantments.isEmpty()) {
            return baseMessage;
        }

        return baseMessage + " with " + enchantments.size() + " enchantment(s)";
    }

    private static CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> buildEnchantmentStringSuggestions(
        final CommandSourceStack source,
        final SuggestionsBuilder builder
    ) {
        try {
            final String remainingLower = builder.getRemainingLowerCase();

            if (!remainingLower.startsWith(EnchantmentParser.ENCHANTMENTS_PREFIX)) {
                if (EnchantmentParser.ENCHANTMENTS_PREFIX.startsWith(remainingLower)) {
                    builder.suggest(EnchantmentParser.ENCHANTMENTS_PREFIX);
                }
                return builder.buildFuture();
            }

            final Registry<Enchantment> registry = source.getServer().registryAccess().registryOrThrow(Registries.ENCHANTMENT);
            final String input = builder.getRemaining();
            final int commaIndex = input.lastIndexOf(',');
            final String prefix = commaIndex >= 0
                ? input.substring(0, commaIndex + 1)
                : EnchantmentParser.ENCHANTMENTS_PREFIX;
            final String token = commaIndex >= 0
                ? input.substring(commaIndex + 1).trim().toLowerCase(Locale.ROOT)
                : input.substring(EnchantmentParser.ENCHANTMENTS_PREFIX.length()).trim().toLowerCase(Locale.ROOT);

            for (ResourceLocation id : registry.keySet()) {
                final String fullId = id.toString();
                final String shortId = id.getNamespace().equals("minecraft")
                    ? id.getPath()
                    : fullId;

                if (token.isEmpty() || fullId.startsWith(token)) {
                    builder.suggest(prefix + fullId + ":" + EnchantmentParser.MIN_LEVEL);
                }

                if (!shortId.equals(fullId) && (token.isEmpty() || shortId.startsWith(token))) {
                    builder.suggest(prefix + shortId + ":" + EnchantmentParser.MIN_LEVEL);
                }
            }
        } catch (Exception exception) {
            BetterEnchantCommands.LOGGER.debug("Failed to build /give enchantment suggestions", exception);
        }

        return builder.buildFuture();
    }
}

