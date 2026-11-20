package com.laevatein.mixin;

import com.laevatein.LaevateinConfig;
import com.laevatein.combat.PlayerHeatAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 热量附着于玩家实体（非剑物品），重新登录后保留进度。 */
@Mixin(Player.class)
public abstract class PlayerHeatMixin implements PlayerHeatAccess {
    @Unique private int laevatein$heatTicks;

    @Override public int laevatein$getHeatTicks() { return laevatein$heatTicks; }

    @Override public void laevatein$setHeatTicks(int ticks) {
        laevatein$heatTicks = Math.max(0, Math.min(LaevateinConfig.HEAT_MAX_TICKS, ticks));
    }

    @Inject(method = "addAdditionalSaveData", at = @At("TAIL"))
    private void laevatein$saveHeat(CompoundTag tag, CallbackInfo ci) {
        tag.putInt("LaevateinWeaponHeat", laevatein$heatTicks);
    }

    @Inject(method = "readAdditionalSaveData", at = @At("TAIL"))
    private void laevatein$loadHeat(CompoundTag tag, CallbackInfo ci) {
        laevatein$setHeatTicks(tag.getInt("LaevateinWeaponHeat"));
    }
}
