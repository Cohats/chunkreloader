package com.chunkreloader.handler;

import com.chunkreloader.config.Config;
import com.chunkreloader.manager.ChunkLoadTracker;
import com.chunkreloader.manager.ProtectedChunkManager;
import com.chunkreloader.util.AreaParser;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

public class ChunkLoadHandler {

    public static void onChunkLoad(ChunkLoadTracker tracker, ServerLevel level, ChunkPos pos) {
        // 检查是否在非记录区域中
        if (isInNonRecordArea(level, pos)) {
            return;
        }

        // 检查是否在保护区域中
        if (ProtectedChunkManager.isProtected(level, pos)) {
            return;
        }

        tracker.recordLoad(pos);
    }

    private static boolean isInNonRecordArea(ServerLevel level, ChunkPos pos) {
        String areaStr = Config.getInstance().getNonRecordArea(level);
        if (areaStr == null || areaStr.isEmpty()) {
            return false;
        }

        var areaOpt = AreaParser.parse(areaStr);
        if (areaOpt.isEmpty()) return false;

        var area = areaOpt.get();
        return area.containsChunk(pos);
    }
}
