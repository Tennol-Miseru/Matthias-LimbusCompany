package com.laevatein.client;

import com.laevatein.Laevatein;
import com.laevatein.item.LaevateinItem;
import com.laevatein.registry.ModComponents;
import com.laevatein.net.ModNetwork;
import com.laevatein.registry.ModEntities;
import com.laevatein.registry.ModItems;
import com.zigythebird.playeranim.animation.PlayerAnimationController;
import com.zigythebird.playeranim.api.PlayerAnimationAccess;
import com.zigythebird.playeranim.api.PlayerAnimationFactory;
import com.zigythebird.playeranimcore.animation.layered.modifier.AbstractFadeModifier;
import com.zigythebird.playeranimcore.api.firstPerson.FirstPersonConfiguration;
import com.zigythebird.playeranimcore.api.firstPerson.FirstPersonMode;
import com.zigythebird.playeranimcore.easing.EasingType;
import com.zigythebird.playeranimcore.enums.PlayState;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import com.mojang.blaze3d.platform.InputConstants;
import org.lwjgl.glfw.GLFW;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.LivingEntityFeatureRendererRegistrationCallback;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.fabricmc.fabric.api.event.client.player.ClientPreAttackCallback;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.resources.ResourceLocation;

public class LaevateinClient implements ClientModInitializer {
    /** PAL 动画层 id。优先级 1500 以上是“重要的玩法动作”。 */
    public static final ResourceLocation LAYER = Laevatein.id("combat");
    private static final KeyMapping RESHELL_KEY = KeyBindingHelper.registerKeyBinding(
        new KeyMapping("key.laevatein.reshell", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_N, "key.categories.laevatein"));
    /** V: 手动四段重踏；每次物理按键只触发一脚，按住不自动续段。 */
    private static final KeyMapping QUAD_KICK_KEY = KeyBindingHelper.registerKeyBinding(
        new KeyMapping("key.laevatein.quad_kick", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_V, "key.categories.laevatein"));
    private boolean previousRightDown;
    private boolean previousLeftDown;
    private boolean previousKickDown;

    @Override
    public void onInitializeClient() {
        HeatHud.init();
        EntityRendererRegistry.register(ModEntities.THROWN_LAEVATEIN, ThrownLaevateinRenderer::new);
        EntityRendererRegistry.register(ModEntities.MATTHIAS, MatthiasRenderer::new);
        EntityRendererRegistry.register(ModEntities.MATTHIAS_RELEASED, MatthiasRenderer::new);
        LivingEntityFeatureRendererRegistrationCallback.EVENT.register((type, renderer, helper, context) -> {
            if (renderer instanceof PlayerRenderer playerRenderer) {
                helper.register(new SwordBackLayer<>(playerRenderer, context.getItemRenderer()));
            }
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null) {
                HeatHud.clear();
                FirstPersonKickRenderer.clear();
                LaevateinSwordPose.clear();
            }
            // 只在按下 / 松开时发送一次状态包，不在每 tick 反复传输攻击指令。
            // 松开、切到其他物品或打开界面都会发送 false；保留原有左键单击回调。
            boolean leftDown = client.player != null && client.screen == null
                && client.options.keyAttack.isDown()
                && client.player.getMainHandItem().is(ModItems.LAEVATEIN);
            if (client.player != null && client.getConnection() != null && leftDown != previousLeftDown) {
                ClientPlayNetworking.send(new ModNetwork.AttackHeldPayload(leftDown));
            }
            previousLeftDown = leftDown;

            boolean rightDown = client.options.keyUse.isDown();
            // 只响应物理按下沿，长按不会在 vanilla use 自动重复时连跳多个形态。
            if (client.player != null && client.screen == null
                && rightDown && !previousRightDown
                && client.player.getMainHandItem().is(ModItems.LAEVATEIN)
                && LaevateinItem.stage(client.player.getMainHandItem()) == 0
                && !client.player.getMainHandItem().has(ModComponents.THROWN)) {
                ClientPlayNetworking.send(ModNetwork.ModePayload.INSTANCE);
            }
            previousRightDown = rightDown;
            // 使用按键上升沿，不消费重复触发的 key repeat 事件。
            // 必须松开 V 再次按下才能续段，长按一次只打一脚。
            boolean kickDown = QUAD_KICK_KEY.isDown();
            if (kickDown && !previousKickDown && client.player != null && client.screen == null
                && client.player.getMainHandItem().is(ModItems.LAEVATEIN)) {
                ClientPlayNetworking.send(ModNetwork.QuadKickPayload.INSTANCE);
            }
            previousKickDown = kickDown;
            while (QUAD_KICK_KEY.consumeClick()) { /* 丢弃系统重复按键 / 旧界面的残留点击 */ }
            while (RESHELL_KEY.consumeClick()) {
                if (client.player != null && client.screen == null
                    && client.player.getMainHandItem().is(ModItems.LAEVATEIN)) {
                    ClientPlayNetworking.send(ModNetwork.ReshellPayload.INSTANCE);
                }
            }
        });

        // 单击依然照旧发一发 AttackPayload；长按的续攻则由服务端的持键状态驱动。
        // 这里继续取消原版攻击/挖掘，防止原版伤害与招式判定叠加。
        ClientPreAttackCallback.EVENT.register((client, player, clickCount) -> {
            if (!player.getMainHandItem().is(ModItems.LAEVATEIN)) return false;
            if (clickCount > 0 && !player.isUsingItem()) ClientPlayNetworking.send(ModNetwork.AttackPayload.INSTANCE);
            return true;
        });

        // 每个客户端玩家都挂一个动画层；只播放服务端触发的动作，平时不接管姿势
        PlayerAnimationFactory.ANIMATION_DATA_FACTORY.registerFactory(LAYER, 1600, player -> {
            PlayerAnimationController ctl = new PlayerAnimationController(player, (controller, state, setter) -> PlayState.STOP);
            // 第一人称也用第三人称模型的手臂，挥砍动作在第一人称下才看得到
            ctl.setFirstPersonMode(FirstPersonMode.THIRD_PERSON_MODEL);
            ctl.setFirstPersonConfiguration(new FirstPersonConfiguration(true, true, true, false));
            return ctl;
        });

        ClientPlayNetworking.registerGlobalReceiver(ModNetwork.HeatSyncPayload.TYPE,
            (payload, ctx) -> HeatHud.accept(payload.ticks(), payload.active()));
        ClientPlayNetworking.registerGlobalReceiver(ModNetwork.PlayerAnimPayload.TYPE, (payload, ctx) -> {
            FirstPersonKickRenderer.onPlayerAnimation(payload.entityId(), payload.anim(), payload.stop());
            if (ctx.client().level == null) return;
            if (!(ctx.client().level.getEntity(payload.entityId()) instanceof AbstractClientPlayer player)) return;
            LaevateinSwordPose.acceptPlayerAnimation(player, payload.anim(), payload.stop());
            if (!(PlayerAnimationAccess.getPlayerAnimationLayer(player, LAYER) instanceof PlayerAnimationController ctl)) return;
            if (payload.stop()) {
                // PAL 的 ResourceLocation 版 replaceAnimationWithFade 只接受已存在的动画 ID。
                // 传 null 会直接返回 false，不会停止 hold_on_last_frame 的双手拆壳动作。
                // 必须显式清理触发动画并停用本模组的动画层，才能恢复原版手臂姿势。
                ctl.stopTriggeredAnimation();
                ctl.stop();
            } else {
                ctl.replaceAnimationWithFade(AbstractFadeModifier.standardFadeIn(2, EasingType.EASE_OUT_SINE), Laevatein.id(payload.anim()));
            }
        });
    }
}
