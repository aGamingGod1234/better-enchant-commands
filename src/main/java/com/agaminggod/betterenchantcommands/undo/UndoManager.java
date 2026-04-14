package com.agaminggod.betterenchantcommands.undo;

import com.agaminggod.betterenchantcommands.config.BetterEnchantConfig;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * Per-operator stack of reversible command operations. Snapshots the original
 * main-hand stack of each affected player so the last mutation can be reverted.
 * Bounded by {@link BetterEnchantConfig#undoHistorySize()} per operator.
 */
public final class UndoManager {
    private static final ConcurrentMap<UUID, Deque<Snapshot>> HISTORY = new ConcurrentHashMap<>();
    private static final UUID CONSOLE_UUID = new UUID(0L, 0L);

    private UndoManager() {
    }

    public static Snapshot beginMainHandSnapshot(final String label) {
        return new Snapshot(label, new ArrayList<>());
    }

    public static void commit(final CommandSourceStack source, final Snapshot snapshot) {
        if (snapshot == null || snapshot.entries.isEmpty()) {
            return;
        }

        final int historyLimit = BetterEnchantConfig.undoHistorySize();
        if (historyLimit <= 0) {
            return;
        }

        final UUID ownerId = ownerOf(source);
        final Deque<Snapshot> queue = HISTORY.computeIfAbsent(ownerId, id -> new ArrayDeque<>());
        synchronized (queue) {
            queue.push(snapshot);
            while (queue.size() > historyLimit) {
                queue.removeLast();
            }
        }
    }

    public static Snapshot popLast(final CommandSourceStack source) {
        final UUID ownerId = ownerOf(source);
        final Deque<Snapshot> queue = HISTORY.get(ownerId);
        if (queue == null) {
            return null;
        }

        synchronized (queue) {
            return queue.isEmpty() ? null : queue.pop();
        }
    }

    public static int depth(final CommandSourceStack source) {
        final Deque<Snapshot> queue = HISTORY.get(ownerOf(source));
        if (queue == null) {
            return 0;
        }
        synchronized (queue) {
            return queue.size();
        }
    }

    public static void clear() {
        HISTORY.clear();
    }

    /**
     * Drops the undo stack for one operator. Call on player disconnect so a
     * long-running server doesn't accumulate stacks for players who have left.
     */
    public static void forgetOwner(final UUID ownerId) {
        if (ownerId == null) {
            return;
        }
        HISTORY.remove(ownerId);
    }

    private static UUID ownerOf(final CommandSourceStack source) {
        if (source.getEntity() instanceof ServerPlayer player) {
            return player.getUUID();
        }
        return CONSOLE_UUID;
    }

    /**
     * Snapshot of one command's mutations. Currently restricted to main-hand
     * replacement so undo is well-defined even when the player moved items.
     */
    public static final class Snapshot {
        private final String label;
        private final List<Entry> entries;

        Snapshot(final String label, final List<Entry> entries) {
            this.label = label;
            this.entries = entries;
        }

        public String label() {
            return label;
        }

        public void record(final ServerPlayer player, final ItemStack original) {
            entries.add(new Entry(player.getUUID(), original.copy()));
        }

        public int restore(final net.minecraft.server.MinecraftServer server) {
            int restored = 0;
            for (Entry entry : entries) {
                final ServerPlayer player = server.getPlayerList().getPlayer(entry.playerId);
                if (player == null) {
                    continue;
                }

                player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, entry.originalStack.copy());
                restored++;
            }
            return restored;
        }

        public int size() {
            return entries.size();
        }
    }

    private record Entry(UUID playerId, ItemStack originalStack) {
    }
}
