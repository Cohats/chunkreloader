package com.chunkreloader.manager;

import com.chunkreloader.config.Config;
import com.chunkreloader.util.AreaParser;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ProtectedChunkManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("ChunkReloaderProtection");

    // Detection flag (lazy-loaded)
    private static Boolean opacLoaded = null;

    /**
     * Check if a chunk is protected (should not be regenerated).
     */
    public static boolean isProtected(ServerLevel level, ChunkPos pos) {
        // 1. Config-based protection area
        if (isInProtectArea(level, pos)) {
            return true;
        }

        // 2. Open Parties and Claims (开放领地)
        if (isOpacLoaded() && isOpacClaim(level, pos)) {
            return true;
        }

        return false;
    }

    // ---- Config-based protection ----

    private static boolean isInProtectArea(ServerLevel level, ChunkPos pos) {
        String areaStr = Config.getInstance().getProtectArea(level);
        if (areaStr == null || areaStr.isEmpty()) {
            return false;
        }

        var areaOpt = AreaParser.parse(areaStr);
        if (areaOpt.isEmpty()) return false;

        var area = areaOpt.get();
        return area.containsChunk(pos);
    }

    // ---- Open Parties and Claims (OPAC) ----

    private static boolean isOpacLoaded() {
        if (opacLoaded == null) {
            try {
                Class.forName("xaero.pac.common.server.api.OpenPACServerAPI");
                opacLoaded = true;
                LOGGER.info("Open Parties and Claims detected - protected claims will be respected");
            } catch (ClassNotFoundException e) {
                opacLoaded = false;
            }
        }
        return opacLoaded;
    }

    @SuppressWarnings("unchecked")
    private static boolean isOpacClaim(ServerLevel level, ChunkPos pos) {
        try {
            Class<?> serverApiClass = Class.forName("xaero.pac.common.server.api.OpenPACServerAPI");
            Object serverApi = serverApiClass.getMethod("get", net.minecraft.server.MinecraftServer.class)
                    .invoke(null, level.getServer());
            Object claimsManager = serverApi.getClass().getMethod("getServerClaimsManager").invoke(serverApi);

            // IServerClaimsManagerAPI.get(ResourceLocation, int, int) — uses chunk coords
            Object claim = claimsManager.getClass()
                    .getMethod("get", net.minecraft.resources.ResourceLocation.class, int.class, int.class)
                    .invoke(claimsManager, level.dimension().location(), pos.x, pos.z);
            return claim != null;
        } catch (Exception e) {
            return false;
        }
    }
}
