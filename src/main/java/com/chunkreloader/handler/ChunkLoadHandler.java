package com.chunkreloader.handler;

import com.chunkreloader.config.Config;
import com.chunkreloader.manager.ChunkLoadTracker;
import com.chunkreloader.manager.ProtectedChunkManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

public class ChunkLoadHandler {

    public static void onChunkLoad(ChunkLoadTracker tracker, ServerLevel level, ChunkPos pos) {
        if (!Config.getInstance().enableAutoReload.get()) {
            return;
        }

        // Check if chunk is in the non-record area
        if (isInNonRecordArea(pos)) {
            return;
        }

        // Check if chunk is protected
        if (ProtectedChunkManager.isProtected(level, pos)) {
            return;
        }

        tracker.recordLoad(pos);
    }

    private static boolean isInNonRecordArea(ChunkPos pos) {
        String areaStr = Config.getInstance().nonRecordArea.get();
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

            // Convert block coords to chunk coords
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
}
