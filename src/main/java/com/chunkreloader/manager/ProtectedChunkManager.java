package com.chunkreloader.manager;

import com.chunkreloader.config.Config;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ProtectedChunkManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("ChunkReloaderProtection");

    // Detection flags (lazy-loaded)
    private static Boolean griefDefenderLoaded = null;
    private static Boolean ftbChunksLoaded = null;
    private static Boolean opacLoaded = null;

    /**
     * Check if a chunk is protected (should not be regenerated).
     */
    public static boolean isProtected(ServerLevel level, ChunkPos pos) {
        // 1. Config-based protection area
        if (isInProtectArea(pos)) {
            return true;
        }

        // 2. GriefDefender
        if (isGriefDefenderLoaded() && isGriefDefenderClaim(level, pos)) {
            return true;
        }

        // 3. FTB Chunks
        if (isFtbChunksLoaded() && isFtbClaim(level, pos)) {
            return true;
        }

        // 4. Open Parties and Claims (开放领地)
        if (isOpacLoaded() && isOpacClaim(level, pos)) {
            return true;
        }

        return false;
    }

    // ---- Config-based protection ----

    private static boolean isInProtectArea(ChunkPos pos) {
        String areaStr = Config.getInstance().protectArea.get();
        if (areaStr == null || areaStr.isEmpty()) {
            return false;
        }

        try {
            String[] parts = areaStr.split(",");
            if (parts.length != 4) return false;

            int bx1 = Integer.parseInt(parts[0].trim());
            int bz1 = Integer.parseInt(parts[1].trim());
            int bx2 = Integer.parseInt(parts[2].trim());
            int bz2 = Integer.parseInt(parts[3].trim());

            int cx1 = bx1 >> 4;
            int cz1 = bz1 >> 4;
            int cx2 = bx2 >> 4;
            int cz2 = bz2 >> 4;

            int minX = Math.min(cx1, cx2);
            int maxX = Math.max(cx1, cx2);
            int minZ = Math.min(cz1, cz2);
            int maxZ = Math.max(cz1, cz2);

            return pos.x >= minX && pos.x <= maxX && pos.z >= minZ && pos.z <= maxZ;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    // ---- GriefDefender ----

    private static boolean isGriefDefenderLoaded() {
        if (griefDefenderLoaded == null) {
            try {
                Class.forName("com.griefdefender.api.GriefDefender");
                griefDefenderLoaded = true;
                LOGGER.info("GriefDefender detected - protected claims will be respected");
            } catch (ClassNotFoundException e) {
                griefDefenderLoaded = false;
            }
        }
        return griefDefenderLoaded;
    }

    @SuppressWarnings("unchecked")
    private static boolean isGriefDefenderClaim(ServerLevel level, ChunkPos pos) {
        try {
            Class<?> gdApi = Class.forName("com.griefdefender.api.GriefDefender");
            Object core = gdApi.getMethod("getCore").invoke(null);
            Object claimManager = core.getClass()
                    .getMethod("getClaimManager", java.util.UUID.class)
                    .invoke(core, level.dimension().location().toString());
            Object claim = claimManager.getClass()
                    .getMethod("getClaimAt", int.class, int.class)
                    .invoke(claimManager, pos.getMinBlockX(), pos.getMinBlockZ());
            return claim != null;
        } catch (Exception e) {
            return false;
        }
    }

    // ---- FTB Chunks ----

    private static boolean isFtbChunksLoaded() {
        if (ftbChunksLoaded == null) {
            try {
                Class.forName("dev.ftb.mods.ftbchunks.api.FTBChunksAPI");
                ftbChunksLoaded = true;
                LOGGER.info("FTB Chunks detected - protected claims will be respected");
            } catch (ClassNotFoundException e) {
                ftbChunksLoaded = false;
            }
        }
        return ftbChunksLoaded;
    }

    @SuppressWarnings("unchecked")
    private static boolean isFtbClaim(ServerLevel level, ChunkPos pos) {
        try {
            Class<?> apiClass = Class.forName("dev.ftb.mods.ftbchunks.api.FTBChunksAPI");

            // Try v2 API: FTBChunksAPI.manager()
            try {
                // Get the API manager
                Object manager = apiClass.getMethod("manager").invoke(null);

                // Try: manager.getChunk(ServerLevel, BlockPos)
                try {
                    Object chunk = manager.getClass()
                            .getMethod("getChunk", ServerLevel.class, net.minecraft.core.BlockPos.class)
                            .invoke(manager, level, pos.getWorldPosition());
                    if (chunk != null) {
                        // Check if chunk has a team (claimed)
                        Object team = chunk.getClass().getMethod("getTeam").invoke(chunk);
                        return team != null;
                    }
                } catch (NoSuchMethodException ignored) {}

                // Try: manager.isClaimed(ServerLevel, BlockPos)
                try {
                    Object result = manager.getClass()
                            .getMethod("isClaimed", ServerLevel.class, net.minecraft.core.BlockPos.class)
                            .invoke(manager, level, pos.getWorldPosition());
                    if (result instanceof Boolean b) return b;
                } catch (NoSuchMethodException ignored) {}

                // Try: manager.getClaimAt(ServerLevel, ChunkPos)
                try {
                    Object claim = manager.getClass()
                            .getMethod("getClaimAt", ServerLevel.class, ChunkPos.class)
                            .invoke(manager, level, pos);
                    return claim != null;
                } catch (NoSuchMethodException ignored) {}

            } catch (NoSuchMethodException ignored) {}

            // Try legacy API: FTBChunksAPI.api()
            try {
                Object api = apiClass.getMethod("api").invoke(null);
                Object manager = api.getClass().getMethod("getManager").invoke(api);
                Object chunkData = manager.getClass()
                        .getMethod("getChunk", ServerLevel.class, net.minecraft.core.BlockPos.class)
                        .invoke(manager, level, pos.getWorldPosition());
                if (chunkData != null) {
                    Object team = chunkData.getClass().getMethod("getTeam").invoke(chunkData);
                    return team != null;
                }
            } catch (NoSuchMethodException ignored) {}

            return false;
        } catch (Exception e) {
            return false;
        }
    }

    // ---- Open Parties and Claims (OPAC) ----

    private static boolean isOpacLoaded() {
        if (opacLoaded == null) {
            try {
                // Try multiple possible OPAC main classes
                boolean found = false;
                for (String clz : new String[]{
                        "xaero.pac.common.server.api.OpenPACServerAPI",
                        "xaero.pac.OpenPartiesAndClaims",
                        "xaero.pac.common.claims.api.IClaimManager"
                }) {
                    try {
                        Class.forName(clz);
                        found = true;
                        break;
                    } catch (ClassNotFoundException ignored) {}
                }
                opacLoaded = found;
                if (found) {
                    LOGGER.info("Open Parties and Claims detected - protected claims will be respected");
                }
            } catch (Exception e) {
                opacLoaded = false;
            }
        }
        return opacLoaded;
    }

    @SuppressWarnings("unchecked")
    private static boolean isOpacClaim(ServerLevel level, ChunkPos pos) {
        try {
            // Try approach 1: OpenPACServerAPI
            try {
                Class<?> serverApiClass = Class.forName("xaero.pac.common.server.api.OpenPACServerAPI");
                Object serverApi = serverApiClass.getMethod("get", net.minecraft.server.MinecraftServer.class)
                        .invoke(null, level.getServer());
                Object claimsManager = serverApi.getClass().getMethod("getServerClaimsManager").invoke(serverApi);
                Object claim = claimsManager.getClass()
                        .getMethod("getClaim", int.class, int.class, net.minecraft.resources.ResourceKey.class)
                        .invoke(claimsManager, pos.x, pos.z, level.dimension());
                return claim != null;
            } catch (Exception ignored) {}

            // Try approach 2: static ClaimManager
            try {
                Class<?> claimManagerClass = Class.forName("xaero.pac.common.claims.api.IClaimManager");
                // Try to find the manager instance
                Class<?> pacClass = Class.forName("xaero.pac.OpenPartiesAndClaims");
                Object instance = pacClass.getMethod("getInstance").invoke(null);
                Object manager = instance.getClass().getMethod("getClaimManager").invoke(instance);
                Object claim = manager.getClass()
                        .getMethod("getClaim", int.class, int.class, net.minecraft.resources.ResourceKey.class)
                        .invoke(manager, pos.x, pos.z, level.dimension());
                return claim != null;
            } catch (Exception ignored) {}

            // Try approach 3: direct claim check via chunk pos
            try {
                Class<?> utilClass = Class.forName("xaero.pac.common.claims.player.IPlayerClaimManager");
                Object manager = utilClass.getMethod("get", ServerLevel.class)
                        .invoke(null, level);
                if (manager != null) {
                    Object claim = manager.getClass()
                            .getMethod("getClaim", int.class, int.class)
                            .invoke(manager, pos.x, pos.z);
                    return claim != null;
                }
            } catch (Exception ignored) {}

            return false;
        } catch (Exception e) {
            return false;
        }
    }
}
