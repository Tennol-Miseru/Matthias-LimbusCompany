package com.laevatein.registry;

import com.laevatein.Laevatein;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;

/** 纯显示型正面状态：实际伤害上限由服务端实际伤害流程保证。 */
public final class ModEffects {
    private ModEffects() {}

    public static final Holder<MobEffect> EXTRAORDINARY_TENACITY = Registry.registerForHolder(
        BuiltInRegistries.MOB_EFFECT,
        Laevatein.id("extraordinary_tenacity"),
        new TenacityEffect());

    /** 99 层高温时获得的显示状态；范围灼烧由 LaevateinHeat 服务端结算。 */
    public static final Holder<MobEffect> SCORCHED_BY_LAEVATEIN = Registry.registerForHolder(
        BuiltInRegistries.MOB_EFFECT,
        Laevatein.id("scorched_by_laevatein"),
        new ScorchedEffect());

    private static final class ScorchedEffect extends MobEffect {
        private ScorchedEffect() { super(MobEffectCategory.HARMFUL, 0xF05A29); }
    }

    private static final class TenacityEffect extends MobEffect {
        private TenacityEffect() { super(MobEffectCategory.BENEFICIAL, 0x8F62BB); }
    }

    public static void init() {}
}
