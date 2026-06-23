package com.agaminggod.betterenchantcommands.util;

import net.minecraft.core.Holder;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;

/**
 * Checks whether an enchantment may legally be applied to a given item stack.
 * Uses the vanilla {@link Enchantment#canEnchant} / supported items predicate,
 * with a special case for books which act as universal enchantment containers.
 */
public final class EnchantmentCompat {
    private EnchantmentCompat() {
    }

    public static boolean isCompatible(final ItemStack stack, final Holder<Enchantment> enchantment) {
        if (stack == null || stack.isEmpty() || enchantment == null) {
            return false;
        }

        // Books accept any enchantment: plain books become enchanted books,
        // and existing enchanted books are legitimate containers for any id.
        if (stack.is(Items.BOOK) || stack.is(Items.ENCHANTED_BOOK)) {
            return true;
        }

        try {
            return enchantment.value().canEnchant(stack);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    public static String shortId(final Holder<Enchantment> enchantment) {
        if (enchantment == null) {
            return "unknown";
        }

        return enchantment.unwrapKey()
            .map(key -> key.identifier().toString())
            .orElse("unknown");
    }

    public static int levelOf(final ItemEnchantments enchantments, final Holder<Enchantment> targetEnchantment) {
        final int directLevel = enchantments.getLevel(targetEnchantment);
        if (directLevel > 0) {
            return directLevel;
        }

        for (Holder<Enchantment> holder : enchantments.keySet()) {
            if (isSameEnchantment(holder, targetEnchantment)) {
                return enchantments.getLevel(holder);
            }
        }

        return 0;
    }

    public static boolean isSameEnchantment(
        final Holder<Enchantment> first,
        final Holder<Enchantment> second
    ) {
        if (first.equals(second)) {
            return true;
        }

        if (first.unwrapKey().isPresent() && first.unwrapKey().equals(second.unwrapKey())) {
            return true;
        }

        return first.value().equals(second.value());
    }
}
