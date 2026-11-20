package com.laevatein.combat;

import com.laevatein.LaevateinConfig;
import com.laevatein.WeaponMode;
import com.laevatein.entity.ThrownLaevatein;
import com.laevatein.item.LaevateinItem;
import com.laevatein.net.ModNetwork;
import com.laevatein.registry.ModComponents;
import com.laevatein.registry.ModSounds;
import com.laevatein.registry.ModItems;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

/** 服务端招式逻辑。所有多 tick 的动作都在 END_SERVER_TICK 里逐 tick 推进。 */
public final class LaevateinCombat {
    private LaevateinCombat() {}

    /** 投出前的占位 UUID：剑已经离手（手上隐藏剑身），但实体要等出手帧才生成。 */
    private static final UUID PENDING_THROW = new UUID(0L, 0L);

    private static final class State {
        long busyUntil;
        long noFallUntil;
        Pierce pierce;
        Dash dash;
        QuadKick quadKick;             // V 每次按键只执行一脚；不自动安排下一脚
        long quadKickReadyAt;         // 冷却从第一脚开始计算
        int quadKickLastPhase;        // 上一次主动完成的段数；有效期内下一次 V 才续段
        long quadKickComboExpiresAt;
        ItemStack quadKickComboStack;
        ServerLevel quadKickComboLevel;
        LivingEntity quadKickComboTarget;
        final List<Task> tasks = new ArrayList<>();
        ItemStack unshelling;          // 正在长按拆壳的那把剑
        boolean reshelling;            // N 键包装动画持续期间阻止攻击
        ItemStack bufferedStack;       // 接受攻击结束前短时间内的下一次左键输入
        boolean attackHeld;            // 客户端左键保持按下，服务端以冷却为节拍续攻
        ItemStack heldStack;           // 本次长按绑定的物品，换手/换槽后不继续攻击
        long bufferedUntil;
        int heavyComboStep;            // 已完成启动的连携段数，0 表示下次从第一段开始
        long heavyComboExpiresAt;      // 超时则从第一段开始；切模式也会重置
    }

    private record Task(long due, Runnable run) {}

    private static final class Pierce {
        final LivingEntity target; final ItemStack stack; int done; int wait;
        Pierce(LivingEntity t, ItemStack s) { target = t; stack = s; }
    }

    private static final class Dash {
        final ThrownLaevatein blade; final LivingEntity target; final ItemStack stack; final int timeout; int age;
        Dash(ThrownLaevatein b, LivingEntity t, ItemStack s, int to) { blade = b; target = t; stack = s; timeout = to; }
    }

    /** 一次 V 请求 = 一脚。仅允许一枚明确的后续按键被缓冲，绝不自动补齐四段。 */
    private static final class QuadKick {
        final ItemStack stack;
        final ServerLevel level;
        final int phase;
        final LivingEntity target;
        int chaseTicks;
        int strikeTicks;
        boolean striking;
        boolean impacted;
        boolean queuedNext;
        boolean chased;      // 用于收招时消除短冲刺的水平惯性

        QuadKick(ItemStack stack, ServerLevel level, LivingEntity target, int phase) {
            this.stack = stack;
            this.level = level;
            this.target = target;
            this.phase = phase;
        }
    }

    private static final Map<UUID, State> STATES = new HashMap<>();

    private static State st(ServerPlayer p) { return STATES.computeIfAbsent(p.getUUID(), k -> new State()); }

    private static long now(ServerPlayer p) { return p.serverLevel().getGameTime(); }

    public static void init() {
        ServerTickEvents.END_SERVER_TICK.register(LaevateinCombat::tick);
        ServerPlayConnectionEvents.DISCONNECT.register((h, s) -> STATES.remove(h.getPlayer().getUUID()));
    }

    // ---------------- 供物品类调用：忙碌、拆壳会话、模式切换判定 ----------------

    public static boolean isBusy(ServerPlayer p) {
        State s = STATES.get(p.getUUID());
        return s != null && (now(p) < s.busyUntil || s.pierce != null || s.dash != null || s.quadKick != null);
    }

    private static void busy(ServerPlayer p, int ticks) { st(p).busyUntil = Math.max(st(p).busyUntil, now(p) + ticks); }

    public static void startUnshell(ServerPlayer p, ItemStack stack) { st(p).unshelling = stack; }

    public static boolean isUnshelling(ServerPlayer p, ItemStack stack) {
        State s = STATES.get(p.getUUID());
        return s != null && s.unshelling == stack;
    }

    public static void abortUnshell(ServerPlayer p) { st(p).unshelling = null; }

    public static void finishUnshell(ServerPlayer p) {
        State s = st(p);
        s.unshelling = null;
    }

    /** 左键持键状态由客户端仅在按下/松开边沿同步；不是逐 tick 攻击包。
     * 单击仍走 onAttack，连续攻击统一由服务器 tick 和 busyUntil 控速。
     */
    public static void setAttackHeld(ServerPlayer p, boolean held) {
        State state = st(p);
        ItemStack stack = p.getMainHandItem();
        if (held && stack.is(ModItems.LAEVATEIN) && !p.isSpectator()) {
            state.attackHeld = true;
            state.heldStack = stack;
        } else {
            state.attackHeld = false;
            state.heldStack = null;
        }
    }

    private static boolean canRepeat(WeaponMode mode) {
        return mode == WeaponMode.SWEEP || mode == WeaponMode.PIERCE || mode == WeaponMode.HEAVY_SLASH;
    }

    /** 每次右键物理按下沿触发一次，和攻击 busy/cooldown 完全解耦。 */
    public static void cycleMode(ServerPlayer p) {
        ItemStack stack = p.getMainHandItem();
        if (!stack.is(ModItems.LAEVATEIN) || p.isSpectator() || LaevateinItem.stage(stack) != 0
            || stack.has(ModComponents.THROWN) || st(p).reshelling || st(p).unshelling != null) return;
        WeaponMode next = LaevateinItem.mode(stack).next();
        State state = st(p);
        state.heavyComboStep = 0;
        state.heavyComboExpiresAt = 0;
        if (state.quadKick != null) quadFinish(p, state, true);
        resetQuadCombo(state);
        stack.set(ModComponents.MODE, next);
        p.displayClientMessage(Component.translatable("message.laevatein.mode", Component.translatable(next.translationKey()))
            .withStyle(ChatFormatting.GOLD), true);
        p.serverLevel().playSound(null, p.blockPosition(), SoundEvents.ARMOR_EQUIP_NETHERITE.value(), SoundSource.PLAYERS, 0.8f, 1.3f);
    }

    /** N 键开始重新包装，复用模型自带的 3 秒 reshell 动画。 */
    public static void requestReshell(ServerPlayer p) {
        ItemStack stack = p.getMainHandItem();
        State state = st(p);
        if (!stack.is(ModItems.LAEVATEIN) || p.isSpectator() || p.isUsingItem() || isBusy(p)
            || state.unshelling != null || state.reshelling || stack.has(ModComponents.THROWN)
            || LaevateinItem.stage(stack) == 3) return;

        // 动画需要所有包装骨骼可见：立即设置 3 层，模型资源会用 scale=0 的关键帧逐层还原。
        state.reshelling = true;
        state.bufferedStack = null;
        state.heavyComboStep = 0;
        state.heavyComboExpiresAt = 0;
        resetQuadCombo(state);
        stack.set(ModComponents.SHELL_STAGE, 3);
        LaevateinHeat.reset(p); // 开始重新包装即立即清除玩家累计的热量
        stack.set(ModComponents.SEAL_HITS, 0);
        stack.set(ModComponents.MODE, WeaponMode.SWEEP);
        ModItems.LAEVATEIN.playShellAnim(p, stack, "reshell");
        ModNetwork.playAnim(p, "reshell_hold");
        busy(p, LaevateinConfig.RESHELL_TICKS);
        schedule(p, LaevateinConfig.RESHELL_TICKS, () -> {
            state.reshelling = false;
            ModItems.LAEVATEIN.stopShellAnim(p, stack, "reshell");
            ModNetwork.stopAnim(p);
        });
    }

    private static void schedule(ServerPlayer p, int delay, Runnable r) { st(p).tasks.add(new Task(now(p) + delay, r)); }

    // ---------------- V 键 · 手动四段重踏（一次物理按下只踢一次） ----------------

    /** 服务端只在按键当刻挑选面前可见的生物，客户端不传目标 ID。 */
    private static LivingEntity findQuadKickTarget(ServerPlayer p, double range) {
        LivingEntity aimed = pick(p, range);
        if (aimed != null && p.hasLineOfSight(aimed)) return aimed;
        LivingEntity nearest = null;
        double best = Double.POSITIVE_INFINITY;
        for (LivingEntity e : cone(p, range, 95)) {
            if (!p.hasLineOfSight(e)) continue;
            double dist = p.distanceToSqr(e);
            if (dist < best) { best = dist; nearest = e; }
        }
        return nearest;
    }

    private static void resetQuadCombo(State state) {
        state.quadKickLastPhase = 0;
        state.quadKickComboExpiresAt = 0;
        state.quadKickComboStack = null;
        state.quadKickComboLevel = null;
        state.quadKickComboTarget = null;
    }

    /** 空中追击只使用之前已选中的目标；不可在追踪中突然掉头改追其他实体。 */
    private static boolean kickTargetValid(ServerPlayer p, LivingEntity t, ServerLevel level) {
        return t != null && valid(p, t) && t.level() == level
            && p.distanceTo(t) <= LaevateinConfig.QUAD_KICK_TRACK_MAX_RANGE
            && p.hasLineOfSight(t);
    }

    /** V 每个上升沿仅对应一脚。动作期间再按一次可缓冲且只缓冲下一脚。 */
    public static void requestQuadKick(ServerPlayer p) {
        ItemStack stack = p.getMainHandItem();
        State state = st(p);
        if (!stack.is(ModItems.LAEVATEIN) || !p.isAlive() || p.isSpectator()
            || p.isPassenger() || p.isUsingItem() || state.reshelling
            || state.unshelling != null || stack.has(ModComponents.THROWN)
            || state.dash != null) return;

        QuadKick current = state.quadKick;
        if (current != null) {
            // 同一段里连按不会跳段，下一段最多缓存一枚真实按键。
            if (current.phase < 4 && !current.queuedNext) current.queuedNext = true;
            return;
        }

        long tick = now(p);
        boolean continuing = state.quadKickLastPhase > 0
            && tick <= state.quadKickComboExpiresAt
            && state.quadKickComboStack == stack && state.quadKickComboLevel == p.serverLevel();
        if (!continuing && tick < state.quadKickReadyAt) {
            long seconds = (state.quadKickReadyAt - tick + 19) / 20;
            p.displayClientMessage(Component.translatable("message.laevatein.quad_kick_cooldown", seconds)
                .withStyle(ChatFormatting.GRAY), true);
            return;
        }
        if (LaevateinItem.stage(stack) > 0 && isBusy(p)) return;
        int phase = continuing ? state.quadKickLastPhase + 1 : 1;
        if (!continuing) {
            resetQuadCombo(state);
            state.quadKickReadyAt = tick + LaevateinConfig.QUAD_KICK_COOLDOWN;
        }
        startQuadKickPhase(p, state, stack, phase);
    }

    private static void startQuadKickPhase(ServerPlayer p, State state, ItemStack stack, int phase) {
        if (phase < 1 || phase > 4) return;
        // V 优先打断本体近战，但不能取消封印甩壳的延迟阶段变更。
        if (LaevateinItem.stage(stack) == 0) {
            state.tasks.clear();
            state.pierce = null;
            state.busyUntil = now(p);
        }
        state.bufferedStack = null;
        state.attackHeld = false;
        state.heldStack = null;
        state.heavyComboStep = 0;
        state.heavyComboExpiresAt = 0;

        // 首脚选目标；随后三脚只追同一个目标，目标消失则空踢，不突然转追附近实体。
        LivingEntity target = phase == 1
            ? findQuadKickTarget(p, LaevateinConfig.QUAD_KICK_TARGET_RANGE)
            : state.quadKickComboTarget;
        if (!kickTargetValid(p, target, p.serverLevel())) target = null;
        QuadKick job = new QuadKick(stack, p.serverLevel(), target, phase);
        state.quadKick = job;
        state.quadKickComboStack = stack;
        state.quadKickComboLevel = p.serverLevel();
        state.quadKickComboTarget = target;

        // 首脚永远原地起脚，不再将玩家弹到半空。后续脚有目标且距离稍远才短冲刺。
        if (phase == 1 || target == null || p.distanceTo(target) <= LaevateinConfig.QUAD_KICK_REACH - 0.45) {
            quadStartStrike(p, job);
        } else {
            job.striking = false;
            ModNetwork.playAnim(p, "quad_kick_chase");
        }
    }

    private static void quadStartStrike(ServerPlayer p, QuadKick job) {
        job.striking = true;
        job.strikeTicks = 0;
        if (job.chased) {
            // 接近目标后停止持续横向飞行；不干扰跳跃 / 重力 / 玩家手动上下移动。
            Vec3 v = p.getDeltaMovement();
            p.setDeltaMovement(v.x * 0.28, v.y, v.z * 0.28);
            p.hurtMarked = true;
        }
        ModNetwork.playAnim(p, "quad_kick_" + job.phase);
        // Sound plays on the real impact frame, not at animation start.
    }

    /** 每脚只能命中一次。限制垂直与横向速度，避免旧版大力叠加导致目标乱飞。 */
    private static void quadImpact(ServerPlayer p, QuadKick job) {
        LivingEntity target = job.target;
        if (!kickTargetValid(p, target, job.level)
            || p.distanceTo(target) > LaevateinConfig.QUAD_KICK_REACH) return;
        int index = job.phase - 1;
        // Break a front-facing raised shield on the actual heavy-stomp impact frame.
        ShieldBreaks.breakRaisedShield(p, target);
        if (!hit(p, target, LaevateinConfig.QUAD_KICK_DAMAGE[index], 0, false)) return;
        Vec3 fwd = target.position().subtract(p.position()).multiply(1, 0, 1);
        if (fwd.lengthSqr() < 0.0001) fwd = p.getLookAngle().multiply(1, 0, 1);
        if (fwd.lengthSqr() < 0.0001) fwd = new Vec3(0, 0, 1);
        fwd = fwd.normalize();
        // 施加固定、有限的一次冲量，不与前一脚的剩余速度累计。
        double speed = LaevateinConfig.QUAD_KICK_FORWARD_FORCE[index];
        double vy = LaevateinConfig.QUAD_KICK_VERTICAL_FORCE[index];
        target.setDeltaMovement(fwd.x * speed, vy, fwd.z * speed);
        target.hasImpulse = true;
        target.hurtMarked = true;
        LaevateinFx.quadKickImpact(p.serverLevel(), target, job.phase);
        ModSounds.play(p.serverLevel(), target, ModSounds.kickSound(job.phase), SoundSource.PLAYERS, 1.05f);
        if (job.phase == 4) ModSounds.play(p.serverLevel(), target, ModSounds.DOWN_STOMP, SoundSource.PLAYERS, 0.95f);
        if (job.phase == 4) {
            ServerLevel origin = job.level;
            schedule(p, 6, () -> {
                if (target.isAlive() && target.level() == origin && target.onGround()) {
                    Vec3 pos = target.position();
                    origin.sendParticles(net.minecraft.core.particles.ParticleTypes.POOF,
                        pos.x, pos.y + 0.05, pos.z, 32, 0.75, 0.1, 0.75, 0.12);
                }
            });
        }
    }

    /** 结束当前一脚。只有明确提前按过另一次 V 时才能接到下一脚。 */
    private static void quadFinish(ServerPlayer p, State state, boolean interrupted) {
        QuadKick job = state.quadKick;
        if (job == null) return;
        state.quadKick = null;
        if (job.chased) {
            // 中断时也刹车，避免松开技能后沿最后一帧的冲刺方向滑飞。
            Vec3 v = p.getDeltaMovement();
            p.setDeltaMovement(v.x * 0.28, v.y, v.z * 0.28);
            p.hurtMarked = true;
        }
        state.noFallUntil = Math.max(state.noFallUntil,
            now(p) + LaevateinConfig.NO_FALL_AFTER_DASH);
        if (interrupted || job.phase == 4) {
            resetQuadCombo(state);
            ModNetwork.stopAnim(p);
            busy(p, LaevateinConfig.QUAD_KICK_RECOVERY_TICKS);
            return;
        }
        state.quadKickLastPhase = job.phase;
        state.quadKickComboExpiresAt = now(p) + LaevateinConfig.QUAD_KICK_COMBO_WINDOW;
        state.quadKickComboTarget = job.target;
        if (job.queuedNext && p.isAlive() && !p.isSpectator() && !p.isPassenger()
            && p.getMainHandItem() == job.stack && p.serverLevel() == job.level
            && !p.isUsingItem() && !job.stack.has(ModComponents.THROWN)) {
            // 每个 queuedNext 均来自新的 V 按键；不会按住 V 就自动推进。
            startQuadKickPhase(p, state, job.stack, job.phase + 1);
        } else {
            ModNetwork.stopAnim(p);
            busy(p, LaevateinConfig.QUAD_KICK_RECOVERY_TICKS);
        }
    }

    private static void quadTick(ServerPlayer p, State state) {
        QuadKick job = state.quadKick;
        if (job == null) return;
        if (!p.isAlive() || p.isSpectator() || p.isPassenger() || p.isUsingItem()
            || p.getMainHandItem() != job.stack || p.serverLevel() != job.level
            || job.stack.has(ModComponents.THROWN)) {
            quadFinish(p, state, true);
            return;
        }
        p.fallDistance = 0;
        state.noFallUntil = now(p) + LaevateinConfig.NO_FALL_AFTER_DASH;

        if (!job.striking) {
            LivingEntity target = job.target;
            if (kickTargetValid(p, target, job.level) && !p.horizontalCollision
                && p.distanceTo(target) > LaevateinConfig.QUAD_KICK_REACH - 0.45
                && job.chaseTicks < LaevateinConfig.QUAD_KICK_CHASE_MAX_TICKS) {
                // 只辅助水平短距离接近。不会每 tick 叠加速度或将玩家向高空牵引。
                Vec3 horizontal = target.position().subtract(p.position()).multiply(1, 0, 1);
                if (horizontal.lengthSqr() > 0.0001) {
                    horizontal = horizontal.normalize().scale(LaevateinConfig.QUAD_KICK_CHASE_SPEED);
                    double y = p.getDeltaMovement().y;
                    // 若目标在上方，只在本次追踪起始时给予一次很小的起跳辅助。
                    if (job.chaseTicks == 0 && p.onGround()
                        && target.getY() > p.getY() + 1.2) y = Math.max(y, LaevateinConfig.QUAD_KICK_CHASE_HOP);
                    p.setDeltaMovement(horizontal.x, y, horizontal.z);
                    p.hurtMarked = true;
                    job.chased = true;
                    LaevateinFx.dashTrail(job.level, p);
                }
                job.chaseTicks++;
                return;
            }
            quadStartStrike(p, job);
            return;
        }

        job.strikeTicks++;
        int index = job.phase - 1;
        if (!job.impacted && job.strikeTicks >= LaevateinConfig.QUAD_KICK_IMPACT_TICKS[index]) {
            job.impacted = true;
            quadImpact(p, job);
        }
        if (job.strikeTicks >= LaevateinConfig.QUAD_KICK_STRIKE_TICKS[index]) {
            quadFinish(p, state, false);
        }
    }

    // ---------------- 左键入口 ----------------

    public static void onAttack(ServerPlayer p) {
        ItemStack s = p.getMainHandItem();
        if (!s.is(ModItems.LAEVATEIN) || p.isUsingItem() || p.isSpectator() || st(p).reshelling) return;
        if (isBusy(p)) {
            // 本体剑在手中时缓冲一发攻击，包括接续投掷；已投出的剑不缓冲误召回。
            if (LaevateinItem.stage(s) == 0 && !s.has(ModComponents.THROWN)) {
                st(p).bufferedStack = s;
                // 四连击中允许在当前动作早期预输入下一段，按键不必恰好卡在收招 8 tick 内。
                st(p).bufferedUntil = LaevateinItem.mode(s) == WeaponMode.HEAVY_SLASH
                    ? Math.max(now(p) + LaevateinConfig.COMBO_BUFFER_TICKS, st(p).busyUntil + 2)
                    : now(p) + LaevateinConfig.COMBO_BUFFER_TICKS;
            }
            return;
        }
        ServerLevel sl = p.serverLevel();
        if (LaevateinItem.stage(s) > 0) { sealSlash(sl, p, s); return; }
        switch (LaevateinItem.mode(s)) {
            case SWEEP -> sweep(sl, p, s);
            case PIERCE -> {
                LivingEntity t = pick(p, LaevateinConfig.PIERCE_RANGE);
                if (t != null) startPierce(p, s, t); else chop(sl, p);
            }
            case THROW -> {
                if (s.has(ModComponents.THROWN)) dashOrRecall(sl, p, s); else throwBlade(p, s);
            }
            case HEAVY_SLASH -> heavySlash(sl, p, s);
        }
    }

    // ---------------- 判定工具 ----------------

    static boolean valid(Player p, Entity e) {
        if (!(e instanceof LivingEntity l) || e == p || !l.isAlive() || l.isSpectator() || e.isAlliedTo(p)) return false;
        if (e instanceof OwnableEntity o && p.getUUID().equals(o.getOwnerUUID())) return false;   // 不打自己的宠物
        return !(e instanceof Player other) || p.canHarmPlayer(other);
    }

    /** 以玩家水平朝向为轴、degrees 为张角的扇形。贴身的目标总是算在内。 */
    static List<LivingEntity> cone(ServerPlayer p, double range, double degrees) {
        Vec3 eye = p.getEyePosition();
        Vec3 look = p.getLookAngle().multiply(1, 0, 1).normalize();
        double cos = Math.cos(Math.toRadians(degrees / 2));
        return p.level().getEntitiesOfClass(LivingEntity.class, p.getBoundingBox().inflate(range), e -> {
            if (!valid(p, e) || e.distanceTo(p) > range) return false;
            Vec3 d = e.position().add(0, e.getBbHeight() * 0.5, 0).subtract(eye).multiply(1, 0, 1);
            return d.lengthSqr() < 1.0 || d.normalize().dot(look) >= cos;
        });
    }

    /** 准星指向的生物。只检测实体，不穿过方块。 */
    static LivingEntity pick(ServerPlayer p, double range) {
        Vec3 eye = p.getEyePosition();
        Vec3 end = eye.add(p.getLookAngle().scale(range));
        var block = p.level().clip(new net.minecraft.world.level.ClipContext(eye, end,
            net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, p));
        Vec3 limit = block.getLocation();
        EntityHitResult r = ProjectileUtil.getEntityHitResult(p, eye, limit,
            p.getBoundingBox().expandTowards(limit.subtract(eye)).inflate(1.0), e -> valid(p, e), eye.distanceToSqr(limit));
        return r != null ? (LivingEntity) r.getEntity() : null;
    }

    /** 造成一次伤害。多段攻击需要先清掉受伤无敌帧。 */
    static boolean hit(ServerPlayer p, LivingEntity e, float dmg, double knockback, boolean ignite) {
        e.invulnerableTime = 0;
        boolean ok = e.hurt(p.damageSources().playerAttack(p), dmg);
        if (ok) {
            // knockback 沿参数方向的反方向推，传 (攻击者 - 目标) 就是把目标推离攻击者
            if (knockback > 0) e.knockback(knockback, p.getX() - e.getX(), p.getZ() - e.getZ());
            if (ignite) e.igniteForSeconds(3);
            p.setLastHurtMob(e);
        }
        return ok;
    }

    // ---------------- 封印态：斜劈，第 8 次命中甩飞一层 ----------------

    private static void sealSlash(ServerLevel sl, ServerPlayer p, ItemStack s) {
        List<LivingEntity> targets = cone(p, LaevateinConfig.SEAL_SLASH_RANGE, LaevateinConfig.SEAL_SLASH_CONE);
        int hits = s.getOrDefault(ModComponents.SEAL_HITS, 0);
        boolean heavy = !targets.isEmpty() && hits + 1 >= LaevateinConfig.SEAL_HITS_PER_LAYER;
        float dmg = LaevateinConfig.SEAL_SLASH_DAMAGE * (heavy ? LaevateinConfig.SEAL_HEAVY_MULT : 1f);

        int landed = 0;
        for (LivingEntity e : targets) {
            // A shield that successfully faces this sealed strike still absorbs the attack,
            // but the impact counts toward breaking the weapon's wrapping.
            boolean shieldBlocked = ShieldBreaks.isRaisedShieldFacingAttacker(p, e);
            boolean damaged = hit(p, e, dmg, heavy ? 1.6 : 0.4, false);
            if (damaged || shieldBlocked) landed++;
        }
        ModNetwork.playAnim(p, heavy ? "seal_heavy" : "seal_slash");
        LaevateinFx.diagonalTrail(sl, p, heavy);
        ModSounds.sealHit(sl, p, LaevateinItem.stage(s), heavy, SoundSource.PLAYERS);

        if (heavy && landed > 0) {
            int stage = LaevateinItem.stage(s);
            ModItems.LAEVATEIN.playShellAnim(p, s, "fling_" + stage);
            s.set(ModComponents.SEAL_HITS, 0);
            LaevateinFx.fireBurst(sl, p);
            ModSounds.breakShell(sl, p, stage, SoundSource.PLAYERS);
            // 渲染器一看到阶段变化就隐藏这一层，所以等 0.5 秒的甩飞动画播完再减阶段
            schedule(p, LaevateinConfig.FLING_DELAY, () -> {
                if (LaevateinItem.stage(s) == stage) s.set(ModComponents.SHELL_STAGE, stage - 1);
            });
            busy(p, Math.max(LaevateinConfig.SEAL_HEAVY_CD, LaevateinConfig.FLING_DELAY + 1));
        } else {
            if (landed > 0) s.set(ModComponents.SEAL_HITS, hits + 1);
            busy(p, LaevateinConfig.SEAL_SLASH_CD);
        }
    }

    // ---------------- 本体 · 四段大力劈砍连携 ----------------

    /**
     * 每次左键仅启动下一段。动画名称 heavy_combo_1..4；判定延后至实际落刀帧。
     * 四段顺序：右斜劈 -> 左斜劈 -> 前方横向重斩 -> 双手终结下劈。
     */
    private static void heavySlash(ServerLevel sl, ServerPlayer p, ItemStack stack) {
        State state = st(p);
        long tick = now(p);
        if (tick > state.heavyComboExpiresAt) state.heavyComboStep = 0;
        int step = state.heavyComboStep + 1;
        if (step > 4) step = 1;
        state.heavyComboStep = step == 4 ? 0 : step;
        state.heavyComboExpiresAt = tick + LaevateinConfig.HEAVY_COMBO_RESET_TICKS;

        int index = step - 1;
        ModNetwork.playAnim(p, "heavy_combo_" + step);
        busy(p, LaevateinConfig.HEAVY_COMBO_COOLDOWNS[index]);
        final int move = step;
        final ServerLevel startLevel = sl;
        schedule(p, LaevateinConfig.HEAVY_COMBO_IMPACT_TICKS[index], () -> {
            // 动画出手至命中期间如果换手、换模式、装壳或死亡，取消本段伤害。
            if (!p.isAlive() || p.serverLevel() != startLevel || p.getMainHandItem() != stack
                || LaevateinItem.stage(stack) != 0 || stack.has(ModComponents.THROWN)
                || st(p).reshelling) return; // 切换形态不取消已挥出的这一刀
            float damage = LaevateinConfig.HEAVY_SLASH_DAMAGE
                * LaevateinConfig.HEAVY_COMBO_DAMAGE_MULTIPLIERS[move - 1];
            double angle = move == 3 ? 120 : (move == 4 ? 95 : LaevateinConfig.HEAVY_SLASH_CONE);
            double knockback = move == 4 ? 1.8 : (move == 3 ? 1.0 : 0.65);
            for (LivingEntity e : cone(p, LaevateinConfig.HEAVY_SLASH_RANGE, angle)) {
                hit(p, e, damage, knockback, true);
            }
            if (move == 3) {
                LaevateinFx.sweepArc(startLevel, p, false);
                ModSounds.play(startLevel, p, ModSounds.SWEEP, SoundSource.PLAYERS, 1.05f);
            } else if (move == 4) {
                LaevateinFx.chopLine(startLevel, p);
                LaevateinFx.fireBurst(startLevel, p);
                ModSounds.play(startLevel, p, ModSounds.HEAVY_FINISH, SoundSource.PLAYERS, 1.2f);
            } else {
                LaevateinFx.diagonalTrail(startLevel, p, true, move == 2);
                ModSounds.play(startLevel, p, ModSounds.BLADE_SLASH, SoundSource.PLAYERS, 1.0f);
            }
        });
    }

    // ---------------- 本体 · 横扫 ----------------

    private static void sweep(ServerLevel sl, ServerPlayer p, ItemStack stack) {
        boolean left = stack.getOrDefault(ModComponents.SWEEP_SIDE, false);
        ModNetwork.playAnim(p, left ? "sweep_left" : "sweep_right");
        stack.set(ModComponents.SWEEP_SIDE, !left);
        busy(p, LaevateinConfig.SWEEP_CD);
        // 横扫的接触点在约 0.30 秒；原版按键瞬间出伤害，弧线尚未出现就已命中。
        schedule(p, 6, () -> {
            if (!p.isAlive() || p.serverLevel() != sl || p.getMainHandItem() != stack
                || LaevateinItem.stage(stack) != 0 || stack.has(ModComponents.THROWN)) return;
            for (LivingEntity e : cone(p, LaevateinConfig.SWEEP_RANGE, LaevateinConfig.SWEEP_CONE)) {
                hit(p, e, LaevateinConfig.SWEEP_DAMAGE, 0.8, true);
            }
            LaevateinFx.sweepArc(sl, p, left);
            ModSounds.play(sl, p, ModSounds.SWEEP, SoundSource.PLAYERS, 1.0f);
        });
    }

    // ---------------- 本体 · 穿刺 / 竖劈 ----------------

    private static void startPierce(ServerPlayer p, ItemStack s, LivingEntity target) {
        st(p).pierce = new Pierce(target, s);
        ModNetwork.playAnim(p, "pierce_rush");
        // Sound follows each of the six actual thrust frames in tickPierce, not the opening pose.
    }

    private static void tickPierce(ServerPlayer p, State st) {
        Pierce job = st.pierce;
        LivingEntity t = job.target;
        if (p.getMainHandItem() != job.stack || !t.isAlive() || t.level() != p.level()
            || p.distanceTo(t) > LaevateinConfig.PIERCE_RANGE + 1.5) {
            endPierce(p, st);
            return;
        }
        if (--job.wait > 0) return;
        ServerLevel sl = p.serverLevel();
        hit(p, t, LaevateinConfig.PIERCE_DAMAGE, 0.05, job.done == LaevateinConfig.PIERCE_HITS - 1);
        LaevateinFx.thrust(sl, p, t);
        // Every thrust has its own fire-pierce sound. Keep the voice sequence separate.
        ModSounds.play(sl, p, ModSounds.FIRE_PIERCE, SoundSource.PLAYERS, 0.82f);
        sl.playSound(null, t.blockPosition(), SoundEvents.TRIDENT_HIT, SoundSource.PLAYERS, 0.19f, 1.4f);
        // 每刺一下往目标方向贴近一点，防止目标被击退后脱离攻击距离
        if (p.distanceTo(t) > 2.5) {
            Vec3 d = t.position().subtract(p.position()).multiply(1, 0, 1).normalize().scale(0.35);
            p.setDeltaMovement(p.getDeltaMovement().add(d));
            p.hurtMarked = true;
        }
        job.wait = LaevateinConfig.PIERCE_INTERVAL;
        if (++job.done >= LaevateinConfig.PIERCE_HITS) endPierce(p, st);
    }

    private static void endPierce(ServerPlayer p, State st) {
        st.pierce = null;
        busy(p, 6);
    }

    private static void chop(ServerLevel sl, ServerPlayer p) {
        ModNetwork.playAnim(p, "chop");
        busy(p, LaevateinConfig.CHOP_CD);
        // 和手臂动作的落刀帧对齐（0.32 秒 ≈ 6 tick）
        schedule(p, 6, () -> {
            Vec3 origin = p.position();
            Vec3 fwd = p.getLookAngle().multiply(1, 0, 1).normalize();
            for (LivingEntity e : p.level().getEntitiesOfClass(LivingEntity.class,
                p.getBoundingBox().inflate(LaevateinConfig.CHOP_LENGTH), e -> valid(p, e))) {
                Vec3 rel = e.position().subtract(origin).multiply(1, 0, 1);
                double along = rel.dot(fwd);
                double side = rel.subtract(fwd.scale(along)).length();
                if (along > -0.5 && along <= LaevateinConfig.CHOP_LENGTH && side <= LaevateinConfig.CHOP_WIDTH + e.getBbWidth() / 2
                    && Math.abs(e.getY() - p.getY()) < 3) {
                    if (hit(p, e, LaevateinConfig.CHOP_DAMAGE, 0.3, true)) e.push(0, 0.35, 0);
                }
            }
            LaevateinFx.chopLine(sl, p);
            ModSounds.play(sl, p, ModSounds.BLADE_SLASH, SoundSource.PLAYERS, 1.0f);
        });
    }

    // ---------------- 本体 · 投掷处决 ----------------

    private static void throwBlade(ServerPlayer p, ItemStack s) {
        s.set(ModComponents.THROWN, PENDING_THROW);       // 立刻隐藏剑身，防止出手前再次触发
        ModNetwork.playAnim(p, "throw");
        busy(p, LaevateinConfig.THROW_RELEASE_DELAY + 4);
        schedule(p, LaevateinConfig.THROW_RELEASE_DELAY, () -> {
            if (!PENDING_THROW.equals(s.get(ModComponents.THROWN))) return;
            if (p.getMainHandItem() != s || !p.isAlive()) { s.remove(ModComponents.THROWN); return; }
            ServerLevel sl = p.serverLevel();
            ThrownLaevatein blade = new ThrownLaevatein(sl, p);
            blade.shootFromRotation(p, p.getXRot(), p.getYRot(), 0f, LaevateinConfig.THROW_SPEED, 0.4f);
            sl.addFreshEntity(blade);
            s.set(ModComponents.THROWN, blade.getUUID());
            sl.playSound(null, p.blockPosition(), SoundEvents.TRIDENT_THROW.value(), SoundSource.PLAYERS, 1f, 0.8f);
            sl.playSound(null, p.blockPosition(), SoundEvents.FIRECHARGE_USE, SoundSource.PLAYERS, 0.6f, 1.2f);
        });
    }

    private static void dashOrRecall(ServerLevel sl, ServerPlayer p, ItemStack s) {
        UUID id = s.get(ModComponents.THROWN);
        if (PENDING_THROW.equals(id)) return;
        if (!(sl.getEntity(id) instanceof ThrownLaevatein blade) || blade.isRemoved()) {
            s.remove(ModComponents.THROWN);
            return;
        }
        LivingEntity target = blade.stuckTarget();
        if (target == null || !target.isAlive()) {
            blade.startRecall();
            sl.playSound(null, p.blockPosition(), SoundEvents.TRIDENT_RETURN, SoundSource.PLAYERS, 1f, 1f);
            busy(p, 4);
            return;
        }
        double dist = p.distanceTo(target);
        int timeout = Math.max(LaevateinConfig.DASH_TIMEOUT_MIN, (int) Math.ceil(dist / LaevateinConfig.DASH_SPEED) + 8);
        st(p).dash = new Dash(blade, target, s, timeout);
        ModNetwork.playAnim(p, "dash_kick");
        sl.playSound(null, p.blockPosition(), SoundEvents.BREEZE_JUMP, SoundSource.PLAYERS, 1f, 0.9f);
    }

    private static void tickDash(ServerPlayer p, State st) {
        Dash job = st.dash;
        LivingEntity t = job.target;
        p.fallDistance = 0;
        st.noFallUntil = now(p) + LaevateinConfig.NO_FALL_AFTER_DASH;
        boolean targetOk = t.isAlive() && t.level() == p.level();
        if (targetOk && p.distanceTo(t) > LaevateinConfig.KICK_REACH && ++job.age <= job.timeout) {
            Vec3 aim = t.position().add(0, t.getBbHeight() * 0.5, 0).subtract(p.position());
            Vec3 v = aim.normalize().scale(LaevateinConfig.DASH_SPEED);
            p.setDeltaMovement(v.x, Math.max(v.y, 0.05) + 0.08, v.z);
            p.hurtMarked = true;
            LaevateinFx.dashTrail(p.serverLevel(), p);
            return;
        }
        if (targetOk && p.distanceTo(t) <= LaevateinConfig.KICK_REACH + 0.5) {
            hit(p, t, LaevateinConfig.KICK_DAMAGE, LaevateinConfig.KICK_KNOCKBACK, true);
            t.push(0, 0.55, 0);
            t.hurtMarked = true;
            LaevateinFx.fireBurst(p.serverLevel(), p);
            ModSounds.play(p.serverLevel(), t, ModSounds.THROW_DASH_KICK, SoundSource.PLAYERS, 1.1f);
            ModSounds.play(p.serverLevel(), t, ModSounds.SWEEP, SoundSource.PLAYERS, 0.8f);
            p.serverLevel().playSound(null, t.blockPosition(), SoundEvents.BLAZE_SHOOT, SoundSource.PLAYERS, 0.65f, 0.9f);
        }
        job.blade.discard();
        job.stack.remove(ModComponents.THROWN);
        p.setDeltaMovement(p.getDeltaMovement().scale(0.15).add(0, 0.2, 0));
        p.hurtMarked = true;
        st.dash = null;
        busy(p, 8);
    }

    // ---------------- 每 tick 推进 ----------------

    private static void tick(MinecraftServer server) {
        Iterator<Map.Entry<UUID, State>> it = STATES.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, State> en = it.next();
            ServerPlayer p = server.getPlayerList().getPlayer(en.getKey());
            if (p == null) { it.remove(); continue; }
            State st = en.getValue();
            long t = now(p);

            if (!st.tasks.isEmpty()) {
                List<Task> due = new ArrayList<>();
                st.tasks.removeIf(task -> { if (task.due() <= t) { due.add(task); return true; } return false; });
                due.forEach(task -> task.run().run());
            }
            if (st.pierce != null) tickPierce(p, st);
            if (st.dash != null) tickDash(p, st);
            if (st.quadKick != null) quadTick(p, st);
            if (st.quadKick == null && st.quadKickLastPhase > 0
                && (t > st.quadKickComboExpiresAt || p.getMainHandItem() != st.quadKickComboStack
                    || p.serverLevel() != st.quadKickComboLevel || !p.isAlive())) {
                resetQuadCombo(st);
            }

            // 攻击输入缓冲：动作允许结束后，在下一可攻击 tick 打出当前已切换的形态。
            if (st.bufferedStack != null) {
                ItemStack buffered = st.bufferedStack;
                if (t > st.bufferedUntil || p.getMainHandItem() != buffered || st.reshelling) {
                    st.bufferedStack = null;
                } else if (!isBusy(p) && !p.isUsingItem() && st.unshelling == null) {
                    st.bufferedStack = null;
                    onAttack(p);
                }
            }

            // 连续攻击：仅三种本体近战形态，且只在上一招完全结束后启动下一招。
            // 投掷/召回与封印斜劈保持原有单击语义，避免持键导致重复投掷或拆壳。
            if (st.attackHeld) {
                ItemStack held = p.getMainHandItem();
                if (held != st.heldStack || !held.is(ModItems.LAEVATEIN)
                    || p.isSpectator() || !p.isAlive()) {
                    st.attackHeld = false;
                    st.heldStack = null;
                } else if (LaevateinItem.stage(held) == 0
                    && !held.has(ModComponents.THROWN)
                    && canRepeat(LaevateinItem.mode(held))
                    && !p.isUsingItem() && !st.reshelling && st.unshelling == null
                    && st.bufferedStack == null && !isBusy(p)) {
                    onAttack(p);
                }
            }

            // 长按拆壳途中切换了物品或被打断：停止当前层的动画，这一层不拆
            if (st.unshelling != null && (!p.isUsingItem() || p.getUseItem() != st.unshelling)) {
                ItemStack s = st.unshelling;
                st.unshelling = null;
                if (LaevateinItem.stage(s) > 0) ModItems.LAEVATEIN.stopShellAnim(p, s, "unshell_" + LaevateinItem.stage(s));
                ModNetwork.stopAnim(p);
            }
            if (t < st.noFallUntil) p.fallDistance = 0;
        }
    }
}
