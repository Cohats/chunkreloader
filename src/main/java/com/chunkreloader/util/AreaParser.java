package com.chunkreloader.util;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.Optional;

/**
 * 解析带世界名称的区域配置字符串
 * 格式: [world:]<x1>,<z1>,<x2>,<z2>
 * 例: "overworld:-50000,-50000,50000,50000" 或 "-50000,-50000,50000,50000"(旧格式, 所有世界)
 */
public class AreaParser {

    public record AreaInfo(@Nullable String worldName, int x1, int z1, int x2, int z2) {
        public boolean isGlobal() { return worldName == null; }

        public boolean matchesWorld(ServerLevel level) {
            if (worldName == null) return true;
            ResourceKey<Level> key = level.dimension();
            String fullName = key.location().toString();
            String shortName = key.location().getPath();
            return fullName.equals(worldName) || shortName.equals(worldName);
        }

        /**
         * 检查区块是否在区域内。
         * 区域值为方块坐标，自动转换为区块坐标进行比较。
         */
        public boolean containsChunk(ChunkPos pos) {
            int minX = Math.min(x1, x2) >> 4;
            int maxX = Math.max(x1, x2) >> 4;
            int minZ = Math.min(z1, z2) >> 4;
            int maxZ = Math.max(z1, z2) >> 4;
            return pos.x >= minX && pos.x <= maxX && pos.z >= minZ && pos.z <= maxZ;
        }
    }

    /**
     * 解析区域字符串
     */
    public static Optional<AreaInfo> parse(String areaStr) {
        if (areaStr == null || areaStr.isEmpty()) return Optional.empty();

        try {
            String worldName = null;
            String coords;

            if (areaStr.contains(":")) {
                int colonIdx = areaStr.indexOf(':');
                worldName = areaStr.substring(0, colonIdx);
                coords = areaStr.substring(colonIdx + 1);
            } else {
                coords = areaStr;
            }

            String[] parts = coords.split(",");
            if (parts.length != 4) return Optional.empty();

            int x1 = Integer.parseInt(parts[0].trim());
            int z1 = Integer.parseInt(parts[1].trim());
            int x2 = Integer.parseInt(parts[2].trim());
            int z2 = Integer.parseInt(parts[3].trim());

            return Optional.of(new AreaInfo(
                    worldName != null && !worldName.isEmpty() ? worldName : null,
                    x1, z1, x2, z2
            ));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /**
     * 通过世界名称获取 ServerLevel
     */
    @Nullable
    public static ServerLevel getWorldByName(MinecraftServer server, String worldName) {
        for (ResourceKey<Level> key : server.levelKeys()) {
            String fullName = key.location().toString();
            String shortName = key.location().getPath();
            if (fullName.equals(worldName) || shortName.equals(worldName)) {
                return server.getLevel(key);
            }
        }
        return null;
    }

    /**
     * 校验世界名称是否有效
     */
    public static boolean isValidWorld(MinecraftServer server, String worldName) {
        return getWorldByName(server, worldName) != null;
    }
}
