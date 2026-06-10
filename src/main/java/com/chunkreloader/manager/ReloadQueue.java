package com.chunkreloader.manager;

import com.chunkreloader.ChunkReloaderMod;
import com.chunkreloader.util.ChunkRegenerator;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedDeque;

public class ReloadQueue {
    private static final Queue<ChunkPos> QUEUE = new ConcurrentLinkedDeque<>();
    private static int totalQueued = 0;
    private static int processed = 0;
    private static long regenCount = 0;
    private static long skipCount = 0;
    private static long failCount = 0;
    private static boolean active = false;
    private static boolean forceMode = false;
    private static ServerLevel targetLevel = null;

    /**
     * Start a new batch reload operation.
     * Returns false if a batch is already in progress.
     */
    public static synchronized boolean startBatch(ServerLevel level, List<ChunkPos> chunks, boolean force) {
        if (active) return false;

        QUEUE.clear();
        QUEUE.addAll(chunks);
        totalQueued = chunks.size();
        processed = 0;
        regenCount = 0;
        skipCount = 0;
        failCount = 0;
        active = true;
        forceMode = force;
        targetLevel = level;
        return true;
    }

    /**
     * Called from server tick to process the next batch.
     */
    public static void tick() {
        if (!active || targetLevel == null) return;

        int batchSize = Math.min(50, QUEUE.size());
        if (batchSize == 0) {
            finish();
            return;
        }

        List<ChunkPos> batch = new ArrayList<>();
        for (int i = 0; i < batchSize; i++) {
            ChunkPos pos = QUEUE.poll();
            if (pos != null) batch.add(pos);
        }

        if (batch.isEmpty()) {
            finish();
            return;
        }

        long[] counters = ChunkRegenerator.regenerateChunks(targetLevel, batch, forceMode);
        regenCount += counters[0];
        skipCount += counters[1];
        failCount += counters[2];
        processed += batch.size();

        if (processed % 500 == 0 || processed == totalQueued || QUEUE.isEmpty()) {
            ChunkReloaderMod.LOGGER.info(
                    "Reload progress: {}/{} chunks processed ({} regen, {} skipped, {} failed)",
                    processed, totalQueued, regenCount, skipCount, failCount
            );
        }

        if (QUEUE.isEmpty()) {
            finish();
        }
    }

    private static void finish() {
        active = false;
        ChunkReloaderMod.LOGGER.info(
                "Reload complete: {} regenerated, {} skipped, {} failed (total: {})",
                regenCount, skipCount, failCount, totalQueued
        );
        targetLevel = null;
    }

    public static boolean isActive() { return active; }
    public static int getProgress() { return totalQueued > 0 ? processed * 100 / totalQueued : 0; }
    public static int getTotalQueued() { return totalQueued; }
    public static int getProcessed() { return processed; }
    public static long getRegenCount() { return regenCount; }
    public static long getSkipCount() { return skipCount; }
    public static long getFailCount() { return failCount; }
}
