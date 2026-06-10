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
}
