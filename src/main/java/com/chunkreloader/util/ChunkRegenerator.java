package com.chunkreloader.util;

import com.chunkreloader.ChunkReloaderMod;
import com.chunkreloader.manager.ChunkLoadTracker;
import com.chunkreloader.manager.ProtectedChunkManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class ChunkRegenerator {
    private static final Logger LOGGER = LoggerFactory.getLogger("ChunkReloaderRegen");

    /**
     * Regenerate all chunks in the rectangle between pos1 and pos2.
     */
    public static long[] regenerateChunks(ServerLevel level, ChunkPos pos1, ChunkPos pos2, boolean force) {
        int minX = Math.min(pos1.x, pos2.x);
        int maxX = Math.max(pos1.x, pos2.x);
        int minZ = Math.min(pos1.z, pos2.z);
        int maxZ = Math.max(pos1.z, pos2.z);

        long regen = 0, skipped = 0, failed = 0;

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                ChunkPos pos = new ChunkPos(x, z);

                if (!force && ProtectedChunkManager.isProtected(level, pos)) {
                    skipped++;
                    continue;
                }

                if (hasPlayersInChunk(level, pos)) {
                    skipped++;
                    continue;
                }

                try {
                    if (regenerateSingleChunk(level, pos)) {
                        regen++;
                    } else {
                        failed++;
                    }
                } catch (Exception e) {
                    LOGGER.error("Failed to regenerate chunk at [{}, {}]: {}", x, z, e.getMessage());
                    failed++;
                }
            }
        }

        return new long[]{regen, skipped, failed};
    }

    /**
     * Regenerate a list of chunks.
     */
    public static long[] regenerateChunks(ServerLevel level, List<ChunkPos> positions, boolean force) {
        long regen = 0, skipped = 0, failed = 0;

        for (ChunkPos pos : positions) {
            if (!force && ProtectedChunkManager.isProtected(level, pos)) {
                skipped++;
                continue;
            }

            if (hasPlayersInChunk(level, pos)) {
                skipped++;
                continue;
            }

            try {
                if (regenerateSingleChunk(level, pos)) {
                    regen++;
                } else {
                    failed++;
                }
            } catch (Exception e) {
                LOGGER.error("Failed to regenerate chunk at [{}]: {}", pos, e.getMessage());
                failed++;
            }
        }

        return new long[]{regen, skipped, failed};
    }

    /**
     * Check if any player is inside this chunk.
     */
    private static boolean hasPlayersInChunk(ServerLevel level, ChunkPos pos) {
        int minBlockX = pos.getMinBlockX();
        int minBlockZ = pos.getMinBlockZ();
        int maxBlockX = pos.getMaxBlockX();
        int maxBlockZ = pos.getMaxBlockZ();

        return level.players().stream().anyMatch(player -> {
            double px = player.getX();
            double pz = player.getZ();
            return px >= minBlockX && px <= maxBlockX && pz >= minBlockZ && pz <= maxBlockZ;
        });
    }

    /**
     * Delete a chunk from disk entirely (MCA header set to 0).
     *
     * When the chunk is loaded next (player walks near), Minecraft finds no data
     * on disk and generates brand-new terrain from scratch.
     *
     * If the chunk is currently in memory, mark unsaved=false so the server
     * does NOT persist the old data back to the (now-deleted) MCA entry.
     * No blocks are modified in memory — the player sees no immediate change.
     */
    private static boolean regenerateSingleChunk(ServerLevel level, ChunkPos pos) {
        // Safety: skip if any player is in this chunk
        if (hasPlayersInChunk(level, pos)) {
            LOGGER.warn("Skipping chunk {} — player is inside", pos);
            return false;
        }

        LevelChunk existingChunk = level.getChunkSource().getChunkNow(pos.x, pos.z);

        // Delete chunk data from disk by clearing the MCA header entry
        clearChunkFromDisk(level, pos);

        // If chunk is loaded in memory, prevent the old data from being saved back
        if (existingChunk != null) {
            existingChunk.setUnsaved(false);
            LOGGER.debug("Chunk {} in memory, marked unsaved=false", pos);
        }

        ChunkLoadTracker.get(level).removeRecord(pos);
        return true;
    }

    /**
     * Delete chunk data from disk by clearing the MCA header entry.
     * When loaded next, Minecraft finds no data and generates fresh terrain.
     */
    private static void clearChunkFromDisk(ServerLevel level, ChunkPos pos) {
        clearChunkFromMcaFile(level, pos);
    }

    /**
     * Delete chunk data from the MCA file by clearing the header entry.
     * Sets the sector offset to 0, marking the chunk as non-existent.
     * Minecraft will generate fresh terrain when the chunk is next loaded.
     */
    private static void clearChunkFromMcaFile(ServerLevel level, ChunkPos pos) {
        try {
            int regionX = Math.floorDiv(pos.x, 32);
            int regionZ = Math.floorDiv(pos.z, 32);
            int localX = pos.x & 31;
            int localZ = pos.z & 31;
            int chunkIndex = localX + localZ * 32;

            java.nio.file.Path regionFile = findRegionFile(level, regionX, regionZ);
            if (regionFile == null) {
                LOGGER.debug("Region file not found for chunk {}", pos);
                return;
            }

            try (java.io.RandomAccessFile file = new java.io.RandomAccessFile(regionFile.toFile(), "rw")) {
                // Check if chunk exists in the MCA header
                file.seek(chunkIndex * 4);
                byte[] header = new byte[4];
                file.read(header);
                int sectorOffset = ((header[0] & 0xFF) << 16)
                        | ((header[1] & 0xFF) << 8)
                        | (header[2] & 0xFF);

                if (sectorOffset == 0) {
                    LOGGER.debug("Chunk {} already absent from MCA r.{}.{}.mca", pos, regionX, regionZ);
                    return;
                }

                // Clear header: set offset=0, count=0 (marks chunk as non-existent)
                file.seek(chunkIndex * 4);
                file.write(new byte[]{0, 0, 0, 0});
                // Clear timestamp
                file.seek(4096L + chunkIndex * 4);
                file.write(new byte[]{0, 0, 0, 0});

                LOGGER.debug("Deleted chunk {} from MCA r.{}.{}.mca", pos, regionX, regionZ);
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to delete chunk {} from MCA: {}", pos, e.getMessage());
        }
    }

    /**
     * Find the region file for the given region coordinates.
     */
    private static java.nio.file.Path findRegionFile(ServerLevel level, int regionX, int regionZ) {
        // Try to determine the correct region file path using the level's dimension path
        try {
            // Method 1: Use the level's storage path (most reliable in 1.21.1)
            var dimensionPath = level.dimension().location().getPath();
            var worldDir = level.getServer().getFile(".");

            // Check common paths
            java.nio.file.Path[] possiblePaths = {
                    // Single-player / server root level
                    worldDir.resolve("region"),
                    // Bukkit-style world directory
                    worldDir.resolve(dimensionPath).resolve("region"),
                    worldDir.resolve("world").resolve("region"),
                    worldDir.resolve("world").resolve(dimensionPath).resolve("region"),
                    // Minecraft saves directory
                    worldDir.resolve("saves").resolve("world").resolve("region"),
            };

            for (java.nio.file.Path p : possiblePaths) {
                java.nio.file.Path test = p.resolve("r." + regionX + "." + regionZ + ".mca");
                if (java.nio.file.Files.exists(test)) {
                    return test;
                }
            }
        } catch (Exception e) {
            LOGGER.debug("Error finding region file: {}", e.getMessage());
        }

        return null;
    }

}
