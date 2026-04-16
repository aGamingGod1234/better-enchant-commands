package com.agaminggod.betterenchantcommands.confirmation;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.IntSupplier;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;

/**
 * Short-lived holder for "are you sure?" confirmations. When a command would
 * affect a target count above the configured threshold, we stash a
 * {@link PendingOperation} here and require the same operator to run
 * {@code /enchants confirm <token>} within the TTL.
 *
 * <p>Only one pending operation is retained per operator: staging a second
 * operation replaces the first, which is the intended behaviour for an admin
 * who is cycling through candidate commands.
 */
public final class ConfirmationManager {
    public static final long DEFAULT_TTL_NANOS = 30L * 1_000_000_000L;
    private static final UUID CONSOLE_UUID = new UUID(0L, 0L);
    private static final ConcurrentMap<UUID, PendingOperation> PENDING = new ConcurrentHashMap<>();

    private ConfirmationManager() {
    }

    public static PendingOperation store(final CommandSourceStack source, final String description, final IntSupplier action) {
        clearExpired();
        final UUID owner = ownerOf(source);
        final String token = UUID.randomUUID().toString();
        final PendingOperation op = new PendingOperation(token, description, action, System.nanoTime() + DEFAULT_TTL_NANOS);
        PENDING.put(owner, op);
        return op;
    }

    /**
     * Variant of {@link #store} that additionally returns any prior pending operation
     * that was replaced. Callers can surface this to the operator so they are not
     * silently left with a stale/orphaned confirmation token.
     */
    public static StoreResult storeWithReplacement(
        final CommandSourceStack source, final String description, final IntSupplier action) {
        clearExpired();
        final UUID owner = ownerOf(source);
        final String token = UUID.randomUUID().toString();
        final PendingOperation op = new PendingOperation(token, description, action, System.nanoTime() + DEFAULT_TTL_NANOS);
        final PendingOperation previous = PENDING.put(owner, op);
        return new StoreResult(op, previous != null && System.nanoTime() <= previous.expiresAtNanos ? previous : null);
    }

    public static PendingOperation consume(final CommandSourceStack source, final String token) {
        clearExpired();
        final UUID owner = ownerOf(source);
        final PendingOperation pending = PENDING.get(owner);
        if (pending == null) {
            return null;
        }

        if (token == null || !token.equals(pending.token)) {
            return null;
        }

        if (System.nanoTime() > pending.expiresAtNanos) {
            PENDING.remove(owner, pending);
            return null;
        }

        // Only remove on successful match; a lookup miss should NOT discard
        // another user's valid pending op.
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

    public record PendingOperation(String token, String description, IntSupplier action, long expiresAtNanos) {
    }

    public record StoreResult(PendingOperation stored, PendingOperation replaced) {
    }
}
