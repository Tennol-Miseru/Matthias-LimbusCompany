package com.laevatein.combat;

import java.util.EnumSet;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.Goal;

/** 给村民、牛、羊等没有战斗 AI 的生物提供仅在调试对战时启用的近战行动。 */
public final class DebugForcedAttackGoal extends Goal {
    private final Mob mob;
    private int strikeCooldown;
    private int pathCooldown;

    public DebugForcedAttackGoal(Mob mob) {
        this.mob = mob;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override public boolean canUse() { return DebugAggroController.forcedTarget(mob) != null; }
    @Override public boolean canContinueToUse() { return canUse(); }

    @Override public void start() {
        strikeCooldown = 0;
        pathCooldown = 0;
    }

    @Override public void stop() { mob.getNavigation().stop(); }

    @Override public void tick() {
        LivingEntity target = DebugAggroController.forcedTarget(mob);
        if (target == null) return;
        mob.getLookControl().setLookAt(target, 30.0F, 30.0F);
        if (--pathCooldown <= 0) {
            pathCooldown = 8;
            mob.getNavigation().moveTo(target, 1.15D);
        }
        if (strikeCooldown > 0) strikeCooldown--;
        double reach = Math.max(1.5D, mob.getBbWidth() * 1.7D + target.getBbWidth() * 0.5D);
        if (strikeCooldown == 0 && mob.distanceToSqr(target) <= reach * reach && mob.hasLineOfSight(target)) {
            strikeCooldown = 20;
            mob.swing(InteractionHand.MAIN_HAND);
            AttributeInstance attack = mob.getAttribute(Attributes.ATTACK_DAMAGE);
            float damage = attack == null ? 2.0F : Math.max(1.0F, (float) attack.getValue());
            target.hurt(mob.damageSources().mobAttack(mob), damage);
        }
    }
}
