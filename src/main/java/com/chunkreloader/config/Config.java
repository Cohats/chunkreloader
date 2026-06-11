package com.chunkreloader.config;

import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

import java.util.HashMap;
import java.util.Map;

public class Config {
    public static final ModConfigSpec SPEC;
    private static final Config INSTANCE;

    static {
        Pair<Config, ModConfigSpec> pair = new ModConfigSpec.Builder().configure(Config::new);
        SPEC = pair.getRight();
        INSTANCE = pair.getLeft();
    }

    // ---- Settings (stored as "world:value" string, or just "value" for all worlds) ----

    public final ModConfigSpec.ConfigValue<String> enableAutoReload;

    public final ModConfigSpec.ConfigValue<String> nonRecordArea;

    public final ModConfigSpec.ConfigValue<String> staleDays;

    public final ModConfigSpec.ConfigValue<String> protectArea;

    public final ModConfigSpec.IntValue autoReloadInterval;

    // ---- Cache for parsed per-world values ----
    private final Map<String, Map<String, String>> parsedCache = new HashMap<>();

    private Config(ModConfigSpec.Builder builder) {
        builder.push("general");

        enableAutoReload = builder
                .comment("Enable auto reload per world. Format: \"world:true\" or \"world:false\". Use \"true\"/\"false\" for all worlds.")
                .define("enableAutoReload", "overworld:false");

        nonRecordArea = builder
                .comment("Area where chunks are NOT tracked for stale detection. Format: [world:]x1,z1,x2,z2 (block coordinates, optional world prefix). Default: ~3125x3125 chunks around spawn.")
                .define("nonRecordArea", "overworld:-50000,-50000,50000,50000");

        staleDays = builder
                .comment("Stale days per world. Format: \"world:14\". Use \"14\" for all worlds.")
                .define("staleDays", "overworld:14");

        protectArea = builder
                .comment("Protected area where chunks will NOT be regenerated. Format: [world:]x1,z1,x2,z2 (block coordinates, optional world prefix).")
                .define("protectArea", "overworld:-50000,-50000,50000,50000");

        autoReloadInterval = builder
                .comment("Interval in seconds between auto-reload checks (0 = every server tick)")
                .defineInRange("autoReloadInterval", 3600, 0, 86400);

        builder.pop();
    }

    public static Config getInstance() {
        return INSTANCE;
    }

    // ---- Per-world value helpers ----

    /**
     * 获取指定世界的 autoReload 值
     */
    public boolean getAutoReload(ServerLevel level) {
        return parseWorldBool(enableAutoReload.get(), level, false);
    }

    /**
     * 获取指定世界的 staleDays 值
     */
    public int getStaleDays(ServerLevel level) {
        return parseWorldInt(staleDays.get(), level, 14);
    }

    /**
     * 获取指定世界的 nonRecordArea 值（仅坐标部分，不含世界前缀）
     */
    public String getNonRecordArea(ServerLevel level) {
        return parseWorldStr(nonRecordArea.get(), level, "-50000,-50000,50000,50000");
    }

    /**
     * 获取指定世界的 protectArea 值（仅坐标部分，不含世界前缀）
     */
    public String getProtectArea(ServerLevel level) {
        return parseWorldStr(protectArea.get(), level, "-50000,-50000,50000,50000");
    }

    /**
     * 将 nonRecordArea 设为指定世界/值
     */
    public void setNonRecordArea(String worldName, String value) {
        nonRecordArea.set(worldName + ":" + value);
    }

    /**
     * 将 protectArea 设为指定世界/值
     */
    public void setProtectArea(String worldName, String value) {
        protectArea.set(worldName + ":" + value);
    }

    /**
     * 将 enableAutoReload 设为指定世界/值
     */
    public void setAutoReload(String worldName, boolean value) {
        enableAutoReload.set(worldName + ":" + value);
    }

    /**
     * 将 staleDays 设为指定世界/值
     */
    public void setStaleDays(String worldName, int days) {
        staleDays.set(worldName + ":" + days);
    }

    private static final java.util.Set<String> DIM_PATHS = java.util.Set.of("overworld", "the_nether", "the_end");

    /**
     * 获取 config 字符串中存储的世界名（冒号前的部分），如果没有则为 null
     */
    public String getStoredWorld(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        int colon = raw.indexOf(':');
        if (colon < 0) return null;
        String world = raw.substring(0, colon).trim();
        return world;
    }

    /**
     * 获取 config 字符串中存储的值
     */
    public String getDisplayValue(String raw) {
        if (raw == null || raw.isEmpty()) return "(default)";
        int colon = raw.indexOf(':');
        if (colon < 0) return raw.trim();
        return raw.substring(colon + 1).trim();
    }

    /**
     * 检查指定世界名是否匹配 config 中存储的世界
     */
    public boolean matchesStoredWorld(String raw, String worldName) {
        if (raw == null || raw.isEmpty()) return false;
        int colon = raw.indexOf(':');
        if (colon < 0) return true; // 无前缀，应用于所有世界
        String stored = raw.substring(0, colon).trim();
        return stored.equals(worldName) || stored.equals("minecraft:" + worldName);
    }

    /**
     * 解析 "world:value" 或 "value" 格式为 boolean
     */
    private boolean parseWorldBool(String raw, ServerLevel level, boolean defaultVal) {
        if (raw == null || raw.isEmpty()) return defaultVal;
        int colon = raw.indexOf(':');
        if (colon < 0) return Boolean.parseBoolean(raw.trim());

        String world = raw.substring(0, colon).trim();
        String val = raw.substring(colon + 1).trim();
        String worldFull = level.dimension().location().toString();
        String worldShort = level.dimension().location().getPath();

        if (worldFull.equals(world) || worldShort.equals(world)) {
            return Boolean.parseBoolean(val);
        }
        return defaultVal;
    }

    /**
     * 解析 "world:value" 或 "value" 格式为 int
     */
    private int parseWorldInt(String raw, ServerLevel level, int defaultVal) {
        if (raw == null || raw.isEmpty()) return defaultVal;
        int colon = raw.indexOf(':');
        if (colon < 0) {
            try { return Integer.parseInt(raw.trim()); }
            catch (NumberFormatException e) { return defaultVal; }
        }

        String world = raw.substring(0, colon).trim();
        String val = raw.substring(colon + 1).trim();
        String worldFull = level.dimension().location().toString();
        String worldShort = level.dimension().location().getPath();

        if (worldFull.equals(world) || worldShort.equals(world)) {
            try { return Integer.parseInt(val.trim()); }
            catch (NumberFormatException e) { return defaultVal; }
        }
        return defaultVal;
    }

    /**
     * 解析 "world:value" 或 "value" 格式为字符串
     */
    private String parseWorldStr(String raw, ServerLevel level, String defaultVal) {
        if (raw == null || raw.isEmpty()) return defaultVal;
        int colon = raw.indexOf(':');
        if (colon < 0) return raw.trim();
        String world = raw.substring(0, colon).trim();
        String val = raw.substring(colon + 1).trim();
        String worldFull = level.dimension().location().toString();
        String worldShort = level.dimension().location().getPath();
        if (worldFull.equals(world) || worldShort.equals(world)) return val;
        return defaultVal;
    }
}
