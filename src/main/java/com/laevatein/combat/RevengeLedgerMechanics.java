package com.laevatein.combat;

import com.laevatein.registry.ModEffects;
import com.laevatein.registry.ModItems;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.player.Inventory;

/**
 * 蜘蛛巢特别条款：仅在真实致死后发动，因此自动考虑盔甲、减伤和吸收等前置机制。
 * 10秒状态的单次伤害上限由 PlayerTenacityMixin 在玩家实际扣血流程前限制。
 */
public final class RevengeLedgerMechanics {
    public static final int COOLDOWN_TICKS = 120 * 20;
    public static final int TENACITY_TICKS = 10 * 20;
    private RevengeLedgerMechanics() {}

    public static void init() {
        ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, damage) -> {
            if (!(entity instanceof ServerPlayer player)) return true;
            LedgerCooldownAccess cooldown = (LedgerCooldownAccess) player;
            if (cooldown.laevatein$getLedgerCooldownTicks() > 0 || !hasLedger(player)) return true;

            // 此时原版已根据实际损失判定为致死；恢复血量并取消本次死亡。
            cooldown.laevatein$setLedgerCooldownTicks(COOLDOWN_TICKS);
            player.getCooldowns().addCooldown(ModItems.REVENGE_LEDGER, COOLDOWN_TICKS);
            player.setHealth(player.getMaxHealth());
            player.addEffect(new MobEffectInstance(ModEffects.EXTRAORDINARY_TENACITY,
                TENACITY_TICKS, 0, false, false, true));
            player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.TOTEM_USE, SoundSource.PLAYERS, 1.0f, 0.95f);
            return false;
        });

        // 尝试在重连时继续显示原版背包物品冷却遮罩；实际限制以持久化玩家字段为准。
        ServerPlayerEvents.JOIN.register(RevengeLedgerMechanics::syncCooldownOverlay);
        // 玩家死亡 / 末地回归等情况下实体被重建时，仍保留尚未到期的冷却。
        ServerPlayerEvents.COPY_FROM.register((oldPlayer, newPlayer, alive) -> {
            int remaining = ((LedgerCooldownAccess)oldPlayer).laevatein$getLedgerCooldownTicks();
            ((LedgerCooldownAccess)newPlayer).laevatein$setLedgerCooldownTicks(remaining);
            syncCooldownOverlay(newPlayer);
        });
    }

    private static boolean hasLedger(ServerPlayer player) {
        Inventory inv = player.getInventory();
        // 主背包+快捷栏 0..35 和副手 40；不检测末影箱或容器内部物品。
        for (int slot = 0; slot < inv.getContainerSize(); slot++) {
            if (inv.getItem(slot).is(ModItems.REVENGE_LEDGER)) return true;
        }
        return false;
    }

    private static void syncCooldownOverlay(ServerPlayer player) {
        int ticks = ((LedgerCooldownAccess)player).laevatein$getLedgerCooldownTicks();
        if (ticks > 0) player.getCooldowns().addCooldown(ModItems.REVENGE_LEDGER, ticks);
    }
}
