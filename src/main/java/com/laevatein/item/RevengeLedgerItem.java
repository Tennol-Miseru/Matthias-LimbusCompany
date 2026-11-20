package com.laevatein.item;

import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

/** 不需要拿在手上；服务端在玩家受到致命伤害时检查背包是否携带本物品。 */
public final class RevengeLedgerItem extends Item {
    public RevengeLedgerItem(Properties properties) { super(properties); }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("tooltip.laevatein.revenge_ledger_1").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tooltip.laevatein.revenge_ledger_2").withStyle(ChatFormatting.LIGHT_PURPLE));
        tooltip.add(Component.translatable("tooltip.laevatein.revenge_ledger_3").withStyle(ChatFormatting.DARK_GRAY));
        super.appendHoverText(stack, context, tooltip, flag);
    }
}
