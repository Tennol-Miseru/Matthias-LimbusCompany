package com.laevatein.registry;

import com.laevatein.Laevatein;
import com.laevatein.entity.ThrownLaevatein;
import com.laevatein.entity.MatthiasEntity;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;

public final class ModEntities {
    private ModEntities() {}

    public static final EntityType<ThrownLaevatein> THROWN_LAEVATEIN = Registry.register(BuiltInRegistries.ENTITY_TYPE,
        Laevatein.id("thrown_laevatein"),
        EntityType.Builder.<ThrownLaevatein>of(ThrownLaevatein::new, MobCategory.MISC)
            .sized(0.5f, 0.5f)
            .clientTrackingRange(8)
            .updateInterval(1)          // 扎中后跟随目标移动，需要每 tick 同步位置
            .build("thrown_laevatein"));

    public static final EntityType<MatthiasEntity> MATTHIAS = Registry.register(BuiltInRegistries.ENTITY_TYPE,
        Laevatein.id("matthias"),
        EntityType.Builder.<MatthiasEntity>of(MatthiasEntity::new, MobCategory.CREATURE)
            .sized(0.6f, 1.8f).clientTrackingRange(10).updateInterval(2).build("matthias"));

    /** Same Matthias AI/model as MATTHIAS, but the newly spawned sword starts with no wrapping. */
    public static final EntityType<MatthiasEntity> MATTHIAS_RELEASED = Registry.register(BuiltInRegistries.ENTITY_TYPE,
        Laevatein.id("matthias_released"),
        EntityType.Builder.<MatthiasEntity>of(MatthiasEntity::new, MobCategory.CREATURE)
            .sized(0.6f, 1.8f).clientTrackingRange(10).updateInterval(2).build("matthias_released"));

    public static void init() {
        FabricDefaultAttributeRegistry.register(MATTHIAS, MatthiasEntity.createAttributes());
        FabricDefaultAttributeRegistry.register(MATTHIAS_RELEASED, MatthiasEntity.createAttributes());
    }
}
