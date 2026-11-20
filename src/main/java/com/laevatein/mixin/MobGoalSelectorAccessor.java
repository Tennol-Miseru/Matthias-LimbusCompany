package com.laevatein.mixin;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** GoalSelector 在 1.21.1 中是受保护字段，通过 Accessor 安全添加临时调试 Goal。 */
@Mixin(Mob.class)
public interface MobGoalSelectorAccessor {
    @Accessor("goalSelector") GoalSelector laevatein$getGoalSelector();
}
