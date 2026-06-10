package com.chunkreloader.util;

import com.chunkreloader.ChunkReloaderMod;
import com.chunkreloader.manager.ChunkLoadTracker;
import com.chunkreloader.manager.ProtectedChunkManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.slf4j.Logger;

import java.util.List;

public class ChunkRegenerator {
    private static final Logger LOGGER = ChunkReloaderMod.LOGGER;

    public static long[] regenerateChunks(ServerLevel level, ChunkPos pos1, ChunkPos pos2, boolean force) {
        int minX = Math.min(pos1.x, pos2.x);
        int maxX = Math.max(pos1.x, pos2.x);
        int minZ = Math.min(pos1.z, pos2.z);
        int maxZ = Math.max(pos1.z, pos2.z);
        long regen = 0, skipped = 0, failed = 0;
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                ChunkPos pos = new ChunkPos(x, z);
                if (!force && ProtectedChunkManager.isProtected(level, pos)) { skipped++; continue; }
                if (hasPlayersInChunk(level, pos)) { skipped++; continue; }
                try {
                    if (regenerateSingleChunk(level, pos)) regen++; else failed++;
                } catch (Exception e) {
                    LOGGER.error("Failed to regenerate chunk at [{}, {}]: {}", x, z, e.getMessage());
                    failed++;
                }
            }
        }
        return new long[]{regen, skipped, failed};
    }

    public static long[] regenerateChunks(ServerLevel level, List<ChunkPos> positions, boolean force) {
        long regen = 0, skipped = 0, failed = 0;
        for (ChunkPos pos : positions) {
            if (!force && ProtectedChunkManager.isProtected(level, pos)) { skipped++; continue; }
            if (hasPlayersInChunk(level, pos)) { skipped++; continue; }
            try {
                if (regenerateSingleChunk(level, pos)) regen++; else failed++;
            } catch (Exception e) {
                LOGGER.error("Failed to regenerate chunk at [{}]: {}", pos, e.getMessage());
                failed++;
            }
        }
        return new long[]{regen, skipped, failed};
    }

    private static boolean hasPlayersInChunk(ServerLevel level, ChunkPos pos) {
        int minX = pos.getMinBlockX(), maxX = pos.getMaxBlockX();
        int minZ = pos.getMinBlockZ(), maxZ = pos.getMaxBlockZ();
        return level.players().stream().anyMatch(p -> p.getX() >= minX && p.getX() <= maxX && p.getZ() >= minZ && p.getZ() <= maxZ);
    }

    /**
     * Delete a chunk from disk (MCA header cleared).
     * When the chunk is next loaded from disk, Minecraft finds no data
     * and generates brand-new terrain.
     *
     * If the chunk is currently in memory, mark unsaved=false so the
     * old data doesn't get written back to the cleared MCA entry.
     * No blocks are modified in memory — player sees no immediate change.
     */
    private static boolean regenerateSingleChunk(ServerLevel level, ChunkPos pos) {
        if (hasPlayersInChunk(level, pos)) {
            LOGGER.warn("Skipping chunk {} — player is inside", pos);
            return false;
        }

        LevelChunk existingChunk = level.getChunkSource().getChunkNow(pos.x, pos.z);

        // Clear MCA header on disk (marks chunk as non-existent)
        clearChunkFromMcaFile(level, pos);

        // Prevent old data from being saved back to the cleared MCA entry
        if (existingChunk != null) {
            existingChunk.setUnsaved(false);
            LOGGER.info("Chunk {} in memory — unsaved=false to prevent save-back", pos);
        }

        ChunkLoadTracker.get(level).removeRecord(pos);
        return true;
    }

    private static void clearChunkFromMcaFile(ServerLevel level, ChunkPos pos) {
        int regionX = Math.floorDiv(pos.x, 32);
        int regionZ = Math.floorDiv(pos.z, 32);
        int localX = pos.x & 31;
        int localZ = pos.z & 31;
        int chunkIndex = localX + localZ * 32;

        java.nio.file.Path regionFile = findRegionFile(level, regionX, regionZ);
        if (regionFile == null) {
            LOGGER.warn("Region file not found for chunk {} (r.{}.{}.mca)", pos, regionX, regionZ);
            return;
        }

        try (java.io.RandomAccessFile file = new java.io.RandomAccessFile(regionFile.toFile(), "rw")) {
            file.seek(chunkIndex * 4);
            byte[] header = new byte[4];
            file.read(header);
            int sectorOffset = ((header[0] & 0xFF) << 16) | ((header[1] & 0xFF) << 8) | (header[2] & 0xFF);
            if (sectorOffset == 0) {
                LOGGER.info("Chunk {} already absent from MCA", pos);
                return;
            }

            // Clear header: offset=0, count=0
            file.seek(chunkIndex * 4);
            file.write(new byte[]{0, 0, 0, 0});
            // Clear timestamp
            file.seek(4096L + chunkIndex * 4);
            file.write(new byte[]{0, 0, 0, 0});

            LOGGER.info("Deleted chunk {} from MCA (was at sector {}): {}", pos, sectorOffset, regionFile);
        } catch (Exception e) {
            LOGGER.warn("Failed to delete chunk {} from MCA: {}", pos, e.getMessage());
        }
    }

    private static java.nio.file.Path findRegionFile(ServerLevel level, int regionX, int regionZ) {
        String filename = "r." + regionX + "." + regionZ + ".mca";

        try {
            var server = level.getServer();
            var worldDir = server.getFile(".");
            var dimPath = level.dimension().location().getPath();
            var dimFolder = switch (dimPath) {
                case "overworld" -> "";
                case "the_nether" -> "DIM-1";
                case "the_end" -> "DIM1";
                default -> dimPath;
            };

            java.util.List<java.nio.file.Path> paths = new java.util.ArrayList<>();

            // Try getWorldPath API
            try {
                var lrClass = Class.forName("net.minecraft.world.level.storage.LevelResource");
                var root = lrClass.getField("LEVEL_ROOT").get(null);
                var getPath = server.getClass().getMethod("getWorldPath", lrClass);
                java.nio.file.Path wp = (java.nio.file.Path) getPath.invoke(server, root);
                paths.add(wp.resolve("region"));
                if (!dimFolder.isEmpty()) paths.add(wp.resolve(dimFolder).resolve("region"));
            } catch (Exception ignored) {}

            // Check saves dir
            java.nio.file.Path savesDir = worldDir.resolve("saves");
            if (java.nio.file.Files.isDirectory(savesDir)) {
                try (var ds = java.nio.file.Files.list(savesDir)) {
                    ds.filter(java.nio.file.Files::isDirectory).limit(1).forEach(save -> {
                        paths.add(save.resolve("region"));
                        if (!dimFolder.isEmpty()) paths.add(save.resolve(dimFolder).resolve("region"));
                    });
                } catch (Exception ignored) {}
            }

            // Common patterns
            if (dimFolder.isEmpty()) paths.add(worldDir.resolve("region"));
            else paths.add(worldDir.resolve(dimFolder).resolve("region"));
            paths.add(worldDir.resolve("world").resolve("region"));
            if (!dimFolder.isEmpty()) paths.add(worldDir.resolve("world").resolve(dimFolder).resolve("region"));

            for (var p : paths) {
                if (p == null) continue;
                var test = p.resolve(filename);
                if (java.nio.file.Files.exists(test)) {
                    LOGGER.info("Found region file: {}", test);
                    return test;
                }
            }

            LOGGER.warn("Region file {} not found. Checked paths:", filename);
            for (var p : paths) {
                if (p != null) LOGGER.warn("  - {}", p.resolve(filename));
            }
        } catch (Exception e) {
            LOGGER.warn("Error finding region file: {}", e.getMessage());
        }

        return null;
    }
}
