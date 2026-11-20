package com.laevatein.mixin;

import com.laevatein.client.FirstPersonKickRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Render only the animated right leg during first-person kicks, after vanilla hands/items. */
@Mixin(ItemInHandRenderer.class)
public abstract class FirstPersonKickMixin {
    @Inject(method = "renderHandsWithItems", at = @At("TAIL"))
    private void laevatein$renderKickLeg(float partialTick, PoseStack matrices,
                                          MultiBufferSource.BufferSource buffers, LocalPlayer player,
                                          int light, CallbackInfo ci) {
        FirstPersonKickRenderer.render(partialTick, matrices, buffers, player, light);
    }
}
