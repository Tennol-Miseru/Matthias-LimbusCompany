package com.laevatein.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.laevatein.entity.MatthiasEntity;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.WeakHashMap;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.util.Mth;

/**
 * NPC-only animation adapter. The weapon is held in Matthias's LEFT arm; the
 * amputated right arm / empty sleeve NEVER receives a weapon animation track.
 *
 * PlayerModel parts do not form a hierarchical skeleton. Rotating `body`
 * without moving the arm/leg pivots pulls the joints apart. Here the chest
 * rotation is bounded and all four joint pivots follow the corresponding
 * rotation of the torso. No body translation or scaled body pose is applied.
 *
 * Animation blending is per-entity, rather than per renderer/model instance:
 * multiple Matthias entities share a single renderer and must not borrow one
 * another's last pose. Weak keys allow despawned entities to be collected.
 */
public final class MatthiasPlayerModel extends PlayerModel<MatthiasEntity> {
    private static final JsonObject ANIMATIONS =
        loadAnimations("/assets/laevatein/player_animations/laevatein_player.json");
    private static final JsonObject ARRIVAL_ANIMATIONS =
        loadAnimations("/assets/laevatein/npc_animations/matthias_arrival.json");
    // left weapon arm, physical left/right legs, torso; 3 Euler angles per part.
    private static final int ARM = 0, LEFT_LEG = 3, RIGHT_LEG = 6, BODY = 9;
    private static final int POSE_SIZE = 12;
    private static final float ENTER_BLEND_TICKS = 2.5f;
    private static final float EXIT_BLEND_TICKS = 4f;
    // Scripted attack legs should not fight the vanilla walking cycle.
    private static final float ATTACK_WALK_REMAINDER = 0.15f;
    // PlayerModel lacks child bones: deliberately keep torso almost upright.
    private static final float MAX_BODY_PITCH = (float) Math.toRadians(5);
    private static final float MAX_BODY_YAW = (float) Math.toRadians(10);
    private static final float MAX_BODY_ROLL = (float) Math.toRadians(4);
    private static final WeakHashMap<MatthiasEntity, BlendState> BLENDS = new WeakHashMap<>();

    private static final class BlendState {
        String action = "";
        int actionStart = Integer.MIN_VALUE;
        float transitionStart;
        boolean previousWasKick;
        final float[] previous = new float[POSE_SIZE];
        final float[] displayed = new float[POSE_SIZE];
    }

    private final ModelPart modelRoot;

    public MatthiasPlayerModel(ModelPart root, boolean slim) {
        super(root, slim);
        this.modelRoot = root;
    }

    @Override
    public void setupAnim(MatthiasEntity entity, float limbSwing, float limbSwingAmount,
                          float ageInTicks, float netHeadYaw, float headPitch) {
        // The same model parts render every Matthias: previous joint-pivot fixes
        // must not leak into the next frame or the next entity.
        modelRoot.getAllParts().forEach(ModelPart::resetPose);
        super.setupAnim(entity, limbSwing, limbSwingAmount, ageInTicks, netHeadYaw, headPitch);
        // Always start from vanilla's current pivots / idle/walk pose. Never carry
        // a ModelPart rotation or offset into the next entity's render call.
        String action = entity.animationName();
        if (action == null) action = "";
        float partial = Mth.clamp(ageInTicks - entity.tickCount, 0f, 1f);
        // A chase may outlast its 0.35-second clip. Never freeze its knees at
        // the final frame: cycle a *small*, continuous section of the clip
        // instead of restarting the full flying-kick pose every 7 ticks.
        float seconds = entity.animationSeconds(partial);
        if ("quad_kick_chase".equals(action)) {
            float cycle = 0.5f - 0.5f * Mth.cos(seconds * (float) (Math.PI * 2.0 / 0.72));
            seconds = 0.08f + 0.18f * cycle;
        }
        float[] goal = readPose(action, seconds, limbSwingAmount);
        BlendState blend = BLENDS.computeIfAbsent(entity, unused -> new BlendState());
        int start = entity.animationStartTick();
        if (!action.equals(blend.action) || start != blend.actionStart) {
            System.arraycopy(blend.displayed, 0, blend.previous, 0, POSE_SIZE);
            blend.previousWasKick = (blend.action.startsWith("quad_kick_")
                && !blend.action.equals("quad_kick_chase")) || blend.action.equals("dash_kick")
                || blend.action.startsWith("revenge_");
            blend.action = action;
            blend.actionStart = start;
            blend.transitionStart = ageInTicks;
        }
        float duration = action.isEmpty() ? EXIT_BLEND_TICKS : ENTER_BLEND_TICKS;
        float t = Mth.clamp((ageInTicks - blend.transitionStart) / duration, 0f, 1f);
        t = t * t * (3f - 2f * t); // smoothstep: zero velocity at both blend boundaries
        for (int i = 0; i < POSE_SIZE; i++) {
            goal[i] = Mth.lerp(t, blend.previous[i], goal[i]);
            blend.displayed[i] = goal[i];
        }

        // With scripted airborne kicks, vanilla's stride must fade down, or
        // its independently animated knees add 40+ degrees to a kick keyframe.
        // The factor is blended on entry and exit to avoid another pose jump.
        boolean scriptedKick = (action.startsWith("quad_kick_") && !action.equals("quad_kick_chase"))
            || action.equals("dash_kick") || action.startsWith("revenge_");
        float kickWeight = scriptedKick ? t : (blend.previousWasKick ? 1f - t : 0f);
        float walkFactor = 1f - (1f - ATTACK_WALK_REMAINDER) * kickWeight;
        leftLeg.xRot *= walkFactor;
        rightLeg.xRot *= walkFactor;
        // The amputated sleeve is NEVER a weapon arm. Subtle balance motion is
        // safe; it does not translate the shoulder or imitate a right-hand grip.
        rightArm.xRot += (float) Math.toRadians(1.0) * Mth.sin(ageInTicks * 0.13f);
        rightArm.zRot += (float) Math.toRadians(0.7) * Mth.sin(ageInTicks * 0.11f + 0.6f);

        leftArm.xRot += goal[ARM];
        leftArm.yRot += goal[ARM + 1];
        leftArm.zRot += goal[ARM + 2];
        leftLeg.xRot += goal[LEFT_LEG];
        leftLeg.yRot += goal[LEFT_LEG + 1];
        leftLeg.zRot += goal[LEFT_LEG + 2];
        rightLeg.xRot += goal[RIGHT_LEG];
        rightLeg.yRot += goal[RIGHT_LEG + 1];
        rightLeg.zRot += goal[RIGHT_LEG + 2];

        // Measure old torso rotation BEFORE applying its additive animation.
        // Children must move by R_new(joint) - R_old(joint), not by an arbitrary
        // offset. This works even if vanilla has already rotated the body.
        float baseX = body.xRot, baseY = body.yRot, baseZ = body.zRot;
        float dx = goal[BODY];
        float dy = goal[BODY + 1];
        float dz = goal[BODY + 2];
        body.xRot += dx;
        body.yRot += dy;
        body.zRot += dz;
        followTorso(leftArm, baseX, baseY, baseZ);
        followTorso(rightArm, baseX, baseY, baseZ);
        followTorso(leftLeg, baseX, baseY, baseZ);
        followTorso(rightLeg, baseX, baseY, baseZ);

        // Surface layers must share both pivot and rotation with their base part.
        rightSleeve.copyFrom(rightArm);
        leftSleeve.copyFrom(leftArm);
        rightPants.copyFrom(rightLeg);
        leftPants.copyFrom(leftLeg);
        jacket.copyFrom(body);
    }

    /** Keep an arm's shoulder or a leg's hip on the rotated torso surface. */
    private void followTorso(ModelPart limb, float oldX, float oldY, float oldZ) {
        float x = limb.x - body.x, y = limb.y - body.y, z = limb.z - body.z;
        float[] before = rotated(x, y, z, oldX, oldY, oldZ);
        float[] after = rotated(x, y, z, body.xRot, body.yRot, body.zRot);
        limb.x += after[0] - before[0];
        limb.y += after[1] - before[1];
        limb.z += after[2] - before[2];
    }

    /** ModelPart translateAndRotate uses Z -> Y -> X, applied to points X first. */
    private static float[] rotated(float x, float y, float z, float pitch, float yaw, float roll) {
        float cx = Mth.cos(pitch), sx = Mth.sin(pitch);
        float cy = Mth.cos(yaw), sy = Mth.sin(yaw);
        float cz = Mth.cos(roll), sz = Mth.sin(roll);
        float y1 = y * cx - z * sx, z1 = y * sx + z * cx;
        float x2 = x * cy + z1 * sy, z2 = -x * sy + z1 * cy;
        return new float[] {x2 * cz - y1 * sz, x2 * sz + y1 * cz, z2};
    }

    private static float[] readPose(String action, float seconds, float limbSwingAmount) {
        float[] result = new float[POSE_SIZE];
        JsonObject clips = action.startsWith("revenge_") ? ARRIVAL_ANIMATIONS : ANIMATIONS;
        if (!clips.has(action)) return result;
        JsonObject animation = clips.getAsJsonObject(action);
        if (animation == null || !animation.has("animation_length")) return result;
        JsonObject bones = animation.getAsJsonObject("bones");
        if (bones == null) return result;
        float time = Mth.clamp(seconds, 0f, animation.get("animation_length").getAsFloat());
        // Only Matthias's WEAPON ARM is mirrored to the physical left arm.
        // His reinforced RIGHT LEG is always the striking leg, just like the
        // player's. Mirroring both legs used to turn right-leg authored kicks
        // into left-leg kicks on Matthias. Keep the original (non-mirrored)
        // leg track and lateral orientation for every kick, including the
        // thrown-sword dash_kick and the chase stance.
        boolean arrival = action.startsWith("revenge_");
        if (arrival) sample(bones, "left_arm", time, result, ARM, false);
        else sample(bones, "right_arm", time, result, ARM, true);
        boolean kick = action.equals("dash_kick") || action.startsWith("quad_kick_");
        if (kick || arrival) {
            sample(bones, "left_leg", time, result, LEFT_LEG, false);
            sample(bones, "right_leg", time, result, RIGHT_LEG, false);
        } else {
            // Preserve existing left/right NPC mirroring for other attacks.
            sample(bones, "right_leg", time, result, LEFT_LEG, true);
            sample(bones, "left_leg", time, result, RIGHT_LEG, true);
        }
        if (arrival) sample(bones, "torso", time, result, BODY, false);
        else sample(bones, "torso", time, result, BODY, true);
        // Unkeyed legs otherwise stay unnaturally motionless during standing
        // slashes. A small foot brace gives the attack weight without moving
        // hip anchors, teleporting the root or tilting the chest.
        if (!bones.has("left_leg") && !bones.has("right_leg")) {
            float phase = animation.get("animation_length").getAsFloat() <= 0f ? 0f
                : Mth.clamp(time / animation.get("animation_length").getAsFloat(), 0f, 1f);
            // Standing feet brace for the blow, but don't fight pathfinding
            // locomotion if the NPC is still moving during an attack.
            float brace = Mth.sin((float) Math.PI * phase)
                * (1f - Mth.clamp(limbSwingAmount * 1.8f, 0f, 0.85f));
            if (action.equals("pierce_rush") || action.equals("chop")) {
                result[LEFT_LEG] -= (float) Math.toRadians(7) * brace;
                result[RIGHT_LEG] += (float) Math.toRadians(4) * brace;
            } else if (action.startsWith("heavy_combo_") || action.startsWith("sweep_")
                || action.equals("seal_slash") || action.equals("seal_break_twohands")) {
                result[LEFT_LEG] -= (float) Math.toRadians(4) * brace;
                result[RIGHT_LEG] += (float) Math.toRadians(3) * brace;
            }
        }
        // The source contains 30-38-degree torso twists. A hard clamp
        // creates a long frozen plateau at its edge. Use a continuous bounded
        // response instead, so the waist still moves throughout the strike.
        result[BODY] = softBound(result[BODY], MAX_BODY_PITCH);
        result[BODY + 1] = softBound(result[BODY + 1], MAX_BODY_YAW);
        result[BODY + 2] = softBound(result[BODY + 2], MAX_BODY_ROLL);
        return result;
    }

    /** Smoothly approaches the limit without ever reaching it or creating a flat spot. */
    private static float softBound(float radians, float limit) {
        return limit * radians / (Math.abs(radians) + limit);
    }

    private static void sample(JsonObject bones, String name, float time, float[] out,
                               int index, boolean mirror) {
        if (!bones.has(name)) return;
        JsonObject bone = bones.getAsJsonObject(name);
        if (bone == null || !bone.has("rotation")) return;
        JsonObject keys = bone.getAsJsonObject("rotation");
        float[] prior = null, after = null;
        float priorTime = Float.NEGATIVE_INFINITY, afterTime = Float.POSITIVE_INFINITY;
        JsonElement followingFrame = null;
        for (String key : keys.keySet()) {
            float at;
            try { at = Float.parseFloat(key); } catch (NumberFormatException ex) { continue; }
            float[] v = vector(keys.get(key));
            if (at <= time && at >= priorTime) { prior = v; priorTime = at; }
            if (at > time && at < afterTime) { after = v; afterTime = at; followingFrame = keys.get(key); }
        }
        if (prior == null && after != null) {
            // Before the first authored key, ease from the neutral rotation;
            // copying that future pose backward creates a one-frame snap.
            prior = new float[] {0f, 0f, 0f};
            priorTime = 0f;
        }
        if (prior == null) return;
        if (after != null && afterTime > priorTime) {
            float t = Mth.clamp((time - priorTime) / (afterTime - priorTime), 0f, 1f);
            if (followingFrame != null && followingFrame.isJsonObject()
                && followingFrame.getAsJsonObject().has("easing")) {
                t = ease(t, followingFrame.getAsJsonObject().get("easing").getAsString());
            } else {
                // The source file's linear interpolation can abruptly change
                // angular velocity at adjacent keyframes. Smooth the segment
                // but keep every authored key pose and its impact time intact.
                t = t * t * (3f - 2f * t);
            }
            for (int i = 0; i < 3; i++) prior[i] = Mth.lerp(t, prior[i], after[i]);
        }
        // Mirror only the left-hand weapon/torso tracks. Kick legs keep the
        // source's true right/left sides; other moves retain legacy mirroring.
        float sign = mirror ? -1f : 1f;
        out[index] = (float) Math.toRadians(prior[0]);
        out[index + 1] = (float) Math.toRadians(sign * prior[1]);
        out[index + 2] = (float) Math.toRadians(sign * prior[2]);
    }

    private static float ease(float t, String easing) {
        return switch (easing) {
            case "easeInQuad" -> t * t;
            case "easeOutQuad" -> 1f - (1f - t) * (1f - t);
            case "easeInOutQuad" -> t < .5f ? 2f * t * t : 1f - (float) Math.pow(-2f * t + 2f, 2) / 2f;
            case "easeInCubic" -> t * t * t;
            case "easeOutCubic" -> 1f - (float) Math.pow(1f - t, 3);
            case "easeInOutCubic" -> t < .5f ? 4f * t * t * t : 1f - (float) Math.pow(-2f * t + 2f, 3) / 2f;
            case "easeInOutSine" -> -(float) Math.cos(Math.PI * t) / 2f + .5f;
            case "easeOutSine" -> (float) Math.sin(Math.PI * t / 2f);
            case "easeInSine" -> 1f - (float) Math.cos(Math.PI * t / 2f);
            default -> t;
        };
    }

    private static float[] vector(JsonElement frame) {
        JsonElement v = frame.isJsonObject() ? frame.getAsJsonObject().get("vector") : frame;
        float[] ret = {0, 0, 0};
        if (v != null && v.isJsonArray())
            for (int i = 0; i < Math.min(3, v.getAsJsonArray().size()); i++)
                ret[i] = v.getAsJsonArray().get(i).getAsFloat();
        return ret;
    }

    private static JsonObject loadAnimations(String path) {
        try (InputStream stream = MatthiasPlayerModel.class.getResourceAsStream(path)) {
            if (stream == null) return new JsonObject();
            JsonObject root = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
            return root.getAsJsonObject("animations");
        } catch (Exception ex) {
            return new JsonObject();
        }
    }
}
