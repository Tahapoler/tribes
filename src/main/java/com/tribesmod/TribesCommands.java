package com.tribesmod;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class TribesCommands {
    public static void register(CommandDispatcher<CommandSourceStack> d, CommandBuildContext ctx, Commands.CommandSelection env) {
        d.register(Commands.literal("tribes")
                .executes(c -> list(c.getSource()))
                .then(Commands.literal("found").requires(s -> s.hasPermission(2)).executes(c -> found(c.getSource()))));
    }

    private static int list(CommandSourceStack src) {
        ServerLevel ow = src.getServer().overworld();
        TribeData data = TribeData.get(ow);
        if (data.tribes.isEmpty()) {
            src.sendSuccess(() -> Component.literal("No tribes yet. Explore the world!"), false);
            return 0;
        }
        BlockPos me = BlockPos.containing(src.getPosition());
        List<Tribe> l = new ArrayList<>(data.tribes.values());
        l.sort(Comparator.comparingDouble(t -> t.center.distSqr(me)));
        int n = 0;
        for (Tribe t : l) {
            if (n++ >= 15) break;
            StringBuilder en = new StringBuilder();
            for (int id : t.enemies) {
                Tribe o = data.tribes.get(id);
                if (o != null) en.append(o.name).append(' ');
            }
            String line = String.format("%s [Lv%d] pop:%d food:%d wood:%d wealth:%d @ %d %d %d  war: %s",
                    t.name, t.level, t.pop, t.food, t.wood, t.wealth,
                    t.center.getX(), t.center.getY(), t.center.getZ(), en.length() == 0 ? "-" : en);
            src.sendSuccess(() -> Component.literal(line), false);
        }
        return l.size();
    }

    private static int found(CommandSourceStack src) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerLevel lv = src.getLevel();
        if (lv.dimension() != Level.OVERWORLD) {
            src.sendFailure(Component.literal("Overworld only."));
            return 0;
        }
        BlockPos p = src.getPlayerOrException().blockPosition();
        int x = p.getX() + 14, z = p.getZ();
        BlockPos pos = new BlockPos(x, TribeManager.groundY(lv, x, z), z);
        if (!lv.hasChunksAt(pos.offset(-24, 0, -24), pos.offset(24, 0, 24))) {
            src.sendFailure(Component.literal("Chunks not loaded here."));
            return 0;
        }
        Tribe t = TribeManager.found(lv, TribeData.get(lv), pos);
        src.sendSuccess(() -> Component.literal("Founded tribe " + t.name), true);
        return 1;
    }
}
