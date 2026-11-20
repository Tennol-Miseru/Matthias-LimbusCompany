package com.laevatein.registry;

import com.laevatein.Laevatein;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;

/** Custom sound events: one authoritative registrar shared by players and Matthias. */
public final class ModSounds {
    private ModSounds() {}

    private static SoundEvent register(String id) {
        return Registry.register(BuiltInRegistries.SOUND_EVENT, Laevatein.id(id),
            SoundEvent.createVariableRangeEvent(Laevatein.id(id)));
    }

    // The supplied audio archive has no outer-shell ordinary hit clip: for now this event
    // reuses wrap impacts (NOT the distinct outer-shell BREAK sound). Easy to swap later.
    public static final SoundEvent SEAL_HIT_OUTER = register("seal_hit_outer");
    public static final SoundEvent SEAL_HIT_PAPER = register("seal_hit_paper");
    public static final SoundEvent SEAL_HIT_MIDDLE = register("seal_hit_middle");
    public static final SoundEvent SEAL_HIT_INNER = register("seal_hit_inner");
    public static final SoundEvent SHELL_OPEN_OUTER = register("shell_open_outer");
    public static final SoundEvent SHELL_OPEN_FINAL = register("shell_open_final");
    public static final SoundEvent SWEEP = register("sweep");
    public static final SoundEvent BLADE_SLASH = register("blade_slash");
    public static final SoundEvent FIRE_PIERCE = register("fire_pierce");
    public static final SoundEvent HEAVY_FINISH = register("heavy_finish");
    public static final SoundEvent KICK_1 = register("kick_1");
    public static final SoundEvent KICK_2 = register("kick_2");
    public static final SoundEvent KICK_3 = register("kick_3");
    public static final SoundEvent KICK_4 = register("kick_4");
    public static final SoundEvent DOWN_STOMP = register("down_stomp");
    public static final SoundEvent THROW_DASH_KICK = register("throw_dash_kick");
    public static final SoundEvent MATTHIAS_COUNTER = register("matthias_counter");
    public static final SoundEvent MATTHIAS_HURT = register("matthias_hurt");
    public static final SoundEvent MATTHIAS_EFFORT = register("matthias_effort");
    public static final SoundEvent MATTHIAS_UNSHELL_1 = register("matthias_unshell_1");
    public static final SoundEvent MATTHIAS_UNSHELL_2 = register("matthias_unshell_2");
    public static final SoundEvent MATTHIAS_UNSHELL_3 = register("matthias_unshell_3");
    public static final SoundEvent MATTHIAS_KICK_VOICE_1 = register("matthias_kick_voice_1");
    public static final SoundEvent MATTHIAS_KICK_VOICE_2 = register("matthias_kick_voice_2");
    public static final SoundEvent MATTHIAS_KICK_VOICE_3 = register("matthias_kick_voice_3");
    public static final SoundEvent MATTHIAS_DASH_VOICE = register("matthias_dash_voice");
    public static final SoundEvent MATTHIAS_PIERCE_START = register("matthias_pierce_start");
    public static final SoundEvent MATTHIAS_PIERCE_END = register("matthias_pierce_end");

    public static void init() {}

    public static void play(ServerLevel level, LivingEntity user, SoundEvent event, SoundSource category, float volume) {
        level.playSound(null, user.blockPosition(), event, category, volume, 1f);
    }

    /** Every wrapper stage has a custom sound, including the first/outer shell. */
    public static void sealHit(ServerLevel level, LivingEntity user, int stage, boolean heavy, SoundSource category) {
        if (stage == 3) play(level, user, SEAL_HIT_OUTER, category, 0.95f);
        else if (stage == 2) play(level, user, SEAL_HIT_MIDDLE, category, 0.95f);
        else if (stage == 1) play(level, user, heavy ? SEAL_HIT_INNER : SEAL_HIT_PAPER, category, 0.95f);
    }

    /** First outer shell and final core reveal have dedicated sounds in the supplied archive. */
    public static void breakShell(ServerLevel level, LivingEntity user, int oldStage, SoundSource category) {
        if (oldStage == 3) play(level, user, SHELL_OPEN_OUTER, category, 1.0f);
        else if (oldStage == 1) play(level, user, SHELL_OPEN_FINAL, category, 1.1f);
        // Stage 2→1 has no unambiguous opening clip in the supplied archive.
    }

    public static SoundEvent kickSound(int phase) {
        return switch (phase) {
            case 1 -> KICK_1;
            case 2 -> KICK_2;
            case 3 -> KICK_3;
            case 4 -> KICK_4;
            default -> throw new IllegalArgumentException("Kick phase " + phase);
        };
    }

    public static SoundEvent matthiasKickVoice(int phase) {
        return switch (phase) {
            case 1 -> MATTHIAS_KICK_VOICE_1;
            case 2 -> MATTHIAS_KICK_VOICE_2;
            case 3 -> MATTHIAS_KICK_VOICE_3;
            default -> MATTHIAS_EFFORT;
        };
    }

    public static SoundEvent matthiasShellVoice(int oldStage) {
        return switch (oldStage) {
            case 3 -> MATTHIAS_UNSHELL_1;
            case 2 -> MATTHIAS_UNSHELL_2;
            case 1 -> MATTHIAS_UNSHELL_3;
            default -> throw new IllegalArgumentException("Shell stage " + oldStage);
        };
    }
}
