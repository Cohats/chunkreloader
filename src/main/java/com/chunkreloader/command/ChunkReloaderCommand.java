package com.chunkreloader.command;

import com.chunkreloader.ChunkReloaderMod;
import com.chunkreloader.config.Config;
import com.chunkreloader.util.AreaParser;
import com.chunkreloader.util.ChunkRegenerator;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

public class ChunkReloaderCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        var root = Commands.literal("chunckreloader")
                .requires(source -> source.hasPermission(2));

        // --- get subcommand ---
        root.then(Commands.literal("get")
                .then(Commands.literal("worldName")
                        .executes(ctx -> listWorlds(ctx.getSource()))
                )
        );

        // --- reload subcommand ---
        root.then(Commands.literal("reload")
                .then(Commands.argument("world", StringArgumentType.word())
                        .then(Commands.argument("x1", IntegerArgumentType.integer())
                                .then(Commands.argument("z1", IntegerArgumentType.integer())
                                        .then(Commands.argument("x2", IntegerArgumentType.integer())
                                                .then(Commands.argument("z2", IntegerArgumentType.integer())
                                                        .executes(ctx -> reloadChunks(
                                                                ctx.getSource(),
                                                                StringArgumentType.getString(ctx, "world"),
                                                                IntegerArgumentType.getInteger(ctx, "x1"),
                                                                IntegerArgumentType.getInteger(ctx, "z1"),
                                                                IntegerArgumentType.getInteger(ctx, "x2"),
                                                                IntegerArgumentType.getInteger(ctx, "z2"),
                                                                false
                                                        ))
                                                        .then(Commands.literal("force")
                                                                .executes(ctx -> reloadChunks(
                                                                        ctx.getSource(),
                                                                        StringArgumentType.getString(ctx, "world"),
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
                )
        );

        // --- set subcommand ---
        root.then(Commands.literal("set")
                .then(Commands.argument("option", StringArgumentType.word())
                        .then(Commands.argument("args", StringArgumentType.greedyString())
                                .executes(ctx -> setConfig(
                                        ctx.getSource(),
                                        StringArgumentType.getString(ctx, "option"),
                                        StringArgumentType.getString(ctx, "args")
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

    // ---- get worldName ----

    private static int listWorlds(CommandSourceStack source) {
        var server = source.getServer();
        source.sendSuccess(() -> Component.literal("§6=== Available Worlds ==="), false);

        for (var key : server.levelKeys()) {
            String name = key.location().toString();
            var level = server.getLevel(key);
            if (level != null) {
                int players = level.players().size();
                source.sendSuccess(() -> Component.literal(
                        "§e- " + name + " §7(" + players + " players)"), false);
            } else {
                source.sendSuccess(() -> Component.literal("§e- " + name + " §7(not loaded)"), false);
            }
        }
        return 1;
    }

    // ---- reload ----

    private static int reloadChunks(CommandSourceStack source, String worldName, int x1, int z1, int x2, int z2, boolean force) {
        var server = source.getServer();

        ServerLevel level = AreaParser.getWorldByName(server, worldName);
        if (level == null) {
            source.sendFailure(Component.literal("§cWrong world name: " + worldName + ". Use /chunckreloader get worldName to list valid worlds."));
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
                "§e[ChunkReloader] Reloading §6" + totalChunks + "§e chunks in world §b" + worldName
                        + "§e from (§b" + minX + "§e,§b" + minZ + "§e) to (§b" + maxX + "§e,§b" + maxZ + "§e)..."),
                true
        );

        long[] counters = ChunkRegenerator.regenerateChunks(level, pos1, pos2, force);

        source.sendSuccess(() -> Component.literal(
                "§a[ChunkReloader] Done! §2" + counters[0] + "§a regenerated, §7" + counters[1] + "§a skipped, §c" + counters[2] + "§a failed."),
                true
        );

        return (int) counters[0];
    }

    // ---- set ----

    private static int setConfig(CommandSourceStack source, String option, String args) {
        var server = source.getServer();

        try {
            switch (option.toLowerCase()) {
                case "enableautoreload":
                case "enable_auto_reload":
                    boolean autoReload = Boolean.parseBoolean(args);
                    Config.getInstance().enableAutoReload.set(autoReload);
                    source.sendSuccess(() -> Component.literal("§a[ChunkReloader] enableAutoReload set to §e" + autoReload), true);
                    break;

                case "staledays":
                case "stale_days":
                    int days = Integer.parseInt(args);
                    if (days < 1) {
                        source.sendFailure(Component.literal("staleDays must be >= 1"));
                        return 0;
                    }
                    Config.getInstance().staleDays.set(days);
                    source.sendSuccess(() -> Component.literal("§a[ChunkReloader] staleDays set to §e" + days), true);
                    break;

                case "nonrecordarea":
                case "non_record_area": {
                    // format: <world> <x1,z1,x2,z2>  or  <world>:<x1,z1,x2,z2>
                    String areaStr = buildAreaString(server, args);
                    if (areaStr == null) return 0;
                    Config.getInstance().nonRecordArea.set(areaStr);
                    source.sendSuccess(() -> Component.literal("§a[ChunkReloader] nonRecordArea set to §e" + areaStr), true);
                    break;
                }

                case "protectarea":
                case "protect_area": {
                    String areaStr = buildAreaString(server, args);
                    if (areaStr == null) return 0;
                    Config.getInstance().protectArea.set(areaStr);
                    source.sendSuccess(() -> Component.literal("§a[ChunkReloader] protectArea set to §e" + areaStr), true);
                    break;
                }

                case "autoreloadinterval":
                case "auto_reload_interval":
                    int interval = Integer.parseInt(args);
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

            saveConfig();
            return 1;

        } catch (NumberFormatException e) {
            source.sendFailure(Component.literal("§cInvalid number format: " + args));
            return 0;
        } catch (IllegalArgumentException e) {
            source.sendFailure(Component.literal("§c" + e.getMessage()));
            return 0;
        }
    }

    /**
     * Build area string with world prefix.
     * Input format: <world> <x1,z1,x2,z2>
     * Output format: <world>:<x1>,<z1>,<x2>,<z2>
     */
    private static String buildAreaString(net.minecraft.server.MinecraftServer server, String args) {
        String[] parts = args.split(" ", 2);
        if (parts.length < 2) {
            throw new IllegalArgumentException("Format: <world> <x1,z1,x2,z2>. Use /chunckreloader get worldName to list worlds.");
        }

        String worldName = parts[0].trim();
        String coords = parts[1].trim();

        // 校验世界名
        if (!AreaParser.isValidWorld(server, worldName)) {
            throw new IllegalArgumentException("Wrong world name: " + worldName + ". Use /chunckreloader get worldName to list valid worlds.");
        }

        // 校验坐标格式
        String[] coordParts = coords.replace(":", ",").split(",");
        if (coordParts.length != 4) {
            throw new IllegalArgumentException("Invalid coordinates. Format: x1,z1,x2,z2");
        }
        for (String p : coordParts) {
            Integer.parseInt(p.trim());
        }

        return worldName + ":" + coords.replace(" ", "");
    }

    private static void saveConfig() {
        try {
            Config.SPEC.save();
        } catch (Exception e) {
            ChunkReloaderMod.LOGGER.warn("Failed to save config: {}", e.getMessage());
        }
    }

    // ---- status ----

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
