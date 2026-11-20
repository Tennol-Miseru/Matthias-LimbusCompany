package com.laevatein;

import com.laevatein.combat.DebugAggroController;
import com.laevatein.combat.LaevateinCombat;
import com.laevatein.item.AggroDebuggerItem;
import com.laevatein.combat.LaevateinHeat;
import com.laevatein.combat.MatthiasRevengeProtocol;
import com.laevatein.combat.RevengeLedgerMechanics;
import com.laevatein.net.ModNetwork;
import com.laevatein.registry.ModComponents;
import com.laevatein.registry.ModEntities;
import com.laevatein.registry.ModEffects;
import com.laevatein.registry.ModItems;
import com.laevatein.registry.ModSounds;
import net.fabricmc.api.ModInitializer;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Laevatein implements ModInitializer {
    public static final String MOD_ID = "laevatein";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }

    @Override
    public void onInitialize() {
        ModComponents.init();   // 组件必须在物品之前注册，物品的默认组件会引用它们
        ModSounds.init();
        ModEffects.init();     // 声音事件在世界/实体开始播放音效之前注册
        ModEntities.init();
        ModItems.init();
        AggroDebuggerItem.init();
        ModNetwork.init();
        LaevateinCombat.init();
        RevengeLedgerMechanics.init();
        LaevateinHeat.init();
        DebugAggroController.init();
        MatthiasRevengeProtocol.init();
    }
}
