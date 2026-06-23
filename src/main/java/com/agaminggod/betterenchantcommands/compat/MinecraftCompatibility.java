package com.agaminggod.betterenchantcommands.compat;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.resources.Identifier;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.item.ItemStack;

public final class MinecraftCompatibility {
    private MinecraftCompatibility() {
    }

    public static boolean hasPermissionLevel(final CommandSourceStack source, final int requiredLevel) {
        if (requiredLevel <= 0) {
            return true;
        }

        return source.permissions().hasPermission(permissionForLevel(requiredLevel));
    }

    public static CommandSourceStack withPermissionLevel(final CommandSourceStack source, final int requiredLevel) {
        return source.withMaximumPermission(LevelBasedPermissionSet.forLevel(PermissionLevel.byId(requiredLevel)));
    }

    public static <T> T getComponentOrDefault(
        final ItemStack stack,
        final DataComponentType<T> componentType,
        final T fallbackValue
    ) {
        return stack.getOrDefault(componentType, fallbackValue);
    }

    public static <T> Holder<T> findRegistryHolderById(final Registry<T> registry, final Identifier id) {
        return registry.get(id).orElse(null);
    }

    private static Permission permissionForLevel(final int requiredLevel) {
        return switch (PermissionLevel.byId(requiredLevel)) {
            case MODERATORS -> Permissions.COMMANDS_MODERATOR;
            case GAMEMASTERS -> Permissions.COMMANDS_GAMEMASTER;
            case ADMINS -> Permissions.COMMANDS_ADMIN;
            case OWNERS -> Permissions.COMMANDS_OWNER;
            case ALL -> Permissions.CHAT_SEND_COMMANDS;
        };
    }
}
