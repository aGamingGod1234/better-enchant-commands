package com.agaminggod.betterenchantcommands.command;

import com.agaminggod.betterenchantcommands.audit.AuditLogger;
import com.agaminggod.betterenchantcommands.config.BetterEnchantConfig;
import com.agaminggod.betterenchantcommands.confirmation.ConfirmationManager;
import com.agaminggod.betterenchantcommands.permission.PermissionHelper;
import com.agaminggod.betterenchantcommands.undo.UndoManager;
import com.agaminggod.betterenchantcommands.util.Messages;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

/**
 * Umbrella command that hosts configuration and moderation subcommands:
 * <ul>
 *     <li>/enchants allow_all_enchantments &lt;true|false&gt;</li>
 *     <li>/enchants undo</li>
 *     <li>/enchants confirm &lt;token&gt;</li>
 *     <li>/enchants status</li>
 * </ul>
 */
public final class EnchantsCommand {
    private static final String COMMAND_NAME = "enchants";
    private static final int REQUIRED_PERMISSION_LEVEL = 2;
    private static final int ADMIN_PERMISSION_LEVEL = 4;

    private EnchantsCommand() {
    }

    public static void register(
        final CommandDispatcher<CommandSourceStack> dispatcher,
        final CommandBuildContext buildContext
    ) {
        dispatcher.register(
            Commands.literal(COMMAND_NAME)
                .requires(source -> PermissionHelper.check(source, PermissionHelper.NODE_ENCHANTS, REQUIRED_PERMISSION_LEVEL))
                .then(Commands.literal("allow_all_enchantments")
                    .executes(EnchantsCommand::executeAllowAllStatus)
                    .then(Commands.argument("value", BoolArgumentType.bool())
                        .requires(source -> PermissionHelper.check(source, PermissionHelper.NODE_ENCHANTS_ADMIN, ADMIN_PERMISSION_LEVEL))
                        .executes(EnchantsCommand::executeAllowAll)
                    )
                )
                .then(Commands.literal("undo").executes(EnchantsCommand::executeUndo))
                .then(Commands.literal("confirm")
                    .then(Commands.argument("token", StringArgumentType.word())
                        .executes(EnchantsCommand::executeConfirm)
                    )
                )
                .then(Commands.literal("status").executes(EnchantsCommand::executeStatus))
        );
    }

    private static int executeAllowAll(final CommandContext<CommandSourceStack> context) {
        final boolean value = BoolArgumentType.getBool(context, "value");
        BetterEnchantConfig.setAllowAllEnchantments(value);
        AuditLogger.logConfigChange(context.getSource(), "allow_all_enchantments", value);
        context.getSource().sendSuccess(() -> Messages.success("config.allow_all_set",
            "allow_all_enchantments is now %s", value ? "true" : "false"), true);
        return 1;
    }

    private static int executeAllowAllStatus(final CommandContext<CommandSourceStack> context) {
        context.getSource().sendSuccess(() -> Messages.info("config.allow_all_status",
            "allow_all_enchantments is %s",
            BetterEnchantConfig.allowAllEnchantments() ? "true" : "false"), false);
        return 1;
    }

    private static int executeStatus(final CommandContext<CommandSourceStack> context) {
        final CommandSourceStack source = context.getSource();
        source.sendSuccess(() -> Messages.accent("status.header", "Better Enchant Commands status:"), false);
        source.sendSuccess(() -> Messages.info("status.allow_all",
            "  allow_all_enchantments: %s",
            BetterEnchantConfig.allowAllEnchantments() ? "true" : "false"), false);
        source.sendSuccess(() -> Messages.info("status.audit_log",
            "  audit_log_enabled: %s",
            BetterEnchantConfig.auditLogEnabled() ? "true" : "false"), false);
        source.sendSuccess(() -> Messages.info("status.undo_size",
            "  undo_history_size: %d", BetterEnchantConfig.undoHistorySize()), false);
        source.sendSuccess(() -> Messages.info("status.undo_depth",
            "  your undo depth: %d", UndoManager.depth(source)), false);
        source.sendSuccess(() -> Messages.info("status.confirm_threshold",
            "  confirmation_threshold: %d targets", BetterEnchantConfig.confirmationThreshold()), false);
        source.sendSuccess(() -> Messages.info("status.luckperms",
            "  LuckPerms integration: %s",
            PermissionHelper.isLuckPermsAvailable() ? "active" : "not installed"), false);
        return 1;
    }

    private static int executeUndo(final CommandContext<CommandSourceStack> context) {
        final CommandSourceStack source = context.getSource();
        if (source.getServer() == null) {
            source.sendFailure(Messages.error("error.server_unavailable", "Server is unavailable."));
            return 0;
        }

        final UndoManager.Snapshot snapshot = UndoManager.popLast(source);
        if (snapshot == null) {
            source.sendFailure(Messages.error("undo.empty",
                "No operations to undo."));
            return 0;
        }

        final int restored = snapshot.restore(source.getServer());
        final String label = snapshot.label();
        source.sendSuccess(() -> Messages.success("undo.success",
            "Undid \"%s\" — restored %d item(s).", label, restored), true);
        AuditLogger.logUndo(source, label, restored);
        return restored;
    }

    private static int executeConfirm(final CommandContext<CommandSourceStack> context) {
        final CommandSourceStack source = context.getSource();
        final String token = StringArgumentType.getString(context, "token");
        ConfirmationManager.clearExpired();
        final ConfirmationManager.PendingOperation pending = ConfirmationManager.consume(source, token);
        if (pending == null) {
            source.sendFailure(Messages.error("confirm.invalid",
                "No pending confirmation matches that token (or it has expired)."));
            return 0;
        }

        try {
            return pending.action().getAsInt();
        } catch (RuntimeException exception) {
            final String message = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
            source.sendFailure(Messages.error("confirm.failed",
                "The confirmed action threw an error: %s", message));
            return 0;
        }
    }
}
