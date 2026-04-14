package com.agaminggod.betterenchantcommands.confirmation;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;

/**
 * Short-lived holder for "are you sure?" confirmations. When a command would
 * affect a target count above the configured threshold, we stash a
 * {@link PendingOperation} here and require the same operator to run
 * {@code /enchants confirm <token>} within the TTL.
 */
public final class ConfirmationManager {
    public static final long DEFAULT_TTL_NANOS = 30L * 1_000_000_000L;
    private static final UUID CONSOLE_UUID = new UUID(0L, 0L);
    private static final ConcurrentMap<UUID, PendingOperation> PENDING = new ConcurrentHashMap<>();

    private ConfirmationManager() {
    }

    public static PendingOperation store(final CommandSourceStack source, final String description, final Runnable action) {
        final UUID owner = ownerOf(source);
        final String token = Long.toHexString(System.nanoTime()) + "-" + Integer.toHexString(System.identityHashCode(action));
        final PendingOperation op = new PendingOperation(token, description, action, System.nanoTime() + DEFAULT_TTL_NANOS);
        PENDING.put(owner, op);
        return op;
    }

    public static PendingOperation consume(final CommandSourceStack source, final String token) {
        final UUID owner = ownerOf(source);
        final PendingOperation pending = PENDING.get(owner);
        if (pending == null) {
            return null;
        }

        if (System.nanoTime() > pending.expiresAtNanos) {
            PENDING.remove(owner, pending);
            return null;
        }

        if (token != null && !token.equals(pending.token)) {
            return null;
        }

        PENDING.remove(owner, pending);
        return pending;
    }

    public static void clearExpired() {
        final long now = System.nanoTime();
        PENDING.entrySet().removeIf(entry -> now > entry.getValue().expiresAtNanos);
    }

    private static UUID ownerOf(final CommandSourceStack source) {
        if (source.getEntity() instanceof ServerPlayer player) {
            return player.getUUID();
        }
        return CONSOLE_UUID;
    }

    public record PendingOperation(String token, String description, Runnable action, long expiresAtNanos) {
    }
}
