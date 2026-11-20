package com.laevatein.registry;

import com.laevatein.Laevatein;
import com.laevatein.WeaponMode;
import com.mojang.serialization.Codec;
import java.util.UUID;
import java.util.function.UnaryOperator;
import net.minecraft.core.Registry;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.util.ExtraCodecs;

/** 武器状态全部存在物品堆上，由原版负责存档和同步。 */
public final class ModComponents {
    private ModComponents() {}

    /** 剩余包装层数：3 = 三层全在，0 = 本体。 */
    public static final DataComponentType<Integer> SHELL_STAGE = reg("shell_stage",
        b -> b.persistent(ExtraCodecs.intRange(0, 3)).networkSynchronized(ByteBufCodecs.VAR_INT));
    /** 封印态下当前这一层已命中的次数。 */
    public static final DataComponentType<Integer> SEAL_HITS = reg("seal_hits",
        b -> b.persistent(ExtraCodecs.intRange(0, 64)).networkSynchronized(ByteBufCodecs.VAR_INT));
    public static final DataComponentType<WeaponMode> MODE = reg("mode",
        b -> b.persistent(WeaponMode.CODEC).networkSynchronized(WeaponMode.STREAM_CODEC));
    /** 下一次横扫的方向：false = 右挥，true = 左挥。 */
    public static final DataComponentType<Boolean> SWEEP_SIDE = reg("sweep_side",
        b -> b.persistent(Codec.BOOL).networkSynchronized(ByteBufCodecs.BOOL));
    /** 已投出的剑实体。有值时手上的剑身隐藏。 */
    public static final DataComponentType<UUID> THROWN = reg("thrown",
        b -> b.persistent(UUIDUtil.CODEC).networkSynchronized(UUIDUtil.STREAM_CODEC));

    /** Legacy pre-1.0.34 selection component; retained to load older debugger stacks. */
    public static final DataComponentType<UUID> DEBUG_MATTHIAS = reg("debug_matthias",
        b -> b.persistent(UUIDUtil.CODEC).networkSynchronized(UUIDUtil.STREAM_CODEC));

    /** Generic selected attacking Mob. Stored as UUID; entity authority remains server-side. */
    public static final DataComponentType<UUID> DEBUG_SOURCE = reg("debug_source",
        b -> b.persistent(UUIDUtil.CODEC).networkSynchronized(UUIDUtil.STREAM_CODEC));

    private static <T> DataComponentType<T> reg(String id, UnaryOperator<DataComponentType.Builder<T>> op) {
        return Registry.register(BuiltInRegistries.DATA_COMPONENT_TYPE, Laevatein.id(id),
            op.apply(DataComponentType.builder()).build());
    }

    public static void init() {}
}
