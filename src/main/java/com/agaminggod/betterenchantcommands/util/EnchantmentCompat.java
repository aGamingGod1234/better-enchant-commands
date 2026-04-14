package com.agaminggod.betterenchantcommands.util;

import net.minecraft.core.Holder;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;

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
}
