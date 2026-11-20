package com.laevatein.combat;

import com.laevatein.entity.MatthiasEntity;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/** 服务端粒子和音效。sendParticles 会把粒子发给附近所有玩家。 */
public final class LaevateinFx {
    private LaevateinFx() {}

    /** 拆掉或甩飞一层包装时的“火星四溅”。 */
    public static void fireBurst(ServerLevel sl, LivingEntity p) {
        Vec3 c = p.getEyePosition().add(p.getLookAngle().scale(1.2)).subtract(0, 0.4, 0);
        sl.sendParticles(ParticleTypes.FLAME, c.x, c.y, c.z, 60, 0.6, 0.8, 0.6, 0.25);
        sl.sendParticles(ParticleTypes.LAVA, c.x, c.y, c.z, 14, 0.4, 0.6, 0.4, 0.0);
        sl.sendParticles(ParticleTypes.SMALL_FLAME, c.x, c.y, c.z, 40, 0.8, 0.8, 0.8, 0.12);
        sl.sendParticles(ParticleTypes.EXPLOSION, c.x, c.y, c.z, 1, 0, 0, 0, 0);
        sl.playSound(null, p.blockPosition(), SoundEvents.BLAZE_SHOOT, SoundSource.PLAYERS, 1.0f, 0.8f);
        sl.playSound(null, p.blockPosition(), SoundEvents.CHAIN_BREAK, SoundSource.PLAYERS, 1.0f, 0.7f);
    }

    /** 玩家的前、右、上三个方向（水平朝向）。 */
    private static Vec3[] basis(LivingEntity p) {
        Vec3 fwd = p.getLookAngle().multiply(1, 0, 1).normalize();
        Vec3 right = new Vec3(-fwd.z, 0, fwd.x);
        return new Vec3[] { fwd, right, new Vec3(0, 1, 0) };
    }

    private static Vec3 at(Vec3 origin, Vec3[] b, double f, double r, double u) {
        return origin.add(b[0].scale(f)).add(b[1].scale(r)).add(b[2].scale(u));
    }

    private static void dot(ServerLevel sl, ParticleOptions type, Vec3 v, int n, double spread) {
        sl.sendParticles(type, v.x, v.y, v.z, n, spread, spread, spread, 0.01);
    }

    /** 封印态斜劈：左上 → 右下。 */
    public static void diagonalTrail(ServerLevel sl, LivingEntity p, boolean heavy) {
        diagonalTrail(sl, p, heavy, false);
    }

    /** 反手斩以反向火焰轨迹对应第二段动画。 */
    public static void diagonalTrail(ServerLevel sl, LivingEntity p, boolean heavy, boolean reverse) {
        Vec3 o = p.getEyePosition();
        Vec3[] b = basis(p);
        int n = heavy ? 12 : 8;
        for (int i = 0; i <= n; i++) {
            double k = (double) i / n;
            if (reverse) k = 1.0 - k;
            // Matthias swings left-handed: mirror the arc without changing players' FX.
            double side = -1.3 + 2.6 * k;
            Vec3 v = at(o, b, 1.6, p instanceof MatthiasEntity ? -side : side, 0.9 - 1.8 * k);
            if (i % 2 == 0) dot(sl, ParticleTypes.SWEEP_ATTACK, v, 1, 0);
            dot(sl, heavy ? ParticleTypes.FLAME : ParticleTypes.SMALL_FLAME, v, heavy ? 3 : 1, 0.05);
        }
    }

    /** 本体横扫：160° 水平弧线，left 决定挥动方向。 */
    public static void sweepArc(ServerLevel sl, LivingEntity p, boolean left) {
        if (p instanceof MatthiasEntity) left = !left; // left-handed mirror of the arm animation
        Vec3 o = p.position().add(0, p.getBbHeight() * 0.6, 0);
        Vec3[] b = basis(p);
        int n = 12;
        for (int i = 0; i <= n; i++) {
            double k = (double) i / n;
            double ang = Math.toRadians(-80 + 160 * (left ? 1 - k : k));
            Vec3 v = at(o, b, Math.cos(ang) * 3.0, Math.sin(ang) * 3.0, 0);
            if (i % 2 == 0) dot(sl, ParticleTypes.SWEEP_ATTACK, v, 1, 0);
            dot(sl, ParticleTypes.FLAME, v, 2, 0.08);
        }
    }

    /** 穿刺：沿玩家到目标的连线。 */
    public static void thrust(ServerLevel sl, LivingEntity p, Entity t) {
        Vec3 a = p.getEyePosition().subtract(0, 0.3, 0);
        if (p instanceof MatthiasEntity) {
            Vec3[] b = basis(p);
            a = at(a, b, 0.20, -0.35, -0.10); // NPC's physical left sword arm
        }
        Vec3 c = t.position().add(0, t.getBbHeight() * 0.6, 0);
        for (int i = 1; i <= 5; i++) dot(sl, ParticleTypes.CRIT, a.lerp(c, i / 5.0), 1, 0.02);
        dot(sl, ParticleTypes.SMALL_FLAME, c, 6, 0.15);
    }

    /** 竖劈：从上到下的火焰线，然后沿地面向前烧出一条。 */
    public static void chopLine(ServerLevel sl, LivingEntity p) {
        Vec3 o = p.position();
        Vec3[] b = basis(p);
        for (int i = 0; i <= 8; i++) dot(sl, ParticleTypes.FLAME, at(o, b, 1.5, 0, 3.0 - i * 0.35), 2, 0.05);
        for (int i = 0; i <= 10; i++) {
            Vec3 v = at(o, b, 1.0 + i * 0.4, 0, 0.1);
            dot(sl, ParticleTypes.FLAME, v, 3, 0.1);
            if (i % 3 == 0) dot(sl, ParticleTypes.LAVA, v, 1, 0.05);
        }
    }

    public static void dashTrail(ServerLevel sl, LivingEntity p) {
        Vec3 v = p.position().add(0, 0.9, 0);
        dot(sl, ParticleTypes.FLAME, v, 4, 0.2);
        dot(sl, ParticleTypes.CLOUD, v, 1, 0.1);
    }

    /** 四段重踏的命中反馈：上挑、重直踹、追踹、向下砸落各自不同。 */
    public static void quadKickImpact(ServerLevel sl, LivingEntity target, int phase) {
        Vec3 c = target.position().add(0, target.getBbHeight() * 0.55, 0);
        sl.sendParticles(ParticleTypes.CRIT, c.x, c.y, c.z, phase == 4 ? 18 : 11,
            0.35, 0.4, 0.35, 0.17);
        sl.sendParticles(ParticleTypes.CLOUD, c.x, c.y, c.z, phase == 4 ? 24 : 10,
            0.4, 0.4, 0.4, 0.13);
        if (phase == 4) {
            Vec3 foot = target.position().add(0, 0.15, 0);
            sl.sendParticles(ParticleTypes.POOF, foot.x, foot.y, foot.z, 28, 0.7, 0.15, 0.7, 0.08);
        } else {
            sl.sendParticles(ParticleTypes.SWEEP_ATTACK, c.x, c.y, c.z, 2, 0.1, 0.2, 0.1, 0.0);
        }
    }

    /** 投掷后扎在目标身上的持续灼烧。 */
    public static void burn(ServerLevel sl, Entity target) {
        Vec3 c = target.position().add(0, target.getBbHeight() * 0.6, 0);
        sl.sendParticles(ParticleTypes.FLAME, c.x, c.y, c.z, 6, 0.25, 0.3, 0.25, 0.03);
        sl.sendParticles(ParticleTypes.LAVA, c.x, c.y, c.z, 1, 0.1, 0.1, 0.1, 0);
    }
}
