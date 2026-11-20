package com.laevatein.mixin;

import com.laevatein.registry.ModEffects;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Player overrides LivingEntity#actuallyHurt in MC 1.21.1, so the old
 * LivingEntity-targeted injection did not run for players. Cap the damage
 * argument on Player's actual damage path, before armor/absorption handling.
 * This limits vanilla health loss from each damaging hit to at most 1 point.
 */
@Mixin(Player.class)
public abstract class PlayerTenacityMixin {
    @ModifyVariable(
        method = "actuallyHurt(Lnet/minecraft/world/damagesource/DamageSource;F)V",
        at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private float laevatein$capTenacityDamage(float amount) {
        if ((Object) this instanceof ServerPlayer player
            && player.hasEffect(ModEffects.EXTRAORDINARY_TENACITY)) {
            return Math.min(amount, 1.0f);
        }
        return amount;
    }
}
