package com.laevatein.client;

import com.laevatein.Laevatein;
import com.laevatein.entity.ThrownLaevatein;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/** 复用 GeckoLib 物品模型渲染投出去的剑：本体形态，剑尖朝飞行方向。 */
public class ThrownLaevateinRenderer extends EntityRenderer<ThrownLaevatein> {
    private static final float SCALE = 0.5f;
    private final ItemRenderer itemRenderer;
    private final ItemStack stack = ThrownLaevatein.renderStack();

    public ThrownLaevateinRenderer(EntityRendererProvider.Context ctx) {
        super(ctx);
        this.itemRenderer = ctx.getItemRenderer();
    }

    @Override
    public void render(ThrownLaevatein e, float yaw, float pt, PoseStack pose, MultiBufferSource buffers, int light) {
        pose.pushPose();
        // 与原版三叉戟相同的朝向计算；模型剑尖朝 +Y，最后再绕 X 轴转 90° 让剑尖指向前方
        pose.mulPose(Axis.YP.rotationDegrees(Mth.lerp(pt, e.yRotO, e.getYRot()) - 90f));
        pose.mulPose(Axis.ZP.rotationDegrees(Mth.lerp(pt, e.xRotO, e.getXRot()) - 90f));
        pose.scale(SCALE, SCALE, SCALE);
        pose.translate(-0.5f, -0.5f, -0.5f);
        // Keep the flying sword luminous regardless of world light level.
        itemRenderer.renderStatic(stack, ItemDisplayContext.NONE, LightTexture.FULL_BRIGHT,
            OverlayTexture.NO_OVERLAY, pose, buffers, e.level(), e.getId());
        pose.popPose();
        super.render(e, yaw, pt, pose, buffers, light);
    }

    @Override
    public ResourceLocation getTextureLocation(ThrownLaevatein e) {
        return Laevatein.id("textures/item/laevatein.png");
    }
}
