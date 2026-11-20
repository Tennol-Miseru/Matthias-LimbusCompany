package com.laevatein.client;

import com.laevatein.entity.MatthiasEntity;
import com.laevatein.registry.ModComponents;
import com.laevatein.registry.ModItems;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix3f;
import org.joml.Matrix4fc;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Animation-aware weapon-only orientation for the horizontal sweeps.
 *
 * PAL animates the arm and moves the hand through its arc, but the GeoItem's
 * blade axis remains local +Y.  This class corrects the rendered sword after
 * vanilla's hand and item-display transforms have been applied.  It therefore
 * rotates the blade around the grip without rewriting any arm/torso animation.
 */
public final class LaevateinSwordPose {
    private static final float SWEEP_DURATION = 0.55f;
    private static final float HEAVY_SWEEP_DURATION = 0.65f;
    // The straight grip shaft spans -8.5..+0.5 Geo units. Its centre is
    // four pixels below the model origin, which sits next to the guard.
    private static final float GRIP_CENTER_Y = -4f / 16f;

    private static final Map<AbstractClientPlayer, PlayerAction> PLAYER_ACTIONS = new WeakHashMap<>();
    private static final Map<LivingEntity, HandMotionSample> HAND_MOTION = new WeakHashMap<>();
    private static final ThreadLocal<Deque<RenderContext>> RENDER_CONTEXT =
        ThreadLocal.withInitial(ArrayDeque::new);

    private record PlayerAction(String animation, int startTick) {}
    private record PoseAction(String animation, float seconds, float duration) {}
    private record HandMotionSample(String animation, float seconds,
                                    Vector3f position, Vector3f entityPosition,
                                    Vector3f tangent) {}
    private record RenderContext(LivingEntity entity, ItemStack stack,
                                 ItemDisplayContext displayContext, boolean leftHand,
                                 Vector3f armAxis, Vector3f fallbackWidthAxis,
                                 Vector3f handPosition) {}

    private LaevateinSwordPose() {}

    /** Captured around ItemInHandRenderer.renderItem; GeckoLib's preRender runs inside it. */
    public static void beginRender(LivingEntity entity, ItemStack stack,
                                   ItemDisplayContext displayContext, boolean leftHand, PoseStack handPose) {
        // The PAL first-person pass also comes through ItemInHandLayer with a
        // third-person display context. Vanilla's direct-hand pass has no arm
        // bone and should keep its own idle/equip orientation.
        boolean animatedHandSocket = (displayContext == ItemDisplayContext.THIRD_PERSON_RIGHT_HAND
            || displayContext == ItemDisplayContext.THIRD_PERSON_LEFT_HAND)
            && stack.is(ModItems.LAEVATEIN);
        Matrix4fc socket = handPose.last().pose();
        Vector3f armAxis = animatedHandSocket ? armSweepAxis(socket) : null;
        Vector3f fallbackWidthAxis = animatedHandSocket ? armSweepWidthAxis(socket) : null;
        Vector3f handPosition = animatedHandSocket
            ? new Vector3f(socket.m30(), socket.m31(), socket.m32()) : null;
        RENDER_CONTEXT.get().push(new RenderContext(entity, stack, displayContext, leftHand,
            armAxis, fallbackWidthAxis, handPosition));
    }

    public static void endRender() {
        Deque<RenderContext> contexts = RENDER_CONTEXT.get();
        if (!contexts.isEmpty()) contexts.pop();
        if (contexts.isEmpty()) RENDER_CONTEXT.remove();
    }

    /** Called by the existing S2C animation receiver for both local and remote players. */
    public static void acceptPlayerAnimation(AbstractClientPlayer player, String animation, boolean stop) {
        if (stop || durationOf(animation) <= 0f) {
            PLAYER_ACTIONS.remove(player);
        } else {
            PLAYER_ACTIONS.put(player, new PlayerAction(animation, player.tickCount));
        }
    }

    /** Drop stale client references when a world unloads or the player leaves. */
    public static void clear() {
        PLAYER_ACTIONS.clear();
        HAND_MOTION.clear();
        RENDER_CONTEXT.remove();
    }

    /**
     * Apply only during sweep_right / sweep_left / heavy_combo_3, and only to
     * the Laevatein currently attached to a hand.  The default upright hold is
     * untouched outside those clips.
     */
    public static void apply(PoseStack poseStack, ItemDisplayContext currentContext, float partialTick) {
        Deque<RenderContext> contexts = RENDER_CONTEXT.get();
        if (contexts.isEmpty()) return;
        RenderContext context = contexts.peek();
        if (context == null || context.displayContext() != currentContext
            || !isHandContext(currentContext) || !context.stack().is(ModItems.LAEVATEIN)
            || context.stack().has(ModComponents.THROWN)
            || context.stack().getOrDefault(ModComponents.SHELL_STAGE, 3) != 0
            || context.leftHand() != (context.entity().getMainArm() == HumanoidArm.LEFT)) return;

        PoseAction action = activeAction(context.entity(), partialTick);
        if (action == null) return;

        float progress = Mth.clamp(action.seconds() / action.duration(), 0f, 1f);
        float blend = sweepBlend(progress);
        if (blend <= 0.001f) return;

        if (isSweepAction(action.animation())) {
            if (context.armAxis() != null && context.handPosition() != null) {
                // New approach: the blade's broad-face plane follows the actual
                // hand travel tangent. The sword's local +X (blade width) is
                // aligned to the hand's left/right motion, while local +Y
                // remains aligned to the forearm. The broad-face normal is then
                // perpendicular to the sweep direction, so the cutting edge
                // leads instead of the flat blade face. This is view-agnostic:
                // PAL first-person and third-person use the same hand socket.
                Vector3f travelAxis = handTravelAxis(context, action);
                rotateAroundGrip(poseStack, context.armAxis(), travelAxis, blend);
            }
            return;
        }
    }

    /**
     * Derive the blade-width axis from the animated hand's actual displacement
     * between rendered samples. This replaces the old palm-facing-axis guess,
     * which could align the broad face into the target and make the sword slap
     * with its flat. Duplicate first-/third-person render calls at the same
     * animation timestamp reuse the same tangent rather than producing zero.
     */
    private static Vector3f handTravelAxis(RenderContext context, PoseAction action) {
        LivingEntity entity = context.entity();
        Vector3f currentPosition = new Vector3f(context.handPosition());
        HandMotionSample previous = HAND_MOTION.get(entity);
        if (previous == null || !previous.animation().equals(action.animation())
            || action.seconds() < previous.seconds() - 0.001f) {
            Vector3f fallback = new Vector3f(context.fallbackWidthAxis());
            fallback.normalize();
            HAND_MOTION.put(entity, new HandMotionSample(action.animation(), action.seconds(),
                currentPosition, entityPosition(entity), fallback));
            return fallback;
        }

        float elapsed = action.seconds() - previous.seconds();
        if (elapsed <= 0.0005f) return new Vector3f(previous.tangent());

        Vector3f tangent = new Vector3f(currentPosition).sub(previous.position());
        // Third-person remote entities move relative to the camera, so remove
        // their world displacement. For the camera entity (including local
        // first-person and local third-person), camera tracking already cancels
        // body translation in the hand socket and must not be subtracted twice.
        if (Minecraft.getInstance().getCameraEntity() != entity) {
            tangent.sub(entityPosition(entity).sub(previous.entityPosition()));
        }
        // Remove tiny vertical/noise components only when the motion is almost
        // stationary; otherwise preserve the authored diagonal sweep path.
        if (tangent.lengthSquared() < 1.0e-7f) {
            tangent.set(previous.tangent());
        } else {
            tangent.normalize();
            // A small temporal blend suppresses frame-to-frame model jitter
            // without delaying the direction change of the actual sweep.
            tangent.lerp(previous.tangent(), 0.12f).normalize();
        }

        HAND_MOTION.put(entity, new HandMotionSample(action.animation(), action.seconds(),
            currentPosition, entityPosition(entity), new Vector3f(tangent)));
        return tangent;
    }

    private static Vector3f entityPosition(LivingEntity entity) {
        return new Vector3f((float) entity.getX(), (float) entity.getY(), (float) entity.getZ());
    }

    // ItemInHandLayer appends X(-90) and Y(180) after translateToHand.
    // In that socket, -Z points along shoulder -> palm. This long axis is
    // sampled directly from the animated arm for both first- and third-person.
    static Vector3f armSweepAxis(Matrix4fc handSocket) {
        return handSocket.transformDirection(new Vector3f(0f, 0.04f, -1f)).normalize();
    }

    /** Fallback lateral axis used only on the first rendered sample of a clip. */
    static Vector3f armSweepWidthAxis(Matrix4fc handSocket) {
        // Fallback is the hand socket's lateral axis. During the sweep this is
        // superseded by the measured hand-travel tangent.
        return handSocket.transformDirection(new Vector3f(1f, 0f, 0f)).normalize();
    }

    // In entity rendering the item stack carries world-oriented axes; the
    // view rotation is on RenderSystem's model-view stack. In vanilla's
    // direct-hand pass GameRenderer prepends the inverse of that view rotation
    // to PoseStack, too. In both 1.21.1 paths the basis seen here is therefore
    // world-oriented. A second world-to-camera transform would double-rotate.
    static void rotateAroundGrip(PoseStack poseStack, Vector3f worldLongAxis,
                                 Vector3f worldWidthAxis, float blend) {
        Matrix3f itemToRenderSpace = new Matrix3f(poseStack.last().pose());
        float determinant = itemToRenderSpace.determinant();
        if (!Float.isFinite(determinant) || Math.abs(determinant) < 1.0e-6f) return;
        itemToRenderSpace.invert();

        // The GeoModel's blade runs along local +Y; its width is local +X.
        // Convert both animated hand-socket axes back into the item-local basis.
        Vector3f localLong = itemToRenderSpace.transform(worldLongAxis, new Vector3f()).normalize();
        Vector3f localWidth = itemToRenderSpace.transform(worldWidthAxis, new Vector3f()).normalize();
        if (!finite(localLong) || !finite(localWidth)
            || localLong.lengthSquared() < 1.0e-6f || localWidth.lengthSquared() < 1.0e-6f) return;

        // Gram-Schmidt prevents small non-uniform display-scale / interpolation
        // errors from introducing shear into the sword's orientation.
        localWidth.sub(new Vector3f(localLong).mul(localWidth.dot(localLong)));
        if (localWidth.lengthSquared() < 1.0e-6f) return;
        localWidth.normalize();

        // First align the blade length with the forearm. Then rotate around that
        // same axis until the blade width follows the measured hand-travel
        // tangent. The resulting +Z broad-face normal is perpendicular to the
        // actual sweep direction, not a fixed world or camera-facing vector.
        Quaternionf alignLong = new Quaternionf().rotationTo(0f, 1f, 0f,
            localLong.x, localLong.y, localLong.z);
        Vector3f alignedWidth = alignLong.transform(new Vector3f(1f, 0f, 0f)).normalize();
        float sin = localLong.dot(new Vector3f(alignedWidth).cross(localWidth));
        float cos = Mth.clamp(alignedWidth.dot(localWidth), -1f, 1f);
        float roll = (float) Math.atan2(sin, cos);
        Quaternionf alignWidth = new Quaternionf().rotationAxis(roll,
            localLong.x, localLong.y, localLong.z);
        Quaternionf fullCorrection = alignWidth.mul(alignLong);
        Quaternionf easedCorrection = new Quaternionf().identity().slerp(fullCorrection, blend);
        poseStack.translate(0f, GRIP_CENTER_Y, 0f);
        poseStack.mulPose(easedCorrection);
        poseStack.translate(0f, -GRIP_CENTER_Y, 0f);
    }

    private static boolean finite(Vector3f v) {
        return Float.isFinite(v.x) && Float.isFinite(v.y) && Float.isFinite(v.z);
    }

    private static PoseAction activeAction(LivingEntity entity, float partialTick) {
        if (entity instanceof MatthiasEntity matthias) {
            String animation = matthias.animationName();
            float duration = durationOf(animation);
            if (duration <= 0f) return null;
            float seconds = matthias.animationSeconds(partialTick);
            return seconds <= duration + 0.035f ? new PoseAction(animation, seconds, duration) : null;
        }
        if (entity instanceof AbstractClientPlayer player) {
            PlayerAction state = PLAYER_ACTIONS.get(player);
            if (state == null) return null;
            float duration = durationOf(state.animation());
            float seconds = (player.tickCount + partialTick - state.startTick()) / 20f;
            if (seconds > duration + 0.035f || seconds < 0f) {
                PLAYER_ACTIONS.remove(player);
                return null;
            }
            return new PoseAction(state.animation(), seconds, duration);
        }
        return null;
    }

    private static boolean isNormalSweep(String animation) {
        return "sweep_right".equals(animation) || "sweep_left".equals(animation);
    }

    private static boolean isSweepAction(String animation) {
        return isNormalSweep(animation) || "heavy_combo_3".equals(animation);
    }

    private static float durationOf(String animation) {
        if (isNormalSweep(animation)) return SWEEP_DURATION;
        if ("heavy_combo_3".equals(animation)) return HEAVY_SWEEP_DURATION;
        return 0f;
    }

    /** Smoothly ramp the weapon-only cant in and out; the neutral hold remains upright. */
    private static float sweepBlend(float progress) {
        float in = smoothstep(0.02f, 0.20f, progress);
        float out = 1f - smoothstep(0.78f, 0.98f, progress);
        return Mth.clamp(in * out, 0f, 1f);
    }

    private static float smoothstep(float edge0, float edge1, float value) {
        float t = Mth.clamp((value - edge0) / (edge1 - edge0), 0f, 1f);
        return t * t * (3f - 2f * t);
    }

    private static boolean isHandContext(ItemDisplayContext context) {
        return context == ItemDisplayContext.FIRST_PERSON_RIGHT_HAND
            || context == ItemDisplayContext.FIRST_PERSON_LEFT_HAND
            || context == ItemDisplayContext.THIRD_PERSON_RIGHT_HAND
            || context == ItemDisplayContext.THIRD_PERSON_LEFT_HAND;
    }


}
