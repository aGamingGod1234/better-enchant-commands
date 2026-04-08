package com.agaminggod.betterenchantcommands.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import net.minecraft.resources.Identifier;

public final class EnchantmentParser {
    public static final int MIN_LEVEL = 1;
    public static final int MAX_LEVEL = 255;
    public static final String ENCHANTMENTS_PREFIX = "enchantments:";
    private static final String DEFAULT_NAMESPACE = "minecraft";
    private static final String ENCHANTMENT_SEPARATOR = ",";
    private static final String PART_SEPARATOR = ":";
    private static final int MAX_INPUT_LENGTH = 2048;
    private static final int MAX_ENCHANTMENT_ENTRIES = 50;

    private EnchantmentParser() {
    }

    public static ParseResult parse(final String rawInput) {
        if (rawInput == null || rawInput.isBlank()) {
            return ParseResult.failure("Enchantments string cannot be empty. Expected enchantments:<id>:<level>,...");
        }

        if (rawInput.length() > MAX_INPUT_LENGTH) {
            return ParseResult.failure("Enchantments string is too long (max " + MAX_INPUT_LENGTH + " characters).");
        }

        final String loweredInput = rawInput.toLowerCase(Locale.ROOT);
        if (!loweredInput.startsWith(ENCHANTMENTS_PREFIX)) {
            return ParseResult.failure("Enchantments must start with \"enchantments:\"");
        }

        final String payload = rawInput.substring(ENCHANTMENTS_PREFIX.length()).trim();
        if (payload.isEmpty()) {
            return ParseResult.failure("No enchantments were provided. Expected enchantments:<id>:<level>,...");
        }

        final String[] rawEntries = payload.split(ENCHANTMENT_SEPARATOR);
        if (rawEntries.length > MAX_ENCHANTMENT_ENTRIES) {
            return ParseResult.failure("Too many enchantments (max " + MAX_ENCHANTMENT_ENTRIES + ").");
        }

        final List<ParsedEnchantment> parsedEnchantments = new ArrayList<>(rawEntries.length);

        for (final String rawEntry : rawEntries) {
            final String token = rawEntry.trim();
            if (token.isEmpty()) {
                return ParseResult.failure("Found an empty enchantment entry. Expected <id>:<level> entries separated by commas.");
            }

            final String[] parts = token.split(PART_SEPARATOR);
            final ParsedToken parsedToken = parseToken(parts, token);
            if (!parsedToken.success()) {
                return ParseResult.failure(parsedToken.errorMessage());
            }

            final int level;
            try {
                level = Integer.parseInt(parsedToken.levelToken());
            } catch (NumberFormatException exception) {
                return ParseResult.failure("Invalid level \"" + parsedToken.levelToken() + "\" for enchantment \"" + token
                    + "\". Level must be an integer between " + MIN_LEVEL + " and " + MAX_LEVEL + ".");
            }

            if (level < MIN_LEVEL || level > MAX_LEVEL) {
                return ParseResult.failure("Invalid level " + level + " for enchantment \"" + token + "\". Level must be between "
                    + MIN_LEVEL + " and " + MAX_LEVEL + ".");
            }

            final Identifier resourceLocation;
            try {
                resourceLocation = Identifier.parse(parsedToken.namespace() + PART_SEPARATOR + parsedToken.path());
            } catch (Exception exception) {
                return ParseResult.failure("Invalid enchantment id \"" + parsedToken.namespace() + PART_SEPARATOR + parsedToken.path()
                    + "\". Expected valid namespaced id like minecraft:sharpness.");
            }

            parsedEnchantments.add(new ParsedEnchantment(resourceLocation, level));
        }

        return ParseResult.success(parsedEnchantments);
    }

    private static ParsedToken parseToken(final String[] parts, final String originalToken) {
        if (parts.length == 2) {
            final String path = parts[0].trim().toLowerCase(Locale.ROOT);
            final String level = parts[1].trim();

            if (path.isEmpty()) {
                return ParsedToken.failure("Missing enchantment id in \"" + originalToken + "\". Expected <id>:<level>.");
            }

            return ParsedToken.success(DEFAULT_NAMESPACE, path, level);
        }

        if (parts.length == 3) {
            final String namespace = parts[0].trim().toLowerCase(Locale.ROOT);
            final String path = parts[1].trim().toLowerCase(Locale.ROOT);
            final String level = parts[2].trim();

            if (namespace.isEmpty() || path.isEmpty()) {
                return ParsedToken.failure("Invalid enchantment id in \"" + originalToken
                    + "\". Expected <namespace>:<id>:<level>.");
            }

            return ParsedToken.success(namespace, path, level);
        }

        return ParsedToken.failure("Invalid enchantment token \"" + originalToken
            + "\". Expected <id>:<level> or <namespace>:<id>:<level>.");
    }

    public record ParsedEnchantment(Identifier id, int level) {
    }

    public static final class ParseResult {
        private final boolean successful;
        private final List<ParsedEnchantment> enchantments;
        private final String errorMessage;

        private ParseResult(final boolean successful, final List<ParsedEnchantment> enchantments, final String errorMessage) {
            this.successful = successful;
            this.enchantments = enchantments;
            this.errorMessage = errorMessage;
        }

        public static ParseResult success(final List<ParsedEnchantment> enchantments) {
            return new ParseResult(true, Collections.unmodifiableList(enchantments), null);
        }

        public static ParseResult failure(final String message) {
            return new ParseResult(false, List.of(), message);
        }

        public boolean successful() {
            return successful;
        }

        public List<ParsedEnchantment> enchantments() {
            return enchantments;
        }

        public String errorMessage() {
            return errorMessage;
        }
    }

    private record ParsedToken(boolean success, String namespace, String path, String levelToken, String errorMessage) {
        static ParsedToken success(final String namespace, final String path, final String levelToken) {
            return new ParsedToken(true, namespace, path, levelToken, null);
        }

        static ParsedToken failure(final String errorMessage) {
            return new ParsedToken(false, null, null, null, errorMessage);
        }
    }
}

