package com.laevatein.mixin;

import com.laevatein.client.LaevateinSwordPose;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Exposes the holder only while the actual hand-item draw is running. */
@Mixin(ItemInHandRenderer.class)
public abstract class LaevateinItemPoseContextMixin {
    @WrapOperation(method = "renderItem", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/entity/ItemRenderer;renderStatic(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;ZLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/world/level/Level;III)V"))
    private void laevatein$withSwordPose(ItemRenderer renderer, LivingEntity entity, ItemStack stack,
                                        ItemDisplayContext displayContext, boolean leftHand,
                                        PoseStack poseStack, MultiBufferSource buffers, Level level,
                                        int light, int overlay, int seed, Operation<Void> original) {
        // PAL can cancel the outer method before this draw. A HEAD/RETURN
        // injection pair could leave a stale context on that early exit.
        LaevateinSwordPose.beginRender(entity, stack, displayContext, leftHand, poseStack);
        try {
            original.call(renderer, entity, stack, displayContext, leftHand, poseStack, buffers,
                level, light, overlay, seed);
        } finally {
            LaevateinSwordPose.endRender();
        }
    }
}
