package com.tribesmod;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.EnumSet;

public class TribeGoals {

    /** Farmer: harvests ripe crops and replants. Woodcutter: cuts trees and replants saplings. */
    public static class WorkGoal extends Goal {
        private final TribesmanEntity m;
        private BlockPos target;
        private int cooldown, timeout;

        public WorkGoal(TribesmanEntity m) {
            this.m = m;
            setFlags(EnumSet.of(Flag.MOVE));
        }

        @Override
        public boolean canUse() {
            if (m.getRole() > 1 || !(m.level() instanceof ServerLevel)) return false;
            if (cooldown > 0) { cooldown--; return false; }
            target = find((ServerLevel) m.level());
            if (target == null) { cooldown = 60; return false; }
            return true;
        }

        @Override
        public boolean canContinueToUse() { return target != null && timeout > 0; }

        @Override
        public void start() {
            timeout = 300;
            go();
        }

        private void go() {
            m.getNavigation().moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, 1.0);
        }

        @Override
        public void tick() {
            timeout--;
            if (m.distanceToSqr(Vec3.atCenterOf(target)) < 9.0) {
                act((ServerLevel) m.level());
                target = null;
                cooldown = 20;
            } else if (m.getNavigation().isDone()) {
                go();
            }
        }

        @Override
        public void stop() {
            target = null;
            m.getNavigation().stop();
        }

        private BlockPos find(ServerLevel level) {
            Tribe t = m.tribe();
            if (t == null) return null;
            BlockPos c = m.blockPosition();
            double best = Double.MAX_VALUE;
            BlockPos bp = null;
            for (BlockPos p : BlockPos.betweenClosed(c.offset(-12, -1, -12), c.offset(12, 2, 12))) {
                if (p.distSqr(t.center) > 32 * 32) continue;
                BlockState s = level.getBlockState(p);
                boolean ok;
                if (m.getRole() == TribesmanEntity.FARMER) {
                    ok = s.getBlock() instanceof CropBlock cb && cb.isMaxAge(s);
                } else {
                    ok = s.is(BlockTags.LOGS) && isTree(level, p);
                }
                if (ok) {
                    double d = p.distSqr(c);
                    if (d < best) { best = d; bp = p.immutable(); }
                }
            }
            return bp;
        }

        private boolean isTree(ServerLevel level, BlockPos p) {
            for (BlockPos q : BlockPos.betweenClosed(p.offset(-2, 1, -2), p.offset(2, 7, 2))) {
                if (level.getBlockState(q).is(BlockTags.LEAVES)) return true;
            }
            return false;
        }

        private void act(ServerLevel level) {
            Tribe t = m.tribe();
            if (t == null) return;
            BlockState s = level.getBlockState(target);
            if (m.getRole() == TribesmanEntity.FARMER && s.getBlock() instanceof CropBlock cb && cb.isMaxAge(s)) {
                level.destroyBlock(target, false);
                level.setBlock(target, cb.getStateForAge(0), 3);
                t.food += 3;
            } else if (m.getRole() == TribesmanEntity.WOODCUTTER && s.is(BlockTags.LOGS)) {
                level.destroyBlock(target, false);
                t.wood += 4;
                if (level.getBlockState(target.below()).is(BlockTags.DIRT)) {
                    level.setBlock(target, Blocks.OAK_SAPLING.defaultBlockState(), 3);
                }
            } else {
                return;
            }
            m.swing(InteractionHand.MAIN_HAND);
            TribeData.get(level).setDirty();
        }
    }

    /** Trader: walks to a friendly nearby tribe and trades. */
    public static class TradeGoal extends Goal {
        private final TribesmanEntity m;
        private Tribe target;
        private int cd, timeout;

        public TradeGoal(TribesmanEntity m) {
            this.m = m;
            setFlags(EnumSet.of(Flag.MOVE));
        }

        @Override
        public boolean canUse() {
            if (m.getRole() != TribesmanEntity.TRADER || !(m.level() instanceof ServerLevel)) return false;
            if (cd > 0) { cd--; return false; }
            ServerLevel sl = (ServerLevel) m.level();
            Tribe mine = m.tribe();
            if (mine == null) { cd = 100; return false; }
            double best = 120 * 120;
            Tribe bt = null;
            for (Tribe o : TribeData.get(sl).tribes.values()) {
                if (o.id == mine.id || mine.enemies.contains(o.id)) continue;
                double d = o.center.distSqr(m.blockPosition());
                if (d < best && o.center.distSqr(mine.center) > 24 * 24) { best = d; bt = o; }
            }
            if (bt == null) { cd = 200; return false; }
            target = bt;
            return true;
        }

        @Override
        public boolean canContinueToUse() { return target != null && timeout > 0; }

        @Override
        public void start() {
            timeout = 1200;
            go();
        }

        private void go() {
            m.getNavigation().moveTo(target.center.getX() + 0.5, target.center.getY(), target.center.getZ() + 0.5, 1.0);
        }

        @Override
        public void tick() {
            timeout--;
            if (m.blockPosition().distSqr(target.center) < 36) {
                Tribe mine = m.tribe();
                if (mine != null) {
                    mine.wealth += 6;
                    target.wealth += 3;
                    if (mine.food > target.food + 20) { mine.food -= 10; target.food += 10; }
                    else if (target.food > mine.food + 20) { target.food -= 10; mine.food += 10; }
                    m.swing(InteractionHand.MAIN_HAND);
                    TribeData.get((ServerLevel) m.level()).setDirty();
                }
                target = null;
                cd = 1200;
            } else if (m.getNavigation().isDone()) {
                go();
            }
        }

        @Override
        public void stop() {
            target = null;
            m.getNavigation().stop();
        }
    }

    /** Warrior: marches toward the nearest enemy tribe. The normal targeting AI does the fighting. */
    public static class RaidGoal extends Goal {
        private final TribesmanEntity m;
        private Tribe target;
        private int cd, timeout;

        public RaidGoal(TribesmanEntity m) {
            this.m = m;
            setFlags(EnumSet.of(Flag.MOVE));
        }

        @Override
        public boolean canUse() {
            if (m.getRole() != TribesmanEntity.WARRIOR || !(m.level() instanceof ServerLevel)) return false;
            if (cd > 0) { cd--; return false; }
            ServerLevel sl = (ServerLevel) m.level();
            Tribe mine = m.tribe();
            if (mine == null || mine.enemies.isEmpty()) { cd = 100; return false; }
            TribeData data = TribeData.get(sl);
            double best = 100 * 100;
            Tribe bt = null;
            for (int id : new ArrayList<>(mine.enemies)) {
                Tribe o = data.tribes.get(id);
                if (o == null) continue;
                double d = o.center.distSqr(m.blockPosition());
                if (d < best) { best = d; bt = o; }
            }
            if (bt == null || m.getRandom().nextInt(3) != 0) { cd = 80; return false; }
            target = bt;
            return true;
        }

        @Override
        public boolean canContinueToUse() {
            Tribe mine = m.tribe();
            return target != null && timeout > 0 && mine != null && mine.enemies.contains(target.id)
                    && m.blockPosition().distSqr(target.center) > 36;
        }

        @Override
        public void start() {
            timeout = 600;
            go();
        }

        private void go() {
            m.getNavigation().moveTo(target.center.getX() + 0.5, target.center.getY(), target.center.getZ() + 0.5, 1.1);
        }

        @Override
        public void tick() {
            timeout--;
            if (m.getNavigation().isDone()) go();
        }

        @Override
        public void stop() {
            target = null;
            cd = 400;
            m.getNavigation().stop();
        }
    }

    /** Everyone drifts back home if they wander too far. */
    public static class ReturnHomeGoal extends Goal {
        private final TribesmanEntity m;

        public ReturnHomeGoal(TribesmanEntity m) {
            this.m = m;
            setFlags(EnumSet.of(Flag.MOVE));
        }

        @Override
        public boolean canUse() {
            Tribe t = m.tribe();
            return t != null && m.getTarget() == null && m.blockPosition().distSqr(t.center) > 28 * 28;
        }

        @Override
        public boolean canContinueToUse() {
            Tribe t = m.tribe();
            return t != null && !m.getNavigation().isDone() && m.blockPosition().distSqr(t.center) > 64;
        }

        @Override
        public void start() {
            Tribe t = m.tribe();
            if (t != null) m.getNavigation().moveTo(t.center.getX() + 0.5, t.center.getY(), t.center.getZ() + 0.5, 1.0);
        }
    }
}
