package com.agaminggod.betterenchantcommands.audit;

import com.agaminggod.betterenchantcommands.BetterEnchantCommands;
import com.agaminggod.betterenchantcommands.config.BetterEnchantConfig;
import java.util.Collection;
import java.util.Map;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.enchantment.Enchantment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Append-only audit trail of privileged command executions. Writes structured
 * single-line entries to the {@code AUDIT} logger so server operators can route
 * them to a dedicated file via log4j config.
 */
public final class AuditLogger {
    private static final Logger LOGGER = LoggerFactory.getLogger(BetterEnchantCommands.MOD_ID + ".audit");

    private AuditLogger() {
    }

    public static void logEnchant(
        final CommandSourceStack operator,
        final Collection<ServerPlayer> targets,
        final String enchantmentId,
        final int level
    ) {
        if (!BetterEnchantConfig.auditLogEnabled()) {
            return;
        }

        LOGGER.info("[AUDIT] action=enchant operator={} targets={} enchantment={} level={}",
            operatorName(operator), targetNames(targets), enchantmentId, level);
    }

    public static void logUnenchant(
        final CommandSourceStack operator,
        final Collection<ServerPlayer> targets,
        final String enchantmentId
    ) {
        if (!BetterEnchantConfig.auditLogEnabled()) {
            return;
        }

        LOGGER.info("[AUDIT] action=unenchant operator={} targets={} enchantment={}",
            operatorName(operator), targetNames(targets),
            enchantmentId == null ? "*" : enchantmentId);
    }

    public static void logGive(
        final CommandSourceStack operator,
        final Collection<ServerPlayer> targets,
        final String item,
        final int count,
        final Map<Holder<Enchantment>, Integer> enchantments
    ) {
        if (!BetterEnchantConfig.auditLogEnabled()) {
            return;
        }

        final StringBuilder encs = new StringBuilder();
        for (Map.Entry<Holder<Enchantment>, Integer> entry : enchantments.entrySet()) {
            if (encs.length() > 0) {
                encs.append(',');
            }
            encs.append(entry.getKey().unwrapKey().map(key -> key.identifier().toString()).orElse("unknown"));
            encs.append(':').append(entry.getValue());
        }

        LOGGER.info("[AUDIT] action=give operator={} targets={} item={} count={} enchantments={}",
            operatorName(operator), targetNames(targets), item, count,
            encs.length() == 0 ? "none" : encs);
    }

    public static void logConfigChange(final CommandSourceStack operator, final String key, final Object value) {
        LOGGER.info("[AUDIT] action=config operator={} key={} value={}",
            operatorName(operator), key, value);
    }

    public static void logUndo(final CommandSourceStack operator, final String label, final int restored) {
        if (!BetterEnchantConfig.auditLogEnabled()) {
            return;
        }

        LOGGER.info("[AUDIT] action=undo operator={} label=\"{}\" restored={}",
            operatorName(operator), label, restored);
    }

    private static String operatorName(final CommandSourceStack operator) {
        try {
            return operator.getTextName();
        } catch (RuntimeException exception) {
            return "unknown";
        }
    }

    private static String targetNames(final Collection<ServerPlayer> targets) {
        final StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (ServerPlayer player : targets) {
            if (!first) {
                sb.append(',');
            }
            sb.append(player.getScoreboardName());
            first = false;
        }
        sb.append(']');
        return sb.toString();
    }
}
