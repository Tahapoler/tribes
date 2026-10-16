package com.tribesmod;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

public class Tribe {
    public int id;
    public String name = "Tribe";
    public BlockPos center = BlockPos.ZERO;
    public int food = 20, wood = 0, wealth = 0, level = 1, pop = 4, slots = 0;
    public Set<Integer> enemies = new HashSet<>();

    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.putInt("id", id);
        t.putString("name", name);
        t.putInt("x", center.getX());
        t.putInt("y", center.getY());
        t.putInt("z", center.getZ());
        t.putInt("food", food);
        t.putInt("wood", wood);
        t.putInt("wealth", wealth);
        t.putInt("level", level);
        t.putInt("pop", pop);
        t.putInt("slots", slots);
        t.putIntArray("enemies", new ArrayList<>(enemies));
        return t;
    }

    public static Tribe load(CompoundTag t) {
        Tribe r = new Tribe();
        r.id = t.getInt("id");
        r.name = t.getString("name");
        r.center = new BlockPos(t.getInt("x"), t.getInt("y"), t.getInt("z"));
        r.food = t.getInt("food");
        r.wood = t.getInt("wood");
        r.wealth = t.getInt("wealth");
        r.level = Math.max(1, t.getInt("level"));
        r.pop = t.getInt("pop");
        r.slots = t.getInt("slots");
        for (int e : t.getIntArray("enemies")) r.enemies.add(e);
        return r;
    }

    public double strength() {
        return pop + level * 3 + wealth / 40.0;
    }
}
