package com.laevatein.combat;

/** Mixin 到玩家身上的背包保命道具冷却状态，写入玩家 NBT。 */
public interface LedgerCooldownAccess {
    int laevatein$getLedgerCooldownTicks();
    void laevatein$setLedgerCooldownTicks(int ticks);
}
