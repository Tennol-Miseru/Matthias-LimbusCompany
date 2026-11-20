package com.laevatein.client;

import com.laevatein.Laevatein;
import com.laevatein.entity.MatthiasEntity;
import com.laevatein.registry.ModComponents;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.resources.ResourceLocation;

/** 真正的 PlayerModel（64x64 标准玩家皮肤 UV），允许以后直接替换皮肤 PNG。 */
public final class MatthiasRenderer extends MobRenderer<MatthiasEntity, MatthiasPlayerModel> {
    private static final ResourceLocation TEXTURE = Laevatein.id("textures/entity/matthias.png");

    public MatthiasRenderer(EntityRendererProvider.Context context) {
        super(context, new MatthiasPlayerModel(context.bakeLayer(ModelLayers.PLAYER), false), 0.5f);
        // Render the cloak before the back-sheathed sword so the weapon remains
        // visibly in front of the cloth. The fur collar is part of the same layer.
        this.addLayer(new MatthiasCapeLayer(this, MatthiasCapeModel.bakeRoot()));
        this.addLayer(new ItemInHandLayer<>(this, context.getItemInHandRenderer()));
        this.addLayer(new SwordBackLayer<>(this, context.getItemRenderer()));
    }

    @Override
    public ResourceLocation getTextureLocation(MatthiasEntity entity) { return TEXTURE; }

    @Override
    public void render(MatthiasEntity entity, float yaw, float partialTicks, PoseStack pose,
                       MultiBufferSource buffers, int light) {
        // Matthias is right-hand amputated. His right sleeve remains visible,
        // but it never assumes an item-holding pose. His physical LEFT arm holds
        // MAINHAND sword (the engine resolves main-hand side via getMainArm()).
        getModel().rightArmPose = HumanoidModel.ArmPose.EMPTY;
        getModel().leftArmPose = entity.isSwordOnBack() || entity.getMainHandItem().isEmpty()
            || entity.getMainHandItem().has(ModComponents.THROWN)
            ? HumanoidModel.ArmPose.EMPTY : HumanoidModel.ArmPose.ITEM;
        super.render(entity, yaw, partialTicks, pose, buffers, light);
    }
}
