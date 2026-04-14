package com.agaminggod.betterenchantcommands.util;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * Helpers for building user-facing messages. Uses {@code translatableWithFallback}
 * so clients that ship this mod's lang file get localized text while others still
 * see a readable English string.
 */
public final class Messages {
    public static final String PREFIX = "better-enchant-commands.";

    private Messages() {
    }

    public static MutableComponent of(final String key, final String fallback, final Object... args) {
        return Component.translatableWithFallback(PREFIX + key, fallback, args);
    }

    public static MutableComponent error(final String key, final String fallback, final Object... args) {
        return of(key, fallback, args).withStyle(ChatFormatting.RED);
    }

    public static MutableComponent success(final String key, final String fallback, final Object... args) {
        return of(key, fallback, args);
    }

    public static MutableComponent info(final String key, final String fallback, final Object... args) {
        return of(key, fallback, args).withStyle(ChatFormatting.GRAY);
    }

    public static MutableComponent accent(final String key, final String fallback, final Object... args) {
        return of(key, fallback, args).withStyle(ChatFormatting.GOLD);
    }
}
