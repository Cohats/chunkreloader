package com.chunkreloader.command;

import com.chunkreloader.ChunkReloaderMod;
import com.chunkreloader.config.Config;
import com.chunkreloader.manager.ChunkLoadTracker;
import com.chunkreloader.manager.ProtectedChunkManager;
import com.chunkreloader.manager.ReloadQueue;
import com.chunkreloader.util.AreaParser;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.List;

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
        var reloadWorldArg = Commands.argument("world", StringArgumentType.word());

        // reload <world> all [force]
        reloadWorldArg.then(Commands.literal("all")
                .executes(ctx -> reloadAll(
                        ctx.getSource(),
                        StringArgumentType.getString(ctx, "world"),
                        false
                ))
                .then(Commands.literal("force")
                        .executes(ctx -> reloadAll(
                                ctx.getSource(),
                                StringArgumentType.getString(ctx, "world"),
                                true
                        ))
                )
        );

        // reload <world> <x1> <z1> <x2> <z2> [force]
        reloadWorldArg.then(Commands.argument("x1", IntegerArgumentType.integer())
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
        );

        root.then(Commands.literal("reload").then(reloadWorldArg));

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

    // ---- reload <world> all [force] ----

    /**
     * Reload all tracked (player-visited) chunks outside protected area.
     */
    private static int reloadAll(CommandSourceStack source, String worldName, boolean force) {
        if (ReloadQueue.isActive()) {
            source.sendFailure(Component.literal("§cA reload is already in progress. Wait for it to complete."));
            return 0;
        }

        var server = source.getServer();
        ServerLevel level = AreaParser.getWorldByName(server, worldName);
        if (level == null) {
            source.sendFailure(Component.literal("§cWrong world name: " + worldName + ". Use /chunckreloader get worldName to list valid worlds."));
            return 0;
        }

        ChunkLoadTracker tracker = ChunkLoadTracker.get(level);
        List<ChunkPos> allTracked = tracker.getAllTrackedChunks();

        if (allTracked.isEmpty()) {
            source.sendSuccess(() -> Component.literal("§e[ChunkReloader] No tracked chunks found for world §b" + worldName + "§e. Players need to load chunks first."), false);
            return 1;
        }

        // Filter out protected chunks (unless force)
        List<ChunkPos> toReload = new ArrayList<>();
        for (ChunkPos pos : allTracked) {
            if (!force && ProtectedChunkManager.isProtected(level, pos)) {
                continue;
            }
            toReload.add(pos);
        }

        if (toReload.isEmpty()) {
            source.sendSuccess(() -> Component.literal("§e[ChunkReloader] All tracked chunks are protected. Nothing to reload."), false);
            return 1;
        }

        if (!ReloadQueue.startBatch(level, toReload, force)) {
            source.sendFailure(Component.literal("§cFailed to start reload. Another reload may be in progress."));
            return 0;
        }

        source.sendSuccess(() -> Component.literal(
                "§e[ChunkReloader] Queued §6" + toReload.size() + "§e chunks for regeneration in world §b" + worldName
                        + (force ? " §c(force)" : "")
        ), true);

        return 1;
    }

    // ---- reload <world> <x1> <z1> <x2> <z2> [force] ----

    private static int reloadChunks(CommandSourceStack source, String worldName, int x1, int z1, int x2, int z2, boolean force) {
        if (ReloadQueue.isActive()) {
            source.sendFailure(Component.literal("§cA reload is already in progress. Wait for it to complete."));
            return 0;
        }

        var server = source.getServer();

        ServerLevel level = AreaParser.getWorldByName(server, worldName);
        if (level == null) {
            source.sendFailure(Component.literal("§cWrong world name: " + worldName + ". Use /chunckreloader get worldName to list valid worlds."));
            return 0;
        }

        // Convert block coordinates to chunk coordinates (>> 4 = divide by 16, floor)
        ChunkPos pos1 = new ChunkPos(x1 >> 4, z1 >> 4);
        ChunkPos pos2 = new ChunkPos(x2 >> 4, z2 >> 4);

        int minX = Math.min(pos1.x, pos2.x);
        int maxX = Math.max(pos1.x, pos2.x);
        int minZ = Math.min(pos1.z, pos2.z);
        int maxZ = Math.max(pos1.z, pos2.z);

        long totalChunks = (long) (maxX - minX + 1) * (maxZ - minZ + 1);
        if (totalChunks <= 0) {
            source.sendFailure(Component.literal("Invalid area specified"));
            return 0;
        }

        ChunkLoadTracker tracker = ChunkLoadTracker.get(level);
        List<ChunkPos> toReload = new ArrayList<>();
        long[] skippedProtected = {0};
        long[] skippedUntracked = {0};

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                ChunkPos pos = new ChunkPos(x, z);

                if (!force && ProtectedChunkManager.isProtected(level, pos)) {
                    skippedProtected[0]++;
                    continue;
                }

                // Without force, only reload chunks that have been visited by players (tracked)
                if (!force && !tracker.isTracked(pos)) {
                    skippedUntracked[0]++;
                    continue;
                }

                toReload.add(pos);
            }
        }

        if (toReload.isEmpty()) {
            long sp = skippedProtected[0];
            long su = skippedUntracked[0];
            source.sendSuccess(() -> Component.literal(
                    "§e[ChunkReloader] No chunks to reload. "
                    + "Protected: " + sp + ", Not player-modified: " + su
                    + ". Use 'force' to reload all chunks regardless."
            ), false);
            return 1;
        }

        if (!ReloadQueue.startBatch(level, toReload, force)) {
            source.sendFailure(Component.literal("§cFailed to start reload. Another reload may be in progress."));
            return 0;
        }

        long finalSkippedProtected = skippedProtected[0];
        long finalSkippedUntracked = skippedUntracked[0];
        int size = toReload.size();
        source.sendSuccess(() -> Component.literal(
                "§e[ChunkReloader] Queued §6" + size + "§e / §7" + totalChunks + "§e chunks for regeneration in world §b" + worldName
                        + " §7(protected: " + finalSkippedProtected + ", unmodified: " + finalSkippedUntracked + ")"
                        + (force ? " §c(force)" : "")
        ), true);

        return (int) toReload.size();
    }

    // ---- set ----

    private static int setConfig(CommandSourceStack source, String option, String args) {
        var server = source.getServer();

        try {
            switch (option.toLowerCase()) {
                case "enableautoreload":
                case "enable_auto_reload": {
                    var wv = parseWorldValue(server, args);
                    if (wv == null) return 0;
                    Config.getInstance().setAutoReload(wv.worldName, Boolean.parseBoolean(wv.value));
                    source.sendSuccess(() -> Component.literal("§a[ChunkReloader] enableAutoReload set to §e" + wv.value + "§a for world §e" + wv.worldName), true);
                    break;
                }

                case "staledays":
                case "stale_days": {
                    var wv = parseWorldValue(server, args);
                    if (wv == null) return 0;
                    int days = Integer.parseInt(wv.value);
                    if (days < 1) {
                        source.sendFailure(Component.literal("staleDays must be >= 1"));
                        return 0;
                    }
                    Config.getInstance().setStaleDays(wv.worldName, days);
                    source.sendSuccess(() -> Component.literal("§a[ChunkReloader] staleDays set to §e" + days + "§a days for world §e" + wv.worldName), true);
                    break;
                }

                case "nonrecordarea":
                case "non_record_area": {
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

    private static record WorldValue(String worldName, String value) {}

    private static WorldValue parseWorldValue(net.minecraft.server.MinecraftServer server, String args) {
        String[] parts = args.split(" ", 2);
        if (parts.length < 2) {
            throw new IllegalArgumentException("Format: <world> <value>. Use /chunckreloader get worldName to list worlds.");
        }
        String worldName = parts[0].trim();
        String value = parts[1].trim();

        if (!AreaParser.isValidWorld(server, worldName)) {
            throw new IllegalArgumentException("Wrong world name: " + worldName + ". Use /chunckreloader get worldName to list valid worlds.");
        }
        return new WorldValue(worldName, value);
    }

    private static String buildAreaString(net.minecraft.server.MinecraftServer server, String args) {
        String[] parts = args.split(" ", 2);
        if (parts.length < 2) {
            throw new IllegalArgumentException("Format: <world> <x1,z1,x2,z2>. Use /chunckreloader get worldName to list worlds.");
        }

        String worldName = parts[0].trim();
        String coords = parts[1].trim();

        if (!AreaParser.isValidWorld(server, worldName)) {
            throw new IllegalArgumentException("Wrong world name: " + worldName + ". Use /chunckreloader get worldName to list valid worlds.");
        }

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
        source.sendSuccess(() -> Component.literal("§eAuto Reload: §f" + config.enableAutoReload.get()), false);
        source.sendSuccess(() -> Component.literal("§eStale Days: §f" + config.staleDays.get()), false);
        source.sendSuccess(() -> Component.literal("§eNon-Record Area: §f" + config.nonRecordArea.get()), false);
        source.sendSuccess(() -> Component.literal("§eProtect Area: §f" + (config.protectArea.get().isEmpty() ? "(none)" : config.protectArea.get())), false);
        source.sendSuccess(() -> Component.literal("§eCheck Interval: §f" + config.autoReloadInterval.get() + "s"), false);

        // Show reload queue progress if active
        if (ReloadQueue.isActive()) {
            source.sendSuccess(() -> Component.literal("§6=== Reload Progress ==="), false);
            source.sendSuccess(() -> Component.literal("§eProgress: §f" + ReloadQueue.getProgress() + "%"), false);
            source.sendSuccess(() -> Component.literal("§eProcessed: §f" + ReloadQueue.getProcessed() + "/" + ReloadQueue.getTotalQueued()), false);
            source.sendSuccess(() -> Component.literal("§aRegenerated: §f" + ReloadQueue.getRegenCount()), false);
            source.sendSuccess(() -> Component.literal("§7Skipped: §f" + ReloadQueue.getSkipCount()), false);
            source.sendSuccess(() -> Component.literal("§cFailed: §f" + ReloadQueue.getFailCount()), false);
        }

        return 1;
    }
}
