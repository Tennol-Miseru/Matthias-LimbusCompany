package com.laevatein.client;

import com.laevatein.registry.ModItems;
import net.minecraft.client.player.AbstractClientPlayer;

/**
 * Carry state is determined by the currently equipped item, NOT a combat-animation timer.
 * Selecting the sword keeps it visible in the hand even while standing still.
 * Switching to a different hotbar slot sheathes the sword on the back if it is in inventory.
 */
public final class ClientSwordCarry {
    private ClientSwordCarry() {}

    public static boolean isHandActive(AbstractClientPlayer player) {
        // Offhand is also genuinely equipped, not an item merely present in inventory.
        return player.getMainHandItem().is(ModItems.LAEVATEIN)
            || player.getOffhandItem().is(ModItems.LAEVATEIN);
    }
}
