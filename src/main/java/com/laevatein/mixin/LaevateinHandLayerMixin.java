package com.laevatein.mixin;

import com.laevatein.client.ClientSwordCarry;
import com.laevatein.entity.MatthiasEntity;
import com.laevatein.registry.ModItems;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Hide *only* the sword (not players' other held items) when the sword is on the back. */
@Mixin(ItemInHandLayer.class)
public abstract class LaevateinHandLayerMixin {
    @Inject(method = "renderArmWithItem", at = @At("HEAD"), cancellable = true)
    private void laevatein$hideSword(LivingEntity entity, ItemStack stack, ItemDisplayContext display,
                                      HumanoidArm arm, PoseStack pose, MultiBufferSource buffers,
                                      int light, CallbackInfo ci) {
        // Right sleeve has no hand. Never render *any* item there, even if an
        // external mod/NBT accidentally puts an item in Matthias's offhand.
        if (entity instanceof MatthiasEntity npc) {
            if (arm == HumanoidArm.RIGHT || (stack.is(ModItems.LAEVATEIN) && npc.isSwordOnBack()))
                ci.cancel();
            return;
        }
        if (stack.is(ModItems.LAEVATEIN) && entity instanceof AbstractClientPlayer player
            && !ClientSwordCarry.isHandActive(player)) ci.cancel();
    }
}
