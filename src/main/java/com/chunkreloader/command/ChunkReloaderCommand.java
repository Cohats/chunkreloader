package com.chunkreloader.command;

import com.chunkreloader.ChunkReloaderMod;
import com.chunkreloader.config.Config;
import com.chunkreloader.util.ChunkRegenerator;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

public class ChunkReloaderCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        var root = Commands.literal("chunckreloader")
                .requires(source -> source.hasPermission(2));

        // --- reload subcommand ---
        root.then(Commands.literal("reload")
                .then(Commands.argument("x1", IntegerArgumentType.integer())
                        .then(Commands.argument("z1", IntegerArgumentType.integer())
                                .then(Commands.argument("x2", IntegerArgumentType.integer())
                                        .then(Commands.argument("z2", IntegerArgumentType.integer())
                                                .executes(ctx -> reloadChunks(
                                                        ctx.getSource(),
                                                        IntegerArgumentType.getInteger(ctx, "x1"),
                                                        IntegerArgumentType.getInteger(ctx, "z1"),
                                                        IntegerArgumentType.getInteger(ctx, "x2"),
                                                        IntegerArgumentType.getInteger(ctx, "z2"),
                                                        false
                                                ))
                                                .then(Commands.literal("force")
                                                        .executes(ctx -> reloadChunks(
                                                                ctx.getSource(),
                                                                IntegerArgumentType.getInteger(ctx, "x1"),
                                                                IntegerArgumentType.getInteger(ctx, "z1"),
                                                                IntegerArgumentType.getInteger(ctx, "x2"),
                                                                IntegerArgumentType.getInteger(ctx, "z2"),
                                                                true
                                                        ))
                                                )
                                        )
                                )
                        )
                )
        );

        // --- set subcommand ---
        root.then(Commands.literal("set")
                .then(Commands.argument("option", StringArgumentType.word())
                        .then(Commands.argument("value", StringArgumentType.greedyString())
                                .executes(ctx -> setConfig(
                                        ctx.getSource(),
                                        StringArgumentType.getString(ctx, "option"),
                                        StringArgumentType.getString(ctx, "value")
                                ))
                        )
                )
        );

        // --- status subcommand ---
        root.then(Commands.literal("status")
                .executes(ctx -> showStatus(ctx.getSource()))
        );

        dispatcher.register(root);
    }

    private static int reloadChunks(CommandSourceStack source, int x1, int z1, int x2, int z2, boolean force) {
        if (!(source.getLevel() instanceof ServerLevel level)) {
            source.sendFailure(Component.literal("This command must be executed on the server"));
            return 0;
        }

        ChunkPos pos1 = new ChunkPos(x1, z1);
        ChunkPos pos2 = new ChunkPos(x2, z2);

        int minX = Math.min(pos1.x, pos2.x);
        int maxX = Math.max(pos1.x, pos2.x);
        int minZ = Math.min(pos1.z, pos2.z);
        int maxZ = Math.max(pos1.z, pos2.z);

        long totalChunks = (long) (maxX - minX + 1) * (maxZ - minZ + 1);
        if (totalChunks <= 0) {
            source.sendFailure(Component.literal("Invalid area specified"));
            return 0;
        }

        source.sendSuccess(() -> Component.literal(
                "§e[ChunkReloader] Starting regeneration of §6" + totalChunks + "§e chunks from (§b" + minX + "§e,§b" + minZ + "§e) to (§b" + maxX + "§e,§b" + maxZ + "§e)..."),
                true
        );

        long[] counters = ChunkRegenerator.regenerateChunks(level, pos1, pos2, force);

        source.sendSuccess(() -> Component.literal(
                "§a[ChunkReloader] Complete! §2" + counters[0] + "§a regenerated, §7" + counters[1] + "§a skipped, §c" + counters[2] + "§a failed."),
                true
        );

        return (int) counters[0];
    }

    private static int setConfig(CommandSourceStack source, String option, String value) {
        try {
            switch (option.toLowerCase()) {
                case "enableautoreload":
                case "enable_auto_reload":
                    boolean autoReload = Boolean.parseBoolean(value);
                    Config.getInstance().enableAutoReload.set(autoReload);
                    source.sendSuccess(() -> Component.literal("§a[ChunkReloader] enableAutoReload set to §e" + autoReload), true);
                    break;

                case "staledays":
                case "stale_days":
                    int days = Integer.parseInt(value);
                    if (days < 1) {
                        source.sendFailure(Component.literal("staleDays must be >= 1"));
                        return 0;
                    }
                    Config.getInstance().staleDays.set(days);
                    source.sendSuccess(() -> Component.literal("§a[ChunkReloader] staleDays set to §e" + days), true);
                    break;

                case "nonrecordarea":
                case "non_record_area":
                    validateAreaFormat(value);
                    Config.getInstance().nonRecordArea.set(value);
                    source.sendSuccess(() -> Component.literal("§a[ChunkReloader] nonRecordArea set to §e" + value), true);
                    break;

                case "protectarea":
                case "protect_area":
                    if (!value.isEmpty()) validateAreaFormat(value);
                    Config.getInstance().protectArea.set(value);
                    source.sendSuccess(() -> Component.literal("§a[ChunkReloader] protectArea set to §e" + value), true);
                    break;

                case "autoreloadinterval":
                case "auto_reload_interval":
                    int interval = Integer.parseInt(value);
                    if (interval < 0) {
                        source.sendFailure(Component.literal("autoReloadInterval must be >= 0"));
                        return 0;
                    }
                    if (interval > 0 && interval < 60) {
                        source.sendSuccess(() -> Component.literal("§e[ChunkReloader] Warning: interval < 60s may cause lag"), true);
                    }
                    Config.getInstance().autoReloadInterval.set(interval);
                    source.sendSuccess(() -> Component.literal("§a[ChunkReloader] autoReloadInterval set to §e" + interval + "§a seconds"), true);
                    break;

                default:
                    source.sendFailure(Component.literal("§cUnknown option: " + option
                            + ". Available: enableAutoReload, staleDays, nonRecordArea, protectArea, autoReloadInterval"));
                    return 0;
            }

            // Save config to disk
            saveConfig();
            return 1;
        } catch (NumberFormatException e) {
            source.sendFailure(Component.literal("§cInvalid number format: " + value));
            return 0;
        } catch (IllegalArgumentException e) {
            source.sendFailure(Component.literal("§c" + e.getMessage()));
            return 0;
        }
    }

    private static void validateAreaFormat(String area) {
        String[] parts = area.split(",");
        if (parts.length != 4) {
            throw new IllegalArgumentException("Area format: x1,z1,x2,z2 (block coordinates), got " + parts.length + " parts");
        }
        for (String part : parts) {
            Integer.parseInt(part.trim());
        }
    }

    private static void saveConfig() {
        try {
            Config.SPEC.save();
        } catch (Exception e) {
            ChunkReloaderMod.LOGGER.warn("Failed to save config: {}", e.getMessage());
        }
    }

    private static int showStatus(CommandSourceStack source) {
        var config = Config.getInstance();

        source.sendSuccess(() -> Component.literal("§6=== ChunkReloader Status ==="), false);
        source.sendSuccess(() -> Component.literal("§eAuto Reload: " + (config.enableAutoReload.get() ? "§aON" : "§cOFF")), false);
        source.sendSuccess(() -> Component.literal("§eStale Days: §f" + config.staleDays.get()), false);
        source.sendSuccess(() -> Component.literal("§eNon-Record Area: §f" + config.nonRecordArea.get()), false);
        source.sendSuccess(() -> Component.literal("§eProtect Area: §f" + (config.protectArea.get().isEmpty() ? "(none)" : config.protectArea.get())), false);
        source.sendSuccess(() -> Component.literal("§eCheck Interval: §f" + config.autoReloadInterval.get() + "s"), false);

        return 1;
    }
}
