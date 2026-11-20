package com.laevatein.combat;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/** Shared shield-break handling for Laevatein's four-stage heavy stomp. */
public final class ShieldBreaks {
    private ShieldBreaks() {}

    /** True only when a vanilla shield is actively raised toward the attacker. */
    public static boolean isRaisedShieldFacingAttacker(LivingEntity attacker, LivingEntity target) {
        if (attacker == null || target == null || !target.isAlive()
            || !target.isBlocking() || !target.getUseItem().is(Items.SHIELD)) return false;
        Vec3 toAttacker = attacker.position().subtract(target.position()).multiply(1.0, 0.0, 1.0);
        Vec3 facing = target.getLookAngle().multiply(1.0, 0.0, 1.0);
        if (toAttacker.lengthSqr() < 1.0E-6 || facing.lengthSqr() < 1.0E-6) return false;
        return !(facing.normalize().dot(toAttacker.normalize()) <= 0.15);
    }

    /** Disables a raised vanilla shield when the attacker is in front of it. */
    public static boolean breakRaisedShield(LivingEntity attacker, LivingEntity target) {
        if (!isRaisedShieldFacingAttacker(attacker, target)) return false;
        if (target instanceof Player player) player.disableShield();
        else target.stopUsingItem();
        return true;
    }
}
