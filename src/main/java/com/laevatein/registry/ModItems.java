package com.laevatein.registry;

import com.laevatein.Laevatein;
import com.laevatein.WeaponMode;
import com.laevatein.item.LaevateinItem;
import com.laevatein.item.RevengeLedgerItem;
import com.laevatein.item.AggroDebuggerItem;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.SpawnEggItem;

public final class ModItems {
    private ModItems() {}

    public static final LaevateinItem LAEVATEIN = Registry.register(BuiltInRegistries.ITEM, Laevatein.id("laevatein"),
        new LaevateinItem(new Item.Properties()
            .stacksTo(1)
            .fireResistant()
            .rarity(Rarity.EPIC)
            .component(ModComponents.SHELL_STAGE, 3)
            .component(ModComponents.SEAL_HITS, 0)
            .component(ModComponents.MODE, WeaponMode.SWEEP)
            .component(ModComponents.SWEEP_SIDE, false)));

    /** 随身携带生效，不会在自动救命时消耗物品。 */
    public static final Item REVENGE_LEDGER = Registry.register(BuiltInRegistries.ITEM,
        Laevatein.id("revenge_ledger"),
        new RevengeLedgerItem(new Item.Properties().stacksTo(1).rarity(Rarity.EPIC)));

    /** Select any AI-controlled mob and then its target; works with hostile and passive mobs. */
    public static final Item AGGRO_DEBUGGER = Registry.register(BuiltInRegistries.ITEM,
        Laevatein.id("aggro_debugger"),
        new AggroDebuggerItem(new Item.Properties().stacksTo(1).rarity(Rarity.UNCOMMON)));

    public static final Item MATTHIAS_SPAWN_EGG = Registry.register(BuiltInRegistries.ITEM,
        Laevatein.id("matthias_spawn_egg"),
        new SpawnEggItem(ModEntities.MATTHIAS, 0x28202E, 0x9E53C6, new Item.Properties()));

    /** Spawn an otherwise identical Matthias, holding a fully released Laevatein from his first tick. */
    public static final Item MATTHIAS_RELEASED_SPAWN_EGG = Registry.register(BuiltInRegistries.ITEM,
        Laevatein.id("matthias_released_spawn_egg"),
        new SpawnEggItem(ModEntities.MATTHIAS_RELEASED, 0x3B2838, 0xE96721, new Item.Properties()));

    public static void init() {
        // Combat equipment: the weapon itself.
        ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.COMBAT).register(e ->
            e.accept(LAEVATEIN));

        // Survival utility and admin/debug utility do not belong in the combat tab.
        ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.TOOLS_AND_UTILITIES).register(e -> {
            e.accept(REVENGE_LEDGER);
            e.accept(AGGRO_DEBUGGER);
        });

        // Both Matthias variants are spawn eggs, so keep them with vanilla spawn eggs.
        ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.SPAWN_EGGS).register(e -> {
            e.accept(MATTHIAS_SPAWN_EGG);
            e.accept(MATTHIAS_RELEASED_SPAWN_EGG);
        });
    }
}
