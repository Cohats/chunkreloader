package com.chunkreloader.manager;

import com.chunkreloader.ChunkReloaderMod;
import com.chunkreloader.util.ChunkRegenerator;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
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
    private static long startTime = 0;
    private static int lastReportedPct = -1;

    /**
     * Start a new batch reload operation.
     * Returns false if a batch is already in progress.
     */
    public static synchronized boolean startBatch(ServerLevel level, List<ChunkPos> chunks, boolean force) {
        return startBatch(level, chunks, force, null);
    }

    /**
     * Start a batch reload with a custom start message to OP players.
     * If label is null, a default message is generated.
     */
    public static synchronized boolean startBatch(ServerLevel level, List<ChunkPos> chunks, boolean force, String label) {
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
        startTime = System.currentTimeMillis();
        lastReportedPct = -1;

        // Send start message to all OP players
        if (level.getServer() != null) {
            String msgText = (label != null) ? label
                    : "§6[ChunkReloader] §eStarting reload of §6" + totalQueued + "§e chunks"
                    + (force ? " §c(force mode)" : "") + "...";
            Component msg = Component.literal(msgText);
            for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
                if (player.hasPermissions(2)) {
                    player.sendSystemMessage(msg);
                }
            }
        }

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

        // Log progress every 500 chunks
        if (processed % 500 == 0 || processed == totalQueued || QUEUE.isEmpty()) {
            ChunkReloaderMod.LOGGER.info(
                    "Reload progress: {}/{} chunks processed ({} regen, {} skipped, {} failed)",
                    processed, totalQueued, regenCount, skipCount, failCount
            );
        }

        // Send chat progress to OP players at 25%, 50%, 75%, 100%
        int pct = totalQueued > 0 ? processed * 100 / totalQueued : 100;
        int milestone = (pct / 25) * 25; // Round down to nearest 25
        if (milestone > lastReportedPct && milestone > 0 && milestone <= 100) {
            lastReportedPct = milestone;
            sendProgressMessage();
        }

        if (QUEUE.isEmpty()) {
            finish();
        }
    }

    private static void sendProgressMessage() {
        if (targetLevel == null || targetLevel.getServer() == null) return;

        int pct = getProgress();
        String bar = buildProgressBar(pct);
        Component msg = Component.literal(
                "§6[ChunkReloader] §e" + bar + " §6" + pct + "%"
                + " §7(" + processed + "/" + totalQueued
                + " §a↑" + regenCount + " §7↓" + skipCount + " §c✗" + failCount + "§7)"
        );

        for (ServerPlayer player : targetLevel.getServer().getPlayerList().getPlayers()) {
            if (player.hasPermissions(2)) {
                player.sendSystemMessage(msg);
            }
        }
    }

    private static String buildProgressBar(int pct) {
        int filled = pct / 10;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            if (i < filled) sb.append("■");
            else sb.append("□");
        }
        return sb.toString();
    }

    private static void finish() {
        active = false;
        long elapsed = (System.currentTimeMillis() - startTime) / 1000;

        // Flush entity data to persist removed entities
        if (targetLevel != null) {
            ChunkRegenerator.flushEntityData(targetLevel);
        }

        ChunkReloaderMod.LOGGER.info(
                "Reload complete: {} regenerated, {} skipped, {} failed (total: {}, {}s)",
                regenCount, skipCount, failCount, totalQueued, elapsed
        );

        // Send completion message to all OP players
        if (targetLevel != null && targetLevel.getServer() != null) {
            Component msg = Component.literal(
                    "§a[ChunkReloader] §2Reload complete! §a" + regenCount + "§2 regenerated, §7" + skipCount
                    + "§2 skipped, §c" + failCount + "§2 failed"
                    + " §7(" + totalQueued + " chunks, " + elapsed + "s)"
            );
            for (ServerPlayer player : targetLevel.getServer().getPlayerList().getPlayers()) {
                if (player.hasPermissions(2)) {
                    player.sendSystemMessage(msg);
                }
            }
        }

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
