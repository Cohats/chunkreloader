package com.chunkreloader.manager;

import com.chunkreloader.config.Config;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;

public class ProtectedChunkManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("ChunkReloaderProtection");
    private static Boolean griefDefenderLoaded = null;

    /**
     * Check if a chunk is protected (should not be regenerated).
     */
    public static boolean isProtected(ServerLevel level, ChunkPos pos) {
        // 1. Check config-based protection area
        if (isInProtectArea(pos)) {
            return true;
        }

        // 2. Check GriefDefender (soft dependency)
        if (isGriefDefenderLoaded() && isGriefDefenderClaim(level, pos)) {
            return true;
        }

        return false;
    }

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
            // Use reflection for soft dependency
            Class<?> gdApi = Class.forName("com.griefdefender.api.GriefDefender");
            Class<?> claimManagerClass = Class.forName("com.griefdefender.api.claim.ClaimManager");

            // Get the core instance
            Object core = gdApi.getMethod("getCore").invoke(null);

            // Get the claim manager for this world
            Object claimManager = core.getClass()
                    .getMethod("getClaimManager", java.util.UUID.class)
                    .invoke(core, level.dimension().location().toString());

            // Check if there's a claim at the chunk position
            Object claim = claimManager.getClass()
                    .getMethod("getClaimAt", int.class, int.class)
                    .invoke(claimManager, pos.getMinBlockX(), pos.getMinBlockZ());

            return claim != null;
        } catch (Exception e) {
            return false;
        }
    }
}
