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
     * Delete a chunk from disk (MCA header set to 0) and try to unload it
     * from memory so the next access generates fresh terrain.
     *
     * Safety: skips if any player is inside the chunk.
     */
    private static boolean regenerateSingleChunk(ServerLevel level, ChunkPos pos) {
        if (hasPlayersInChunk(level, pos)) {
            LOGGER.warn("Skipping chunk {} — player is inside", pos);
            return false;
        }

        LevelChunk existingChunk = level.getChunkSource().getChunkNow(pos.x, pos.z);

        // Step 1: Delete from disk (MCA header = 0)
        clearChunkFromDisk(level, pos);

        // Step 2: Try to unload from memory so it's immediately gone
        if (existingChunk != null) {
            tryForceUnload(level, pos, existingChunk);
        }

        ChunkLoadTracker.get(level).removeRecord(pos);
        return true;
    }

    /**
     * Try to save the chunk, re-clear MCA, and mark unsaved=false.
     * This gives ChunkMap a chance to cleanly unload the chunk on next tick.
     * If reflection save fails, falls back to natural unload (safe).
     */
    private static void tryForceUnload(ServerLevel level, ChunkPos pos, LevelChunk chunk) {
        try {
            // Access ChunkMap via AT (public net.minecraft.server.level.ServerChunkCache chunkMap)
            var chunkMap = level.getChunkSource().chunkMap;

            // Find a save method via reflection (method name varies by MC version)
            for (var m : chunkMap.getClass().getDeclaredMethods()) {
                if (!"save".equals(m.getName())) continue;
                Class<?>[] params = m.getParameterTypes();
                if (params.length == 1 && params[0].isInstance(chunk)) {
                    m.setAccessible(true);
                    m.invoke(chunkMap, chunk);

                    // Save wrote old data to MCA — re-clear so it's gone from disk
                    clearChunkFromDisk(level, pos);
                    break;
                }
            }
        } catch (Exception e) {
            LOGGER.debug("Save-reflect failed for {}: {} (safe fallback)", pos, e.getMessage());
        }

        chunk.setUnsaved(false);
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
