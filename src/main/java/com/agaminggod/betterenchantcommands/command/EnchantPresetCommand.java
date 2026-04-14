package com.agaminggod.betterenchantcommands.command;

import com.agaminggod.betterenchantcommands.BetterEnchantCommands;
import com.agaminggod.betterenchantcommands.audit.AuditLogger;
import com.agaminggod.betterenchantcommands.compat.MinecraftCompatibility;
import com.agaminggod.betterenchantcommands.config.BetterEnchantConfig;
import com.agaminggod.betterenchantcommands.config.BetterEnchantConfig.PresetEntry;
import com.agaminggod.betterenchantcommands.permission.PermissionHelper;
import com.agaminggod.betterenchantcommands.undo.UndoManager;
import com.agaminggod.betterenchantcommands.util.EnchantmentCompat;
import com.agaminggod.betterenchantcommands.util.Messages;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;

public final class EnchantPresetCommand {
    private static final String COMMAND_NAME = "enchantpreset";
    private static final String NAME_ARGUMENT = "name";
    private static final String TARGETS_ARGUMENT = "targets";
    private static final int REQUIRED_PERMISSION_LEVEL = 2;

    private static final SuggestionProvider<CommandSourceStack> PRESET_NAME_SUGGESTIONS = (context, builder) -> {
        final String remaining = builder.getRemainingLowerCase();
        for (String name : BetterEnchantConfig.presetsSnapshot().keySet()) {
            if (remaining.isEmpty() || name.toLowerCase().startsWith(remaining)) {
                builder.suggest(name);
            }
        }
        return builder.buildFuture();
    };

    private EnchantPresetCommand() {
    }

    public static void register(
        final CommandDispatcher<CommandSourceStack> dispatcher,
        final CommandBuildContext buildContext
    ) {
        dispatcher.register(
            Commands.literal(COMMAND_NAME)
                .requires(source -> PermissionHelper.check(source, PermissionHelper.NODE_PRESET, REQUIRED_PERMISSION_LEVEL))
                .then(Commands.literal("list").executes(EnchantPresetCommand::executeList))
                .then(Commands.literal("save")
                    .then(Commands.argument(NAME_ARGUMENT, StringArgumentType.word())
                        .executes(EnchantPresetCommand::executeSave)
                    )
                )
                .then(Commands.literal("delete")
                    .then(Commands.argument(NAME_ARGUMENT, StringArgumentType.word())
                        .suggests(PRESET_NAME_SUGGESTIONS)
                        .executes(EnchantPresetCommand::executeDelete)
                    )
                )
                .then(Commands.literal("apply")
                    .then(Commands.argument(NAME_ARGUMENT, StringArgumentType.word())
                        .suggests(PRESET_NAME_SUGGESTIONS)
                        .then(Commands.argument(TARGETS_ARGUMENT, EntityArgument.players())
                            .executes(EnchantPresetCommand::executeApply)
                        )
                    )
                )
        );
    }

    private static int executeList(final CommandContext<CommandSourceStack> context) {
        final CommandSourceStack source = context.getSource();
        final var presets = BetterEnchantConfig.presetsSnapshot();
        if (presets.isEmpty()) {
            source.sendSuccess(() -> Messages.info("preset.list_empty",
                "No presets defined. Use /enchantpreset save <name> to store the held item's enchantments."), false);
            return 0;
        }

        source.sendSuccess(() -> Messages.accent("preset.list_header",
            "Presets (%d):", presets.size()), false);
        for (var entry : presets.entrySet()) {
            final String name = entry.getKey();
            final int size = entry.getValue().size();
            source.sendSuccess(() -> Messages.info("preset.list_entry",
                "  - %s (%d enchantments)", name, size), false);
        }
        return presets.size();
    }

    private static int executeSave(final CommandContext<CommandSourceStack> context) {
        final CommandSourceStack source = context.getSource();
        final String name = StringArgumentType.getString(context, NAME_ARGUMENT);
        try {
            final ServerPlayer player = source.getPlayerOrException();
            final ItemStack stack = player.getMainHandItem();
            if (stack.isEmpty()) {
                source.sendFailure(Messages.error("error.not_holding_item",
                    "You must hold the enchanted item you want to save as a preset."));
                return 0;
            }

            final ItemEnchantments enchantments = MinecraftCompatibility.getComponentOrDefault(
                stack, DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY);
            if (enchantments.isEmpty()) {
                source.sendFailure(Messages.error("preset.no_enchantments",
                    "The held item has no enchantments to save."));
                return 0;
            }

            final List<PresetEntry> entries = new ArrayList<>();
            for (Holder<Enchantment> holder : enchantments.keySet()) {
                entries.add(new PresetEntry(EnchantmentCompat.shortId(holder), enchantments.getLevel(holder)));
            }

            if (!BetterEnchantConfig.savePreset(name, entries)) {
                source.sendFailure(Messages.error("preset.save_failed",
                    "Failed to save preset \"%s\".", name));
                return 0;
            }

            AuditLogger.logConfigChange(source, "preset.save", name);
            source.sendSuccess(() -> Messages.success("preset.saved",
                "Saved preset \"%s\" with %d enchantments.", name, entries.size()), true);
            return entries.size();
        } catch (CommandSyntaxException exception) {
            source.sendFailure(Messages.error("error.not_a_player",
                "Only players can save presets from a held item."));
            return 0;
        }
    }

    private static int executeDelete(final CommandContext<CommandSourceStack> context) {
        final CommandSourceStack source = context.getSource();
        final String name = StringArgumentType.getString(context, NAME_ARGUMENT);
        if (!BetterEnchantConfig.deletePreset(name)) {
            source.sendFailure(Messages.error("preset.not_found",
                "Preset \"%s\" does not exist.", name));
            return 0;
        }

        AuditLogger.logConfigChange(source, "preset.delete", name);
        source.sendSuccess(() -> Messages.success("preset.deleted",
            "Deleted preset \"%s\".", name), true);
        return 1;
    }

    private static int executeApply(final CommandContext<CommandSourceStack> context) {
        final CommandSourceStack source = context.getSource();
        final String name = StringArgumentType.getString(context, NAME_ARGUMENT);
        try {
            final Collection<ServerPlayer> targets = EntityArgument.getPlayers(context, TARGETS_ARGUMENT);
            final List<PresetEntry> entries = BetterEnchantConfig.preset(name);
            if (entries == null) {
                source.sendFailure(Messages.error("preset.not_found",
                    "Preset \"%s\" does not exist.", name));
                return 0;
            }

            if (source.getServer() == null) {
                source.sendFailure(Messages.error("error.server_unavailable", "Server is unavailable."));
                return 0;
            }

            final Registry<Enchantment> registry = source.getServer().registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
            final List<ResolvedEntry> resolved = new ArrayList<>(entries.size());
            for (PresetEntry entry : entries) {
                final Identifier id;
                try {
                    id = Identifier.parse(entry.id());
                } catch (RuntimeException exception) {
                    source.sendFailure(Messages.error("preset.bad_id",
                        "Invalid id in preset: %s", entry.id()));
                    return 0;
                }
                final Holder<Enchantment> holder = MinecraftCompatibility.findRegistryHolderById(registry, id);
                if (holder == null) {
                    source.sendFailure(Messages.error("error.unknown_enchantment",
                        "Unknown enchantment(s): %s", entry.id()));
                    return 0;
                }
                resolved.add(new ResolvedEntry(holder, entry.level(), entry.id()));
            }

            final UndoManager.Snapshot snapshot = UndoManager.beginMainHandSnapshot("/enchantpreset apply " + name);
            int successes = 0;
            for (ServerPlayer target : targets) {
                final ItemStack stack = target.getMainHandItem();
                if (stack.isEmpty()) {
                    continue;
                }

                if (!BetterEnchantConfig.allowAllEnchantments()) {
                    boolean allCompatible = true;
                    for (ResolvedEntry entry : resolved) {
                        if (!EnchantmentCompat.isCompatible(stack, entry.holder)) {
                            source.sendFailure(Messages.error("error.incompatible",
                                "You cannot enchant %s with %s",
                                stack.getHoverName().getString(), entry.id));
                            allCompatible = false;
                            break;
                        }
                    }
                    if (!allCompatible) {
                        continue;
                    }
                }

                snapshot.record(target, stack);
                final ItemEnchantments current = MinecraftCompatibility.getComponentOrDefault(
                    stack, DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY);
                final ItemEnchantments.Mutable mutable = new ItemEnchantments.Mutable(current);
                for (ResolvedEntry entry : resolved) {
                    mutable.set(entry.holder, entry.level);
                }
                stack.set(DataComponents.ENCHANTMENTS, mutable.toImmutable());
                successes++;
            }

            if (successes > 0) {
                UndoManager.commit(source, snapshot);
                final int finalSuccesses = successes;
                source.sendSuccess(() -> Messages.success("preset.applied",
                    "Applied preset \"%s\" to %d player(s).", name, finalSuccesses), true);
            }
            return successes;
        } catch (CommandSyntaxException exception) {
            source.sendFailure(Messages.error("error.syntax", "%s", exception.getMessage()));
            return 0;
        } catch (RuntimeException exception) {
            source.sendFailure(Messages.error("error.internal",
                "An internal error occurred while applying the preset."));
            BetterEnchantCommands.LOGGER.error("Unhandled /enchantpreset error: {}", exception.getMessage(), exception);
            return 0;
        }
    }

    private record ResolvedEntry(Holder<Enchantment> holder, int level, String id) {
    }
}
