package com.chunkreloader.util;

import com.chunkreloader.ChunkReloaderMod;
import com.chunkreloader.manager.ChunkLoadTracker;
import com.chunkreloader.manager.ProtectedChunkManager;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.storage.ChunkStorage;
import org.slf4j.Logger;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

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
     * Regenerate a single chunk using ChunkStorage API.
     *
     * Since ChunkMap extends ChunkStorage (confirmed by diagnostic dump),
     * we call write(ChunkPos, CompoundTag) directly on the chunkMap.
     * This properly updates both the MCA file AND the RegionFile's in-memory
     * header cache — solving the core bug where direct file writes were invisible.
     *
     * Strategy: read existing chunk NBT, clear all block/section data,
     * set Status to "minecraft:empty", write back. On next load, Minecraft
     * finds an empty-status chunk and regenerates terrain from scratch.
     */
    private static boolean regenerateSingleChunk(ServerLevel level, ChunkPos pos) {
        if (hasPlayersInChunk(level, pos)) {
            LOGGER.warn("Skipping chunk {} — player is inside", pos);
            return false;
        }

        // Force-load the chunk into memory so we can modify it
        LevelChunk existingChunk = level.getChunkSource().getChunkNow(pos.x, pos.z);
        if (existingChunk == null) {
            existingChunk = level.getChunk(pos.x, pos.z);
        }

        // Remove ALL non-player entities from this chunk (prevents accumulation)
        if (existingChunk != null) {
            removeEntitiesFromChunk(level, pos);
            existingChunk.setUnsaved(false);
        }

        // Clear block data via ChunkStorage API
        boolean success = false;
        try {
            clearChunkViaChunkStorage(level, pos);
            success = true;
        } catch (Exception e) {
            LOGGER.warn("ChunkStorage API failed for chunk {}, falling back to MCA: {}", pos, e.getMessage());
        }

        if (!success) {
            try {
                clearChunkFromMcaFile(level, pos);
                success = true;
            } catch (Exception e2) {
                LOGGER.error("Both ChunkStorage and MCA methods failed for chunk {}: {}", pos, e2.getMessage());
                return false;
            }
        }

        // Clear entity data on disk via proper API
        clearEntityDataViaStorage(level, pos);

        ChunkLoadTracker.get(level).removeRecord(pos);
        LOGGER.info("Chunk {} — will regenerate on next load", pos);
        return true;
    }

    private static void removeEntitiesFromChunk(ServerLevel level, ChunkPos pos) {
        int minX = pos.getMinBlockX(), maxX = pos.getMaxBlockX();
        int minZ = pos.getMinBlockZ(), maxZ = pos.getMaxBlockZ();
        var box = new net.minecraft.world.phys.AABB(minX, level.getMinBuildHeight(), minZ,
                maxX + 1, level.getMaxBuildHeight(), maxZ + 1);
        var entities = level.getEntities((net.minecraft.world.entity.Entity) null, box, e -> true);
        int removed = 0;
        for (var entity : entities) {
            if (!(entity instanceof net.minecraft.world.entity.player.Player)) {
                entity.discard();
                removed++;
            }
        }
        LOGGER.info("Chunk {}: {} total {} removed", pos, entities.size(), removed);
    }

    /**
     * Write an empty chunk through ChunkStorage API (ChunkMap extends ChunkStorage).
     * This is the proper way — updates both MCA file and RegionFile in-memory cache.
     */
    private static void clearChunkViaChunkStorage(ServerLevel level, ChunkPos pos) throws Exception {
        var chunkMap = level.getChunkSource().chunkMap;

        // ChunkMap extends ChunkStorage — get read(ChunkPos) method
        // In 1.21.1, read() returns CompletableFuture<Optional<CompoundTag>>
        Method readMethod = findMethod(ChunkStorage.class, "read", ChunkPos.class);

        @SuppressWarnings("unchecked")
        CompletableFuture<Optional<CompoundTag>> future =
            (CompletableFuture<Optional<CompoundTag>>) readMethod.invoke(chunkMap, pos);
        Optional<CompoundTag> optional = future.get(5, java.util.concurrent.TimeUnit.SECONDS);

        CompoundTag tag;
        if (optional.isEmpty()) {
            LOGGER.info("Chunk {} has no data on disk — creating fresh empty tag", pos);
            tag = createEmptyChunkTag(pos, level);
        } else {
            tag = optional.get();
            // Clear all block/entity/light data from existing tag
            tag.remove("sections");
            tag.remove("block_entities");
            tag.remove("block_ticks");
            tag.remove("entities");
            tag.remove("fluid_ticks");
            tag.remove("post_processing");
            tag.remove("carving_masks");
            tag.remove("Heightmaps");
            tag.remove("isLightOn");
            tag.remove("below_zero_retrogen");
            tag.remove("blending_data");
            tag.putString("Status", "minecraft:empty");
            tag.put("sections", new ListTag());
        }

        // Get write(ChunkPos, CompoundTag) method and write
        Method writeMethod = findMethod(ChunkStorage.class, "write", ChunkPos.class, CompoundTag.class);
        writeMethod.invoke(chunkMap, pos, tag);

        LOGGER.info("Written empty-status chunk {} via ChunkStorage API", pos);
    }

    private static CompoundTag createEmptyChunkTag(ChunkPos pos, ServerLevel level) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("xPos", pos.x);
        tag.putInt("zPos", pos.z);
        tag.putInt("yPos", level.getMinBuildHeight());
        tag.putString("Status", "minecraft:empty");
        tag.put("sections", new ListTag());
        tag.put("block_entities", new ListTag());
        tag.put("entities", new ListTag());
        try {
            // Try to get current data version
            var mcVersion = Class.forName("net.minecraft.SharedConstants");
            var versionGetter = mcVersion.getMethod("getCurrentVersion");
            Object version = versionGetter.invoke(null);
            var dvGetter = version.getClass().getMethod("getDataVersion");
            int dataVersion = (int) dvGetter.invoke(version);
            tag.putInt("DataVersion", dataVersion);
        } catch (Exception e) {
            tag.putInt("DataVersion", 3953); // 1.21.1 default
        }
        return tag;
    }

    private static Method findMethod(Class<?> clazz, String name, Class<?>... paramTypes) throws NoSuchMethodException {
        try {
            Method m = clazz.getMethod(name, paramTypes);
            m.setAccessible(true);
            return m;
        } catch (NoSuchMethodException e) {
            Method m = clazz.getDeclaredMethod(name, paramTypes);
            m.setAccessible(true);
            return m;
        }
    }

    // ========== Fallback: direct MCA file manipulation ==========

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

            file.seek(chunkIndex * 4);
            file.write(new byte[]{0, 0, 0, 0});
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

    /**
     * Clear entity data for a chunk via the proper EntityPersistentStorage API.
     *
     * Uses PersistentEntitySectionManager.permanentStorage.storeEntities()
     * to write an empty entity list. This goes through IOWorker -> RegionFile,
     * properly updating both the MCA file AND the RegionFile's in-memory cache.
     */
    private static void clearEntityDataViaStorage(ServerLevel level, ChunkPos pos) {
        try {
            // Get entityManager from ServerLevel
            var emField = ServerLevel.class.getDeclaredField("entityManager");
            emField.setAccessible(true);
            Object em = emField.get(level);

            // Get permanentStorage field from PersistentEntitySectionManager
            var storageField = em.getClass().getDeclaredField("permanentStorage");
            storageField.setAccessible(true);
            Object storage = storageField.get(em);

            if (storage == null) return;

            // Create empty ChunkEntities<Entity>(pos, List.of())
            Class<?> chunkEntitiesClass = Class.forName("net.minecraft.world.level.entity.ChunkEntities");
            var constructor = chunkEntitiesClass.getConstructor(ChunkPos.class, List.class);
            Object emptyEntities = constructor.newInstance(pos, List.of());

            // Call EntityPersistentStorage.storeEntities(ChunkEntities)
            var storeMethod = storage.getClass().getMethod("storeEntities", chunkEntitiesClass);
            storeMethod.invoke(storage, emptyEntities);

            LOGGER.info("Cleared entity data for chunk {} via EntityPersistentStorage", pos);
        } catch (Exception e) {
            LOGGER.warn("Failed to clear entity data via storage for chunk {}: {}", pos, e.getMessage());
        }
    }

    /**
     * Called after ALL chunks in a batch are processed. Saves empty entity data
     * and flushes the entity storage to disk.
     */
    public static void flushEntityData(ServerLevel level) {
        try {
            var emField = ServerLevel.class.getDeclaredField("entityManager");
            emField.setAccessible(true);
            Object em = emField.get(level);

            // Save all loaded entity sections
            em.getClass().getMethod("saveAll").invoke(em);

            // Also flush the permanent storage to ensure writes are on disk
            var storageField = em.getClass().getDeclaredField("permanentStorage");
            storageField.setAccessible(true);
            Object storage = storageField.get(em);
            if (storage != null) {
                var flushMethod = storage.getClass().getMethod("flush", boolean.class);
                flushMethod.invoke(storage, true);
            }
        } catch (Exception e) {
            LOGGER.warn("Entity flush failed: {}", e.getMessage());
        }
    }

}
