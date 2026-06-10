package com.chunkreloader;

import com.chunkreloader.command.ChunkReloaderCommand;
import com.chunkreloader.config.Config;
import com.chunkreloader.handler.ChunkLoadHandler;
import com.chunkreloader.manager.ChunkLoadTracker;
import com.chunkreloader.manager.ReloadQueue;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(ChunkReloaderMod.MODID)
public class ChunkReloaderMod {
    public static final String MODID = "chunkreloader";
    public static final Logger LOGGER = LoggerFactory.getLogger(MODID);

    private ChunkLoadTracker tracker;

    public ChunkReloaderMod(IEventBus modBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC, MODID + "-common.toml");

        var gameBus = NeoForge.EVENT_BUS;

        gameBus.addListener(this::onServerStarting);
        gameBus.addListener(this::onServerStopping);
        gameBus.addListener(this::onRegisterCommands);
        gameBus.addListener(this::onChunkLoad);
        gameBus.addListener(this::onServerTick);
    }

    private void onServerStarting(ServerStartingEvent event) {
        tracker = ChunkLoadTracker.get(event.getServer().overworld());
        LOGGER.info("ChunkReloader started");
    }

    private void onServerStopping(ServerStoppingEvent event) {
        if (tracker != null) {
            tracker.setDirty();
        }
        LOGGER.info("ChunkReloader stopped");
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        ChunkReloaderCommand.register(event.getDispatcher());
    }

    private void onChunkLoad(ChunkEvent.Load event) {
        if (tracker != null && event.getLevel() instanceof net.minecraft.server.level.ServerLevel serverLevel) {
            ChunkLoadHandler.onChunkLoad(tracker, serverLevel, event.getChunk().getPos());
        }
    }

    private void onServerTick(ServerTickEvent.Post event) {
        var server = event.getServer();
        if (server == null) return;

        // Process batch reload queue (manual reload command)
        ReloadQueue.tick();

        // Process each world's tracker
        for (var key : server.levelKeys()) {
            var level = server.getLevel(key);
            if (level == null) continue;

            var worldTracker = ChunkLoadTracker.get(level);
            worldTracker.tick(level);

            // Process any queued stale chunks
            ChunkLoadTracker.processStaleChunks(level);
        }
    }
}
