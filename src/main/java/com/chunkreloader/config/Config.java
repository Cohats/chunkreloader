package com.chunkreloader.config;

import net.neoforged.neoforge.common.ModConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

public class Config {
    public static final ModConfigSpec SPEC;
    private static final Config INSTANCE;

    static {
        Pair<Config, ModConfigSpec> pair = new ModConfigSpec.Builder().configure(Config::new);
        SPEC = pair.getRight();
        INSTANCE = pair.getLeft();
    }

    // ---- Settings ----

    public final ModConfigSpec.BooleanValue enableAutoReload;

    public final ModConfigSpec.ConfigValue<String> nonRecordArea;

    public final ModConfigSpec.IntValue staleDays;

    public final ModConfigSpec.ConfigValue<String> protectArea;

    public final ModConfigSpec.IntValue autoReloadInterval;

    private Config(ModConfigSpec.Builder builder) {
        builder.push("general");

        enableAutoReload = builder
                .comment("Enable automatic reload of chunks that haven't been loaded for staleDays days")
                .define("enableAutoReload", false);

        nonRecordArea = builder
                .comment("Area where chunks are NOT tracked for stale detection. Format: x1,z1,x2,z2 (block coordinates)")
                .define("nonRecordArea", "-50000,-50000,50000,50000");

        staleDays = builder
                .comment("Number of days after which a chunk is considered stale and will be regenerated")
                .defineInRange("staleDays", 30, 1, Integer.MAX_VALUE);

        protectArea = builder
                .comment("Protected area where chunks will NOT be regenerated. Format: x1,z1,x2,z2 (block coordinates). Leave empty to disable.")
                .define("protectArea", "");

        autoReloadInterval = builder
                .comment("Interval in seconds between auto-reload checks (0 = every server tick, minimum 60)")
                .defineInRange("autoReloadInterval", 300, 0, 86400);

        builder.pop();
    }

    public static Config getInstance() {
        return INSTANCE;
    }
}
