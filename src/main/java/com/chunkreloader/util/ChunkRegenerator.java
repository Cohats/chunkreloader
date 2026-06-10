package com.chunkreloader.util;

import com.chunkreloader.ChunkReloaderMod;
import com.chunkreloader.manager.ChunkLoadTracker;
import com.chunkreloader.manager.ProtectedChunkManager;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

public class ChunkRegenerator {
    private static final Logger LOGGER = LoggerFactory.getLogger("ChunkReloaderRegen");

    // Reflective handles (cached for performance)
    private static Object storageInstance;
    private static Method storageWriteMethod;
    private static Field storageField;
    private static boolean storageResolved = false;

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
     * Regenerate a single chunk. Tries MCA file manipulation first,
     * falls back to block-by-block clearing if that fails.
     */
    private static boolean regenerateSingleChunk(ServerLevel level, ChunkPos pos) {
        boolean diskCleared = clearChunkFromDisk(level, pos);

        if (!diskCleared) {
            LOGGER.info("Using fallback block clearing for chunk {}", pos);
            fallbackClearChunk(level, pos);
        }

        // Reload the chunk to trigger regeneration
        try {
            level.getChunk(pos.x, pos.z);
        } catch (Exception e) {
            LOGGER.warn("Could not reload chunk {}: {}", pos, e.getMessage());
        }

        ChunkLoadTracker.get(level).removeRecord(pos);
        return true;
    }

    /**
     * Clear chunk data from the region file by modifying the MCA header.
     * Uses chunkMap storage accessed via runtime reflection.
     */
    private static boolean clearChunkFromDisk(ServerLevel level, ChunkPos pos) {
        try {
            ServerChunkCache cache = level.getChunkSource();
            ChunkMap chunkMap = cache.chunkMap; // via AT

            // Save the chunk first if possible
            LevelChunk existingChunk = cache.getChunkNow(pos.x, pos.z);
            if (existingChunk != null) {
                trySaveChunk(chunkMap, existingChunk);
            }

            // Find and use storage to write empty chunk data
            if (!tryWriteEmptyChunk(chunkMap, pos)) {
                // Fallback: directly modify the MCA file
                clearChunkFromMcaFile(level, pos);
            }

            return true;
        } catch (Exception e) {
            LOGGER.warn("Disk clear failed for chunk {}: {}", pos, e.getMessage());
            return false;
        }
    }

    /**
     * Try to save a chunk using ChunkMap.save via runtime reflection.
     */
    private static void trySaveChunk(ChunkMap chunkMap, LevelChunk chunk) {
        try {
            Method saveMethod = findSaveMethod(chunkMap);
            if (saveMethod != null) {
                saveMethod.invoke(chunkMap, chunk);
            }
        } catch (Exception e) {
            LOGGER.debug("Could not save chunk: {}", e.getMessage());
        }
    }

    /**
     * Try to write an empty chunk tag to the storage to trigger regeneration.
     */
    private static boolean tryWriteEmptyChunk(ChunkMap chunkMap, ChunkPos pos) {
        try {
            if (!storageResolved) {
                resolveStorage(chunkMap);
            }

            if (storageWriteMethod != null && storageInstance != null) {
                CompoundTag emptyTag = new CompoundTag();
                emptyTag.putInt("xPos", pos.x);
                emptyTag.putInt("zPos", pos.z);
                emptyTag.putString("Status", "minecraft:empty");
                storageWriteMethod.invoke(storageInstance, pos, emptyTag);
                return true;
            }
        } catch (Exception e) {
            LOGGER.debug("Storage write failed: {}", e.getMessage());
        }
        return false;
    }

    /**
     * Find the ChunkMap.save method via reflection since the AT might not match.
     */
    private static Method findSaveMethod(ChunkMap chunkMap) {
        for (Method m : chunkMap.getClass().getDeclaredMethods()) {
            if (m.getName().contains("save") || m.getName().contains("Save")) {
                Class<?>[] params = m.getParameterTypes();
                if (params.length == 1 && params[0] != null &&
                        params[0].getSimpleName().contains("ChunkAccess")) {
                    m.setAccessible(true);
                    return m;
                }
            }
        }
        return null;
    }

    /**
     * Find the storage field in ChunkMap by type at runtime.
     */
    private static void resolveStorage(ChunkMap chunkMap) {
        storageResolved = true;
        // Search by type name
        for (Field field : chunkMap.getClass().getDeclaredFields()) {
            String typeName = field.getType().getName().toLowerCase();
            if ((typeName.contains("chunkstorage") || typeName.contains("regionstorage"))
                    && !typeName.contains("light")) {
                field.setAccessible(true);
                storageField = field;
                try {
                    storageInstance = field.get(chunkMap);
                    findWriteMethod(storageInstance);
                    LOGGER.info("Found storage via type: {} as {}", field.getName(), typeName);
                    return;
                } catch (Exception e) {
                    LOGGER.debug("Could not access storage field {}: {}", field.getName(), e.getMessage());
                }
            }
        }

        // Fallback: try known names
        for (String name : new String[]{"storage", "chunkStorage", "regionStorage"}) {
            try {
                Field field = chunkMap.getClass().getDeclaredField(name);
                field.setAccessible(true);
                storageField = field;
                storageInstance = field.get(chunkMap);
                findWriteMethod(storageInstance);
                LOGGER.info("Found storage via name: {}", name);
                return;
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * Find the write method on the storage object.
     */
    private static void findWriteMethod(Object storage) {
        if (storage == null) return;
        for (Method m : storage.getClass().getMethods()) {
            if (m.getName().equals("write") || m.getName().equals("save")) {
                Class<?>[] params = m.getParameterTypes();
                if (params.length == 2
                        && params[0].getSimpleName().contains("ChunkPos")
                        && params[1].getSimpleName().contains("Compound")) {
                    storageWriteMethod = m;
                    LOGGER.info("Found write method: {}", m.getName());
                    return;
                }
            }
        }
    }

    /**
     * Direct MCA file manipulation: clear the chunk header entry.
     */
    private static void clearChunkFromMcaFile(ServerLevel level, ChunkPos pos) {
        try {
            int regionX = Math.floorDiv(pos.x, 32);
            int regionZ = Math.floorDiv(pos.z, 32);
            int localX = pos.x & 31;
            int localZ = pos.z & 31;
            int chunkIndex = localX + localZ * 32;

            // Try to find the region file - check common locations
            java.nio.file.Path[] possiblePaths = {
                    level.getServer().getFile(".").resolve("region"),
                    level.getServer().getFile(".").resolve("world/region"),
                    level.getServer().getFile(".").resolve("saves/world/region"),
            };

            java.nio.file.Path regionFile = null;
            for (java.nio.file.Path p : possiblePaths) {
                java.nio.file.Path test = p.resolve("r." + regionX + "." + regionZ + ".mca");
                if (java.nio.file.Files.exists(test)) {
                    regionFile = test;
                    break;
                }
            }

            if (regionFile == null) {
                LOGGER.debug("Region file not found for chunk {}", pos);
                return;
            }

            try (java.io.RandomAccessFile file = new java.io.RandomAccessFile(regionFile.toFile(), "rw")) {
                // Check if chunk exists in the MCA header
                file.seek(chunkIndex * 4);
                byte[] offsetBytes = new byte[4];
                file.read(offsetBytes);
                int sectorOffset = ((offsetBytes[0] & 0xFF) << 16)
                        | ((offsetBytes[1] & 0xFF) << 8)
                        | (offsetBytes[2] & 0xFF);

                if (sectorOffset == 0) {
                    LOGGER.debug("Chunk {} has no data in region file", pos);
                    return;
                }

                // Clear header entry
                file.seek(chunkIndex * 4);
                file.write(new byte[]{0, 0, 0, 0});
                // Clear timestamp entry
                file.seek(4096 + chunkIndex * 4);
                file.write(new byte[]{0, 0, 0, 0});

                LOGGER.debug("Cleared chunk {} from MCA file", pos);
            }
        } catch (Exception e) {
            LOGGER.warn("MCA manipulation failed: {}", e.getMessage());
        }
    }

    /**
     * Fallback: set all blocks in the chunk to air using public API.
     */
    private static void fallbackClearChunk(ServerLevel level, ChunkPos pos) {
        int minY = level.getMinBuildHeight();
        int maxY = level.getMaxBuildHeight();

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = minY; y < maxY; y++) {
                    level.setBlock(
                            new BlockPos(pos.getMinBlockX() + x, y, pos.getMinBlockZ() + z),
                            Blocks.AIR.defaultBlockState(),
                            3
                    );
                }
            }
        }

        LOGGER.debug("Fallback cleared chunk {} with air blocks", pos);
    }
}
