package com.laevatein;

import com.mojang.serialization.Codec;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.StringRepresentable;

/** 本体模式：右键按下立即切换。新枚举必须追加以兼容旧有序号。 */
public enum WeaponMode implements StringRepresentable {
    SWEEP("sweep"),
    PIERCE("pierce"),
    THROW("throw"),
    HEAVY_SLASH("heavy_slash");

    public static final Codec<WeaponMode> CODEC = StringRepresentable.fromEnum(WeaponMode::values);
    public static final StreamCodec<ByteBuf, WeaponMode> STREAM_CODEC =
        ByteBufCodecs.idMapper(i -> values()[i], WeaponMode::ordinal);

    private final String id;

    WeaponMode(String id) { this.id = id; }

    @Override public String getSerializedName() { return id; }

    public WeaponMode next() { return values()[(ordinal() + 1) % values().length]; }

    public String translationKey() { return "mode.laevatein." + id; }
}
