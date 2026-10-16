package com.tribesmod;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;

public class TribeManager {
    static final String[] P1 = {"Stone", "Iron", "Red", "Black", "Wolf", "Raven", "Storm", "Ember", "Frost", "Gold", "Oak", "Shadow", "Sun", "Bone"};
    static final String[] P2 = {"fang", "clan", "hold", "born", "wood", "guard", "hawk", "blade", "hollow", "men", "river", "peak"};
    static final double[] WEIGHTS = {4, 2, 2.5, 1.5};
    static final int MIN_DIST = 150;
    static final int MAX_TRIBES = 60;
    static final int MAX_ENTITIES = 14;

    // ================= main loop (every 5 seconds) =================
    public static void tick(ServerLevel level) {
        TribeData data = TribeData.get(level);
        RandomSource rnd = level.random;
        if (data.tribes.size() < MAX_TRIBES) {
            for (ServerPlayer p : level.players()) {
                if (rnd.nextInt(2) == 0) tryFound(level, data, p.blockPosition(), rnd);
            }
        }
        for (Tribe t : new ArrayList<>(data.tribes.values())) tickTribe(level, data, t, rnd);
        data.setDirty();
    }

    static void tickTribe(ServerLevel level, TribeData data, Tribe t, RandomSource rnd) {
        if (!data.tribes.containsKey(t.id)) return;
        boolean near = isNear(level, t);

        if (near) {
            List<TribesmanEntity> mem = members(level, t);
            if (mem.size() > t.pop) t.pop = mem.size();
            if (t.pop <= 0) { removeTribe(data, t); return; }
            int[] c = new int[4];
            for (TribesmanEntity m : mem) c[m.getRole()]++;
            int want = Math.min(t.pop, MAX_ENTITIES) - mem.size();
            for (int i = 0; i < Math.min(want, 3); i++) spawnMember(level, t, pickRole(c));
            if (t.wood >= 30 && t.slots < 4 + 2 * t.level
                    && level.hasChunksAt(t.center.offset(-40, 0, -40), t.center.offset(40, 0, 40))) {
                t.wood -= 30;
                buildSlot(level, t);
            }
        } else {
            if (t.pop <= 0) { removeTribe(data, t); return; }
            // simulated growth while nobody is around
            t.food += t.pop + 1;
            t.wood += Math.max(1, t.pop / 2);
        }

        // ---- economy ----
        t.food += 1;
        t.food = Math.max(0, t.food - t.pop / 3);
        if (t.food >= 40 && t.pop < 6 + 4 * t.level) { t.food -= 40; t.pop++; }
        if (t.food > 150) { t.food -= 100; t.wealth += 20; }
        if (t.wood > 200 && t.slots >= 4 + 2 * t.level) { t.wood -= 100; t.wealth += 10; }
        if (t.level < 10 && t.wealth >= 40 * t.level && t.pop >= 4 + 3 * t.level) {
            t.wealth -= 40 * t.level;
            t.level++;
            notifyNear(level, t.center, "§6[" + t.name + "] grew to level " + t.level + "!");
        }

        // ---- diplomacy ----
        if (rnd.nextInt(6) == 0) {
            Tribe o = nearestOther(data, t, 450);
            if (o != null) {
                if (t.enemies.contains(o.id)) {
                    if (rnd.nextInt(4) == 0) makePeace(level, t, o);
                } else if (rnd.nextInt(8) == 0 && t.strength() >= o.strength() * 0.7) {
                    t.enemies.add(o.id);
                    o.enemies.add(t.id);
                    notifyNear(level, t.center, "§c[WAR] " + t.name + " declared war on " + o.name + "!");
                } else if (rnd.nextInt(3) == 0) {
                    trade(t, o);
                }
            }
        }

        // ---- simulated war (only when no player is near either tribe) ----
        if (!near) {
            for (int eid : new ArrayList<>(t.enemies)) {
                Tribe o = data.tribes.get(eid);
                if (o == null) { t.enemies.remove(eid); continue; }
                if (t.id < o.id && !isNear(level, o) && rnd.nextInt(3) == 0) skirmish(level, t, o, rnd);
            }
        }
    }

    static void trade(Tribe a, Tribe b) {
        a.wealth += 5;
        b.wealth += 5;
        if (a.food > b.food + 30) { a.food -= 10; b.food += 10; }
        else if (b.food > a.food + 30) { b.food -= 10; a.food += 10; }
    }

    static void skirmish(ServerLevel level, Tribe a, Tribe b, RandomSource rnd) {
        double sa = a.strength() * (0.5 + rnd.nextDouble());
        double sb = b.strength() * (0.5 + rnd.nextDouble());
        Tribe w = sa >= sb ? a : b;
        Tribe l = sa >= sb ? b : a;
        if (l.pop <= 2) {
            w.wealth += 20;
            l.wealth = Math.max(0, l.wealth - 20);
            makePeace(level, w, l);
            return;
        }
        l.pop--;
        w.wealth += 10;
        w.food += 10;
        l.food = Math.max(0, l.food - 10);
    }

    static void makePeace(ServerLevel level, Tribe a, Tribe b) {
        a.enemies.remove(b.id);
        b.enemies.remove(a.id);
        notifyNear(level, a.center, "§a[PEACE] " + a.name + " and " + b.name + " made peace.");
    }

    static void removeTribe(TribeData data, Tribe t) {
        data.tribes.remove(t.id);
        for (Tribe o : data.tribes.values()) o.enemies.remove(t.id);
    }

    static Tribe nearestOther(TribeData data, Tribe t, double maxDist) {
        Tribe best = null;
        double bd = maxDist * maxDist;
        for (Tribe o : data.tribes.values()) {
            if (o.id == t.id) continue;
            double d = o.center.distSqr(t.center);
            if (d < bd) { bd = d; best = o; }
        }
        return best;
    }

    static void notifyNear(ServerLevel level, BlockPos pos, String msg) {
        for (ServerPlayer p : level.players()) {
            if (p.blockPosition().distSqr(pos) < 400 * 400) p.sendSystemMessage(Component.literal(msg));
        }
    }

    static boolean isNear(ServerLevel level, Tribe t) {
        for (ServerPlayer p : level.players()) {
            if (p.blockPosition().distSqr(t.center) < 48 * 48) return true;
        }
        return false;
    }

    static List<TribesmanEntity> members(ServerLevel level, Tribe t) {
        return level.getEntitiesOfClass(TribesmanEntity.class, new AABB(t.center).inflate(48),
                e -> e.getTribeId() == t.id && e.isAlive());
    }

    static int pickRole(int[] c) {
        int best = 0;
        double bv = Double.MAX_VALUE;
        for (int r = 0; r < 4; r++) {
            double v = c[r] / WEIGHTS[r];
            if (v < bv) { bv = v; best = r; }
        }
        c[best]++;
        return best;
    }

    // ================= founding =================
    static void tryFound(ServerLevel level, TribeData data, BlockPos origin, RandomSource rnd) {
        int dist = 40 + rnd.nextInt(45);
        double ang = rnd.nextDouble() * Math.PI * 2;
        int x = origin.getX() + (int) (Math.cos(ang) * dist);
        int z = origin.getZ() + (int) (Math.sin(ang) * dist);
        BlockPos probe = new BlockPos(x, 64, z);
        if (!level.hasChunksAt(probe.offset(-24, 0, -24), probe.offset(24, 0, 24))) return;
        BlockPos pos = new BlockPos(x, groundY(level, x, z), z);
        if (!suitable(level, pos)) return;
        for (Tribe t : data.tribes.values()) {
            if (t.center.distSqr(pos) < MIN_DIST * MIN_DIST) return;
        }
        found(level, data, pos);
    }

    static boolean suitable(ServerLevel level, BlockPos pos) {
        BlockState below = level.getBlockState(pos.below());
        if (pos.getY() <= level.getSeaLevel()) return false;
        if (!(below.is(BlockTags.DIRT) || below.is(BlockTags.SAND))) return false;
        if (!level.getFluidState(pos).isEmpty() || !level.getFluidState(pos.below()).isEmpty()) return false;
        int[][] corners = {{8, 8}, {-8, 8}, {8, -8}, {-8, -8}};
        for (int[] c : corners) {
            int h = groundY(level, pos.getX() + c[0], pos.getZ() + c[1]);
            if (Math.abs(h - pos.getY()) > 4) return false;
            if (!level.getFluidState(new BlockPos(pos.getX() + c[0], h - 1, pos.getZ() + c[1])).isEmpty()) return false;
        }
        return true;
    }

    public static int groundY(ServerLevel level, int x, int z) {
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        while (y > level.getMinBuildHeight()) {
            BlockState s = level.getBlockState(new BlockPos(x, y - 1, z));
            if (s.isAir() || s.is(BlockTags.LOGS) || s.is(BlockTags.LEAVES)) y--;
            else break;
        }
        return y;
    }

    public static Tribe found(ServerLevel level, TribeData data, BlockPos pos) {
        RandomSource rnd = level.random;
        Tribe t = new Tribe();
        t.id = data.nextId++;
        t.name = P1[rnd.nextInt(P1.length)] + P2[rnd.nextInt(P2.length)];
        t.center = pos;
        flatten(level, pos.offset(-7, 0, -7), 15, 15);
        level.setBlock(pos, Blocks.CAMPFIRE.defaultBlockState(), 3);
        data.tribes.put(t.id, t);
        for (int i = 0; i < 4; i++) buildSlot(level, t);
        int[] c = new int[4];
        for (int i = 0; i < 4; i++) spawnMember(level, t, pickRole(c));
        data.setDirty();
        notifyNear(level, pos, "§e[Tribe] A new tribe appeared: " + t.name);
        return t;
    }

    public static void spawnMember(ServerLevel level, Tribe t, int role) {
        RandomSource r = level.random;
        int x = t.center.getX() + r.nextInt(9) - 4;
        int z = t.center.getZ() + r.nextInt(9) - 4;
        int y = groundY(level, x, z);
        TribesmanEntity e = new TribesmanEntity(TribesMod.TRIBESMAN, level);
        e.moveTo(x + 0.5, y, z + 0.5, r.nextFloat() * 360f, 0f);
        e.setupTribe(t, role);
        level.addFreshEntity(e);
    }

    // ================= building =================
    static void buildSlot(ServerLevel level, Tribe t) {
        int i = t.slots++;
        double ang = Math.toRadians(i * 60.0 + (i / 6) * 30.0);
        int r = 11 + 7 * (i / 6);
        int cx = t.center.getX() + (int) Math.round(Math.cos(ang) * r);
        int cz = t.center.getZ() + (int) Math.round(Math.sin(ang) * r);
        int y = groundY(level, cx, cz);
        BlockPos o = new BlockPos(cx - 2, y, cz - 2);
        flatten(level, o, 5, 5);
        if (i % 2 == 0) buildHut(level, o); else buildFarm(level, o);
    }

    static void flatten(ServerLevel level, BlockPos o, int w, int d) {
        for (int dx = 0; dx < w; dx++) {
            for (int dz = 0; dz < d; dz++) {
                for (int dy = 0; dy <= 6; dy++) {
                    BlockPos p = o.offset(dx, dy, dz);
                    if (!level.getBlockState(p).isAir()) level.setBlock(p, Blocks.AIR.defaultBlockState(), 2);
                }
                BlockPos g = o.offset(dx, -1, dz);
                BlockState cur = level.getBlockState(g);
                if (!(cur.is(BlockTags.DIRT) || cur.is(BlockTags.SAND))) {
                    level.setBlock(g, Blocks.GRASS_BLOCK.defaultBlockState(), 2);
                }
                for (int dy = -2; dy >= -6; dy--) {
                    BlockPos q = o.offset(dx, dy, dz);
                    BlockState s = level.getBlockState(q);
                    if (s.isAir() || !s.getFluidState().isEmpty() || s.is(BlockTags.LEAVES) || s.is(BlockTags.LOGS)) {
                        level.setBlock(q, Blocks.DIRT.defaultBlockState(), 2);
                    } else break;
                }
            }
        }
    }

    static void buildHut(ServerLevel level, BlockPos o) {
        BlockState plank = Blocks.SPRUCE_PLANKS.defaultBlockState();
        BlockState cobble = Blocks.COBBLESTONE.defaultBlockState();
        for (int dx = 0; dx < 5; dx++) {
            for (int dz = 0; dz < 5; dz++) {
                level.setBlock(o.offset(dx, -1, dz), plank, 3);
                boolean edge = dx == 0 || dx == 4 || dz == 0 || dz == 4;
                boolean corner = (dx == 0 || dx == 4) && (dz == 0 || dz == 4);
                if (edge) {
                    for (int dy = 0; dy < 3; dy++) {
                        BlockState s = corner ? cobble : plank;
                        if (dy == 1 && !corner && ((dz == 2 && (dx == 0 || dx == 4)) || (dx == 2 && dz == 4))) s = Blocks.GLASS_PANE.defaultBlockState();
                        if (dx == 2 && dz == 0 && dy < 2) s = Blocks.AIR.defaultBlockState();
                        level.setBlock(o.offset(dx, dy, dz), s, 3);
                    }
                }
                level.setBlock(o.offset(dx, 3, dz), plank, 3);
            }
        }
        level.setBlock(o.offset(2, 0, 2), Blocks.LANTERN.defaultBlockState(), 3);
        level.setBlock(o.offset(1, 0, 3), Blocks.CRAFTING_TABLE.defaultBlockState(), 3);
        level.setBlock(o.offset(3, 0, 3), Blocks.BARREL.defaultBlockState(), 3);
    }

    static void buildFarm(ServerLevel level, BlockPos o) {
        RandomSource rnd = level.random;
        for (int dx = 0; dx < 5; dx++) {
            for (int dz = 0; dz < 5; dz++) {
                BlockPos g = o.offset(dx, -1, dz);
                if (dx == 2 && dz == 2) {
                    level.setBlock(g, Blocks.WATER.defaultBlockState(), 3);
                } else {
                    level.setBlock(g, Blocks.FARMLAND.defaultBlockState().setValue(FarmBlock.MOISTURE, 7), 3);
                    level.setBlock(g.above(), Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, rnd.nextInt(8)), 3);
                }
            }
        }
    }
}
