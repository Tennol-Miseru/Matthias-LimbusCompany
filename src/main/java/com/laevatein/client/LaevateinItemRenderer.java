package com.laevatein.client;

import com.laevatein.Laevatein;
import com.laevatein.item.LaevateinItem;
import com.laevatein.registry.ModComponents;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import org.jetbrains.annotations.Nullable;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.model.DefaultedItemGeoModel;
import software.bernie.geckolib.renderer.GeoItemRenderer;

/**
 * 按物品堆上的阶段隐藏骨骼。烘焙模型在所有物品堆之间共享，所以每次渲染前都要重设一遍。
 * 资源路径：geo/item/laevatein.geo.json、animations/item/laevatein.animation.json、textures/item/laevatein.png
 */
public class LaevateinItemRenderer extends GeoItemRenderer<LaevateinItem> {
    private ItemDisplayContext currentContext = ItemDisplayContext.NONE;

    public LaevateinItemRenderer() {
        super(new DefaultedItemGeoModel<>(Laevatein.id("laevatein")));
    }

    @Override
    public void renderByItem(ItemStack stack, ItemDisplayContext context, PoseStack poses,
                             MultiBufferSource buffers, int packedLight, int packedOverlay) {
        if ((context == ItemDisplayContext.FIRST_PERSON_RIGHT_HAND || context == ItemDisplayContext.FIRST_PERSON_LEFT_HAND)
            && Minecraft.getInstance().player != null
            && !ClientSwordCarry.isHandActive(Minecraft.getInstance().player)) return;
        ItemDisplayContext previous = currentContext;
        currentContext = context;
        try {
            // The sword model uses full-bright lightmap illumination even in darkness.
            // This is visual emission, not dynamic block/sky lighting.
            super.renderByItem(stack, context, poses, buffers, LightTexture.FULL_BRIGHT, packedOverlay);
        } finally {
            currentContext = previous;
        }
    }

    private boolean renderingHand() {
        return currentContext == ItemDisplayContext.FIRST_PERSON_RIGHT_HAND
            || currentContext == ItemDisplayContext.FIRST_PERSON_LEFT_HAND
            || currentContext == ItemDisplayContext.THIRD_PERSON_RIGHT_HAND
            || currentContext == ItemDisplayContext.THIRD_PERSON_LEFT_HAND;
    }

    @Override
    public void preRender(PoseStack poseStack, LaevateinItem animatable, BakedGeoModel model, @Nullable MultiBufferSource bufferSource,
                          @Nullable VertexConsumer buffer, boolean isReRender, float partialTick, int packedLight, int packedOverlay, int colour) {
        ItemStack s = getCurrentItemStack();
        int stage = s == null ? 3 : LaevateinItem.stage(s);
        boolean inHand = renderingHand();
        boolean emptyHand = inHand && s != null && s.has(ModComponents.THROWN);
        hide(model, "grip", emptyHand);       // 投出去后连剑柄都不显示，玩家只剩原本的手。
        hide(model, "shell_3", stage < 3 || emptyHand);
        hide(model, "shell_2", stage < 2 || emptyHand);
        hide(model, "shell_1", stage < 1 || emptyHand);
        hide(model, "core", emptyHand);
        // Preserve the renderer's normal preparation first. The sweep correction
        // is then applied in the final hand + item-display basis, around the grip.
        super.preRender(poseStack, animatable, model, bufferSource, buffer, isReRender, partialTick, packedLight, packedOverlay, colour);
        // Only the rendered sword rotates; PAL arm motion and its hand pivot remain intact.
        if (inHand) LaevateinSwordPose.apply(poseStack, currentContext, partialTick);
    }

    private static void hide(BakedGeoModel model, String bone, boolean hidden) {
        model.getBone(bone).ifPresent(b -> b.setHidden(hidden));   // 父骨骼隐藏时子骨骼（包括锁链）一起隐藏
    }
}
