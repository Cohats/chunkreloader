package com.chunkreloader.util;

import com.chunkreloader.ChunkReloaderMod;
import com.chunkreloader.manager.ChunkLoadTracker;
import com.chunkreloader.manager.ProtectedChunkManager;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
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
import java.util.Map;

public class ChunkRegenerator {
    private static final Logger LOGGER = LoggerFactory.getLogger("ChunkReloaderRegen");

    // Reflective handles (cached for performance)
    private static Object storageInstance;
    private static Method storageWriteMethod;
    private static Field storageField;
    private static boolean storageResolved = false;

    private static Field visibleChunkMapField;
    private static boolean chunkMapResolved = false;

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
     * Regenerate a single chunk by clearing both disk (MCA) and memory.
     *
     * 1. Clear MCA header on disk — so future loads regenerate fresh terrain
     * 2. If chunk is loaded in memory, clear all blocks to air
     * 3. Try to drop the chunk from ChunkMap so it reloads from (cleared) disk
     * 4. Call level.getChunk() to trigger load/generation
     */
    private static boolean regenerateSingleChunk(ServerLevel level, ChunkPos pos) {
        LevelChunk existingChunk = level.getChunkSource().getChunkNow(pos.x, pos.z);
        boolean wasLoaded = existingChunk != null;

        // Step 1: Clear from disk (MCA file or storage write)
        clearChunkFromDisk(level, pos);

        // Step 2: If chunk is loaded in memory, clear it directly
        if (wasLoaded) {
            LOGGER.debug("Chunk {} is in memory, clearing blocks to air", pos);
            fallbackClearChunk(level, pos);
        }

        // Step 3: Try to drop from ChunkMap so next getChunk() actually loads from disk
        if (wasLoaded) {
            try {
                forceUnloadChunk(level, pos);
            } catch (Exception e) {
                LOGGER.debug("Could not force-unload chunk {}: {}", pos, e.getMessage());
            }
        }

        // Step 4: Trigger chunk load — if unloaded, this reads cleared MCA and regenerates
        try {
            level.getChunk(pos.x, pos.z);
        } catch (Exception e) {
            LOGGER.warn("Could not reload chunk {}: {}", pos, e.getMessage());
        }

        ChunkLoadTracker.get(level).removeRecord(pos);
        return true;
    }

    /**
     * Try to force-unload a chunk from the ChunkMap's internal structures.
     * Uses reflection to find and remove the chunk from visibleChunkMap.
     */
    private static void forceUnloadChunk(ServerLevel level, ChunkPos pos) throws Exception {
        ServerChunkCache cache = level.getChunkSource();
        ChunkMap chunkMap = cache.chunkMap;

        if (!chunkMapResolved) {
            resolveChunkMap(chunkMap);
        }

        long packedPos = ChunkPos.asLong(pos.x, pos.z);

        if (visibleChunkMapField != null) {
            Object map = visibleChunkMapField.get(chunkMap);
            if (map instanceof Long2ObjectMap<?> longMap) {
                longMap.remove(packedPos);
                LOGGER.debug("Removed chunk {} from visibleChunkMap", pos);
            } else if (map instanceof Map<?, ?> genericMap) {
                ((Map<Long, ?>) genericMap).remove(packedPos);
                LOGGER.debug("Removed chunk {} from chunk map", pos);
            }
        }
    }

    /**
     * Find the internal chunk map field (visibleChunkMap or similar) in ChunkMap.
     */
    private static void resolveChunkMap(ChunkMap chunkMap) {
        chunkMapResolved = true;

        // Look for Long2ObjectMap fields (ChunkMap uses these internally)
        for (Field field : chunkMap.getClass().getDeclaredFields()) {
            Class<?> type = field.getType();
            // Look for maps from long -> LevelChunk
            if (Long2ObjectMap.class.isAssignableFrom(type)) {
                field.setAccessible(true);
                visibleChunkMapField = field;
                LOGGER.info("Found chunk map field: {} ({})", field.getName(), type.getSimpleName());
                return;
            }
        }

        // Fallback: look for Map<Long, ?> fields
        for (Field field : chunkMap.getClass().getDeclaredFields()) {
            if (Map.class.isAssignableFrom(field.getType())) {
                String typeName = field.getGenericType().getTypeName();
                if (typeName.contains("Long") && (typeName.contains("Chunk") || typeName.contains("chunk"))) {
                    field.setAccessible(true);
                    visibleChunkMapField = field;
                    LOGGER.info("Found chunk map field (fallback): {} ({})", field.getName(), typeName);
                    return;
                }
            }
        }
    }

    /**
     * Clear chunk data from disk.
     * Tries storage write via reflection first, falls back to MCA manipulation.
     */
    private static void clearChunkFromDisk(ServerLevel level, ChunkPos pos) {
        try {
            ServerChunkCache cache = level.getChunkSource();
            ChunkMap chunkMap = cache.chunkMap;

            // Try reflection storage write first
            if (tryWriteEmptyChunk(chunkMap, pos)) {
                LOGGER.debug("Wrote empty chunk data to storage for {}", pos);
            } else {
                // Fallback: directly modify the MCA file
                clearChunkFromMcaFile(level, pos);
            }
        } catch (Exception e) {
            LOGGER.warn("Disk clear failed for chunk {}: {}", pos, e.getMessage());
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

            // Try to find the region file
            java.nio.file.Path regionFile = findRegionFile(level, regionX, regionZ);

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

                // Clear header entry (offset = 0, marking chunk as non-existent)
                file.seek(chunkIndex * 4);
                file.write(new byte[]{0, 0, 0, 0});
                // Clear timestamp entry
                file.seek(4096 + chunkIndex * 4);
                file.write(new byte[]{0, 0, 0, 0});

                LOGGER.debug("Cleared chunk {} from MCA file r.{}.{}.mca", pos, regionX, regionZ);
            }
        } catch (Exception e) {
            LOGGER.warn("MCA manipulation failed for chunk {}: {}", pos, e.getMessage());
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

        LOGGER.debug("Cleared chunk {} with air blocks (fallback)", pos);
    }
}
