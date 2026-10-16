package com.tribesmod;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.*;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

public class TribesmanEntity extends PathfinderMob {
    public static final int FARMER = 0, WOODCUTTER = 1, TRADER = 2, WARRIOR = 3;
    public static final String[] ROLE_NAMES = {"Farmer", "Woodcutter", "Trader", "Warrior"};

    private static final EntityDataAccessor<Integer> TRIBE =
            SynchedEntityData.defineId(TribesmanEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> ROLE =
            SynchedEntityData.defineId(TribesmanEntity.class, EntityDataSerializers.INT);

    public TribesmanEntity(EntityType<? extends PathfinderMob> type, Level level) {
        super(type, level);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return PathfinderMob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20.0)
                .add(Attributes.MOVEMENT_SPEED, 0.3)
                .add(Attributes.ATTACK_DAMAGE, 3.0)
                .add(Attributes.FOLLOW_RANGE, 32.0);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(TRIBE, 0);
        builder.define(ROLE, 0);
    }

    public int getTribeId() { return entityData.get(TRIBE); }
    public int getRole() { return Math.max(0, Math.min(3, entityData.get(ROLE))); }

    public Tribe tribe() {
        if (level() instanceof ServerLevel sl) return TribeData.get(sl).tribes.get(getTribeId());
        return null;
    }

    public void setupTribe(Tribe t, int role) {
        entityData.set(TRIBE, t.id);
        entityData.set(ROLE, role);
        setCustomName(Component.literal(t.name + " " + ROLE_NAMES[role]));
        setCustomNameVisible(true);
        setPersistenceRequired();
        equip(t.level);
    }

    private void hand(Item i) { setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(i)); }

    private void equip(int lvl) {
        int tier = lvl >= 6 ? 2 : lvl >= 3 ? 1 : 0;
        switch (getRole()) {
            case FARMER -> hand(new Item[]{Items.STONE_HOE, Items.IRON_HOE, Items.DIAMOND_HOE}[tier]);
            case WOODCUTTER -> hand(new Item[]{Items.STONE_AXE, Items.IRON_AXE, Items.DIAMOND_AXE}[tier]);
            case TRADER -> hand(Items.EMERALD);
            case WARRIOR -> {
                hand(new Item[]{Items.STONE_SWORD, Items.IRON_SWORD, Items.DIAMOND_SWORD}[tier]);
                Item[][] armor = {
                        {Items.LEATHER_HELMET, Items.LEATHER_CHESTPLATE, Items.LEATHER_LEGGINGS, Items.LEATHER_BOOTS},
                        {Items.IRON_HELMET, Items.IRON_CHESTPLATE, Items.IRON_LEGGINGS, Items.IRON_BOOTS},
                        {Items.DIAMOND_HELMET, Items.DIAMOND_CHESTPLATE, Items.DIAMOND_LEGGINGS, Items.DIAMOND_BOOTS}};
                EquipmentSlot[] slots = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
                for (int i = 0; i < 4; i++) setItemSlot(slots[i], new ItemStack(armor[tier][i]));
            }
        }
        for (EquipmentSlot s : new EquipmentSlot[]{EquipmentSlot.MAINHAND, EquipmentSlot.HEAD,
                EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) setDropChance(s, 0f);
    }

    @Override
    protected void registerGoals() {
        goalSelector.addGoal(0, new FloatGoal(this));
        goalSelector.addGoal(1, new MeleeAttackGoal(this, 1.1, true));
        goalSelector.addGoal(2, new TribeGoals.RaidGoal(this));
        goalSelector.addGoal(2, new TribeGoals.TradeGoal(this));
        goalSelector.addGoal(3, new TribeGoals.WorkGoal(this));
        goalSelector.addGoal(4, new TribeGoals.ReturnHomeGoal(this));
        goalSelector.addGoal(5, new WaterAvoidingRandomStrollGoal(this, 0.8));
        goalSelector.addGoal(6, new LookAtPlayerGoal(this, Player.class, 8.0f));
        goalSelector.addGoal(7, new RandomLookAroundGoal(this));
        targetSelector.addGoal(1, new HurtByTargetGoal(this));
        targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, LivingEntity.class, 10, true, false, this::shouldTarget));
    }

    private boolean shouldTarget(LivingEntity e) {
        if (e instanceof Monster) return true;
        if (e instanceof TribesmanEntity o && getRole() == WARRIOR && o.getTribeId() != getTribeId()) {
            Tribe t = tribe();
            return t != null && t.enemies.contains(o.getTribeId());
        }
        return false;
    }

    @Override
    public void die(DamageSource src) {
        super.die(src);
        if (level() instanceof ServerLevel sl) {
            TribeData data = TribeData.get(sl);
            Tribe t = data.tribes.get(getTribeId());
            if (t != null) {
                t.pop = Math.max(0, t.pop - 1);
                if (src.getEntity() instanceof TribesmanEntity k) {
                    Tribe kt = data.tribes.get(k.getTribeId());
                    if (kt != null) { kt.wealth += 5; kt.food += 5; }
                }
                data.setDirty();
            }
        }
    }

    @Override
    public InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (!level().isClientSide) {
            Tribe t = tribe();
            if (t != null) {
                player.displayClientMessage(Component.literal(String.format(
                        "%s | Lv %d | Pop %d | Food %d | Wood %d | Wealth %d | At war with: %d",
                        t.name, t.level, t.pop, t.food, t.wood, t.wealth, t.enemies.size())), true);
            }
        }
        return InteractionResult.sidedSuccess(level().isClientSide);
    }

    @Override
    public boolean removeWhenFarAway(double d) { return false; }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putInt("TribeId", getTribeId());
        tag.putInt("TribeRole", getRole());
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        entityData.set(TRIBE, tag.getInt("TribeId"));
        entityData.set(ROLE, tag.getInt("TribeRole"));
    }
}
