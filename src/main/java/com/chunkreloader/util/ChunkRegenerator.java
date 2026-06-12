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

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public class ChunkRegenerator {
    private static final Logger LOGGER = ChunkReloaderMod.LOGGER;

    // Tracks which regions (packed regionX,regionZ) were modified during a batch
    private static final java.util.Set<Long> AFFECTED_REGIONS = new java.util.HashSet<>();

    private static long packRegionKey(int rx, int rz) {
        return ((long) rx << 32) | (rz & 0xFFFFFFFFL);
    }

    /**
     * Mark a region as affected (needs MCA compaction after batch completes).
     */
    public static void trackRegion(ChunkPos pos) {
        AFFECTED_REGIONS.add(packRegionKey(Math.floorDiv(pos.x, 32), Math.floorDiv(pos.z, 32)));
    }

    /**
     * Compact all affected MCA files (both block and entity data), then clear the tracking set.
     * Returns long[]{sizeBefore, sizeAfter, saved}.
     */
    public static long[] compactAffectedRegions(ServerLevel level) {
        if (AFFECTED_REGIONS.isEmpty()) return new long[]{0, 0, 0};

        LOGGER.info("Compacting {} affected region files...", AFFECTED_REGIONS.size());

        // Compute total size before compaction
        Path regionDir = findRegionDir(level);
        Path entityDir = findEntityDir(level);
        long sizeBefore = totalRegionSize(AFFECTED_REGIONS, regionDir, entityDir);

        // Flush pending writes first
        try {
            var chunkMap = level.getChunkSource().chunkMap;
            chunkMap.getClass().getMethod("flushWorker").invoke(chunkMap);
        } catch (Exception e) {
            LOGGER.warn("Flush chunk worker failed: {}", e.getMessage());
        }

        // Compact block and entity region files
        for (long key : AFFECTED_REGIONS) {
            int rx = (int) (key >> 32);
            int rz = (int) (long) key;
            compactSingleRegion(level, rx, rz, regionDir, entityDir);
        }

        // Compute total size after compaction
        long sizeAfter = totalRegionSize(AFFECTED_REGIONS, regionDir, entityDir);
        long saved = sizeBefore - sizeAfter;

        int pct = sizeBefore > 0 ? (int) (saved * 100 / sizeBefore) : 0;
        LOGGER.info("Compaction done: {} -> {}, freed {} ({}%)",
                formatSize(sizeBefore), formatSize(sizeAfter), formatSize(saved), pct);

        AFFECTED_REGIONS.clear();
        return new long[]{sizeBefore, sizeAfter, saved};
    }

    private static long totalRegionSize(java.util.Set<Long> regions, Path regionDir, Path entityDir) {
        long total = 0;
        for (long key : regions) {
            int rx = (int) (key >> 32);
            int rz = (int) key;
            try {
                if (regionDir != null) {
                    Path f = regionDir.resolve("r." + rx + "." + rz + ".mca");
                    if (java.nio.file.Files.exists(f)) total += java.nio.file.Files.size(f);
                }
                if (entityDir != null) {
                    Path f = entityDir.resolve("r." + rx + "." + rz + ".mca");
                    if (java.nio.file.Files.exists(f)) total += java.nio.file.Files.size(f);
                }
            } catch (java.io.IOException ignored) {}
        }
        return total;
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + "B";
        if (bytes < 1024 * 1024) return String.format("%.1fKB", bytes / 1024.0);
        return String.format("%.1fMB", bytes / (1024.0 * 1024.0));
    }

    private static void compactSingleRegion(ServerLevel level, int rx, int rz, Path regionDir, Path entityDir) {
        // Compact block MCA
        if (regionDir != null) {
            Path mca = regionDir.resolve("r." + rx + "." + rz + ".mca");
            if (java.nio.file.Files.exists(mca)) {
                try {
                    compactMCAFile(level, mca, false);
                } catch (Exception e) {
                    LOGGER.debug("Skipped block region r.{}.{}.mca: {}", rx, rz, e.getMessage());
                }
            }
        }
        // Compact entity MCA
        if (entityDir != null) {
            Path mca = entityDir.resolve("r." + rx + "." + rz + ".mca");
            if (java.nio.file.Files.exists(mca)) {
                try {
                    compactMCAFile(level, mca, true);
                } catch (Exception e) {
                    LOGGER.debug("Skipped entity region r.{}.{}.mca: {}", rx, rz, e.getMessage());
                }
            }
        }
    }

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

        // Track which region file was modified for later MCA compaction
        trackRegion(pos);

        // Only touch in-memory entities if the chunk is already loaded (don't force-load it)
        LevelChunk existingChunk = level.getChunkSource().getChunkNow(pos.x, pos.z);
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

    private static void compactMCAFile(ServerLevel level, Path mcaPath, boolean isEntity) throws Exception {
        String name = mcaPath.getFileName().toString();
        String[] parts = name.replace("r.", "").replace(".mca", "").split("\\.");
        int regionX = Integer.parseInt(parts[0]);
        int regionZ = Integer.parseInt(parts[1]);

        // Read all protected chunks from the MCA file
        java.util.List<CompactEntry> toKeep = new java.util.ArrayList<>();
        long oldSize;

        try (java.io.RandomAccessFile file = new java.io.RandomAccessFile(mcaPath.toFile(), "r")) {
            oldSize = file.length();
            byte[] header = new byte[4096];
            file.readFully(header);
            byte[] timestampData = new byte[4096];
            file.readFully(timestampData);

            for (int i = 0; i < 1024; i++) {
                int off = i * 4;
                int sectorOff = ((header[off] & 0xFF) << 16) | ((header[off + 1] & 0xFF) << 8) | (header[off + 2] & 0xFF);
                int sectorCnt = header[off + 3] & 0xFF;
                if (sectorOff == 0 || sectorCnt == 0) continue;

                int localX = i & 31;
                int localZ = i >> 5;
                ChunkPos pos = new ChunkPos(regionX * 32 + localX, regionZ * 32 + localZ);

                // Skip non-protected chunks
                if (!ProtectedChunkManager.isProtected(level, pos)) continue;

                // Read raw sector data
                int readLen = sectorCnt * 4096;
                byte[] raw = new byte[readLen];
                file.seek(sectorOff * 4096L);
                file.readFully(raw);

                // Determine actual payload length from the 4-byte header
                int payloadLen = ((raw[0] & 0xFF) << 24) | ((raw[1] & 0xFF) << 16)
                               | ((raw[2] & 0xFF) << 8) | (raw[3] & 0xFF);
                int actualBytes = Math.min(4 + payloadLen, readLen);

                // Trim to actual data
                byte[] data = new byte[actualBytes];
                System.arraycopy(raw, 0, data, 0, actualBytes);

                int timestamp = ((timestampData[off] & 0xFF) << 24) | ((timestampData[off + 1] & 0xFF) << 16)
                              | ((timestampData[off + 2] & 0xFF) << 8) | (timestampData[off + 3] & 0xFF);

                toKeep.add(new CompactEntry(i, data, timestamp));
            }
        }

        if (toKeep.isEmpty()) {
            LOGGER.info("No protected chunks in {}, writing empty header", name);
        }

        // Write new compacted MCA file
        Path tmpFile = mcaPath.resolveSibling(name + ".compacting");
        writeCompactedMCA(tmpFile, toKeep);

        // Invalidate RegionFile cache so the old file handle is released
        if (isEntity) {
            invalidateEntityRegionFileCache(level, regionX, regionZ);
        } else {
            var chunkMap = level.getChunkSource().chunkMap;
            invalidateRegionFileCache(chunkMap, regionX, regionZ);
        }

        // Replace old file with compacted one
        try {
            java.nio.file.Files.move(tmpFile, mcaPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            long saved = oldSize - java.nio.file.Files.size(mcaPath);
            LOGGER.info("Compacted {} (freed {} bytes)", name, saved);
        } catch (Exception e) {
            LOGGER.warn("Failed to replace {}: {}", mcaPath, e.getMessage());
            java.nio.file.Files.deleteIfExists(tmpFile);
        }
    }

    private record CompactEntry(int index, byte[] data, int timestamp) {}

    private static void writeCompactedMCA(Path path, java.util.List<CompactEntry> entries) throws IOException {
        int[] offsets = new int[1024];
        int[] sizes = new int[1024];
        int[] timestamps = new int[1024];

        // Build new file: header (8KB) + data
        // Calculate sector positions
        int nextSector = 2; // sectors 0-1 are header/timestamps

        // First pass: compute sizes and assign sectors
        java.util.List<byte[]> dataBlocks = new java.util.ArrayList<>();
        for (CompactEntry entry : entries) {
            int needed = (entry.data.length + 4095) / 4096; // round up to sectors
            offsets[entry.index] = nextSector;
            sizes[entry.index] = needed;
            timestamps[entry.index] = entry.timestamp;
            nextSector += needed;
            dataBlocks.add(entry.data);
        }

        // Write file
        try (java.io.RandomAccessFile out = new java.io.RandomAccessFile(path.toFile(), "rw")) {
            // Allocate header + timestamps
            out.setLength(nextSector * 4096L);

            // Write header entries
            out.seek(0);
            for (int i = 0; i < 1024; i++) {
                int off = offsets[i];
                int sz = sizes[i];
                if (off == 0) {
                    out.write(new byte[]{0, 0, 0, 0});
                } else {
                    out.write(new byte[]{
                            (byte) ((off >> 16) & 0xFF),
                            (byte) ((off >> 8) & 0xFF),
                            (byte) (off & 0xFF),
                            (byte) sz
                    });
                }
            }

            // Write timestamps
            for (int i = 0; i < 1024; i++) {
                out.writeInt(timestamps[i]);
            }

            // Write chunk data
            int dataIdx = 0;
            for (CompactEntry entry : entries) {
                byte[] data = dataBlocks.get(dataIdx++);
                long pos = offsets[entry.index] * 4096L;
                out.seek(pos);
                out.write(data);
                // Pad to sector boundary
                int pad = 4096 - (data.length % 4096);
                if (pad != 4096) {
                    out.write(new byte[pad]);
                }
            }
        }
    }

    /**
     * Evict a specific RegionFile from the ChunkStorage's IOWorker cache.
     * This closes the cached RegionFile so we can safely replace the MCA file.
     */
    private static void invalidateRegionFileCache(Object chunkMap, int regionX, int regionZ) {
        try {
            var workerField = ChunkStorage.class.getDeclaredField("worker");
            workerField.setAccessible(true);
            Object worker = workerField.get(chunkMap);
            if (worker == null) return;

            var storageField = worker.getClass().getDeclaredField("storage");
            storageField.setAccessible(true);
            Object rfs = storageField.get(worker);
            if (rfs == null) return;

            var cacheField = rfs.getClass().getDeclaredField("regionCache");
            cacheField.setAccessible(true);
            Object cache = cacheField.get(rfs);
            if (cache == null) return;

            long key = ChunkPos.asLong(regionX, regionZ);
            var removeMethod = cache.getClass().getMethod("remove", long.class);
            Object regionFile = removeMethod.invoke(cache, key);

            if (regionFile != null) {
                regionFile.getClass().getMethod("close").invoke(regionFile);
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to evict RegionFile for r.{}.{}.mca: {}", regionX, regionZ, e.getMessage());
        }
    }

    /**
     * Evict a specific RegionFile from the entity storage's IOWorker cache.
     */
    private static void invalidateEntityRegionFileCache(ServerLevel level, int regionX, int regionZ) {
        try {
            var emField = ServerLevel.class.getDeclaredField("entityManager");
            emField.setAccessible(true);
            Object em = emField.get(level);

            var permField = em.getClass().getDeclaredField("permanentStorage");
            permField.setAccessible(true);
            Object perm = permField.get(em);
            if (perm == null) return;

            // EntityStorage may have a field named 'storage' or 'simpleRegionStorage'
            // We need to find the IOWorker to access the RegionFile cache
            Object ioworker = findEntityWorker(perm);
            if (ioworker == null) return;

            var storageField = ioworker.getClass().getDeclaredField("storage");
            storageField.setAccessible(true);
            Object rfs = storageField.get(ioworker);
            if (rfs == null) return;

            var cacheField = rfs.getClass().getDeclaredField("regionCache");
            cacheField.setAccessible(true);
            Object cache = cacheField.get(rfs);
            if (cache == null) return;

            long key = ChunkPos.asLong(regionX, regionZ);
            var removeMethod = cache.getClass().getMethod("remove", long.class);
            Object regionFile = removeMethod.invoke(cache, key);

            if (regionFile != null) {
                regionFile.getClass().getMethod("close").invoke(regionFile);
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to evict entity RegionFile for r.{}.{}.mca: {}", regionX, regionZ, e.getMessage());
        }
    }

    /**
     * Find the IOWorker from an EntityPersistentStorage implementation.
     * Tries accessing known field patterns.
     */
    private static Object findEntityWorker(Object entityStorage) {
        // EntityStorage wraps SimpleRegionStorage which has an IOWorker
        // Try common field names for the SimpleRegionStorage
        for (String fieldName : new String[]{"storage", "simpleRegionStorage", "regionStorage"}) {
            try {
                var f = entityStorage.getClass().getDeclaredField(fieldName);
                f.setAccessible(true);
                Object sr = f.get(entityStorage);
                if (sr != null) {
                    try {
                        var wf = sr.getClass().getDeclaredField("worker");
                        wf.setAccessible(true);
                        return wf.get(sr);
                    } catch (Exception ignored) {}
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    private static Path findRegionDir(ServerLevel level) {
        // Same logic as findRegionDir in ChunkReloaderCommand
        try {
            var server = level.getServer();
            var lrClass = Class.forName("net.minecraft.world.level.storage.LevelResource");
            var root = lrClass.getField("LEVEL_ROOT").get(null);
            var getPath = server.getClass().getMethod("getWorldPath", lrClass);
            java.nio.file.Path wp = (java.nio.file.Path) getPath.invoke(server, root);

            var dimPath = level.dimension().location().getPath();
            Path dir = switch (dimPath) {
                case "overworld" -> wp.resolve("region");
                case "the_nether" -> wp.resolve("DIM-1").resolve("region");
                case "the_end" -> wp.resolve("DIM1").resolve("region");
                default -> wp.resolve(dimPath).resolve("region");
            };
            if (java.nio.file.Files.isDirectory(dir)) return dir;
        } catch (Exception ignored) {}
        return null;
    }

    private static Path findEntityDir(ServerLevel level) {
        try {
            var server = level.getServer();
            var lrClass = Class.forName("net.minecraft.world.level.storage.LevelResource");
            var root = lrClass.getField("LEVEL_ROOT").get(null);
            var getPath = server.getClass().getMethod("getWorldPath", lrClass);
            java.nio.file.Path wp = (java.nio.file.Path) getPath.invoke(server, root);

            var dimPath = level.dimension().location().getPath();
            Path dir = switch (dimPath) {
                case "overworld" -> wp.resolve("entities");
                case "the_nether" -> wp.resolve("DIM-1").resolve("entities");
                case "the_end" -> wp.resolve("DIM1").resolve("entities");
                default -> wp.resolve(dimPath).resolve("entities");
            };
            if (java.nio.file.Files.isDirectory(dir)) return dir;
        } catch (Exception ignored) {}
        return null;
    }
}
