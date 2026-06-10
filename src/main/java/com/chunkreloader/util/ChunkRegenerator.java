package com.chunkreloader.util;

import com.chunkreloader.ChunkReloaderMod;
import com.chunkreloader.manager.ChunkLoadTracker;
import com.chunkreloader.manager.ProtectedChunkManager;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

public class ChunkRegenerator {
    private static final Logger LOGGER = ChunkReloaderMod.LOGGER;

    // Reflective storage handles
    private static Object storageInstance;
    private static Method storageWriteMethod;
    private static boolean storageInitialized = false;

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

    /**
     * Regenerate a list of chunks.
     */
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
     * Delete a chunk from disk (MCA) and try to unload from memory.
     */
    private static boolean regenerateSingleChunk(ServerLevel level, ChunkPos pos) {
        if (hasPlayersInChunk(level, pos)) {
            LOGGER.warn("Skipping chunk {} — player is inside", pos);
            return false;
        }

        LevelChunk existingChunk = level.getChunkSource().getChunkNow(pos.x, pos.z);

        // Delete from disk
        clearChunkFromDisk(level, pos);

        // Try to unload from memory
        if (existingChunk != null) {
            tryForceUnload(level, pos, existingChunk);
        }

        ChunkLoadTracker.get(level).removeRecord(pos);
        return true;
    }

    // ---- Disk Deletion ----

    private static void clearChunkFromDisk(ServerLevel level, ChunkPos pos) {
        // Method 1: Write empty NBT via ChunkStorage reflection (uses correct path automatically)
        if (tryStorageWrite(level, pos)) {
            LOGGER.info("Cleared chunk {} via storage write", pos);
            return;
        }

        // Method 2: Direct MCA file manipulation with auto-detected path
        if (clearChunkFromMcaFile(level, pos)) {
            LOGGER.info("Deleted chunk {} from MCA file", pos);
            return;
        }

        LOGGER.warn("Failed to delete chunk {} from disk — no method succeeded", pos);
    }

    // ---- ChunkStorage Reflection ----

    /**
     * Try to write an empty chunk to ChunkStorage via reflection.
     * This is the most reliable method because ChunkStorage knows the correct file path.
     */
    private static boolean tryStorageWrite(ServerLevel level, ChunkPos pos) {
        try {
            var chunkMap = level.getChunkSource().chunkMap;

            if (!storageInitialized) {
                initStorage(chunkMap);
            }

            if (storageWriteMethod != null && storageInstance != null) {
                CompoundTag tag = new CompoundTag();
                tag.putInt("xPos", pos.x);
                tag.putInt("zPos", pos.z);
                tag.putString("Status", "minecraft:empty");
                tag.putInt("DataVersion", 3839);
                storageWriteMethod.invoke(storageInstance, pos, tag);
                return true;
            }
        } catch (Exception e) {
            LOGGER.info("Storage write failed: {}", e.getMessage());
        }
        return false;
    }

    private static void initStorage(Object chunkMap) {
        storageInitialized = true;
        // Search fields by type for ChunkStorage or RegionStorage
        for (Field field : chunkMap.getClass().getDeclaredFields()) {
            String typeName = field.getType().getName().toLowerCase();
            if ((typeName.contains("chunkstorage") || typeName.contains("regionstorage"))
                    && !typeName.contains("light")) {
                field.setAccessible(true);
                try {
                    storageInstance = field.get(chunkMap);
                    findWriteMethod(storageInstance);
                    if (storageWriteMethod != null) {
                        LOGGER.info("Found storage: {} (type: {})", field.getName(), field.getType().getSimpleName());
                        return;
                    }
                } catch (Exception ignored) {}
            }
        }
        // Fallback: try known field names
        for (String name : new String[]{"storage", "chunkStorage", "regionStorage", "cache"}) {
            try {
                Field field = chunkMap.getClass().getDeclaredField(name);
                field.setAccessible(true);
                storageInstance = field.get(chunkMap);
                findWriteMethod(storageInstance);
                if (storageWriteMethod != null) {
                    LOGGER.info("Found storage via name: {}", name);
                    return;
                }
            } catch (Exception ignored) {}
        }
    }

    private static void findWriteMethod(Object storage) {
        if (storage == null) return;
        for (Method m : storage.getClass().getMethods()) {
            if (!"write".equals(m.getName())) continue;
            Class<?>[] params = m.getParameterTypes();
            if (params.length == 2 && params[0].getSimpleName().contains("ChunkPos")
                    && params[1].getSimpleName().contains("Compound")) {
                storageWriteMethod = m;
                return;
            }
        }
    }

    // ---- MCA File Manipulation ----

    /**
     * Direct MCA file manipulation: clear the chunk header entry.
     * Returns true if successful, false if region file wasn't found.
     */
    private static boolean clearChunkFromMcaFile(ServerLevel level, ChunkPos pos) {
        int regionX = Math.floorDiv(pos.x, 32);
        int regionZ = Math.floorDiv(pos.z, 32);
        int localX = pos.x & 31;
        int localZ = pos.z & 31;
        int chunkIndex = localX + localZ * 32;

        java.nio.file.Path regionFile = findRegionFile(level, regionX, regionZ);
        if (regionFile == null) return false;

        try (java.io.RandomAccessFile file = new java.io.RandomAccessFile(regionFile.toFile(), "rw")) {
            file.seek(chunkIndex * 4);
            byte[] header = new byte[4];
            file.read(header);
            int sectorOffset = ((header[0] & 0xFF) << 16) | ((header[1] & 0xFF) << 8) | (header[2] & 0xFF);
            if (sectorOffset == 0) {
                LOGGER.info("Chunk {} already deleted from MCA", pos);
                return true;
            }
            file.seek(chunkIndex * 4);
            file.write(new byte[]{0, 0, 0, 0});
            file.seek(4096L + chunkIndex * 4);
            file.write(new byte[]{0, 0, 0, 0});
            LOGGER.info("Deleted chunk {} from MCA", pos);
            return true;
        } catch (Exception e) {
            LOGGER.warn("MCA manipulation failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Find the MCA region file by checking all likely paths.
     */
    private static java.nio.file.Path findRegionFile(ServerLevel level, int regionX, int regionZ) {
        String filename = "r." + regionX + "." + regionZ + ".mca";

        try {
            var server = level.getServer();
            var worldDir = server.getFile(".");
            var dimPath = level.dimension().location().getPath();

            // Map dimension path to Minecraft's folder convention
            String dimFolder = switch (dimPath) {
                case "overworld" -> "";
                case "the_nether" -> "DIM-1";
                case "the_end" -> "DIM1";
                default -> dimPath;
            };

            // Collect all possible paths
            java.util.List<java.nio.file.Path> paths = new java.util.ArrayList<>();

            // 1. Try getWorldPath API (most reliable in 1.21.1)
            try {
                var levelResourceClass = Class.forName("net.minecraft.world.level.storage.LevelResource");
                var rootConstant = levelResourceClass.getField("LEVEL_ROOT").get(null);
                Method getPath = server.getClass().getMethod("getWorldPath", levelResourceClass);
                java.nio.file.Path worldRoot = (java.nio.file.Path) getPath.invoke(server, rootConstant);
                paths.add(worldRoot.resolve("region"));
                if (!dimFolder.isEmpty()) paths.add(worldRoot.resolve(dimFolder).resolve("region"));
            } catch (Exception ignored) {}

            // 2. Direct paths from server directory
            java.nio.file.Path[] bases = {worldDir, worldDir.resolve("saves")};

            // Try to find the save folder
            java.nio.file.Path savesDir = worldDir.resolve("saves");
            if (java.nio.file.Files.isDirectory(savesDir)) {
                try (var dirStream = java.nio.file.Files.list(savesDir)) {
                    dirStream.filter(java.nio.file.Files::isDirectory).limit(1).forEach(save -> {
                        paths.add(save.resolve("region"));
                        if (!dimFolder.isEmpty()) paths.add(save.resolve(dimFolder).resolve("region"));
                    });
                } catch (Exception ignored) {}
            }

            // 3. Common patterns (for server root or current directory)
            if (dimFolder.isEmpty()) {
                paths.add(worldDir.resolve("region"));
            } else {
                paths.add(worldDir.resolve(dimFolder).resolve("region"));
            }
            paths.add(worldDir.resolve("world").resolve("region"));
            if (!dimFolder.isEmpty()) paths.add(worldDir.resolve("world").resolve(dimFolder).resolve("region"));

            // Check each path
            for (java.nio.file.Path p : paths) {
                if (p == null) continue;
                java.nio.file.Path test = p.resolve(filename);
                if (java.nio.file.Files.exists(test)) {
                    LOGGER.info("Found region file: {}", test);
                    return test;
                }
            }

            LOGGER.warn("Region file {} not found in any path for dim={}", filename, dimPath);
            // Debug: print paths we checked
            for (java.nio.file.Path p : paths) {
                if (p != null) LOGGER.info("  Checked: {}", p.resolve(filename));
            }
        } catch (Exception e) {
            LOGGER.warn("Error finding region file: {}", e.getMessage());
        }

        return null;
    }

    // ---- Force Unload ----

    private static void tryForceUnload(ServerLevel level, ChunkPos pos, LevelChunk chunk) {
        try {
            var chunkMap = level.getChunkSource().chunkMap;
            for (var m : chunkMap.getClass().getDeclaredMethods()) {
                if (!"save".equals(m.getName())) continue;
                Class<?>[] params = m.getParameterTypes();
                if (params.length == 1 && params[0].isInstance(chunk)) {
                    m.setAccessible(true);
                    m.invoke(chunkMap, chunk);
                    clearChunkFromDisk(level, pos);
                    break;
                }
            }
        } catch (Exception e) {
            LOGGER.info("Save-reflect failed for {}: {}", pos, e.getMessage());
        }
        chunk.setUnsaved(false);
    }
}
