package com.laevatein.mixin;

import com.laevatein.combat.LedgerCooldownAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 玩家冷却存在 NBT 上，与书本数目无关；离线时不倒计时。 */
@Mixin(Player.class)
public abstract class PlayerLedgerMixin implements LedgerCooldownAccess {
    @Unique private int laevatein$ledgerCooldownTicks;

    @Override
    public int laevatein$getLedgerCooldownTicks() { return laevatein$ledgerCooldownTicks; }

    @Override
    public void laevatein$setLedgerCooldownTicks(int ticks) {
        laevatein$ledgerCooldownTicks = Math.max(0, ticks);
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void laevatein$tickLedgerCooldown(CallbackInfo ci) {
        Player player = (Player)(Object)this;
        if (!player.level().isClientSide && laevatein$ledgerCooldownTicks > 0) {
            laevatein$ledgerCooldownTicks--;
        }
    }

    @Inject(method = "addAdditionalSaveData", at = @At("TAIL"))
    private void laevatein$saveLedger(CompoundTag tag, CallbackInfo ci) {
        tag.putInt("LaevateinLedgerCooldown", laevatein$ledgerCooldownTicks);
    }

    @Inject(method = "readAdditionalSaveData", at = @At("TAIL"))
    private void laevatein$loadLedger(CompoundTag tag, CallbackInfo ci) {
        laevatein$ledgerCooldownTicks = Math.max(0, tag.getInt("LaevateinLedgerCooldown"));
    }
}
