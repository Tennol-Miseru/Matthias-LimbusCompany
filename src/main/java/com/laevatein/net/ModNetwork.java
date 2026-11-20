package com.laevatein.net;

import com.laevatein.Laevatein;
import com.laevatein.combat.LaevateinCombat;
import io.netty.buffer.ByteBuf;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

public final class ModNetwork {
    private ModNetwork() {}

    /** 客户端 → 服务端：手持莱瓦汀按了一下左键。目标由服务端自己判定。 */
    public record AttackPayload() implements CustomPacketPayload {
        public static final AttackPayload INSTANCE = new AttackPayload();
        public static final Type<AttackPayload> TYPE = new Type<>(Laevatein.id("attack"));
        public static final StreamCodec<RegistryFriendlyByteBuf, AttackPayload> CODEC = StreamCodec.unit(INSTANCE);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** 客户端 → 服务端：左键按下或松开状态，只在状态变化时发送。
     * 服务端自行按攻击冷却连发；封印态和投掷态不会自动重复。
     */
    public record AttackHeldPayload(boolean held) implements CustomPacketPayload {
        public static final Type<AttackHeldPayload> TYPE = new Type<>(Laevatein.id("attack_held"));
        public static final StreamCodec<ByteBuf, AttackHeldPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.BOOL, AttackHeldPayload::held, AttackHeldPayload::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** 客户端 → 服务端：右键按下沿。不给客户端选择形态，始终由服务端递增，防注入。 */
    public record ModePayload() implements CustomPacketPayload {
        public static final ModePayload INSTANCE = new ModePayload();
        public static final Type<ModePayload> TYPE = new Type<>(Laevatein.id("next_mode"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ModePayload> CODEC = StreamCodec.unit(INSTANCE);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** 客户端 → 服务端：N 键要求将莱瓦汀重新装入三层包装。 */
    public record ReshellPayload() implements CustomPacketPayload {
        public static final ReshellPayload INSTANCE = new ReshellPayload();
        public static final Type<ReshellPayload> TYPE = new Type<>(Laevatein.id("reshell"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ReshellPayload> CODEC = StreamCodec.unit(INSTANCE);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** 客户端 → 服务端：每次重新按下 V 请求一脚；服务端决定段数、追踪与受击力度。 */
    public record QuadKickPayload() implements CustomPacketPayload {
        public static final QuadKickPayload INSTANCE = new QuadKickPayload();
        public static final Type<QuadKickPayload> TYPE = new Type<>(Laevatein.id("quad_kick"));
        public static final StreamCodec<RegistryFriendlyByteBuf, QuadKickPayload> CODEC = StreamCodec.unit(INSTANCE);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** 服务端 → 客户端：玩家级高温计量与背包激活状态。 */
    public record HeatSyncPayload(int ticks, boolean active) implements CustomPacketPayload {
        public static final Type<HeatSyncPayload> TYPE = new Type<>(Laevatein.id("heat_sync"));
        public static final StreamCodec<ByteBuf, HeatSyncPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, HeatSyncPayload::ticks,
            ByteBufCodecs.BOOL, HeatSyncPayload::active,
            HeatSyncPayload::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** 服务端 → 客户端：让某个玩家播放（或淡出）一段手臂动作。 */
    public record PlayerAnimPayload(int entityId, String anim, boolean stop) implements CustomPacketPayload {
        public static final Type<PlayerAnimPayload> TYPE = new Type<>(Laevatein.id("player_anim"));
        public static final StreamCodec<ByteBuf, PlayerAnimPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, PlayerAnimPayload::entityId,
            ByteBufCodecs.STRING_UTF8, PlayerAnimPayload::anim,
            ByteBufCodecs.BOOL, PlayerAnimPayload::stop,
            PlayerAnimPayload::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public static void init() {
        PayloadTypeRegistry.playC2S().register(AttackPayload.TYPE, AttackPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(AttackHeldPayload.TYPE, AttackHeldPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(ModePayload.TYPE, ModePayload.CODEC);
        PayloadTypeRegistry.playC2S().register(ReshellPayload.TYPE, ReshellPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(QuadKickPayload.TYPE, QuadKickPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(PlayerAnimPayload.TYPE, PlayerAnimPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(HeatSyncPayload.TYPE, HeatSyncPayload.CODEC);
        // Fabric 1.21 的接收回调已经在服务端主线程上执行
        ServerPlayNetworking.registerGlobalReceiver(AttackPayload.TYPE, (payload, ctx) -> LaevateinCombat.onAttack(ctx.player()));
        ServerPlayNetworking.registerGlobalReceiver(AttackHeldPayload.TYPE,
            (payload, ctx) -> LaevateinCombat.setAttackHeld(ctx.player(), payload.held()));
        ServerPlayNetworking.registerGlobalReceiver(ModePayload.TYPE, (payload, ctx) -> LaevateinCombat.cycleMode(ctx.player()));
        ServerPlayNetworking.registerGlobalReceiver(ReshellPayload.TYPE, (payload, ctx) -> LaevateinCombat.requestReshell(ctx.player()));
        ServerPlayNetworking.registerGlobalReceiver(QuadKickPayload.TYPE, (payload, ctx) -> LaevateinCombat.requestQuadKick(ctx.player()));
    }

    /** 玩家热量仅发给自己；实际伤害由服务器判定。 */
    public static void sendHeat(ServerPlayer player, int ticks, boolean active) {
        ServerPlayNetworking.send(player, new HeatSyncPayload(ticks, active));
    }

    /** 发给追踪这个玩家的所有客户端，包括玩家自己。 */
    public static void playAnim(ServerPlayer player, String anim) {
        broadcast(player, new PlayerAnimPayload(player.getId(), anim, false));
    }

    public static void stopAnim(ServerPlayer player) {
        broadcast(player, new PlayerAnimPayload(player.getId(), "", true));
    }

    private static void broadcast(ServerPlayer player, PlayerAnimPayload payload) {
        ServerPlayNetworking.send(player, payload);
        for (ServerPlayer other : PlayerLookup.tracking(player)) {
            if (other != player) ServerPlayNetworking.send(other, payload);
        }
    }
}
