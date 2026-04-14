package com.agaminggod.betterenchantcommands.command;

import com.agaminggod.betterenchantcommands.permission.PermissionHelper;
import com.agaminggod.betterenchantcommands.util.Messages;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.enchantment.Enchantment;

public final class EnchantListCommand {
    private static final String COMMAND_NAME = "enchantlist";
    private static final String FILTER_ARGUMENT = "filter";
    private static final int REQUIRED_PERMISSION_LEVEL = 2;
    private static final int MAX_ENTRIES_PER_PAGE = 40;

    private EnchantListCommand() {
    }

    public static void register(
        final CommandDispatcher<CommandSourceStack> dispatcher,
        final CommandBuildContext buildContext
    ) {
        dispatcher.register(
            Commands.literal(COMMAND_NAME)
                .requires(source -> PermissionHelper.check(source, PermissionHelper.NODE_LIST, REQUIRED_PERMISSION_LEVEL))
                .executes(context -> execute(context, null))
                .then(Commands.argument(FILTER_ARGUMENT, StringArgumentType.word())
                    .executes(context -> execute(context, StringArgumentType.getString(context, FILTER_ARGUMENT)))
                )
        );
    }

    private static int execute(final CommandContext<CommandSourceStack> context, final String filter) {
        final CommandSourceStack source = context.getSource();
        if (source.getServer() == null) {
            source.sendFailure(Messages.error("error.server_unavailable", "Server is unavailable."));
            return 0;
        }

        final Registry<Enchantment> registry = source.getServer().registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
        final String normalizedFilter = filter == null ? "" : filter.toLowerCase(Locale.ROOT);

        final List<String> matching = new ArrayList<>();
        for (Identifier id : registry.keySet()) {
            final String fullId = id.toString();
            if (normalizedFilter.isEmpty() || fullId.toLowerCase(Locale.ROOT).contains(normalizedFilter)) {
                matching.add(fullId);
            }
        }
        Collections.sort(matching);

        if (matching.isEmpty()) {
            source.sendSuccess(() -> Messages.info("list.none_matched",
                "No enchantments matched \"%s\".", normalizedFilter), false);
            return 0;
        }

        final int total = matching.size();
        source.sendSuccess(() -> Messages.accent("list.enchantments_header",
            "Enchantments (%d):", total), false);

        final int limit = Math.min(MAX_ENTRIES_PER_PAGE, matching.size());
        for (int i = 0; i < limit; i++) {
            final String entry = matching.get(i);
            source.sendSuccess(() -> Messages.info("list.enchantments_entry",
                "  - %s", entry), false);
        }

        if (matching.size() > limit) {
            final int remaining = matching.size() - limit;
            source.sendSuccess(() -> Messages.info("list.enchantments_truncated",
                "  (%d more — use /enchantlist <filter> to narrow)", remaining), false);
        }

        return total;
    }
}
