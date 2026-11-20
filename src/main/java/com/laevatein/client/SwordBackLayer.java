package com.laevatein.client;

import com.laevatein.entity.MatthiasEntity;
import com.laevatein.registry.ModComponents;
import com.laevatein.registry.ModItems;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/** Third-person sword scabbard, attached to the back of the player body (not world origin). */
public final class SwordBackLayer<T extends LivingEntity, M extends HumanoidModel<T>> extends RenderLayer<T, M> {
    private final ItemRenderer itemRenderer;

    public SwordBackLayer(RenderLayerParent<T, M> parent, ItemRenderer itemRenderer) {
        super(parent);
        this.itemRenderer = itemRenderer;
    }

    private static ItemStack carriedSword(LivingEntity entity) {
        if (entity instanceof MatthiasEntity matthias) {
            return matthias.isSwordOnBack() ? matthias.getMainHandItem() : ItemStack.EMPTY;
        }
        if (entity instanceof AbstractClientPlayer player) {
            if (ClientSwordCarry.isHandActive(player)) return ItemStack.EMPTY;
            // Only the 36 actual backpack/hotbar slots count; offhand sword is already equipped.
            // This branch runs only when neither held hand contains Laevatein.
            for (int i = 0; i < 36; i++) {
                ItemStack stack = player.getInventory().getItem(i);
                if (stack.is(ModItems.LAEVATEIN)) return stack;
            }
        }
        return ItemStack.EMPTY;
    }

    @Override
    public void render(PoseStack pose, MultiBufferSource buffers, int light, T entity,
                       float limbSwing, float limbSwingAmount, float partialTicks,
                       float ageInTicks, float netHeadYaw, float headPitch) {
        if (entity.isInvisible()) return;
        ItemStack sword = carriedSword(entity);
        if (!sword.is(ModItems.LAEVATEIN) || sword.has(ModComponents.THROWN)) return;

        pose.pushPose();
        // Local body origin: +Z points to the back. Follow torso turns and crouching.
        getParentModel().body.translateAndRotate(pose);
        // Minecraft's local model Y grows downwards: negative Y raises the sword.
        // In-game feedback: reducing this local X value moves the visible sword
        // to the right. v1.0.19 shifts it right by ~0.2 relative to v1.0.18.
        // Preserve Y/Z, orientation, and scale.
        pose.translate(0.545, -0.09, 0.22);
        // Back carry is intentionally independent from the Blockbench FIXED/hand Display poses.
        // Reproduce the v1.0.9 back-layer FIXED transform locally so the player's edits to
        // first/third person and FIXED do not change the sword's position on the back.
        pose.mulPose(Axis.ZP.rotationDegrees(80));
        pose.scale(1.08f, 1.08f, 1.08f);
        pose.translate(2.5f / 16f, 2.5f / 16f, 0f);
        pose.mulPose(Axis.ZP.rotationDegrees(-45));
        pose.scale(0.44f, 0.44f, 0.44f); // Twice the old back-carry size.
        itemRenderer.renderStatic(sword, ItemDisplayContext.NONE, LightTexture.FULL_BRIGHT,
            OverlayTexture.NO_OVERLAY, pose, buffers, entity.level(), entity.getId());
        pose.popPose();
    }
}
