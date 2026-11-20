package com.laevatein.item;

import com.laevatein.LaevateinConfig;
import com.laevatein.WeaponMode;
import com.laevatein.client.LaevateinItemRenderer;
import com.laevatein.combat.LaevateinCombat;
import com.laevatein.combat.LaevateinFx;
import com.laevatein.net.ModNetwork;
import com.laevatein.registry.ModComponents;
import com.laevatein.registry.ModSounds;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.item.v1.FabricItem;
import net.minecraft.ChatFormatting;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import software.bernie.geckolib.animatable.GeoItem;
import software.bernie.geckolib.animatable.SingletonGeoAnimatable;
import software.bernie.geckolib.animatable.client.GeoRenderProvider;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.PlayState;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

public class LaevateinItem extends Item implements GeoItem, FabricItem {
    public static final String SHELL_CONTROLLER = "shell";
    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

    public LaevateinItem(Properties properties) {
        super(properties);
        SingletonGeoAnimatable.registerSyncedAnimatable(this); // 允许服务端触发动画
    }

    public static int stage(ItemStack s) { return s.getOrDefault(ModComponents.SHELL_STAGE, 3); }

    public static WeaponMode mode(ItemStack s) { return s.getOrDefault(ModComponents.MODE, WeaponMode.SWEEP); }

    // ---------------- GeckoLib ----------------

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        AnimationController<LaevateinItem> ctl = new AnimationController<>(this, SHELL_CONTROLLER, 0, state -> PlayState.STOP);
        for (int i = 1; i <= 3; i++) {
            ctl.triggerableAnim("unshell_" + i, RawAnimation.begin().thenPlayAndHold("animation.laevatein.unshell_" + i));
            ctl.triggerableAnim("fling_" + i, RawAnimation.begin().thenPlayAndHold("animation.laevatein.fling_" + i));
        }
        ctl.triggerableAnim("reshell", RawAnimation.begin().thenPlayAndHold("animation.laevatein.reshell"));
        controllers.add(ctl);
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() { return cache; }

    public void playShellAnim(ServerPlayer player, ItemStack stack, String anim) {
        triggerAnim(player, GeoItem.getOrAssignId(stack, player.serverLevel()), SHELL_CONTROLLER, anim);
    }

    public void stopShellAnim(ServerPlayer player, ItemStack stack, String anim) {
        stopTriggeredAnim(player, GeoItem.getOrAssignId(stack, player.serverLevel()), SHELL_CONTROLLER, anim);
    }

    /** 渲染器类只在客户端调用到这里时才会加载，专用服务端不会碰到它。 */
    @Override
    public void createGeoRenderer(Consumer<GeoRenderProvider> consumer) {
        consumer.accept(new GeoRenderProvider() {
            private LaevateinItemRenderer renderer;

            @Override
            public BlockEntityWithoutLevelRenderer getGeoItemRenderer() {
                if (renderer == null) renderer = new LaevateinItemRenderer();
                return renderer;
            }
        });
    }

    // ---------------- 右键：封印态长按拆壳；本体态切换改由按下沿网络包处理 ----------------

    @Override
    public int getUseDuration(ItemStack stack, LivingEntity entity) { return 72000; }

    /** 手臂姿势完全交给 Player Animation Lib，这里不用原版的举盾或拉弓姿势。 */
    @Override
    public UseAnim getUseAnimation(ItemStack stack) { return UseAnim.NONE; }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (hand != InteractionHand.MAIN_HAND || stack.has(ModComponents.THROWN)) return InteractionResultHolder.fail(stack);
        if (stage(stack) == 0) {
            // 不进入 startUsingItem，也不在 releaseUsing 切换，确保攻击中可以立即右键换形态。
            // 右键按下沿由客户端的 ModePayload 触发；原版自动重复 use 不会连跳多档。
            return InteractionResultHolder.consume(stack);
        }
        if (player instanceof ServerPlayer sp) {
            if (LaevateinCombat.isBusy(sp)) return InteractionResultHolder.fail(stack);
            LaevateinCombat.startUnshell(sp, stack);
            playShellAnim(sp, stack, "unshell_" + stage(stack));
            ModNetwork.playAnim(sp, "unshell_hold");
        }
        player.startUsingItem(hand);
        return InteractionResultHolder.consume(stack);
    }

    @Override
    public void onUseTick(Level level, LivingEntity user, ItemStack stack, int remaining) {
        if (!(user instanceof ServerPlayer sp) || stage(stack) == 0 || !LaevateinCombat.isUnshelling(sp, stack)) return;
        int used = getUseDuration(stack, user) - remaining;
        if (used <= 0 || used % LaevateinConfig.UNSHELL_TICKS_PER_LAYER != 0) return;

        int oldStage = stage(stack);
        int next = oldStage - 1;
        stack.set(ModComponents.SHELL_STAGE, next);
        stack.set(ModComponents.SEAL_HITS, 0);
        LaevateinFx.fireBurst(sp.serverLevel(), sp);
        ModSounds.breakShell(sp.serverLevel(), sp, oldStage, SoundSource.PLAYERS);
        if (next > 0) {
            playShellAnim(sp, stack, "unshell_" + next);
            ModNetwork.playAnim(sp, "unshell_hold");
        } else {
            LaevateinCombat.finishUnshell(sp);
            ModNetwork.stopAnim(sp);
            sp.stopUsingItem();   // 不会触发 releaseUsing
        }
    }

    @Override
    public void releaseUsing(ItemStack stack, Level level, LivingEntity user, int remaining) {
        if (!(user instanceof ServerPlayer sp)) return;
        if (stage(stack) > 0 && LaevateinCombat.isUnshelling(sp, stack)) {
            // 拆到一半松开：这一层作废，已拆的层保留。
            stopShellAnim(sp, stack, "unshell_" + stage(stack));
            LaevateinCombat.abortUnshell(sp);
            ModNetwork.stopAnim(sp);
        }
    }

    // ---------------- 其他 ----------------

    /** 兜底：投出去的剑实体已经不存在（区块卸载、换维度、重新登录）时，把剑还给玩家。 */
    @Override
    public void inventoryTick(ItemStack stack, Level level, Entity entity, int slot, boolean selected) {
        if (!(level instanceof ServerLevel sl) || level.getGameTime() % 20 != 0) return;
        UUID id = stack.get(ModComponents.THROWN);
        if (id != null && sl.getEntity(id) == null) stack.remove(ModComponents.THROWN);
    }

    /** 组件每次变化时，不播放原版物品“重新举起”的上下晃动。 */
    @Override
    public boolean allowComponentsUpdateAnimation(Player player, InteractionHand hand, ItemStack oldStack, ItemStack newStack) {
        return false;
    }

    /** 手持莱瓦汀不能挖方块（创造模式也一样）。 */
    @Override
    public boolean canAttackBlock(BlockState state, Level level, BlockPos pos, Player player) { return false; }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext ctx, List<Component> tooltip, TooltipFlag flag) {
        int stage = stage(stack);
        if (stage > 0) {
            tooltip.add(Component.translatable("tooltip.laevatein.sealed", stage).withStyle(ChatFormatting.DARK_RED));
            tooltip.add(Component.translatable("tooltip.laevatein.seal_hits",
                stack.getOrDefault(ModComponents.SEAL_HITS, 0), LaevateinConfig.SEAL_HITS_PER_LAYER).withStyle(ChatFormatting.GRAY));
        } else {
            tooltip.add(Component.translatable("tooltip.laevatein.unsealed").withStyle(ChatFormatting.GOLD));
            tooltip.add(Component.translatable("tooltip.laevatein.mode", Component.translatable(mode(stack).translationKey()))
                .withStyle(ChatFormatting.GRAY));
        }
    }
}
