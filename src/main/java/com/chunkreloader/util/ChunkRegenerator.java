package com.chunkreloader.util;

import com.chunkreloader.ChunkReloaderMod;
import com.chunkreloader.manager.ChunkLoadTracker;
import com.chunkreloader.manager.ProtectedChunkManager;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

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
     * Regenerate a single chunk by clearing disk (MCA) data.
     *
     * If chunk is loaded in memory and has players → skip (safety).
     * Otherwise: clear MCA on disk so future loads regenerate fresh terrain.
     * If loaded in memory, also mark as unsaved=false + clear to air
     * so the old data is not persisted when the chunk naturally unloads.
     */
    private static boolean regenerateSingleChunk(ServerLevel level, ChunkPos pos) {
        // Safety: skip if any player is in this chunk
        if (hasPlayersInChunk(level, pos)) {
            LOGGER.warn("Skipping chunk {} — player is inside", pos);
            return false;
        }

        LevelChunk existingChunk = level.getChunkSource().getChunkNow(pos.x, pos.z);

        // Clear from disk (MCA file or storage write)
        clearChunkFromDisk(level, pos);

        // If chunk is loaded in memory, prevent it from saving old data
        // and clear blocks for immediate visual effect
        if (existingChunk != null) {
            // Mark as not-unsaved so the server won't persist the old data
            existingChunk.setUnsaved(false);
            // Clear blocks to air so the player sees immediate change
            fallbackClearChunk(level, pos);
            LOGGER.debug("Cleared in-memory chunk {} (unsaved={})", pos, existingChunk.isUnsaved());
        }

        ChunkLoadTracker.get(level).removeRecord(pos);
        return true;
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
     * Direct MCA file manipulation: write a minimal empty chunk NBT (Status=empty)
     * so Minecraft regenerates the chunk when it's loaded again.
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

            // Create minimal empty chunk NBT — Minecraft will regenerate terrain
            // when it sees Status="minecraft:empty"
            CompoundTag emptyChunk = new CompoundTag();
            emptyChunk.putInt("xPos", pos.x);
            emptyChunk.putInt("zPos", pos.z);
            emptyChunk.putString("Status", "minecraft:empty");
            emptyChunk.putInt("DataVersion", 3839); // 1.21.1 data version
            emptyChunk.put("sections", new ListTag());     // empty sections
            emptyChunk.put("block_entities", new ListTag()); // no block entities
            emptyChunk.put("block_ticks", new ListTag());
            emptyChunk.put("fluid_ticks", new ListTag());
            emptyChunk.put("entities", new ListTag());
            emptyChunk.put("HeightMap", new CompoundTag()); // empty heightmap

            // Compress NBT with ZLib (compression type 2, standard for MC region files)
            byte[] compressed;
            try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
                 DeflaterOutputStream deflater = new DeflaterOutputStream(baos, new Deflater(Deflater.DEFAULT_COMPRESSION));
                 DataOutputStream dataOut = new DataOutputStream(deflater)) {
                NbtIo.write(emptyChunk, dataOut);
                dataOut.flush();
                compressed = baos.toByteArray();
            }

            // Calculate required sectors (each sector = 4096 bytes)
            // Entry format: [4 bytes length][1 byte compression][compressed bytes]
            int entryLength = 1 + compressed.length; // 1 = compression type byte
            int sectorCount = (entryLength + 4095) / 4096;
            if (sectorCount < 1) sectorCount = 1;

            try (java.io.RandomAccessFile file = new java.io.RandomAccessFile(regionFile.toFile(), "rw")) {
                // Read current header to find existing chunk
                file.seek(chunkIndex * 4);
                byte[] header = new byte[4];
                file.read(header);
                int currentSector = ((header[0] & 0xFF) << 16)
                        | ((header[1] & 0xFF) << 8)
                        | (header[2] & 0xFF);
                int currentSectors = header[3] & 0xFF;

                int writeSector;
                if (currentSector > 0 && currentSectors >= sectorCount) {
                    // Reuse existing space
                    writeSector = currentSector;
                } else {
                    // Append to end of file, aligned to sector boundary
                    long fileLen = file.length();
                    writeSector = (int) ((fileLen + 4095) / 4096);
                }

                // Write: [4 bytes entry length][1 byte compression type 2=ZLib][compressed NBT]
                long writePos = (long) writeSector * 4096;
                file.seek(writePos);
                file.writeInt(entryLength);
                file.writeByte(2); // ZLib compression
                file.write(compressed);

                // Pad remaining sector space with zeros
                long endPos = writePos + sectorCount * 4096L;
                if (file.length() < endPos) {
                    file.setLength(endPos);
                }

                // Update header
                file.seek(chunkIndex * 4);
                byte[] newHeader = new byte[]{
                        (byte) ((writeSector >> 16) & 0xFF),
                        (byte) ((writeSector >> 8) & 0xFF),
                        (byte) (writeSector & 0xFF),
                        (byte) sectorCount
                };
                file.write(newHeader);

                // Update timestamp
                file.seek(4096L + chunkIndex * 4);
                file.writeInt((int) (System.currentTimeMillis() / 1000));

                LOGGER.debug("Wrote empty chunk {} to MCA r.{}.{}.mca (sector={}, count={})",
                        pos, regionX, regionZ, writeSector, sectorCount);
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to write empty chunk data for {}: {}", pos, e.getMessage());
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
