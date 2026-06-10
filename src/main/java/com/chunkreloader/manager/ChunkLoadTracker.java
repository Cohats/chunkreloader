package com.chunkreloader.manager;

import com.chunkreloader.ChunkReloaderMod;
import com.chunkreloader.config.Config;
import com.chunkreloader.util.ChunkRegenerator;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class ChunkLoadTracker extends SavedData {
    private static final String DATA_NAME = "chunkreloader_data";
    private static final long TICK_INTERVAL = 6000; // Check every ~5 minutes (6000 ticks = 5 min at 20 tps)

    // Map of chunk position -> last load timestamp (millis)
    private final Map<Long, Long> loadTimestamps = new ConcurrentHashMap<>();
    private int tickCounter = 0;

    public ChunkLoadTracker() {
        super();
    }

    public static ChunkLoadTracker get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                new Factory<>(ChunkLoadTracker::new, ChunkLoadTracker::load),
                DATA_NAME
        );
    }

    public static ChunkLoadTracker load(CompoundTag tag, HolderLookup.Provider provider) {
        ChunkLoadTracker tracker = new ChunkLoadTracker();

        // Load from compound with chunk keys (format: chunk_<packedPos> = <timestamp>)
        for (String key : tag.getAllKeys()) {
            if (key.startsWith("chunk_")) {
                try {
                    long chunkPos = Long.parseLong(key.substring(6));
                    long timestamp = tag.getLong(key);
                    tracker.loadTimestamps.put(chunkPos, timestamp);
                } catch (NumberFormatException ignored) {
                }
            }
        }

        return tracker;
    }

    @Override
    public @NotNull CompoundTag save(@NotNull CompoundTag tag, @NotNull HolderLookup.Provider provider) {
        for (Map.Entry<Long, Long> entry : loadTimestamps.entrySet()) {
            tag.putLong("chunk_" + entry.getKey(), entry.getValue());
        }
        return tag;
    }

    public void recordLoad(ChunkPos pos) {
        long packed = ChunkPos.asLong(pos.x, pos.z);
        loadTimestamps.put(packed, System.currentTimeMillis());
        setDirty();
    }

    public void removeRecord(ChunkPos pos) {
        long packed = ChunkPos.asLong(pos.x, pos.z);
        loadTimestamps.remove(packed);
        setDirty();
    }

    public boolean isTracked(ChunkPos pos) {
        return loadTimestamps.containsKey(ChunkPos.asLong(pos.x, pos.z));
    }

    /**
     * Get all tracked chunk positions in this world.
     */
    public List<ChunkPos> getAllTrackedChunks() {
        List<ChunkPos> result = new ArrayList<>();
        for (Long packedPos : loadTimestamps.keySet()) {
            int x = ChunkPos.getX(packedPos);
            int z = ChunkPos.getZ(packedPos);
            result.add(new ChunkPos(x, z));
        }
        return result;
    }

    public void tick(ServerLevel level) {
        if (!Config.getInstance().getAutoReload(level)) {
            return;
        }

        tickCounter++;
        int interval = Config.getInstance().autoReloadInterval.get();
        int tickInterval = (interval > 0) ? interval * 20 : 1; // Convert seconds to ticks

        if (tickCounter < tickInterval) {
            return;
        }
        tickCounter = 0;

        long now = System.currentTimeMillis();
        long staleThreshold = now - (Config.getInstance().getStaleDays(level) * 86400000L); // days to millis

        List<Long> toRemove = new ArrayList<>();

        for (Map.Entry<Long, Long> entry : loadTimestamps.entrySet()) {
            if (entry.getValue() < staleThreshold) {
                long packedPos = entry.getKey();
                int chunkX = ChunkPos.getX(packedPos);
                int chunkZ = ChunkPos.getZ(packedPos);
                ChunkPos pos = new ChunkPos(chunkX, chunkZ);

                // Mark for regeneration (actual regeneration happens on main thread later)
                toRemove.add(packedPos);

                ChunkReloaderMod.LOGGER.info("Chunk at ({},{}) is stale. Queueing for regeneration.", chunkX, chunkZ);
            }
        }

        // Remove from tracking and regenerate
        for (long packedPos : toRemove) {
            int chunkX = ChunkPos.getX(packedPos);
            int chunkZ = ChunkPos.getZ(packedPos);
            loadTimestamps.remove(packedPos);
            setDirty();

            // We don't have a reference to the server level here, so we delegate
            // the actual regeneration to be picked up in the next phase
            StaleChunkQueue.queue(chunkX, chunkZ);
        }
    }

    /**
     * Process queued stale chunks for regeneration.
     * Called from the server thread.
     */
    public static void processStaleChunks(ServerLevel level) {
        if (!Config.getInstance().getAutoReload(level)) return;

        List<ChunkPos> batch = StaleChunkQueue.drainBatch(50); // Process up to 50 at a time
        if (batch.isEmpty()) return;

        long[] counters = ChunkRegenerator.regenerateChunks(level, batch, true);

        if (counters[0] > 0) {
            ChunkReloaderMod.LOGGER.info(
                    "Auto-reload: {} chunks regenerated, {} skipped, {} failed",
                    counters[0], counters[1], counters[2]
            );
        }
    }

    /**
     * Static queue for stale chunks to be regenerated.
     */
    private static class StaleChunkQueue {
        private static final java.util.Queue<ChunkPos> QUEUE = new java.util.concurrent.ConcurrentLinkedDeque<>();

        static void queue(int x, int z) {
            QUEUE.add(new ChunkPos(x, z));
        }

        static List<ChunkPos> drainBatch(int max) {
            List<ChunkPos> batch = new ArrayList<>();
            ChunkPos pos;
            while (batch.size() < max && (pos = QUEUE.poll()) != null) {
                batch.add(pos);
            }
            return batch;
        }
    }
}
