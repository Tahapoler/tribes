package com.tribesmod;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.Level;

public class TribesMod implements ModInitializer {
    public static final String MOD_ID = "tribes";

    public static final EntityType<TribesmanEntity> TRIBESMAN = Registry.register(
            BuiltInRegistries.ENTITY_TYPE,
            ResourceLocation.fromNamespaceAndPath(MOD_ID, "tribesman"),
            EntityType.Builder.of(TribesmanEntity::new, MobCategory.CREATURE)
                    .sized(0.6f, 1.95f).clientTrackingRange(10).build("tribesman"));

    @Override
    public void onInitialize() {
        FabricDefaultAttributeRegistry.register(TRIBESMAN, TribesmanEntity.createAttributes());
        ServerTickEvents.END_WORLD_TICK.register(level -> {
            if (level.dimension() == Level.OVERWORLD && level.getGameTime() % 100 == 0) {
                TribeManager.tick(level);
            }
        });
        CommandRegistrationCallback.EVENT.register(TribesCommands::register);
    }
}
