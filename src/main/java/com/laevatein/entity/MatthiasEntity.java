package com.laevatein.entity;

import com.laevatein.Laevatein;
import com.laevatein.LaevateinConfig;
import com.laevatein.WeaponMode;
import com.laevatein.combat.LaevateinFx;
import com.laevatein.combat.ShieldBreaks;
import com.laevatein.item.LaevateinItem;
import com.laevatein.registry.ModComponents;
import com.laevatein.registry.ModEntities;
import com.laevatein.registry.ModEffects;
import com.laevatein.registry.ModItems;
import com.laevatein.registry.ModSounds;
import java.util.UUID;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import software.bernie.geckolib.animatable.GeoItem;

/**
 * 马蒂亚斯：PlayerModel 外观的独立服务器端 NPC。
 * 所有攻击均由此状态机裁决；不伪造 ServerPlayer，不允许 NPC 使用玩家的长按拆壳。
 * 物品仍是原版 LaevateinItem，同步 SHELL_STAGE/SEAL_HITS/MODE/THROWN 组件。
 */
public class MatthiasEntity extends PathfinderMob {
    public static final float MAX_HP = 500f;
    public static final float LAST_STAND_HP = 70f;
    public static final double MAX_SCALING_ARMOR = 15.0;
    public static final int LAST_STAND_TICKS = 8 * 20;
    public static final int COUNTER_CHARGE_TICKS = 180;
    /** High-ground pursuit kick: one activation per ten seconds. */
    public static final int HIGH_KICK_COOLDOWN_TICKS = 10 * 20;
    public static final double HIGH_KICK_HEIGHT_THRESHOLD = 3.0;
    /** Despawn after five uninterrupted minutes without combat activity. */
    public static final int IDLE_DESPAWN_TICKS = 5 * 60 * 20;

    private static final EntityDataAccessor<String> ACTION = SynchedEntityData.defineId(MatthiasEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Integer> ACTION_START = SynchedEntityData.defineId(MatthiasEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> COUNTER_CHARGE = SynchedEntityData.defineId(MatthiasEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> INVULNERABLE_TICKS = SynchedEntityData.defineId(MatthiasEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Boolean> SWORD_ON_BACK = SynchedEntityData.defineId(MatthiasEntity.class, EntityDataSerializers.BOOLEAN);

    private enum Move { IDLE, SEALED, SWEEP, PIERCE, CHOP, HEAVY, THROW, DASH, KICK, HIGH_KICK }
    private Move move = Move.IDLE;
    private int moveTicks;
    private int comboStep;
    private int nextAttack;
    /** NPC-only post-move recovery from v1.0.16. */
    private int attackDelay;
    private int sealedCycles;
    private int sequence;
    private boolean kickStriking;
    private boolean counterSequence;
    private boolean lastStandUsed;
    private int hurtVoiceUntil;
    private int highKickCooldown;
    private int idleDespawnTicks;
    private boolean highKickStriking;
    private int lastStandRemaining;
    private LivingEntity lockedTarget;
    private ThrownLaevatein thrownBlade;
    private boolean specialDeathLootDropped;

    // Only chat encounters enter the arrival sequence. Spawn eggs stay neutral.
    @Nullable private UUID chatCaller;
    private int chatAnger;
    private boolean chatFalling;
    private boolean chatLanded;
    private int chatFloorY;
    private int chatFallTicks;
    private int chatRecovery;
    private double chatFallSpeed = 0.55;

    public MatthiasEntity(EntityType<? extends PathfinderMob> type, Level level) {
        super(type, level);
        this.xpReward = 80;
    }

    /**
     * Matthias has no right hand. The Laevatein remains in MAINHAND for existing
     * combat, NBT, thrown-blade and equipment logic, but Minecraft must attach
     * that main-hand stack to his LEFT physical arm.
     * Do not move the sword to OFFHAND: that would break the combat state machine.
     */
    @Override
    public HumanoidArm getMainArm() {
        return HumanoidArm.LEFT;
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
            .add(Attributes.MAX_HEALTH, MAX_HP)
            .add(Attributes.ATTACK_DAMAGE, LaevateinConfig.SEAL_SLASH_DAMAGE)
            .add(Attributes.MOVEMENT_SPEED, 0.31)
            .add(Attributes.FOLLOW_RANGE, 32)
            .add(Attributes.KNOCKBACK_RESISTANCE, 0.65);
    }

    /**
     * 500 HP -> 0 armor, 70 HP -> 15 armor. Below 70 HP it remains at 15.
     * This uses the entity's actual health, so healing can reduce the bonus again.
     */
    public static double armorForHealth(float health) {
        float clamped = Mth.clamp(health, LAST_STAND_HP, MAX_HP);
        return MAX_SCALING_ARMOR * (MAX_HP - clamped) / (MAX_HP - LAST_STAND_HP);
    }

    /** Armor is inherited from LivingEntity.createLivingAttributes(); do not add it twice. */
    private void refreshArmorForHealth() {
        AttributeInstance armor = getAttribute(Attributes.ARMOR);
        if (armor == null) return;
        double target = armorForHealth(getHealth());
        if (Math.abs(armor.getBaseValue() - target) > 0.0001) {
            armor.setBaseValue(target);
        }
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder b) {
        super.defineSynchedData(b);
        b.define(ACTION, "");
        b.define(ACTION_START, 0);
        b.define(COUNTER_CHARGE, 0);
        b.define(INVULNERABLE_TICKS, 0);
        b.define(SWORD_ON_BACK, true);
    }

    @Override
    protected void registerGoals() {
        goalSelector.addGoal(1, new FloatGoal(this));
        goalSelector.addGoal(6, new WaterAvoidingRandomStrollGoal(this, 0.8));
        goalSelector.addGoal(7, new LookAtPlayerGoal(this, Player.class, 12f));
        goalSelector.addGoal(8, new RandomLookAroundGoal(this));
        // Neutral until provoked by *any* living entity (not only players).
        targetSelector.addGoal(1, new HurtByTargetGoal(this));
    }

    /** 动作起点与名称通过原版 SynchedEntityData 同步，渲染器读取玩家原动画 JSON。 */
    public String animationName() { return entityData.get(ACTION); }
    public int animationStartTick() { return entityData.get(ACTION_START); }
    public float animationSeconds(float partialTicks) {
        return Math.max(0, (tickCount - entityData.get(ACTION_START) + partialTicks) / 20f);
    }
    public float counterProgress() { return entityData.get(COUNTER_CHARGE) / (float) COUNTER_CHARGE_TICKS; }
    public int lockTicks() { return entityData.get(INVULNERABLE_TICKS); }
    /** Synced to clients; idle = sheathed, combat/counter = drawn. */
    public boolean isSwordOnBack() { return entityData.get(SWORD_ON_BACK); }

    /** Only the debugging item can force an artificial fight. */
    public boolean debugSetTarget(LivingEntity target) {
        if (target == this || !target.isAlive() || target.level() != level() || isAlliedTo(target)) return false;
        setTarget(target);
        lockedTarget = target;
        attackDelay = 0;
        entityData.set(SWORD_ON_BACK, false);
        getNavigation().stop();
        return true;
    }

    public void debugClearTarget() {
        // Clear the reverse direction created by the debugger too; otherwise a hostile
        // Mob immediately hits Matthias again and recreates the very same aggro target.
        LivingEntity former = getTarget();
        if (former instanceof net.minecraft.world.entity.Mob mob && mob.getTarget() == this)
            mob.setTarget(null);
        setTarget(null);
        lockedTarget = null;
        counterSequence = false;
        move = Move.IDLE;
        attackDelay = 0;
        getNavigation().stop();
        recoverSword();
        entityData.set(SWORD_ON_BACK, true);
        anim("");
    }

    private void anim(String name) {
        entityData.set(ACTION, name);
        entityData.set(ACTION_START, tickCount);
    }

    private void ensureSword() {
        if (getMainHandItem().isEmpty()) {
            ItemStack sword = new ItemStack(ModItems.LAEVATEIN);
            // The released spawn egg uses a second entity type but exactly the same
            // MatthiasEntity AI, appearance, combat, counter and persistence logic.
            // Its type survives world saves, so a newly restored empty hand also
            // receives the correct (released) sword rather than rewrapping it.
            boolean initiallyReleased = getType() == ModEntities.MATTHIAS_RELEASED || chatAnger == 3;
            sword.set(ModComponents.SHELL_STAGE, initiallyReleased ? 0 : 3);
            sword.set(ModComponents.SEAL_HITS, 0);
            sword.set(ModComponents.MODE, WeaponMode.SWEEP);
            setItemSlot(EquipmentSlot.MAINHAND, sword);
            setDropChance(EquipmentSlot.MAINHAND, 0f);
            setPersistenceRequired();
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (!(level() instanceof ServerLevel sl) || !isAlive()) return;
        ensureSword();
        if (highKickCooldown > 0) highKickCooldown--;
        if (lastStandRemaining > 0) {
            lastStandRemaining--;
            entityData.set(INVULNERABLE_TICKS, lastStandRemaining);
            if (getHealth() < LAST_STAND_HP) setHealth(LAST_STAND_HP);
        }
        refreshArmorForHealth();
        if (chatFalling) {
            tickChatFall(sl);
            return;
        }
        // The released blade's curse is active immediately after landing (including
        // the arrival recovery) and for released-spawn-egg Matthias from his first tick.
        tickReleasedHeatAura(sl);
        if (chatRecovery > 0) {
            if (chatLanded && animationName().equals("revenge_land")) emitArrivalRing(sl, 18 - chatRecovery);
            if (--chatRecovery == 0) anim("");
            emitPurple(sl);
            return;
        }
        if (chatLanded && getTarget() == null && chatCaller != null) {
            if (sl.getEntity(chatCaller) instanceof ServerPlayer caller
                && caller.isAlive() && !caller.isSpectator() && !isAlliedTo(caller)
                && distanceToSqr(caller) <= 32 * 32) setTarget(caller);
        }
        // Iron golems actively regard Matthias as a hostile threat, even though
        // his entity type remains CREATURE for spawn/ambient behavior.
        provokeNearbyIronGolems(sl);
        // 只有进入战斗才逐步进入紫色反击阶段；反击结束后重新蓄势。
        LivingEntity target = getTarget();
        if (target != null && (!target.isAlive() || target.level() != level() || distanceTo(target) > 40)) {
            setTarget(null);
            target = null;
        }
        boolean combatActive = target != null && target.isAlive() && distanceTo(target) <= 26.0
            || move != Move.IDLE || counterSequence;
        if (combatActive) {
            idleDespawnTicks = 0;
        } else if (++idleDespawnTicks >= IDLE_DESPAWN_TICKS) {
            recoverSword();
            discard();
            return;
        }
        entityData.set(SWORD_ON_BACK, target == null && move == Move.IDLE && !counterSequence);
        if (target != null && target.isAlive() && !counterSequence) {
            int charge = entityData.get(COUNTER_CHARGE);
            if (charge < COUNTER_CHARGE_TICKS) entityData.set(COUNTER_CHARGE, charge + 1);
        }
        // A single high-ground pursuit kick is allowed when a player is at least
        // three blocks above Matthias. The ten-second cooldown prevents spam.
        if (move == Move.IDLE && highKickCooldown <= 0 && target instanceof Player player
            && player.getY() - getY() >= HIGH_KICK_HEIGHT_THRESHOLD
            && distanceTo(player) <= 24.0 && hasLineOfSight(player)) {
            startHighKick(player);
            emitPurple(sl);
            advanceMove(sl);
            return;
        }
        emitPurple(sl);
        if (attackDelay > 0) attackDelay--;
        if (move != Move.IDLE) {
            advanceMove(sl);
            return;
        }
        // Restore the NPC's original post-attack pause. Current move and counter
        // timelines remain independent: a counter can still interrupt recovery.
        if (target == null || !target.isAlive() || distanceTo(target) > 26 || attackDelay > 0) return;
        if (distanceTo(target) > 3.6) {
            // Resume navigation on the first idle tick after an attack, rather
            // than waiting up to eight ticks for the next periodic update.
            if (getNavigation().isDone() || tickCount % 5 == 0)
                getNavigation().moveTo(target, 1.18);
            return;
        }
        getNavigation().stop();
        lockedTarget = target;
        ItemStack sword = getMainHandItem();
        if (LaevateinItem.stage(sword) > 0) {
            // 尚未解放时主要靠斜劈累计命中解壳，中间穿插玩家同款四段踢技。
            if (++sealedCycles % 6 == 0) start(Move.KICK, 1, false);
            else start(Move.SEALED, 0, false);
        } else {
            // 本体四形态与四段踢技全部能在普通战斗中使用。
            switch (nextAttack++ % 6) {
                case 0 -> start(Move.SWEEP, 0, false);
                case 1 -> start(Move.PIERCE, 0, false);
                case 2 -> start(Move.CHOP, 0, false);
                case 3 -> start(Move.HEAVY, 1, false);
                case 4 -> start(Move.KICK, 1, false);
                default -> start(Move.THROW, 0, false);
            }
        }
    }

    /**
     * Nearby iron golems acquire Matthias if they do not already have a live target.
     * This is deliberately one-way: Matthias remains neutral until attacked.
     */
    private void provokeNearbyIronGolems(ServerLevel sl) {
        for (IronGolem golem : sl.getEntitiesOfClass(IronGolem.class,
            getBoundingBox().inflate(24.0), g -> g.isAlive() && !g.isAlliedTo(this))) {
            LivingEntity current = golem.getTarget();
            if (current == null || !current.isAlive() || current.isRemoved())
                golem.setTarget(this);
        }
    }

    private void startHighKick(Player target) {
        lockedTarget = target;
        highKickCooldown = HIGH_KICK_COOLDOWN_TICKS;
        highKickStriking = false;
        move = Move.HIGH_KICK;
        moveTicks = 0;
        comboStep = 3;
        counterSequence = false;
        kickStriking = false;
        getNavigation().stop();
        faceTarget();
        Vec3 horizontal = target.position().subtract(position()).multiply(1, 0, 1);
        if (horizontal.lengthSqr() < 0.0001) horizontal = getLookAngle().multiply(1, 0, 1);
        if (horizontal.lengthSqr() > 0.0001) horizontal = horizontal.normalize();
        // 0.90 vertical launch produces an approximately five-block apex in vanilla gravity.
        setDeltaMovement(horizontal.x * 0.34, 0.90, horizontal.z * 0.34);
        hasImpulse = true;
        anim("quad_kick_chase");
    }

    /**
     * Fully released Matthias carries the same 10-block Laevatein flame aura as a
     * player at 99 heat layers. It starts immediately, burns nearby players and
     * hostile/aggroed mobs for 1 HP per second, and never damages Matthias himself.
     * Fire-resistant and fire-immune entities are excluded. This is independent of
     * the player's heat meter: no player layers are added or synchronized here.
     */
    private void tickReleasedHeatAura(ServerLevel sl) {
        ItemStack sword = getMainHandItem();
        if (sword.isEmpty() || LaevateinItem.stage(sword) != 0) return;

        if (tickCount % 10 == 0) {
            // A restrained, persistent flame halo around the body; not an explosion.
            for (int i = 0; i < 6; i++) {
                double angle = tickCount * 0.035 + Math.PI * 2.0 * i / 6.0;
                double radius = 0.46;
                sl.sendParticles(ParticleTypes.SMALL_FLAME,
                    getX() + Math.cos(angle) * radius,
                    getY() + 0.35 + (i % 3) * 0.48,
                    getZ() + Math.sin(angle) * radius,
                    1, 0.025, 0.055, 0.025, 0.002);
            }
        }
        if (tickCount % LaevateinConfig.HEAT_DAMAGE_INTERVAL != 0) return;

        double radius = LaevateinConfig.HEAT_AURA_RADIUS;
        for (LivingEntity target : sl.getEntitiesOfClass(LivingEntity.class,
            getBoundingBox().inflate(radius), this::isReleasedAuraTarget)) {
            if (distanceToSqr(target) > LaevateinConfig.HEAT_AURA_RADIUS_SQR
                || target.hasEffect(MobEffects.FIRE_RESISTANCE) || target.fireImmune()) continue;

            // Reuse the same visible debuff icon as the player's scorched-flame state.
            target.addEffect(new MobEffectInstance(ModEffects.SCORCHED_BY_LAEVATEIN,
                40, 0, true, false, true));
            target.hurt(sl.damageSources().onFire(), LaevateinConfig.HEAT_DAMAGE);
            sl.sendParticles(ParticleTypes.SMALL_FLAME,
                target.getX(), target.getY() + target.getBbHeight() * 0.55, target.getZ(),
                2, target.getBbWidth() * 0.3, target.getBbHeight() * 0.2,
                target.getBbWidth() * 0.3, 0.004);
        }
    }

    private boolean isReleasedAuraTarget(LivingEntity target) {
        if (target == this || !target.isAlive() || isAlliedTo(target)) return false;
        if (target instanceof Player player) return !player.isCreative() && !player.isSpectator();
        if (target instanceof Enemy) return true;
        return target instanceof Mob mob && mob.getTarget() == this;
    }

    public void beginChatArrival(ServerPlayer caller, int floorY, int anger) {
        chatCaller = caller.getUUID();
        chatFalling = true;
        chatFloorY = floorY;
        chatAnger = anger;
        ensureSword();
        setPersistenceRequired();
        setYBodyRot(getYRot());
        setYHeadRot(getYRot());
        entityData.set(SWORD_ON_BACK, anger < 3);
        if (anger >= 2) feedCounterFromChat(anger);
        anim("revenge_fall");
    }

    public boolean isChatSummoned() { return chatCaller != null; }
    public boolean isChatCaller(ServerPlayer player) { return player.getUUID().equals(chatCaller); }
    public UUID getChatCaller() { return chatCaller; }
    public int getChatAnger() { return chatAnger; }
    public boolean hasFinishedChatArrival() { return chatLanded; }

    public void raiseChatAnger(int anger) {
        if (anger <= chatAnger || anger > 3) return;
        chatAnger = anger;
        feedCounterFromChat(anger);
        if (anger < 3) return;

        ItemStack sword = getMainHandItem();
        boolean wasSealed = sword.isEmpty() || LaevateinItem.stage(sword) > 0;
        if (!wasSealed) return; // Don't cancel an already released attack or throw.
        ensureSword();
        recoverSword();
        sword = getMainHandItem();
        sword.set(ModComponents.SHELL_STAGE, 0);
        sword.set(ModComponents.SEAL_HITS, 0);
        sword.set(ModComponents.MODE, WeaponMode.SWEEP);
        entityData.set(SWORD_ON_BACK, false);
        if (!chatFalling) {
            finish(0);
            chatRecovery = 16;
            anim("revenge_release");
            if (level() instanceof ServerLevel sl)
                ModSounds.play(sl, this, ModSounds.SHELL_OPEN_FINAL, SoundSource.HOSTILE, 1f);
        }
    }

    private void feedCounterFromChat(int anger) {
        int charge = anger >= 3 ? COUNTER_CHARGE_TICKS : COUNTER_CHARGE_TICKS / 2;
        entityData.set(COUNTER_CHARGE, Math.max(charge, entityData.get(COUNTER_CHARGE)));
    }

    @Override
    public void travel(Vec3 input) {
        if (!level().isClientSide && chatFalling) {
            // Server controls downward motion. Vanilla collision still stops the fall.
            Vec3 descent = new Vec3(0.0, -chatFallSpeed, 0.0);
            setDeltaMovement(descent);
            move(MoverType.SELF, descent);
            chatFallSpeed = Math.min(1.55, chatFallSpeed + 0.15);
            return;
        }
        if (!level().isClientSide && chatRecovery > 0) {
            getNavigation().stop();
            super.travel(Vec3.ZERO);
            return;
        }
        super.travel(input);
    }

    @Override
    public boolean causeFallDamage(float distance, float multiplier, DamageSource source) {
        return !chatFalling && super.causeFallDamage(distance, multiplier, source);
    }

    private void tickChatFall(ServerLevel sl) {
        if (chatCaller == null || !(sl.getEntity(chatCaller) instanceof ServerPlayer caller)
            || !caller.isAlive() || caller.isSpectator() || distanceToSqr(caller) > 40 * 40
            || sl.getDifficulty() == net.minecraft.world.Difficulty.PEACEFUL) {
            discard();
            return;
        }
        if (++chatFallTicks > 32 || getY() < chatFloorY + 0.95) {
            discard(); // The checked floor was removed during the descent.
            return;
        }
        if (!onGround()) {
            sl.sendParticles(ParticleTypes.WITCH, getX(), getY() + .95, getZ(), 5 + 3 * chatAnger,
                .23, .44, .23, .025);
            sl.sendParticles(ParticleTypes.CLOUD, getX(), getY() + 1.25, getZ(), 4, .24, .28, .24, .025);
            return;
        }
        chatFalling = false;
        chatLanded = true;
        chatRecovery = 18;
        setDeltaMovement(Vec3.ZERO);
        fallDistance = 0;
        getNavigation().stop();
        if (!isAlliedTo(caller)) setTarget(caller);
        entityData.set(SWORD_ON_BACK, false);
        anim("revenge_land");

        double radius = arrivalRadius();
        float damage = chatAnger == 3 ? 12f : chatAnger == 2 ? 10f : 8f;
        double knockback = chatAnger == 3 ? 1.5 : chatAnger == 2 ? 1.25 : 1.0;
        sl.sendParticles(ParticleTypes.POOF, getX(), getY() + .16, getZ(), 48, .85, .08, .85, .10);
        sl.sendParticles(ParticleTypes.WITCH, getX(), getY() + .6, getZ(), 46, .6, .48, .6, .09);
        ModSounds.play(sl, this, ModSounds.DOWN_STOMP, SoundSource.HOSTILE, 1.3f);

        for (LivingEntity target : sl.getEntitiesOfClass(LivingEntity.class,
            getBoundingBox().inflate(radius, 2.0, radius), e -> e != this && e.isAlive()
                && !e.isSpectator() && !isAlliedTo(e) && hasLineOfSight(e))) {
            double dx = getX() - target.getX(), dz = getZ() - target.getZ();
            if (dx * dx + dz * dz > radius * radius || Math.abs(target.getY() - getY()) > 2.5) continue;
            if (target.hurt(damageSources().mobAttack(this), damage))
                target.knockback(knockback, dx, dz);
        }
    }

    private double arrivalRadius() { return chatAnger == 3 ? 7.0 : chatAnger == 2 ? 6.0 : 5.0; }

    private void emitArrivalRing(ServerLevel sl, int age) {
        if (age > 8 || age % 2 != 0) return;
        double radius = arrivalRadius() * (age + 2) / 10.0;
        for (int i = 0; i < 24; i++) {
            double a = Math.PI * 2 * i / 24.0;
            sl.sendParticles(i % 3 == 0 ? ParticleTypes.CLOUD : ParticleTypes.WITCH,
                getX() + Math.cos(a) * radius, getY() + .16, getZ() + Math.sin(a) * radius,
                1, .055, .065, .055, .01);
        }
    }

    private void emitPurple(ServerLevel sl) {
        int charge = entityData.get(COUNTER_CHARGE);
        if (charge == 0 || tickCount % 2 != 0) return;
        float percent = charge / (float) COUNTER_CHARGE_TICKS;
        int count = 1 + (int) (percent * 15);
        double height = Math.max(0.25, getBbHeight() * percent);
        // 从脚踝的一点稀疏粒子逐渐蔓延到头顶，满蓄势时遍布全身。
        sl.sendParticles(ParticleTypes.WITCH, getX(), getY() + height * 0.5, getZ(),
            count, getBbWidth() * percent * 0.55, Math.max(0.05, height * 0.5),
            getBbWidth() * percent * 0.55, 0.008);
        if (percent >= 1f && tickCount % 8 == 0)
            sl.sendParticles(ParticleTypes.PORTAL, getX(), getY() + getBbHeight() * 0.5, getZ(),
                12, 0.38, getBbHeight() * 0.5, 0.38, 0.03);
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (level().isClientSide) return super.hurt(source, amount);
        if (chatFalling) return false;
        Entity owner = source.getEntity() != null ? source.getEntity() : source.getDirectEntity();
        if (owner instanceof LivingEntity aggressor && aggressor != this && aggressor.isAlive()
            && !isAlliedTo(aggressor)) {
            setTarget(aggressor);
            entityData.set(SWORD_ON_BACK, false);
        }
        if (chatRecovery == 0 && entityData.get(COUNTER_CHARGE) >= COUNTER_CHARGE_TICKS && !counterSequence
            && owner instanceof LivingEntity attacker && attacker != this && attacker.isAlive()) {
            // 反击针对这一次真正的敌人攻击：阻挡该次伤害、锁定攻击者，随后启动完整连携。
            entityData.set(COUNTER_CHARGE, 0);
            lockedTarget = attacker;
            attackDelay = 0;
            counterSequence = true;
            getNavigation().stop();
            if (LaevateinItem.stage(getMainHandItem()) == 0) {
                recoverSword();
                start(Move.THROW, 0, true);
            }
            else start(Move.KICK, 1, true);
            // Random supplied counter voice, emitted once for each counter activation.
            ModSounds.play((ServerLevel) level(), this, ModSounds.MATTHIAS_COUNTER, SoundSource.HOSTILE, 1.2f);
            return false;
        }
        if (lastStandRemaining > 0) return false;
        if (!lastStandUsed && getHealth() > LAST_STAND_HP && getHealth() - amount <= LAST_STAND_HP) {
            float permitted = getHealth() - LAST_STAND_HP;
            if (permitted > 0) super.hurt(source, permitted);
            setHealth(LAST_STAND_HP);
            refreshArmorForHealth();
            lastStandUsed = true;
            lastStandRemaining = LAST_STAND_TICKS;
            entityData.set(INVULNERABLE_TICKS, lastStandRemaining);
            if (level() instanceof ServerLevel sl) {
                sl.sendParticles(ParticleTypes.WITCH, getX(), getY() + 1, getZ(), 65, 0.7, 1, 0.7, 0.14);
                sl.playSound(null, blockPosition(), SoundEvents.TOTEM_USE, SoundSource.HOSTILE, 1.1f, 0.8f);
            }
            return true;
        }
        boolean landed = super.hurt(source, amount);
        if (landed) refreshArmorForHealth();
        if (landed && tickCount >= hurtVoiceUntil && getRandom().nextFloat() < 0.30f) {
            // Voice lines can be several seconds long: do not overlap on every damage tick.
            hurtVoiceUntil = tickCount + 100;
            ModSounds.play((ServerLevel) level(), this, ModSounds.MATTHIAS_HURT, SoundSource.HOSTILE, 1f);
        }
        return landed;
    }

    private void kickVoice(int phase) {
        if (phase >= 1 && phase <= 4 && level() instanceof ServerLevel sl)
            ModSounds.play(sl, this, ModSounds.matthiasKickVoice(phase), SoundSource.HOSTILE, 1f);
    }

    private void start(Move newMove, int step, boolean counter) {
        this.move = newMove;
        this.moveTicks = 0;
        this.comboStep = step;
        this.counterSequence = counter;
        this.kickStriking = false;
        // Random exertion is separate from long scripted counter and piercing voice lines.
        if (!counter && (newMove == Move.SEALED || newMove == Move.SWEEP
            || newMove == Move.CHOP || newMove == Move.HEAVY)
            && getRandom().nextFloat() < 0.23f && level() instanceof ServerLevel sl)
            ModSounds.play(sl, this, ModSounds.MATTHIAS_EFFORT, SoundSource.HOSTILE, 0.85f);
        switch (newMove) {
            case SEALED -> anim("seal_slash");
            case SWEEP -> { getMainHandItem().set(ModComponents.MODE, WeaponMode.SWEEP); anim(sequence++ % 2 == 0 ? "sweep_right" : "sweep_left"); }
            case PIERCE -> {
                getMainHandItem().set(ModComponents.MODE, WeaponMode.PIERCE); anim("pierce_rush");
                if (level() instanceof ServerLevel sl) {
                    ModSounds.play(sl, this, ModSounds.MATTHIAS_PIERCE_START, SoundSource.HOSTILE, 1f);
                }
            }
            case CHOP -> { getMainHandItem().set(ModComponents.MODE, WeaponMode.PIERCE); anim("chop"); }
            case HEAVY -> { getMainHandItem().set(ModComponents.MODE, WeaponMode.HEAVY_SLASH); anim("heavy_combo_" + step); }
            case THROW -> { getMainHandItem().set(ModComponents.MODE, WeaponMode.THROW); anim("throw"); }
            case DASH -> {
                anim("dash_kick");
                if (level() instanceof ServerLevel sl)
                    ModSounds.play(sl, this, ModSounds.MATTHIAS_DASH_VOICE, SoundSource.HOSTILE, 1f);
            }
            case KICK -> {
                // 先判断距离。离目标较远时使用追击姿态，接近后才播放这脚的踢出动画。
                if (reach(LaevateinConfig.QUAD_KICK_REACH)) {
                    kickStriking = true;
                    anim("quad_kick_" + step);
                    kickVoice(step);
                } else {
                    anim("quad_kick_chase");
                    // Begin approaching immediately, not on the following animation tick.
                    if (goodTarget()) getNavigation().moveTo(lockedTarget, 1.65);
                }
            }
            case HIGH_KICK -> anim("quad_kick_chase");
            default -> anim("");
        }
    }

    private boolean goodTarget() {
        return lockedTarget != null && lockedTarget.isAlive() && lockedTarget.level() == level()
            && lockedTarget != this && !isAlliedTo(lockedTarget);
    }

    private void faceTarget() {
        if (!goodTarget()) return;
        double dx = lockedTarget.getX() - getX();
        double dz = lockedTarget.getZ() - getZ();
        float desiredYaw = (float) (Mth.atan2(dz, dx) * (180 / Math.PI)) - 90f;
        // No instantaneous 180-degree rotations while a limb is mid-swing.
        // Body turns first with a bounded angular velocity, head may lead slightly.
        float nextYaw = Mth.approachDegrees(getYRot(), desiredYaw, 14f);
        setYRot(nextYaw);
        setYBodyRot(Mth.approachDegrees(yBodyRot, nextYaw, 14f));
        setYHeadRot(Mth.approachDegrees(getYHeadRot(), desiredYaw, 22f));
        getLookControl().setLookAt(lockedTarget, 45, 35);
    }

    private boolean reach(double max) { return goodTarget() && distanceTo(lockedTarget) <= max && hasLineOfSight(lockedTarget); }

    private boolean damage(float damage, double knockback, boolean fire, double range) {
        return reach(range) && harm(lockedTarget, damage, knockback, fire);
    }

    private boolean harm(LivingEntity t, float damage, double knockback, boolean fire) {
        t.invulnerableTime = 0;
        boolean landed = t.hurt(damageSources().mobAttack(this), damage);
        if (landed) {
            if (knockback > 0) t.knockback(knockback, getX() - t.getX(), getZ() - t.getZ());
            if (fire) t.igniteForSeconds(3);
            setLastHurtMob(t);
            if (level() instanceof ServerLevel sl) {
                sl.sendParticles(ParticleTypes.FLAME, t.getX(), t.getY() + t.getBbHeight() * 0.5,
                    t.getZ(), 12, 0.3, 0.3, 0.3, 0.08);
                // Attack-type audio plays once in the corresponding move's impact window.
            }
        }
        return landed;
    }

    /** 与玩家相同的朝向扇形 AOE；每一挥最多给每个目标一次命中。 */
    private int areaDamage(float damage, double knockback, boolean ignite, double range, double cone) {
        return areaDamage(damage, knockback, ignite, range, cone, false);
    }

    /** Sealed wrapping attacks count a correctly-facing shield block as an impact for unwrapping. */
    private int areaDamage(float damage, double knockback, boolean ignite, double range, double cone,
                           boolean countShieldBlocks) {
        Vec3 fwd = getLookAngle().multiply(1, 0, 1);
        if (fwd.lengthSqr() < 0.0001) fwd = new Vec3(0, 0, 1);
        fwd = fwd.normalize();
        int landed = 0;
        double cosHalf = Math.cos(Math.toRadians(cone * 0.5));
        for (LivingEntity t : level().getEntitiesOfClass(LivingEntity.class, getBoundingBox().inflate(range), e ->
            e != this && e.isAlive() && !e.isSpectator() && !isAlliedTo(e) && hasLineOfSight(e))) {
            if (distanceTo(t) > range) continue;
            Vec3 v = t.position().subtract(position()).multiply(1, 0, 1);
            if (v.lengthSqr() >= 1.0 && v.normalize().dot(fwd) < cosHalf) continue;
            boolean shieldBlocked = countShieldBlocks && ShieldBreaks.isRaisedShieldFacingAttacker(this, t);
            if (harm(t, damage, knockback, ignite) || shieldBlocked) landed++;
        }
        return landed;
    }

    private void advanceMove(ServerLevel sl) {
        moveTicks++;
        if (goodTarget() && move != Move.DASH && move != Move.THROW) faceTarget();
        switch (move) {
            case SEALED -> {
                if (moveTicks == 5) {
                    ItemStack sword = getMainHandItem();
                    int stage = LaevateinItem.stage(sword);
                    if (stage <= 0) { finish(7); return; }
                    int hits = sword.getOrDefault(ModComponents.SEAL_HITS, 0);
                    boolean critical = hits + 1 >= LaevateinConfig.MATTHIAS_SEAL_HITS_PER_LAYER;
                    ModSounds.sealHit(sl, this, stage, critical, SoundSource.HOSTILE);
                    boolean landed = areaDamage(LaevateinConfig.SEAL_SLASH_DAMAGE * (critical ? 2 : 1),
                        critical ? 1.6 : 0.4, false, LaevateinConfig.SEAL_SLASH_RANGE,
                        LaevateinConfig.SEAL_SLASH_CONE, true) > 0;
                    if (landed) {
                        if (critical) {
                            sword.set(ModComponents.SEAL_HITS, 0);
                            anim("seal_break_twohands");
                            // 先播放该层甩壳，再到第 10 tick 才真的隐藏骨骼。
                            ModItems.LAEVATEIN.triggerAnim(this, GeoItem.getOrAssignId(sword, sl),
                                LaevateinItem.SHELL_CONTROLLER, "fling_" + stage);
                            LaevateinFx.diagonalTrail(sl, this, true);
                            fireBurst(sl);
                            ModSounds.breakShell(sl, this, stage, SoundSource.HOSTILE);
                            ModSounds.play(sl, this, ModSounds.matthiasShellVoice(stage), SoundSource.HOSTILE, 1f);
                        } else {
                            sword.set(ModComponents.SEAL_HITS, hits + 1);
                            LaevateinFx.diagonalTrail(sl, this, false);
                        }
                    }
                }
                if (moveTicks == 5 + LaevateinConfig.FLING_DELAY && "seal_break_twohands".equals(animationName())) {
                    ItemStack sword = getMainHandItem();
                    int stage = LaevateinItem.stage(sword);
                    if (stage > 0) sword.set(ModComponents.SHELL_STAGE, stage - 1);
                }
                if (moveTicks >= 20) finish(8);
            }
            case SWEEP -> {
                if (moveTicks == 6) {
                    areaDamage(LaevateinConfig.SWEEP_DAMAGE, 0.8, true, LaevateinConfig.SWEEP_RANGE, LaevateinConfig.SWEEP_CONE);
                    LaevateinFx.sweepArc(sl, this, sequence % 2 == 0);
                    ModSounds.play(sl, this, ModSounds.SWEEP, SoundSource.HOSTILE, 1f);
                }
                if (moveTicks >= LaevateinConfig.SWEEP_CD) finish(5);
            }
            case PIERCE -> {
                if (moveTicks <= LaevateinConfig.PIERCE_HITS * LaevateinConfig.PIERCE_INTERVAL
                    && moveTicks % LaevateinConfig.PIERCE_INTERVAL == 0)
                    {
                        damage(LaevateinConfig.PIERCE_DAMAGE, 0.05, false, LaevateinConfig.PIERCE_RANGE);
                        ModSounds.play(sl, this, ModSounds.FIRE_PIERCE, SoundSource.HOSTILE, 0.82f);
                        if (goodTarget()) LaevateinFx.thrust(sl, this, lockedTarget);
                    }
                if (moveTicks >= 18) {
                    ModSounds.play(sl, this, ModSounds.MATTHIAS_PIERCE_END, SoundSource.HOSTILE, 0.95f);
                    finish(5);
                }
            }
            case CHOP -> {
                if (moveTicks == 6) {
                    areaDamage(LaevateinConfig.CHOP_DAMAGE, 0.3, true, LaevateinConfig.CHOP_LENGTH, 50);
                    LaevateinFx.chopLine(sl, this);
                    ModSounds.play(sl, this, ModSounds.BLADE_SLASH, SoundSource.HOSTILE, 1f);
                }
                if (moveTicks >= LaevateinConfig.CHOP_CD) finish(5);
            }
            case HEAVY -> {
                int i = Math.max(0, Math.min(3, comboStep - 1));
                if (moveTicks == LaevateinConfig.HEAVY_COMBO_IMPACT_TICKS[i]) {
                    float d = LaevateinConfig.HEAVY_SLASH_DAMAGE * LaevateinConfig.HEAVY_COMBO_DAMAGE_MULTIPLIERS[i];
                    areaDamage(d, comboStep == 4 ? 1.8 : 0.7, true,
                        LaevateinConfig.HEAVY_SLASH_RANGE, comboStep == 3 ? 120 : LaevateinConfig.HEAVY_SLASH_CONE);
                    if (comboStep == 4) { LaevateinFx.chopLine(sl, this); fireBurst(sl); }
                    else if (comboStep == 3) LaevateinFx.sweepArc(sl, this, false);
                    else LaevateinFx.diagonalTrail(sl, this, true, comboStep == 2);
                    ModSounds.play(sl, this, comboStep == 4 ? ModSounds.HEAVY_FINISH
                        : comboStep == 3 ? ModSounds.SWEEP : ModSounds.BLADE_SLASH, SoundSource.HOSTILE, 1.1f);
                }
                if (moveTicks >= LaevateinConfig.HEAVY_COMBO_COOLDOWNS[i]) {
                    if (comboStep < 4 && (counterSequence || nextAttack % 6 == 4)) start(Move.HEAVY, comboStep + 1, counterSequence);
                    else finish(10);
                }
            }
            case KICK -> {
                // NPC 不给玩家施加任何追击速度；仅靠路径寻路贴近已锁定的攻击者。
                // 玩家受到的四次踢击冲量独立覆盖，不叠加旧速度。
                int i = Math.max(0, Math.min(3, comboStep - 1));
                if (!kickStriking) {
                    // The previous implementation could spend 18 ticks chasing,
                    // then execute an air-kick even if pathfinding had failed.
                    // Abort the attempt instead: normal AI can re-path immediately.
                    if (!goodTarget()) { finish(5); return; }
                    if (!reach(LaevateinConfig.QUAD_KICK_REACH)) {
                        if (moveTicks >= 16 || distanceTo(lockedTarget) >= LaevateinConfig.QUAD_KICK_TRACK_MAX_RANGE) {
                            finish(5);
                            return;
                        }
                        if (!getNavigation().moveTo(lockedTarget, 1.65)) {
                            finish(5);
                            return;
                        }
                        return;
                    }
                    getNavigation().stop();
                    kickStriking = true;
                    moveTicks = 0; // 从真正踢出的那帧开始计算接触时间
                    anim("quad_kick_" + comboStep);
                    kickVoice(comboStep);
                    return;
                }
                if (moveTicks == LaevateinConfig.QUAD_KICK_IMPACT_TICKS[i]) {
                    // Matthias heavy stomp also breaks a shield facing him on impact.
                    if (reach(LaevateinConfig.QUAD_KICK_REACH)) ShieldBreaks.breakRaisedShield(this, lockedTarget);
                    if (damage(LaevateinConfig.QUAD_KICK_DAMAGE[i], 0, false, LaevateinConfig.QUAD_KICK_REACH)) {
                        Vec3 horizontal = lockedTarget.position().subtract(position()).multiply(1, 0, 1);
                        if (horizontal.lengthSqr() < 0.0001) horizontal = getLookAngle().multiply(1, 0, 1);
                        if (horizontal.lengthSqr() > 0.0001) horizontal = horizontal.normalize();
                        lockedTarget.setDeltaMovement(horizontal.x * LaevateinConfig.QUAD_KICK_FORWARD_FORCE[i],
                            LaevateinConfig.QUAD_KICK_VERTICAL_FORCE[i], horizontal.z * LaevateinConfig.QUAD_KICK_FORWARD_FORCE[i]);
                        lockedTarget.hasImpulse = true;
                        lockedTarget.hurtMarked = true;
                        LaevateinFx.quadKickImpact(sl, lockedTarget, comboStep);
                        ModSounds.play(sl, lockedTarget, ModSounds.kickSound(comboStep), SoundSource.HOSTILE, 1.05f);
                        if (comboStep == 4) ModSounds.play(sl, lockedTarget, ModSounds.DOWN_STOMP, SoundSource.HOSTILE, 1.05f);
                    }
                }
                if (moveTicks >= LaevateinConfig.QUAD_KICK_STRIKE_TICKS[i]) {
                    if (comboStep < 4 && goodTarget()) start(Move.KICK, comboStep + 1, counterSequence);
                    else finish(16);
                }
            }
            case HIGH_KICK -> {
                if (!goodTarget()) { finish(10); return; }
                if (!highKickStriking) {
                    Vec3 toward = lockedTarget.position().subtract(position()).multiply(1, 0, 1);
                    if (toward.lengthSqr() > 0.0001) {
                        toward = toward.normalize();
                        Vec3 current = getDeltaMovement();
                        Vec3 steer = new Vec3(current.x * 0.78 + toward.x * 0.13, current.y,
                            current.z * 0.78 + toward.z * 0.13);
                        double horizontalSpeed = Math.sqrt(steer.x * steer.x + steer.z * steer.z);
                        if (horizontalSpeed > 0.52) steer = new Vec3(steer.x * 0.52 / horizontalSpeed,
                            steer.y, steer.z * 0.52 / horizontalSpeed);
                        setDeltaMovement(steer);
                    }
                    if (moveTicks >= 5 && reach(LaevateinConfig.QUAD_KICK_REACH)) {
                        highKickStriking = true;
                        moveTicks = 0;
                        getNavigation().stop();
                        anim("quad_kick_3");
                        kickVoice(3);
                    } else if (moveTicks >= 60 || (onGround() && moveTicks > 8)) {
                        finish(10);
                    }
                } else {
                    if (moveTicks == 3 && reach(LaevateinConfig.QUAD_KICK_REACH)) {
                        boolean landed = damage(LaevateinConfig.QUAD_KICK_DAMAGE[2], 0.15, false,
                            LaevateinConfig.QUAD_KICK_REACH);
                        if (landed) {
                            Vec3 away = lockedTarget.position().subtract(position()).multiply(1, 0, 1);
                            if (away.lengthSqr() > 0.0001) away = away.normalize();
                            lockedTarget.setDeltaMovement(away.x * LaevateinConfig.QUAD_KICK_FORWARD_FORCE[2],
                                Math.max(0.35, LaevateinConfig.QUAD_KICK_VERTICAL_FORCE[2]),
                                away.z * LaevateinConfig.QUAD_KICK_FORWARD_FORCE[2]);
                            lockedTarget.hasImpulse = true;
                            lockedTarget.hurtMarked = true;
                            LaevateinFx.quadKickImpact(sl, lockedTarget, 3);
                            ModSounds.play(sl, lockedTarget, ModSounds.kickSound(3), SoundSource.HOSTILE, 1.05f);
                        }
                    }
                    if (moveTicks >= 9) finish(10);
                }
            }
            case THROW -> {
                if (moveTicks == LaevateinConfig.THROW_RELEASE_DELAY && goodTarget()) {
                    ItemStack sword = getMainHandItem();
                    ThrownLaevatein arrow = new ThrownLaevatein(sl, this);
                    // Start the projectile at the LEFT hand, rather than the
                    // default eye/right-hand origin of a normally right-handed mob.
                    Vec3 facing = getLookAngle().multiply(1, 0, 1);
                    if (facing.lengthSqr() < 1.0e-4) facing = new Vec3(0, 0, 1);
                    facing = facing.normalize();
                    Vec3 left = new Vec3(facing.z, 0, -facing.x);
                    Vec3 launch = getEyePosition().add(facing.scale(0.38))
                        .add(left.scale(0.34)).subtract(0, 0.50, 0);
                    arrow.setPos(launch.x, launch.y, launch.z);
                    Vec3 v = lockedTarget.getEyePosition().subtract(launch);
                    arrow.setMatthiasHomingTarget(lockedTarget);
                    // Zero spread, NPC-only target guidance each tick; player throws retain scatter.
                    arrow.shoot(v.x, v.y, v.z, (float) LaevateinConfig.MATTHIAS_THROW_TRACK_SPEED, 0f);
                    sl.addFreshEntity(arrow);
                    thrownBlade = arrow;
                    sword.set(ModComponents.THROWN, arrow.getUUID());
                    sl.playSound(null, blockPosition(), SoundEvents.TRIDENT_THROW.value(), SoundSource.HOSTILE, 1.0f, 0.85f);
                }
                if (moveTicks >= 8) {
                    if (thrownBlade != null && !thrownBlade.isRemoved()
                        && thrownBlade.stuckTarget() != null) start(Move.DASH, 0, counterSequence);
                    else if (moveTicks >= 44) {
                        // 投剑未命中时主动收回，保证后续的大力劈砍确实手持完整剑。
                        recoverSword();
                        if (counterSequence) start(Move.HEAVY, 1, true); else finish(12);
                    }
                }
            }
            case DASH -> {
                // 投剑命中后追近踹飞；若路径被挡或超时，则召回而不瞬移穿墙。
                if (!goodTarget() || thrownBlade == null || thrownBlade.isRemoved()) {
                    recoverSword();
                    if (counterSequence) start(Move.HEAVY, 1, true); else finish(10);
                    return;
                }
                if (distanceTo(lockedTarget) > LaevateinConfig.KICK_REACH + 0.5 && moveTicks < 34)
                    getNavigation().moveTo(lockedTarget, 1.65);
                else {
                    if (reach(LaevateinConfig.KICK_REACH + 0.5)) {
                        damage(LaevateinConfig.KICK_DAMAGE, LaevateinConfig.KICK_KNOCKBACK, true, LaevateinConfig.KICK_REACH + 0.5);
                        lockedTarget.push(0, 0.35, 0);
                        fireBurst(sl);
                        ModSounds.play(sl, lockedTarget, ModSounds.THROW_DASH_KICK, SoundSource.HOSTILE, 1.15f);
                        ModSounds.play(sl, lockedTarget, ModSounds.SWEEP, SoundSource.HOSTILE, 0.75f);
                        sl.playSound(null, lockedTarget.blockPosition(), SoundEvents.BLAZE_SHOOT, SoundSource.HOSTILE, 0.6f, 0.95f);
                    }
                    recoverSword();
                    if (counterSequence) start(Move.HEAVY, 1, true); else finish(9);
                }
            }
            default -> {}
        }
    }

    private void sweepFx(ServerLevel sl) {
        if (!goodTarget()) return;
        sl.sendParticles(ParticleTypes.SWEEP_ATTACK, lockedTarget.getX(), lockedTarget.getY() + 1,
            lockedTarget.getZ(), 6, 0.65, 0.4, 0.65, 0.02);
    }

    private void fireBurst(ServerLevel sl) {
        LaevateinFx.fireBurst(sl, this);
    }

    private void recoverSword() {
        if (thrownBlade != null && !thrownBlade.isRemoved()) thrownBlade.discard();
        thrownBlade = null;
        getMainHandItem().remove(ModComponents.THROWN);
    }

    /** 让投掷物正常召回时归还 NPC 的剑而非只支持 ServerPlayer。 */
    public void onBladeReturned(UUID arrowId) {
        if (arrowId.equals(getMainHandItem().get(ModComponents.THROWN)))
            getMainHandItem().remove(ModComponents.THROWN);
        thrownBlade = null;
    }

    private void finish(int cooldown) {
        move = Move.IDLE;
        moveTicks = 0;
        comboStep = 0;
        counterSequence = false;
        highKickStriking = false;
        attackDelay = cooldown;
        anim("");
        getNavigation().stop();
    }

    /**
     * 两种马蒂亚斯死亡时固定掉落完全解放的莱瓦汀和一本蜘蛛巢特别条款账簿。
     * 遵守原版 doMobLoot 开关；不执行父类装备掉落，避免抢夺附魔导致重复掉剑。
     * 构造新物品堆而非复制手中武器，防止投掷中的 THROWN UUID 随掉落残留。
     */
    @Override
    protected void dropCustomDeathLoot(ServerLevel level, DamageSource source, boolean recentlyHit) {
        // 不调用 Mob 的默认装备掉落：即使主手掉率为 0，Looting 也可能提高掉率，
        // 造成封印态装备与专属解放态战利品重复出现。
        if (specialDeathLootDropped) return;
        specialDeathLootDropped = true;

        ItemStack releasedSword = new ItemStack(ModItems.LAEVATEIN);
        releasedSword.set(ModComponents.SHELL_STAGE, 0);
        releasedSword.set(ModComponents.SEAL_HITS, 0);
        releasedSword.set(ModComponents.MODE, WeaponMode.SWEEP);
        releasedSword.remove(ModComponents.THROWN);
        spawnAtLocation(releasedSword);
        spawnAtLocation(new ItemStack(ModItems.REVENGE_LEDGER));
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putBoolean("MatthiasLastStandUsed", lastStandUsed);
        tag.putInt("MatthiasLastStandRemaining", lastStandRemaining);
        tag.putInt("MatthiasCounterCharge", entityData.get(COUNTER_CHARGE));
        tag.putInt("MatthiasAttackSequence", nextAttack);
        tag.putInt("MatthiasSealedCycles", sealedCycles);
        tag.putInt("MatthiasHighKickCooldown", highKickCooldown);
        tag.putInt("MatthiasIdleDespawnTicks", idleDespawnTicks);
        if (chatCaller != null) {
            tag.putUUID("MatthiasChatCaller", chatCaller);
            tag.putInt("MatthiasChatAnger", chatAnger);
            tag.putBoolean("MatthiasChatFalling", chatFalling);
            tag.putBoolean("MatthiasChatLanded", chatLanded);
            tag.putInt("MatthiasChatFloorY", chatFloorY);
        }
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        lastStandUsed = tag.getBoolean("MatthiasLastStandUsed");
        lastStandRemaining = Mth.clamp(tag.getInt("MatthiasLastStandRemaining"), 0, LAST_STAND_TICKS);
        entityData.set(INVULNERABLE_TICKS, lastStandRemaining);
        entityData.set(COUNTER_CHARGE, Mth.clamp(tag.getInt("MatthiasCounterCharge"), 0, COUNTER_CHARGE_TICKS));
        nextAttack = tag.getInt("MatthiasAttackSequence");
        sealedCycles = tag.getInt("MatthiasSealedCycles");
        highKickCooldown = Mth.clamp(tag.getInt("MatthiasHighKickCooldown"), 0, HIGH_KICK_COOLDOWN_TICKS);
        idleDespawnTicks = Mth.clamp(tag.getInt("MatthiasIdleDespawnTicks"), 0, IDLE_DESPAWN_TICKS);
        if (tag.hasUUID("MatthiasChatCaller")) {
            chatCaller = tag.getUUID("MatthiasChatCaller");
            chatAnger = Mth.clamp(tag.getInt("MatthiasChatAnger"), 1, 3);
            chatFalling = tag.getBoolean("MatthiasChatFalling");
            chatLanded = tag.getBoolean("MatthiasChatLanded");
            chatFloorY = tag.getInt("MatthiasChatFloorY");
            if (chatFalling) {
                entityData.set(SWORD_ON_BACK, chatAnger < 3);
                anim("revenge_fall");
            }
        }
        hurtVoiceUntil = tickCount;
        // 空间/目标引用不能跨存档保存；重进后从自然 AI 状态安全恢复。
        move = Move.IDLE;
        counterSequence = false;
        highKickStriking = false;
        lockedTarget = null;
        thrownBlade = null;
        getMainHandItem().remove(ModComponents.THROWN);
        refreshArmorForHealth();
    }
}
