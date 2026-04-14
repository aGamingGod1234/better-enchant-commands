package com.agaminggod.betterenchantcommands.permission;

import com.agaminggod.betterenchantcommands.BetterEnchantCommands;
import com.agaminggod.betterenchantcommands.compat.MinecraftCompatibility;
import java.lang.reflect.Method;
import net.minecraft.commands.CommandSourceStack;

/**
 * Permission check with soft LuckPerms / fabric-permissions-api integration.
 * If the Permissions class is present on the classpath, defer to it so admins can
 * configure granular nodes. Otherwise, fall back to vanilla permission levels.
 */
public final class PermissionHelper {
    public static final String NODE_ENCHANT = "betterenchantcommands.command.enchant";
    public static final String NODE_GIVE = "betterenchantcommands.command.give";
    public static final String NODE_UNENCHANT = "betterenchantcommands.command.unenchant";
    public static final String NODE_INFO = "betterenchantcommands.command.enchantinfo";
    public static final String NODE_LIST = "betterenchantcommands.command.enchantlist";
    public static final String NODE_PRESET = "betterenchantcommands.command.enchantpreset";
    public static final String NODE_REPAIR = "betterenchantcommands.command.repair";
    public static final String NODE_ENCHANTS = "betterenchantcommands.command.enchants";
    public static final String NODE_ENCHANTS_ADMIN = "betterenchantcommands.command.enchants.admin";

    private static final Method CHECK_METHOD = resolveCheckMethod();
    private static final boolean AVAILABLE = CHECK_METHOD != null;

    private PermissionHelper() {
    }

    public static boolean isLuckPermsAvailable() {
        return AVAILABLE;
    }

    public static boolean check(final CommandSourceStack source, final String node, final int fallbackLevel) {
        if (AVAILABLE) {
            try {
                final Object result = CHECK_METHOD.invoke(null, source, node, fallbackLevel);
                if (result instanceof Boolean bool) {
                    return bool;
                }
            } catch (ReflectiveOperationException exception) {
                BetterEnchantCommands.LOGGER.debug("Falling back from LuckPerms check for node {}", node, exception);
            }
        }

        return MinecraftCompatibility.hasPermissionLevel(source, fallbackLevel);
    }

    private static Method resolveCheckMethod() {
        try {
            final Class<?> permissions = Class.forName("me.lucko.fabric.api.permissions.v0.Permissions");
            for (Method candidate : permissions.getMethods()) {
                if (!candidate.getName().equals("check")
                    || candidate.getParameterCount() != 3
                    || candidate.getReturnType() != boolean.class) {
                    continue;
                }

                final Class<?>[] parameters = candidate.getParameterTypes();
                if (parameters[1] == String.class && parameters[2] == int.class) {
                    return candidate;
                }
            }
        } catch (ClassNotFoundException ignored) {
            // LuckPerms / fabric-permissions-api absent; fall back silently.
        } catch (RuntimeException exception) {
            BetterEnchantCommands.LOGGER.debug("Failed to resolve LuckPerms permission method", exception);
        }
        return null;
    }
}
