package com.chunkreloader.command;

import com.chunkreloader.ChunkReloaderMod;
import com.chunkreloader.config.Config;
import com.chunkreloader.manager.ProtectedChunkManager;
import com.chunkreloader.util.ChunkRegenerator;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.BoolArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

public class ReloadAreaCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("reload_area")
                        .requires(source -> source.hasPermission(2)) // OP level 2 or higher
                        .then(Commands.argument("x1", IntegerArgumentType.integer())
                                .then(Commands.argument("z1", IntegerArgumentType.integer())
                                        .then(Commands.argument("x2", IntegerArgumentType.integer())
                                                .then(Commands.argument("z2", IntegerArgumentType.integer())
                                                        .executes(ctx -> execute(
                                                                ctx.getSource(),
                                                                IntegerArgumentType.getInteger(ctx, "x1"),
                                                                IntegerArgumentType.getInteger(ctx, "z1"),
                                                                IntegerArgumentType.getInteger(ctx, "x2"),
                                                                IntegerArgumentType.getInteger(ctx, "z2"),
                                                                false
                                                        ))
                                                        .then(Commands.literal("force")
                                                                .executes(ctx -> execute(
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
    }

    private static int execute(CommandSourceStack source, int x1, int z1, int x2, int z2, boolean force) {
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
                "Starting regeneration of " + totalChunks + " chunks from (" + minX + "," + minZ + ") to (" + maxX + "," + maxZ + ")..."),
                true
        );

        long[] counters = ChunkRegenerator.regenerateChunks(level, pos1, pos2, force);

        source.sendSuccess(() -> Component.literal(
                "Chunk regeneration complete! "
                        + counters[0] + " regenerated, "
                        + counters[1] + " skipped (protected), "
                        + counters[2] + " failed."),
                true
        );

        return (int) counters[0];
    }
}
