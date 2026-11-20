package com.laevatein.combat;

import com.laevatein.entity.MatthiasEntity;
import com.laevatein.mixin.MobGoalSelectorAccessor;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.OcelotAttackGoal;
import net.minecraft.world.entity.ai.goal.RangedAttackGoal;
import net.minecraft.world.entity.ai.goal.RangedBowAttackGoal;
import net.minecraft.world.entity.ai.goal.RangedCrossbowAttackGoal;
import net.minecraft.world.entity.ai.goal.SwellGoal;
import net.minecraft.world.entity.monster.Monster;

/**
 * 临时服务器端调试仇恨，不写入实体 NBT。
 * 普通 Mob 优先保留自身战斗 AI，缺少战斗动作的被动生物使用通用追击/近战目标。
 */
public final class DebugAggroController {
    // value 只保存 UUID，不反向强引用 Mob；避免跨存档/断线堆积实体引用。
    private static final Map<Mob, UUID> FORCED_TARGETS = new WeakHashMap<>();
    private static final double MAX_DISTANCE_SQR = 96.0 * 96.0;

    private DebugAggroController() {}

    public static LivingEntity forcedTarget(Mob source) {
        UUID id = FORCED_TARGETS.get(source);
        if (id == null || !(source.level() instanceof ServerLevel level)) return null;
        Entity target = level.getEntity(id);
        return target instanceof LivingEntity living && living.isAlive() && living != source
            && living.level() == source.level() && source.distanceToSqr(living) <= MAX_DISTANCE_SQR
            ? living : null;
    }

    private static GoalSelector goals(Mob mob) {
        return ((MobGoalSelectorAccessor) mob).laevatein$getGoalSelector();
    }

    private static void installFallback(Mob mob) {
        if (mob instanceof MatthiasEntity) return; // 特殊 Boss 保留独有状态机、连招与动画。
        GoalSelector selector = goals(mob);
        if (selector.getAvailableGoals().stream().anyMatch(g -> g.getGoal() instanceof DebugForcedAttackGoal)) return;
        boolean hasNativeAttack = selector.getAvailableGoals().stream().anyMatch(g ->
            g.getGoal() instanceof MeleeAttackGoal || g.getGoal() instanceof RangedAttackGoal
                || g.getGoal() instanceof RangedBowAttackGoal || g.getGoal() instanceof RangedCrossbowAttackGoal
                || g.getGoal() instanceof OcelotAttackGoal || g.getGoal() instanceof SwellGoal);
        // 被动生物优先执行调试动作；原版敌对生物和已有攻击 Goal 的生物以原生攻击为先。
        selector.addGoal(hasNativeAttack || mob instanceof Monster ? 4 : 0, new DebugForcedAttackGoal(mob));
    }

    private static void removeFallback(Mob mob) {
        if (!(mob instanceof MatthiasEntity))
            goals(mob).removeAllGoals(goal -> goal instanceof DebugForcedAttackGoal);
    }

    private static void assign(Mob source, LivingEntity target) {
        FORCED_TARGETS.put(source, target.getUUID());
        if (source instanceof MatthiasEntity matthias) matthias.debugSetTarget(target);
        else {
            source.setTarget(target);
            installFallback(source);
        }
    }

    /** 原版双向仇恨：两个 Mob 对战时双方指向对方；玩家只会作为可攻击目标。 */
    public static boolean duel(Mob source, LivingEntity target) {
        if (!source.isAlive() || source.isNoAi() || !target.isAlive() || target == source
            || source.level() != target.level() || !(source.level() instanceof ServerLevel)) return false;
        // 保留马蒂亚斯对己方单位的保护；其他生物允许通过调试器指定不同阵营目标。
        if (source instanceof MatthiasEntity && source.isAlliedTo(target)) return false;
        if (target instanceof MatthiasEntity && target.isAlliedTo(source)) return false;
        // 先清掉旧链接，防止更换目标后旧敌人继续强制攻击。
        clear(source);
        if (target instanceof Mob other) clear(other);
        assign(source, target);
        if (target instanceof Mob other && !other.isNoAi()) assign(other, source);
        return true;
    }

    private static void clearSingle(Mob source) {
        UUID previous = FORCED_TARGETS.remove(source);
        if (previous == null) return;
        if (source instanceof MatthiasEntity matthias) matthias.debugClearTarget();
        else {
            if (source.getTarget() != null && previous.equals(source.getTarget().getUUID())) source.setTarget(null);
            source.getNavigation().stop();
            removeFallback(source);
        }
    }

    /** 解除目标的同时清除由本次调试对战产生的反向仇恨。 */
    public static void clear(Mob source) {
        UUID previous = FORCED_TARGETS.get(source);
        Mob opponent = null;
        if (previous != null && source.level() instanceof ServerLevel level
            && level.getEntity(previous) instanceof Mob mob && source.getUUID().equals(FORCED_TARGETS.get(mob)))
            opponent = mob;
        clearSingle(source);
        if (opponent != null && opponent != source) clearSingle(opponent);
        // 兼容尚未通过新控制器绑定的旧马蒂亚斯实例。
        if (previous == null && source instanceof MatthiasEntity matthias) matthias.debugClearTarget();
    }

    public static void init() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTickCount() % 5 != 0 || FORCED_TARGETS.isEmpty()) return;
            Iterator<Map.Entry<Mob, UUID>> iterator = FORCED_TARGETS.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<Mob, UUID> entry = iterator.next();
                Mob source = entry.getKey();
                LivingEntity target = source != null ? forcedTarget(source) : null;
                if (source == null || !source.isAlive() || source.isNoAi() || target == null) {
                    if (source != null) {
                        if (source instanceof MatthiasEntity matthias) matthias.debugClearTarget();
                        else {
                            if (source.getTarget() != null && entry.getValue().equals(source.getTarget().getUUID())) source.setTarget(null);
                            source.getNavigation().stop();
                            removeFallback(source);
                        }
                    }
                    iterator.remove();
                    continue;
                }
                if (source.getTarget() != target) {
                    if (source instanceof MatthiasEntity matthias) matthias.debugSetTarget(target);
                    else source.setTarget(target);
                }
            }
        });
    }
}
