package com.laevatein.item;

import com.laevatein.combat.DebugAggroController;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import com.laevatein.registry.ModComponents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * 任意 Mob 的调试对战工具：首击选攻击方、次击选目标，潜行右键清除。
 * 点击目标可为任意 LivingEntity（包括玩家），但不能控制玩家的输入。
 * 操作全部在服务端进行，不接受客户端伪造的实体 ID。
 */
public final class AggroDebuggerItem extends Item {
    // 创造模式的交互可能收到 ItemStack 副本；保留每位玩家的服务器选择。
    private static final Map<Player, UUID> SERVER_SELECTIONS = new WeakHashMap<>();

    public AggroDebuggerItem(Properties properties) { super(properties); }

    /** 先于村民交易、马匹骑乘、宠物互动等原版右键操作处理调试器。 */
    public static void init() {
        UseEntityCallback.EVENT.register((player, level, hand, entity, hitResult) -> {
            if (player.isSpectator() || !(entity instanceof LivingEntity living)) return InteractionResult.PASS;
            ItemStack stack = player.getItemInHand(hand);
            if (!(stack.getItem() instanceof AggroDebuggerItem debugger)) return InteractionResult.PASS;
            return debugger.interactLivingEntity(stack, player, living, hand);
        });
    }

    private static UUID selected(Player player, ItemStack stack) {
        UUID selected = SERVER_SELECTIONS.get(player);
        if (selected == null) selected = stack.get(ModComponents.DEBUG_SOURCE);
        // 旧版调试器曾保存 DEBUG_MATTHIAS；读取旧存档时平滑迁移。
        if (selected == null) selected = stack.get(ModComponents.DEBUG_MATTHIAS);
        return selected;
    }

    private static void deselect(Player player, ItemStack stack) {
        SERVER_SELECTIONS.remove(player);
        stack.remove(ModComponents.DEBUG_SOURCE);
        stack.remove(ModComponents.DEBUG_MATTHIAS);
    }

    private static void select(Player player, ItemStack stack, Mob mob) {
        SERVER_SELECTIONS.put(player, mob.getUUID());
        stack.set(ModComponents.DEBUG_SOURCE, mob.getUUID());
        stack.remove(ModComponents.DEBUG_MATTHIAS);
        player.displayClientMessage(Component.translatable("message.laevatein.aggro_selected", mob.getDisplayName())
            .withStyle(ChatFormatting.YELLOW), true);
    }

    @Override
    public InteractionResult interactLivingEntity(ItemStack stack, Player player, LivingEntity clicked, InteractionHand hand) {
        if (player.level().isClientSide()) return InteractionResult.SUCCESS;
        if (!(player.level() instanceof ServerLevel sl)) return InteractionResult.PASS;

        if (player.isShiftKeyDown()) {
            if (!(clicked instanceof Mob mob)) {
                player.displayClientMessage(Component.translatable("message.laevatein.aggro_not_mob")
                    .withStyle(ChatFormatting.RED), true);
                return InteractionResult.CONSUME;
            }
            DebugAggroController.clear(mob);
            // 移除旧选择，让下一次右键可重新指定任何生物为攻击方。
            deselect(player, stack);
            player.displayClientMessage(Component.translatable("message.laevatein.aggro_clear", mob.getDisplayName())
                .withStyle(ChatFormatting.GREEN), true);
            return InteractionResult.CONSUME;
        }

        UUID id = selected(player, stack);
        Mob attacker = id != null && sl.getEntity(id) instanceof Mob mob && mob.isAlive() ? mob : null;
        if (attacker == null) {
            deselect(player, stack);
            if (clicked instanceof Mob mob && mob.isAlive() && !mob.isNoAi()) {
                select(player, stack, mob);
            } else {
                player.displayClientMessage(Component.translatable("message.laevatein.aggro_not_mob")
                    .withStyle(ChatFormatting.RED), true);
            }
            return InteractionResult.CONSUME;
        }

        if (clicked == attacker) {
            select(player, stack, attacker);
            return InteractionResult.CONSUME;
        }
        // 保留选择后可连续为同一个攻击方更换目标。
        selectWithoutMessage(player, stack, attacker);
        if (!DebugAggroController.duel(attacker, clicked)) {
            player.displayClientMessage(Component.translatable("message.laevatein.aggro_invalid")
                .withStyle(ChatFormatting.RED), true);
            return InteractionResult.CONSUME;
        }
        player.displayClientMessage(Component.translatable("message.laevatein.aggro_duel",
            attacker.getDisplayName(), clicked.getDisplayName()).withStyle(ChatFormatting.GREEN), false);
        return InteractionResult.CONSUME;
    }

    private static void selectWithoutMessage(Player player, ItemStack stack, Mob attacker) {
        SERVER_SELECTIONS.put(player, attacker.getUUID());
        stack.set(ModComponents.DEBUG_SOURCE, attacker.getUUID());
        stack.remove(ModComponents.DEBUG_MATTHIAS);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> text, TooltipFlag flag) {
        text.add(Component.translatable("tooltip.laevatein.aggro_1").withStyle(ChatFormatting.GRAY));
        text.add(Component.translatable("tooltip.laevatein.aggro_2").withStyle(ChatFormatting.GRAY));
        if (stack.has(ModComponents.DEBUG_SOURCE) || stack.has(ModComponents.DEBUG_MATTHIAS))
            text.add(Component.translatable("tooltip.laevatein.aggro_bound").withStyle(ChatFormatting.YELLOW));
    }
}
