package com.laevatein.combat;

/** 玩家级莱瓦汀热量，保存至玩家 NBT，无法通过换槽位或多把武器绕过。 */
public interface PlayerHeatAccess {
    int laevatein$getHeatTicks();
    void laevatein$setHeatTicks(int ticks);
}
