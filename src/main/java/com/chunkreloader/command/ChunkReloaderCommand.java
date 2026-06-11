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
        var reloadWorldArg = Commands.argument("world", StringArgumentType.word())
                .suggests((ctx, builder) -> {
                    var server = ctx.getSource().getServer();
                    for (var key : server.levelKeys()) {
                        builder.suggest(key.location().getPath());
                    }
                    return builder.buildFuture();
                });

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

        // --- first subcommand: factory reset — delete ALL unprotected chunks ---
        var firstWorldArg = Commands.argument("world", StringArgumentType.word())
                .suggests((ctx, builder) -> {
                    var server = ctx.getSource().getServer();
                    for (var key : server.levelKeys()) {
                        builder.suggest(key.location().getPath());
                    }
                    return builder.buildFuture();
                });
        firstWorldArg.executes(ctx -> first(
                ctx.getSource(),
                StringArgumentType.getString(ctx, "world")
        ));
        root.then(Commands.literal("first").then(firstWorldArg));

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

    // ---- first <world>: factory reset — delete ALL unprotected chunks ----

    private static int first(CommandSourceStack source, String worldName) {
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

        source.sendSuccess(() -> Component.literal("§e[ChunkReloader] Scanning region files for world §b" + worldName + "§e..."), false);

        // Scan ALL existing chunks from region files
        List<ChunkPos> allChunks;
        try {
            allChunks = scanRegionFiles(level);
        } catch (Exception e) {
            source.sendFailure(Component.literal("§cError scanning region files: " + e.getMessage()));
            return 0;
        }

        if (allChunks.isEmpty()) {
            source.sendSuccess(() -> Component.literal("§e[ChunkReloader] No chunks found in world §b" + worldName), false);
            return 1;
        }

        source.sendSuccess(() -> Component.literal("§e[ChunkReloader] Found §6" + allChunks.size() + "§e chunks total. Filtering protected areas..."), false);

        // Filter out protected chunks
        List<ChunkPos> toDelete = new ArrayList<>();
        for (ChunkPos pos : allChunks) {
            if (ProtectedChunkManager.isProtected(level, pos)) continue;
            toDelete.add(pos);
        }

        source.sendSuccess(() -> Component.literal("§e[ChunkReloader] Queuing §c" + toDelete.size() + "§e unprotected chunks for deletion..."), false);

        if (toDelete.isEmpty()) {
            source.sendSuccess(() -> Component.literal("§a[ChunkReloader] All chunks are protected. Nothing to delete."), false);
            return 1;
        }

        boolean started = ReloadQueue.startBatch(level, toDelete, true);
        if (!started) {
            source.sendFailure(Component.literal("§cFailed to start. Another reload may be in progress."));
            return 0;
        }

        return 1;
    }

    /**
     * Scan all region files in the world's region directory and return all existing chunk positions.
     */
    private static List<ChunkPos> scanRegionFiles(ServerLevel level) {
        List<ChunkPos> result = new ArrayList<>();
        java.nio.file.Path regionDir = findRegionDir(level);
        if (regionDir == null || !java.nio.file.Files.isDirectory(regionDir)) {
            return result;
        }

        try (var files = java.nio.file.Files.list(regionDir)) {
            files.filter(p -> p.getFileName().toString().matches("r\\.-?\\d+\\.-?\\d+\\.mca"))
                    .forEach(regionFile -> {
                        String name = regionFile.getFileName().toString();
                        String[] parts = name.replace("r.", "").replace(".mca", "").split("\\.");
                        int regionX = Integer.parseInt(parts[0]);
                        int regionZ = Integer.parseInt(parts[1]);

                        try (java.io.RandomAccessFile file = new java.io.RandomAccessFile(regionFile.toFile(), "r")) {
                            byte[] header = new byte[4096]; // 1024 entries * 4 bytes
                            file.readFully(header);

                            for (int i = 0; i < 1024; i++) {
                                int off = i * 4;
                                int offset = ((header[off] & 0xFF) << 16)
                                           | ((header[off + 1] & 0xFF) << 8)
                                           | (header[off + 2] & 0xFF);
                                if (offset == 0) continue; // No chunk here

                                int localX = i & 31;
                                int localZ = i >> 5;
                                int chunkX = regionX * 32 + localX;
                                int chunkZ = regionZ * 32 + localZ;
                                result.add(new ChunkPos(chunkX, chunkZ));
                            }
                        } catch (Exception e) {
                            ChunkReloaderMod.LOGGER.warn("Failed to read region file {}: {}", regionFile, e.getMessage());
                        }
                    });
        } catch (Exception e) {
            ChunkReloaderMod.LOGGER.warn("Failed to list region files: {}", e.getMessage());
        }

        return result;
    }

    /**
     * Find the region directory for a world.
     */
    private static java.nio.file.Path findRegionDir(ServerLevel level) {
        try {
            var server = level.getServer();
            var worldDir = server.getFile(".");

            // Try getWorldPath API
            try {
                var lrClass = Class.forName("net.minecraft.world.level.storage.LevelResource");
                var root = lrClass.getField("LEVEL_ROOT").get(null);
                var getPath = server.getClass().getMethod("getWorldPath", lrClass);
                java.nio.file.Path wp = (java.nio.file.Path) getPath.invoke(server, root);

                var dimPath = level.dimension().location().getPath();
                java.nio.file.Path dir = switch (dimPath) {
                    case "overworld" -> wp.resolve("region");
                    case "the_nether" -> wp.resolve("DIM-1").resolve("region");
                    case "the_end" -> wp.resolve("DIM1").resolve("region");
                    default -> wp.resolve(dimPath).resolve("region");
                };
                if (java.nio.file.Files.isDirectory(dir)) return dir;
            } catch (Exception ignored) {}

            // Fallback: check saves directory and common patterns
            var dimFolder = switch (level.dimension().location().getPath()) {
                case "overworld" -> "";
                case "the_nether" -> "DIM-1";
                case "the_end" -> "DIM1";
                default -> level.dimension().location().getPath();
            };

            java.nio.file.Path savesDir = worldDir.resolve("saves");
            if (java.nio.file.Files.isDirectory(savesDir)) {
                try (var ds = java.nio.file.Files.list(savesDir)) {
                    var it = ds.filter(java.nio.file.Files::isDirectory).iterator();
                    if (it.hasNext()) {
                        var saveDir = it.next();
                        java.nio.file.Path dir = dimFolder.isEmpty()
                                ? saveDir.resolve("region")
                                : saveDir.resolve(dimFolder).resolve("region");
                        if (java.nio.file.Files.isDirectory(dir)) return dir;
                    }
                }
            }

            java.nio.file.Path dir = dimFolder.isEmpty()
                    ? worldDir.resolve("region")
                    : worldDir.resolve(dimFolder).resolve("region");
            if (java.nio.file.Files.isDirectory(dir)) return dir;
        } catch (Exception e) {
            ChunkReloaderMod.LOGGER.warn("Error finding region dir: {}", e.getMessage());
        }

        return null;
    }

    // ---- status ----

    private static int showStatus(CommandSourceStack source) {
        var config = Config.getInstance();
        var server = source.getServer();

        source.sendSuccess(() -> Component.literal("§6=== ChunkReloader Status ==="), false);

        // Per-world settings
        for (var key : server.levelKeys()) {
            String name = key.location().getPath();
            var level = server.getLevel(key);
            if (level == null) continue;

            boolean autoReload = config.getAutoReload(level);
            int staleDays = config.getStaleDays(level);

            String autoStr = autoReload ? "§atrue" : "§cfalse";
            source.sendSuccess(() -> Component.literal(
                    "§b" + name + "§r:"), false);
            source.sendSuccess(() -> Component.literal(
                    "  §eAuto Reload: §f" + autoStr), false);
            source.sendSuccess(() -> Component.literal(
                    "  §eStale Days: §f" + staleDays + "d"), false);
            source.sendSuccess(() -> Component.literal(
                    "  §eNon-Record Area: §f" + config.getNonRecordArea(level)), false);
            source.sendSuccess(() -> Component.literal(
                    "  §eProtect Area: §f" + config.getProtectArea(level)), false);
        }

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
