package com.laevatein.entity;

import com.laevatein.Laevatein;
import com.laevatein.LaevateinConfig;
import com.laevatein.combat.LaevateinFx;
import com.laevatein.registry.ModComponents;
import com.laevatein.registry.ModEntities;
import com.laevatein.registry.ModItems;
import java.util.UUID;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * 投出去的莱瓦汀。三种状态：
 * 飞行（原版箭的物理）→ 扎中生物（跟随并灼烧）或扎在方块上 → 召回（穿墙飞回主人）。
 * 不写进存档：重新进入世界后，物品上的 THROWN 标记会被 inventoryTick 清除，剑自动回到手上。
 */
public class ThrownLaevatein extends AbstractArrow {
    public static final ResourceKey<DamageType> BURNING_BLADE = ResourceKey.create(Registries.DAMAGE_TYPE, Laevatein.id("burning_blade"));

    private static final EntityDataAccessor<Integer> STUCK_ID = SynchedEntityData.defineId(ThrownLaevatein.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Boolean> RECALLING = SynchedEntityData.defineId(ThrownLaevatein.class, EntityDataSerializers.BOOLEAN);

    private int burnTicks;
    private int life;
    /** Only Matthias's projectile locks onto a living target; player throws never set this. */
    private @Nullable LivingEntity matthiasHomingTarget;

    public ThrownLaevatein(EntityType<? extends ThrownLaevatein> type, Level level) {
        super(type, level);
        this.pickup = Pickup.DISALLOWED;
    }

    public ThrownLaevatein(Level level, LivingEntity owner) {
        super(ModEntities.THROWN_LAEVATEIN, owner, level, renderStack(), null);
        this.pickup = Pickup.DISALLOWED;
    }

    /** 渲染和原版拾取逻辑用的物品：本体形态、不带投掷标记。 */
    public static ItemStack renderStack() {
        ItemStack s = new ItemStack(ModItems.LAEVATEIN);
        s.set(ModComponents.SHELL_STAGE, 0);
        return s;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(STUCK_ID, -1);
        builder.define(RECALLING, false);
    }

    @Override
    protected ItemStack getDefaultPickupItem() { return renderStack(); }

    @Override
    public boolean shouldBeSaved() { return false; }

    @Override
    public void playerTouch(Player player) {}

    @Override
    protected SoundEvent getDefaultHitGroundSoundEvent() { return SoundEvents.TRIDENT_HIT_GROUND; }

    public boolean isRecalling() { return entityData.get(RECALLING); }

    public @Nullable LivingEntity stuckTarget() {
        int id = entityData.get(STUCK_ID);
        return id >= 0 && level().getEntity(id) instanceof LivingEntity l ? l : null;
    }

    /** NPC-only homing: called just before the arrow enters the world. */
    public void setMatthiasHomingTarget(LivingEntity target) {
        if (getOwner() instanceof MatthiasEntity && target.isAlive() && target != getOwner()
            && target.level() == level()) {
            matthiasHomingTarget = target;
            setNoGravity(true);
        }
    }

    public void startRecall() {
        matthiasHomingTarget = null;
        entityData.set(STUCK_ID, -1);
        entityData.set(RECALLING, true);
        setNoGravity(true);
        setNoPhysics(true);
    }

    // ---------------- tick ----------------

    @Override
    public void tick() {
        if (isRecalling()) { tickRecall(); return; }
        LivingEntity stuck = stuckTarget();
        if (stuck != null) { tickStuck(stuck); return; }
        if (entityData.get(STUCK_ID) >= 0 && !level().isClientSide) { startRecall(); return; }   // 扎中的目标已经消失

        // Matthias's throw must track a moving enemy rather than firing a single
        // inaccurate snapshot. Keep normal block collision: never warp through walls.
        Vec3 from = position();
        if (!level().isClientSide && matthiasHomingTarget != null) {
            LivingEntity enemy = matthiasHomingTarget;
            if (!enemy.isAlive() || enemy.isRemoved() || enemy.level() != level() || inGround) {
                startRecall();
                return;
            }
            Vec3 goal = enemy.getBoundingBox().getCenter();
            Vec3 toward = goal.subtract(from);
            if (toward.lengthSqr() > 1.0e-8) {
                setDeltaMovement(toward.normalize().scale(LaevateinConfig.MATTHIAS_THROW_TRACK_SPEED));
                setNoGravity(true);
                hasImpulse = true;
            }
        }
        super.tick();
        if (!level().isClientSide && matthiasHomingTarget != null
            && !inGround && !isRecalling() && entityData.get(STUCK_ID) < 0) {
            // Extra swept hitbox avoids tunnelling at high projectile speed.
            // Check collidable blocks first, so this cannot hit through a wall.
            LivingEntity enemy = matthiasHomingTarget;
            Vec3 to = position();
            Vec3 contact = enemy.getBoundingBox()
                .inflate(LaevateinConfig.MATTHIAS_THROW_HIT_TOLERANCE)
                .clip(from, to).orElse(null);
            if (contact != null) {
                BlockHitResult barrier = level().clip(new ClipContext(from, contact,
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this));
                if (barrier.getType() == HitResult.Type.MISS)
                    onHitEntity(new EntityHitResult(enemy, contact));
            }
        }
        if (!level().isClientSide) {
            life++;
            Entity owner = getOwner();
            if (owner == null || !owner.isAlive() || owner.level() != level()) { discard(); return; }
            boolean grounded = this.inGround;
            if ((!grounded && life > LaevateinConfig.BLADE_MAX_FLIGHT) || life > LaevateinConfig.BLADE_MAX_LIFE) startRecall();
        }
    }

    /** 扎在生物身上：位置跟随目标，每 BURN_INTERVAL tick 造成一次火焰伤害。 */
    private void tickStuck(LivingEntity target) {
        setPos(target.getX(), target.getY() + target.getBbHeight() * 0.6, target.getZ());
        setDeltaMovement(Vec3.ZERO);
        if (!(level() instanceof ServerLevel sl)) return;

        if (!target.isAlive() || target.level() != level() || ++burnTicks > LaevateinConfig.BURN_MAX) {
            startRecall();
            return;
        }
        if (burnTicks % LaevateinConfig.BURN_INTERVAL == 0) {
            target.invulnerableTime = 0;
            target.hurt(burnSource(), LaevateinConfig.BURN_DAMAGE);
            target.setRemainingFireTicks(Math.max(target.getRemainingFireTicks(), 40));
            LaevateinFx.burn(sl, target);
        }
        if (burnTicks % 10 == 0) sl.playSound(null, target.blockPosition(), SoundEvents.FIRE_AMBIENT, SoundSource.PLAYERS, 0.8f, 1.2f);
    }

    /** 召回：穿过方块直线飞回主人，到达后清除主人身上那把剑的投掷标记。 */
    private void tickRecall() {
        Entity owner = getOwner();
        if (owner == null || !owner.isAlive() || owner.level() != level()) {
            if (!level().isClientSide) discard();
            return;
        }
        Vec3 to = owner.getEyePosition().subtract(position());
        if (!level().isClientSide && to.length() < 1.5) {
            if (owner instanceof ServerPlayer sp) returnTo(sp);
            else if (owner instanceof MatthiasEntity npc) npc.onBladeReturned(getUUID());
            discard();
            return;
        }
        Vec3 v = to.normalize().scale(Math.min(LaevateinConfig.RECALL_SPEED, to.length()));
        setDeltaMovement(v);
        setPos(getX() + v.x, getY() + v.y, getZ() + v.z);
        double h = v.horizontalDistance();
        setYRot((float) (Mth.atan2(v.x, v.z) * Mth.RAD_TO_DEG));
        setXRot((float) (Mth.atan2(v.y, h) * Mth.RAD_TO_DEG));
        yRotO = getYRot();
        xRotO = getXRot();
    }

    private void returnTo(ServerPlayer owner) {
        UUID me = getUUID();
        for (ItemStack s : owner.getInventory().items) {
            if (me.equals(s.get(ModComponents.THROWN))) s.remove(ModComponents.THROWN);
        }
        for (ItemStack s : owner.getInventory().offhand) {
            if (me.equals(s.get(ModComponents.THROWN))) s.remove(ModComponents.THROWN);
        }
        level().playSound(null, owner.blockPosition(), SoundEvents.TRIDENT_RETURN, SoundSource.PLAYERS, 1f, 1f);
    }

    // ---------------- 命中 ----------------

    @Override
    protected boolean canHitEntity(Entity e) {
        if (matthiasHomingTarget != null && e != matthiasHomingTarget) return false;
        return e != getOwner() && !isRecalling() && entityData.get(STUCK_ID) < 0 && super.canHitEntity(e);
    }

    /** 不调用 super：原版箭会造成伤害后自我删除或弹开，这里改成钉在目标身上。 */
    @Override
    protected void onHitEntity(EntityHitResult result) {
        if (!(result.getEntity() instanceof LivingEntity target) || level().isClientSide) return;
        entityData.set(STUCK_ID, target.getId());
        matthiasHomingTarget = null;
        setNoGravity(true);
        setDeltaMovement(Vec3.ZERO);
        burnTicks = 0;
        target.invulnerableTime = 0;
        target.hurt(burnSource(), LaevateinConfig.THROW_IMPACT_DAMAGE);
        target.igniteForSeconds(5);
        playSound(SoundEvents.TRIDENT_HIT, 1f, 0.8f);
    }

    private DamageSource burnSource() {
        return new DamageSource(level().registryAccess().registryOrThrow(Registries.DAMAGE_TYPE).getHolderOrThrow(BURNING_BLADE),
            this, getOwner());
    }
}
