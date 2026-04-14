package com.agaminggod.betterenchantcommands.command;

import com.agaminggod.betterenchantcommands.permission.PermissionHelper;
import com.agaminggod.betterenchantcommands.util.EnchantmentCompat;
import com.agaminggod.betterenchantcommands.util.Messages;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.ResourceArgument;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.enchantment.Enchantment;

public final class EnchantInfoCommand {
    private static final String COMMAND_NAME = "enchantinfo";
    private static final String ENCHANTMENT_ARGUMENT = "enchantment";
    private static final int REQUIRED_PERMISSION_LEVEL = 2;

    private EnchantInfoCommand() {
    }

    public static void register(
        final CommandDispatcher<CommandSourceStack> dispatcher,
        final CommandBuildContext buildContext
    ) {
        dispatcher.register(
            Commands.literal(COMMAND_NAME)
                .requires(source -> PermissionHelper.check(source, PermissionHelper.NODE_INFO, REQUIRED_PERMISSION_LEVEL))
                .then(Commands.argument(ENCHANTMENT_ARGUMENT, ResourceArgument.resource(buildContext, Registries.ENCHANTMENT))
                    .executes(EnchantInfoCommand::execute)
                )
        );
    }

    private static int execute(final CommandContext<CommandSourceStack> context) {
        final CommandSourceStack source = context.getSource();
        try {
            final Holder<Enchantment> holder = ResourceArgument.getEnchantment(context, ENCHANTMENT_ARGUMENT);
            final String id = EnchantmentCompat.shortId(holder);
            final Enchantment enchantment = holder.value();

            source.sendSuccess(() -> Messages.accent("info.header",
                "Enchantment: %s", id), false);

            int vanillaMaxLevel = -1;
            try {
                vanillaMaxLevel = enchantment.getMaxLevel();
            } catch (RuntimeException ignored) {
                // Fall back if mapping not present.
            }
            final int finalMaxLevel = vanillaMaxLevel;
            source.sendSuccess(() -> Messages.info("info.max_level",
                "  Vanilla max level: %s",
                finalMaxLevel < 0 ? "unknown" : Integer.toString(finalMaxLevel)), false);
            source.sendSuccess(() -> Messages.info("info.mod_max_level",
                "  Mod-allowed max level: %d", 255), false);
            return 1;
        } catch (CommandSyntaxException exception) {
            source.sendFailure(Messages.error("error.syntax", "%s", exception.getMessage()));
            return 0;
        } catch (RuntimeException exception) {
            source.sendFailure(Messages.error("error.internal",
                "An internal error occurred while running /enchantinfo."));
            return 0;
        }
    }
}
