package com.agaminggod.betterenchantcommands.config;

import com.agaminggod.betterenchantcommands.BetterEnchantCommands;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Persistent mod configuration. Stored as JSON under the Fabric config directory.
 * Thread-safe: writes are serialized behind a read/write lock, and persisted atomically.
 */
public final class BetterEnchantConfig {
    private static final String CONFIG_FILE_NAME = "better-enchant-commands.json";
    private static final int DEFAULT_UNDO_HISTORY = 20;
    private static final int DEFAULT_CONFIRMATION_THRESHOLD = 10;
    private static final int MIN_UNDO_HISTORY = 0;
    private static final int MAX_UNDO_HISTORY = 200;
    private static final int MIN_CONFIRMATION_THRESHOLD = 1;
    private static final int MAX_CONFIRMATION_THRESHOLD = 1000;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final ReentrantReadWriteLock LOCK = new ReentrantReadWriteLock();

    private static volatile boolean allowAllEnchantments = false;
    private static volatile boolean auditLogEnabled = false;
    private static volatile int undoHistorySize = DEFAULT_UNDO_HISTORY;
    private static volatile int confirmationThreshold = DEFAULT_CONFIRMATION_THRESHOLD;
    private static volatile Map<String, List<PresetEntry>> presets = new LinkedHashMap<>();

    private static volatile Path configPath;

    private BetterEnchantConfig() {
    }

    public static void load() {
        LOCK.writeLock().lock();
        try {
            configPath = FabricLoader.getInstance().getConfigDir().resolve(CONFIG_FILE_NAME);
            if (!Files.exists(configPath)) {
                BetterEnchantCommands.LOGGER.info("No config file found, writing defaults to {}", configPath);
                writeLocked();
                return;
            }

            try (Reader reader = Files.newBufferedReader(configPath)) {
                final JsonElement root = JsonParser.parseReader(reader);
                if (!root.isJsonObject()) {
                    BetterEnchantCommands.LOGGER.warn("Config file root is not an object, resetting to defaults");
                    writeLocked();
                    return;
                }

                final JsonObject object = root.getAsJsonObject();
                allowAllEnchantments = optBoolean(object, "allow_all_enchantments", false);
                auditLogEnabled = optBoolean(object, "audit_log_enabled", false);
                undoHistorySize = clamp(optInt(object, "undo_history_size", DEFAULT_UNDO_HISTORY),
                    MIN_UNDO_HISTORY, MAX_UNDO_HISTORY);
                confirmationThreshold = clamp(optInt(object, "confirmation_threshold", DEFAULT_CONFIRMATION_THRESHOLD),
                    MIN_CONFIRMATION_THRESHOLD, MAX_CONFIRMATION_THRESHOLD);
                presets = readPresets(object);
                BetterEnchantCommands.LOGGER.info(
                    "Loaded config: allow_all_enchantments={}, audit_log_enabled={}, undo_history_size={}, confirmation_threshold={}, presets={}",
                    allowAllEnchantments, auditLogEnabled, undoHistorySize, confirmationThreshold, presets.size());
            } catch (IOException | RuntimeException exception) {
                BetterEnchantCommands.LOGGER.error("Failed to load config, keeping previous values", exception);
            }
        } finally {
            LOCK.writeLock().unlock();
        }
    }

    public static boolean allowAllEnchantments() {
        return allowAllEnchantments;
    }

    public static boolean auditLogEnabled() {
        return auditLogEnabled;
    }

    public static int undoHistorySize() {
        return undoHistorySize;
    }

    public static int confirmationThreshold() {
        return confirmationThreshold;
    }

    public static Map<String, List<PresetEntry>> presetsSnapshot() {
        LOCK.readLock().lock();
        try {
            final Map<String, List<PresetEntry>> copy = new LinkedHashMap<>(presets.size());
            for (Map.Entry<String, List<PresetEntry>> entry : presets.entrySet()) {
                copy.put(entry.getKey(), List.copyOf(entry.getValue()));
            }
            return Collections.unmodifiableMap(copy);
        } finally {
            LOCK.readLock().unlock();
        }
    }

    public static List<PresetEntry> preset(final String name) {
        LOCK.readLock().lock();
        try {
            final List<PresetEntry> entries = presets.get(name);
            return entries == null ? null : List.copyOf(entries);
        } finally {
            LOCK.readLock().unlock();
        }
    }

    public static void setAllowAllEnchantments(final boolean value) {
        LOCK.writeLock().lock();
        try {
            allowAllEnchantments = value;
            writeLocked();
        } finally {
            LOCK.writeLock().unlock();
        }
    }

    public static boolean savePreset(final String name, final List<PresetEntry> entries) {
        if (name == null || name.isBlank() || entries == null || entries.isEmpty()) {
            return false;
        }

        LOCK.writeLock().lock();
        try {
            final Map<String, List<PresetEntry>> updated = new LinkedHashMap<>(presets);
            updated.put(name, List.copyOf(entries));
            presets = updated;
            writeLocked();
            return true;
        } finally {
            LOCK.writeLock().unlock();
        }
    }

    public static boolean deletePreset(final String name) {
        LOCK.writeLock().lock();
        try {
            if (!presets.containsKey(name)) {
                return false;
            }

            final Map<String, List<PresetEntry>> updated = new LinkedHashMap<>(presets);
            updated.remove(name);
            presets = updated;
            writeLocked();
            return true;
        } finally {
            LOCK.writeLock().unlock();
        }
    }

    private static void writeLocked() {
        final Path target = configPath;
        if (target == null) {
            return;
        }

        final JsonObject root = new JsonObject();
        root.addProperty("allow_all_enchantments", allowAllEnchantments);
        root.addProperty("audit_log_enabled", auditLogEnabled);
        root.addProperty("undo_history_size", undoHistorySize);
        root.addProperty("confirmation_threshold", confirmationThreshold);

        final JsonObject presetsObject = new JsonObject();
        for (Map.Entry<String, List<PresetEntry>> entry : presets.entrySet()) {
            final JsonArray array = new JsonArray();
            for (PresetEntry presetEntry : entry.getValue()) {
                final JsonObject presetJson = new JsonObject();
                presetJson.addProperty("id", presetEntry.id());
                presetJson.addProperty("level", presetEntry.level());
                array.add(presetJson);
            }
            presetsObject.add(entry.getKey(), array);
        }
        root.add("presets", presetsObject);

        try {
            Files.createDirectories(target.getParent());
            final Path tempPath = target.resolveSibling(target.getFileName() + ".tmp");
            try (Writer writer = Files.newBufferedWriter(tempPath)) {
                GSON.toJson(root, writer);
            }
            Files.move(tempPath, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException exception) {
            BetterEnchantCommands.LOGGER.error("Failed to persist config to {}", target, exception);
        } catch (RuntimeException exception) {
            BetterEnchantCommands.LOGGER.error("Unexpected error writing config", exception);
        }
    }

    private static Map<String, List<PresetEntry>> readPresets(final JsonObject root) {
        final Map<String, List<PresetEntry>> loaded = new LinkedHashMap<>();
        if (!root.has("presets") || !root.get("presets").isJsonObject()) {
            return loaded;
        }

        final JsonObject presetsObject = root.getAsJsonObject("presets");
        for (Map.Entry<String, JsonElement> entry : presetsObject.entrySet()) {
            if (!entry.getValue().isJsonArray()) {
                continue;
            }

            final JsonArray array = entry.getValue().getAsJsonArray();
            final java.util.List<PresetEntry> entries = new java.util.ArrayList<>(array.size());
            for (JsonElement element : array) {
                if (!element.isJsonObject()) {
                    continue;
                }

                final JsonObject presetJson = element.getAsJsonObject();
                final String id = optString(presetJson, "id", null);
                final int level = optInt(presetJson, "level", -1);
                if (id == null || id.isBlank() || level < 1) {
                    continue;
                }
                entries.add(new PresetEntry(id, level));
            }

            if (!entries.isEmpty()) {
                loaded.put(entry.getKey(), entries);
            }
        }
        return loaded;
    }

    private static boolean optBoolean(final JsonObject object, final String key, final boolean fallback) {
        return object.has(key) && object.get(key).isJsonPrimitive() && object.get(key).getAsJsonPrimitive().isBoolean()
            ? object.get(key).getAsBoolean()
            : fallback;
    }

    private static int optInt(final JsonObject object, final String key, final int fallback) {
        try {
            return object.has(key) && object.get(key).isJsonPrimitive() && object.get(key).getAsJsonPrimitive().isNumber()
                ? object.get(key).getAsInt()
                : fallback;
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private static String optString(final JsonObject object, final String key, final String fallback) {
        return object.has(key) && object.get(key).isJsonPrimitive() && object.get(key).getAsJsonPrimitive().isString()
            ? object.get(key).getAsString()
            : fallback;
    }

    private static int clamp(final int value, final int min, final int max) {
        return Math.max(min, Math.min(max, value));
    }

    public record PresetEntry(String id, int level) {
    }
}
