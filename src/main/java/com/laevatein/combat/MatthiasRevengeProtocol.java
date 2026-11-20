package com.laevatein.combat;

import com.laevatein.entity.MatthiasEntity;
import com.laevatein.registry.ModEntities;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.AABB;

public final class MatthiasRevengeProtocol {
    private static final int WARNING = 40;
    private static final int WORLD_COOLDOWN = 5 * 60 * 20;
    private static final int PERSONAL_COOLDOWN = 10 * 60 * 20;
    private static final int DROP_HEIGHT = 12;
    private static final Map<MinecraftServer, Call> PENDING = new WeakHashMap<>();
    private static final Map<MinecraftServer, MatthiasEntity> LOADED = new WeakHashMap<>();

    private static final class Call {
        final UUID caller;
        final ServerLevel level;
        final long arrives;
        int anger;
        Call(ServerPlayer player, long now, int anger) {
            caller = player.getUUID();
            level = player.serverLevel();
            arrives = now + WARNING;
            this.anger = anger;
        }
    }

    private static final class Ledger extends SavedData {
        static final Factory<Ledger> FACTORY = new Factory<>(Ledger::new, Ledger::read, null);
        UUID active;
        long cooledAt;
        final Map<UUID, Long> personal = new HashMap<>();

        static Ledger read(CompoundTag tag, HolderLookup.Provider registries) {
            Ledger state = new Ledger();
            if (tag.hasUUID("Active")) state.active = tag.getUUID("Active");
            state.cooledAt = tag.getLong("CooledAt");
            CompoundTag players = tag.getCompound("Personal");
            for (String key : players.getAllKeys()) {
                try { state.personal.put(UUID.fromString(key), players.getLong(key)); }
                catch (IllegalArgumentException ignored) { }
            }
            return state;
        }

        @Override
        public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
            if (active != null) tag.putUUID("Active", active);
            tag.putLong("CooledAt", cooledAt);
            CompoundTag players = new CompoundTag();
            personal.forEach((uuid, until) -> players.putLong(uuid.toString(), until));
            tag.put("Personal", players);
            return tag;
        }
    }

    private MatthiasRevengeProtocol() {}

    private static Ledger ledger(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(Ledger.FACTORY, "laevatein_revenge_calls");
    }

    public static void init() {
        ServerMessageEvents.CHAT_MESSAGE.register((message, player, channel) -> {
            // Fabric also routes some player commands into CHAT_MESSAGE. Only
            // vanilla public chat is eligible, never /tell, /teammsg or /me.
            if (channel.chatType().is(ChatType.CHAT)) onCall(player, message.signedContent());
        });
        ServerTickEvents.END_SERVER_TICK.register(MatthiasRevengeProtocol::tick);
        ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
            if (!(entity instanceof MatthiasEntity npc) || !npc.isChatSummoned()) return;
            MinecraftServer server = level.getServer();
            if (npc.getUUID().equals(ledger(server).active) && npc.isAlive()) LOADED.put(server, npc);
        });
        ServerEntityEvents.ENTITY_UNLOAD.register((entity, level) -> {
            MinecraftServer server = level.getServer();
            if (LOADED.get(server) == entity) LOADED.remove(server);
            if (entity instanceof MatthiasEntity npc && npc.isChatSummoned()
                && entity.getRemovalReason() == Entity.RemovalReason.DISCARDED) finish(server, npc, false);
        });
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (entity instanceof MatthiasEntity npc && npc.isChatSummoned()
                && npc.level() instanceof ServerLevel level) finish(level.getServer(), npc, true);
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            PENDING.remove(server);
            LOADED.remove(server);
        });
    }

    private static void onCall(ServerPlayer player, String text) {
        if (!player.isAlive() || player.isSpectator()) return;
        int matches = RevengeCalls.matchCount(text);
        if (matches == 0) return;
        ServerLevel level = player.serverLevel();
        MinecraftServer server = level.getServer();
        long now = server.overworld().getGameTime();

        Call queued = PENDING.get(server);
        if (queued != null) {
            if (!player.getUUID().equals(queued.caller) || level != queued.level) {
                tell(player, "busy");
                return;
            }
            int next = RevengeCalls.angerAfter(queued.anger, matches);
            signalAnger(player, queued.anger, next, false);
            queued.anger = next;
            return;
        }

        Ledger state = ledger(server);
        if (state.active != null) {
            MatthiasEntity npc = LOADED.get(server);
            if (npc == null || !npc.isAlive() || !npc.getUUID().equals(state.active)
                || npc.level() != level || npc.distanceToSqr(player) > 32 * 32
                || !npc.isChatCaller(player)) {
                tell(player, "busy");
                return;
            }
            int previous = npc.getChatAnger();
            int next = RevengeCalls.angerAfter(previous, matches);
            if (next > previous) npc.raiseChatAnger(next);
            signalAnger(player, previous, next, true);
            return;
        }
        if (level.getDifficulty() == Difficulty.PEACEFUL) {
            tell(player, "peaceful");
        } else if (now < state.cooledAt) {
            tell(player, "world_cooldown", minutes(state.cooledAt - now));
        } else if (now < state.personal.getOrDefault(player.getUUID(), 0L)) {
            tell(player, "personal_cooldown", minutes(state.personal.get(player.getUUID()) - now));
        } else if (findLanding(player) == null) {
            tell(player, "no_landing");
        } else {
            int anger = RevengeCalls.angerAfter(0, matches);
            PENDING.put(server, new Call(player, now, anger));
            announce(server, "marked", player.getDisplayName());
            level.playSound(null, player.blockPosition(), SoundEvents.ANVIL_LAND, SoundSource.HOSTILE, 0.48f, 0.57f);
            if (anger == RevengeCalls.FULL) announce(server, "prepared", player.getDisplayName());
        }
    }

    private static void tick(MinecraftServer server) {
        Call queued = PENDING.get(server);
        if (queued == null) return;
        long now = server.overworld().getGameTime();
        ServerPlayer player = server.getPlayerList().getPlayer(queued.caller);
        if (player == null || !player.isAlive() || player.isSpectator() || player.serverLevel() != queued.level) {
            PENDING.remove(server);
            return;
        }
        if (now < queued.arrives) return;
        PENDING.remove(server);
        ServerLevel level = queued.level;
        if (level.getDifficulty() == Difficulty.PEACEFUL) {
            tell(player, "peaceful");
            return;
        }
        BlockPos floor = findLanding(player);
        if (floor == null) {
            tell(player, "no_landing");
            return;
        }
        Ledger state = ledger(server);
        if (state.active != null) return;
        boolean released = queued.anger == RevengeCalls.FULL;
        MatthiasEntity npc = (released ? ModEntities.MATTHIAS_RELEASED : ModEntities.MATTHIAS).create(level);
        if (npc == null) return;
        double x = floor.getX() + 0.5, z = floor.getZ() + 0.5;
        float yaw = (float) Math.toDegrees(Math.atan2(player.getZ() - z, player.getX() - x)) - 90.0f;
        npc.moveTo(x, floor.getY() + 1.0 + DROP_HEIGHT, z, yaw, 0.0f);
        if (!level.noCollision(npc)) {
            tell(player, "no_landing");
            return;
        }
        npc.beginChatArrival(player, floor.getY(), queued.anger);
        if (!level.addFreshEntity(npc)) return;
        state.active = npc.getUUID();
        LOADED.put(server, npc);
        state.personal.values().removeIf(until -> until <= now);
        state.personal.put(player.getUUID(), now + PERSONAL_COOLDOWN);
        state.setDirty();
        announce(server, released ? "released_arrival" : "sealed_arrival", player.getDisplayName());
    }

    private static void finish(MinecraftServer server, MatthiasEntity npc, boolean defeated) {
        Ledger state = ledger(server);
        if (!npc.getUUID().equals(state.active)) return;
        LOADED.remove(server);
        state.active = null;
        if (defeated) {
            state.cooledAt = server.overworld().getGameTime() + WORLD_COOLDOWN;
            announce(server, "defeated");
        } else if (!npc.hasFinishedChatArrival()) {
            state.personal.remove(npc.getChatCaller());
        }
        state.setDirty();
    }

    private static BlockPos findLanding(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        double forward = Math.toRadians(player.getYRot() + 90.0f);
        for (int radius = 4; radius <= 7; radius++) {
            for (int step = 0; step < 12; step++) {
                double a = forward + ((step + 1) / 2) * (step % 2 == 0 ? -1 : 1) * Math.PI / 6.0;
                int x = (int) Math.floor(player.getX() + Math.cos(a) * radius);
                int z = (int) Math.floor(player.getZ() + Math.sin(a) * radius);
                for (int dy : new int[]{-1, 0, -2, 1, -3, -4, -5, -6, -7, -8}) {
                    BlockPos floor = new BlockPos(x, player.getBlockY() + dy, z);
                    if (floor.getY() < level.getMinBuildHeight()
                        || floor.getY() + DROP_HEIGHT + 2 >= level.getMaxBuildHeight()
                        || !level.getWorldBorder().isWithinBounds(floor) || !level.hasChunkAt(floor)) continue;
                    if (!level.getBlockState(floor).isFaceSturdy(level, floor, Direction.UP)
                        || !level.getFluidState(floor).isEmpty()) continue;
                    AABB column = new AABB(x + 0.19, floor.getY() + 1.001, z + 0.19,
                        x + 0.81, floor.getY() + DROP_HEIGHT + 2.81, z + 0.81);
                    if (!level.noCollision(column)) continue;
                    boolean dry = true;
                    for (int up = 1; up <= DROP_HEIGHT + 2; up++) {
                        if (!level.getFluidState(floor.above(up)).isEmpty()) { dry = false; break; }
                    }
                    if (dry) return floor;
                }
            }
        }
        return null;
    }

    private static long minutes(long ticks) { return (ticks + 1199) / 1200; }

    private static void signalAnger(ServerPlayer player, int before, int after, boolean arrived) {
        if (after <= before) return;
        announce(player.serverLevel().getServer(),
            after == RevengeCalls.FULL ? (arrived ? "release" : "prepared") : "anger",
            player.getDisplayName());
    }

    private static void tell(ServerPlayer player, String key, Object... args) {
        player.sendSystemMessage(Component.translatable("message.laevatein.revenge." + key, args)
            .withStyle(ChatFormatting.GRAY));
    }

    private static void announce(MinecraftServer server, String key, Object... args) {
        server.getPlayerList().broadcastSystemMessage(
            Component.translatable("message.laevatein.revenge." + key, args)
                .withStyle(ChatFormatting.LIGHT_PURPLE), false);
    }
}
