package com.laevatein.combat;

import com.laevatein.LaevateinConfig;
import com.laevatein.item.LaevateinItem;
import com.laevatein.net.ModNetwork;
import com.laevatein.registry.ModEffects;
import com.laevatein.registry.ModItems;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/**
 * 服务器权威高温状态：同一玩家无论持有几把完全解放的莱瓦汀，60 秒仅增加一次 0..99 层。
 * 99 层且剑仍在背包时获得持续刷新的特殊状态，同时灼烧自身与 10 格内敌对生物。
 */
public final class LaevateinHeat {
    private LaevateinHeat() {}

    public static int layers(int ticks) {
        return Math.min(LaevateinConfig.HEAT_MAX_LAYERS,
            Math.max(0, ticks) * LaevateinConfig.HEAT_MAX_LAYERS / LaevateinConfig.HEAT_MAX_TICKS);
    }

    public static boolean hasReleasedSword(ServerPlayer player) {
        Inventory inventory = player.getInventory();
        // 快捷栏、背包及副手；不扫描箱子、末影箱和潜影盒内部。
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.is(ModItems.LAEVATEIN) && LaevateinItem.stage(stack) == 0) return true;
        }
        return false;
    }

    /** 玩家启动重新包装时清零，并立刻停止灼烧光环，不等待客户端或动画。 */
    public static void reset(ServerPlayer player) {
        ((PlayerHeatAccess) player).laevatein$setHeatTicks(0);
        player.removeEffect(ModEffects.SCORCHED_BY_LAEVATEIN);
        sync(player, hasReleasedSword(player));
    }

    private static void sync(ServerPlayer player, boolean active) {
        ModNetwork.sendHeat(player, ((PlayerHeatAccess) player).laevatein$getHeatTicks(), active);
    }

    private static boolean resistant(LivingEntity entity) {
        return entity.hasEffect(MobEffects.FIRE_RESISTANCE) || entity.fireImmune();
    }

    private static boolean hostileTo(LivingEntity entity, ServerPlayer player) {
        // Minecraft 的敌对生物，以及正在将该玩家作为攻击目标的中立生物。
        return entity instanceof Enemy || (entity instanceof Mob mob && mob.getTarget() == player);
    }

    private static void burnOnce(ServerPlayer player) {
        ServerLevel world = player.serverLevel();
        // “过人的毅力”期间免疫自身莱瓦汀高温灼伤；周围敌人仍正常受伤。
        if (!player.isCreative() && !player.isSpectator() && !resistant(player)
            && !player.hasEffect(ModEffects.EXTRAORDINARY_TENACITY)) {
            player.hurt(player.damageSources().onFire(), LaevateinConfig.HEAT_DAMAGE);
        }
        for (LivingEntity entity : world.getEntitiesOfClass(LivingEntity.class,
            player.getBoundingBox().inflate(LaevateinConfig.HEAT_AURA_RADIUS),
            other -> other != player && other.isAlive() && hostileTo(other, player))) {
            if (player.distanceToSqr(entity) > LaevateinConfig.HEAT_AURA_RADIUS_SQR || resistant(entity)) continue;
            entity.hurt(world.damageSources().onFire(), LaevateinConfig.HEAT_DAMAGE);
            // 小幅火焰粒子，避免多敌群时每 tick 生成大量视觉效果。
            world.sendParticles(ParticleTypes.SMALL_FLAME,
                entity.getX(), entity.getY() + entity.getBbHeight() * 0.55, entity.getZ(),
                2, entity.getBbWidth() * 0.3, entity.getBbHeight() * 0.2,
                entity.getBbWidth() * 0.3, 0.004);
        }
    }

    private static void tick(ServerPlayer player) {
        if (!player.isAlive()) return;
        PlayerHeatAccess data = (PlayerHeatAccess) player;
        boolean active = hasReleasedSword(player);
        int ticks = data.laevatein$getHeatTicks();
        if (active && ticks < LaevateinConfig.HEAT_MAX_TICKS) {
            ticks++;
            data.laevatein$setHeatTicks(ticks);
        }
        boolean full = active && ticks >= LaevateinConfig.HEAT_MAX_TICKS;
        if (full) {
            // 周期性续时，状态图标在 HUD 的原版效果栏保持可见。
            MobEffectInstance effect = player.getEffect(ModEffects.SCORCHED_BY_LAEVATEIN);
            if (effect == null || effect.getDuration() <= 40) {
                player.addEffect(new MobEffectInstance(ModEffects.SCORCHED_BY_LAEVATEIN,
                    80, 0, true, false, true));
            }
            if (player.tickCount % LaevateinConfig.HEAT_DAMAGE_INTERVAL == 0) {
                burnOnce(player);
            } else if (player.tickCount % 10 == 0) {
                player.serverLevel().sendParticles(ParticleTypes.SMALL_FLAME,
                    player.getX(), player.getY() + player.getBbHeight() * 0.6, player.getZ(),
                    2, 0.22, 0.35, 0.22, 0.003);
            }
        } else if (player.hasEffect(ModEffects.SCORCHED_BY_LAEVATEIN)) {
            // 不持有已解放的剑时暂停计量和伤害；重新持有可继续从保留的层数开始。
            player.removeEffect(ModEffects.SCORCHED_BY_LAEVATEIN);
        }
        if (player.tickCount % 5 == 0) sync(player, active);
    }

    public static void init() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) tick(player);
        });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
            sync(handler.player, hasReleasedSword(handler.player)));
        ServerPlayerEvents.COPY_FROM.register((oldPlayer, newPlayer, alive) ->
            ((PlayerHeatAccess) newPlayer).laevatein$setHeatTicks(
                ((PlayerHeatAccess) oldPlayer).laevatein$getHeatTicks()));
    }
}
