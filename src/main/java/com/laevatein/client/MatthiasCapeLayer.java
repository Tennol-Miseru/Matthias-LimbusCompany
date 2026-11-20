package com.laevatein.client;

import com.laevatein.Laevatein;
import com.laevatein.entity.MatthiasEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import java.util.WeakHashMap;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

/**
 * Renders Matthias's cloak and fur collar on top of his PlayerModel.
 *
 * The cape is a six-joint client-side spring chain. Motion state is stored per
 * entity (not per renderer/model pose), so multiple Matthias NPCs cannot share
 * one another's inertia. Weak keys allow despawned entities to be collected.
 */
public final class MatthiasCapeLayer extends RenderLayer<MatthiasEntity, MatthiasPlayerModel> {
    private static final ResourceLocation TEXTURE =
        Laevatein.id("textures/entity/matthias_cape.png");

    private static final int ROW_COUNT = 6;
    private static final float SPRING_RESPONSE = 0.35F;
    private static final float DAMPING = 0.82F;
    // The six row pivots are hierarchical: their X rotations accumulate.
    // Keep the whole chain below ~53 degrees so a forward sprint cannot curl
    // the hem back over the shoulders/head.
    private static final float MAX_TOTAL_TRAIL_ANGLE = 0.92F;
    private static final float MAX_ROW_ANGLE = 0.28F;
    private static final float MAX_SPEED_TRAIL_ANGLE = 0.58F;

    private final ModelPart capeRoot;
    private final ModelPart collar;
    private final ModelPart[] rows = new ModelPart[ROW_COUNT];
    private final WeakHashMap<MatthiasEntity, MotionState> motionStates = new WeakHashMap<>();

    private static final class MotionState {
        final float[] angles = new float[ROW_COUNT];
        float lastAge;
        float lastSpeed;
        float landingPulse;
        float stopImpulse;
        float turnLag;
        float lastYaw;
        float phase;
        float windScale = 1.0F;
        double lastX;
        double lastY;
        double lastZ;
        boolean wasOnGround;
        boolean initialized;
        boolean anglesInitialized;
    }

    public MatthiasCapeLayer(RenderLayerParent<MatthiasEntity, MatthiasPlayerModel> parent,
                             ModelPart bakedRoot) {
        super(parent);
        this.capeRoot = bakedRoot.getChild("cape_root");
        this.collar = bakedRoot.getChild("fur_collar");

        ModelPart row = capeRoot.getChild("row_0");
        rows[0] = row;
        for (int i = 1; i < ROW_COUNT; i++) {
            row = row.getChild("row_" + i);
            rows[i] = row;
        }
    }

    @Override
    public void render(PoseStack poseStack, MultiBufferSource buffers, int packedLight,
                       MatthiasEntity entity, float limbSwing, float limbSwingAmount,
                       float partialTick, float ageInTicks, float netHeadYaw, float headPitch) {
        if (entity.isInvisible()) return;

        // Feature renderers are shared by all entities of a renderer type.
        // Reset every part before applying this entity's current spring pose.
        capeRoot.getAllParts().forEach(ModelPart::resetPose);
        collar.resetPose();
        updateCapePose(entity, ageInTicks);

        VertexConsumer consumer = buffers.getBuffer(RenderType.entityCutoutNoCull(TEXTURE));

        // Cape is rooted at the PlayerModel body pivot and follows its full
        // torso rotation. The segmented pivots then add the cloth lag.
        poseStack.pushPose();
        getParentModel().body.translateAndRotate(poseStack);
        capeRoot.render(poseStack, consumer, packedLight, OverlayTexture.NO_OVERLAY);
        poseStack.popPose();

        // The fur collar follows the torso and 50% of the head's rotation.
        // PlayerModel head and body share the shoulder-origin coordinate frame.
        poseStack.pushPose();
        getParentModel().body.translateAndRotate(poseStack);
        ModelPart head = getParentModel().head;
        poseStack.mulPose(Axis.ZP.rotation(head.zRot * 0.5F));
        poseStack.mulPose(Axis.YP.rotation(head.yRot * 0.5F));
        poseStack.mulPose(Axis.XP.rotation(head.xRot * 0.5F));
        collar.render(poseStack, consumer, packedLight, OverlayTexture.NO_OVERLAY);
        poseStack.popPose();
    }

    private void updateCapePose(MatthiasEntity entity, float ageInTicks) {
        MotionState state = motionStates.computeIfAbsent(entity, ignored -> createState(entity, ageInTicks));

        double vx = entity.getDeltaMovement().x;
        double vz = entity.getDeltaMovement().z;
        float horizontalSpeed = (float) Math.sqrt(vx * vx + vz * vz);
        float speedFactor = Mth.clamp(horizontalSpeed / 0.15F, 0.0F, 1.0F);

        if (!state.initialized) {
            state.lastAge = ageInTicks;
            state.lastX = entity.getX();
            state.lastY = entity.getY();
            state.lastZ = entity.getZ();
            state.lastSpeed = speedFactor;
            state.lastYaw = entity.getYRot();
            state.wasOnGround = entity.onGround();
            state.initialized = true;
        }

        float elapsed = Math.max(0.0F, ageInTicks - state.lastAge);
        if (elapsed > 0.0F) {
            double dx = entity.getX() - state.lastX;
            double dy = entity.getY() - state.lastY;
            double dz = entity.getZ() - state.lastZ;
            double movedSqr = dx * dx + dy * dy + dz * dz;

            // A landing or a large server-side position correction gives the
            // cloth one restrained impulse, which then decays with the spring.
            if ((!state.wasOnGround && entity.onGround()) || movedSqr > 4.0D) {
                state.landingPulse = 0.10F;
            }

            // Sudden braking briefly carries the lower cloth past its resting
            // angle before the spring pulls it back.
            if (state.lastSpeed - speedFactor > 0.35F) {
                state.stopImpulse = 0.05F;
            }

            // A small opposite yaw impulse lets the cape trail a turn for
            // roughly 3-5 ticks instead of rotating rigidly with the torso.
            float yawDelta = Mth.wrapDegrees(entity.getYRot() - state.lastYaw);
            if (Math.abs(yawDelta) > 0.001F) {
                state.turnLag = Mth.clamp(
                    state.turnLag - yawDelta * ((float) Math.PI / 180.0F) * 0.35F,
                    -0.22F, 0.22F
                );
            }

            float decay = (float) Math.pow(DAMPING, elapsed);
            state.landingPulse *= decay;
            state.stopImpulse *= decay;
            state.turnLag *= (float) Math.pow(0.70F, elapsed);
            state.lastAge = ageInTicks;
            state.lastX = entity.getX();
            state.lastY = entity.getY();
            state.lastZ = entity.getZ();
            state.lastSpeed = speedFactor;
            state.lastYaw = entity.getYRot();
            state.wasOnGround = entity.onGround();
        }

        // Convert the per-tick response into a frame-rate-independent blend.
        float blend = elapsed > 0.0F
            ? 1.0F - (float) Math.pow(1.0F - SPRING_RESPONSE, elapsed)
            : 0.0F;
        if (!state.anglesInitialized) {
            blend = 1.0F;
            state.anglesInitialized = true;
        }

        capeRoot.yRot = state.turnLag;

        float totalTrailAngle = 0.0F;
        for (int i = 0; i < ROW_COUNT; i++) {
            float wind = Mth.sin(ageInTicks * 0.08F + i * 0.6F + state.phase)
                * 0.015F * state.windScale;
            // Distribute a fixed total speed response over the six joints.
            // The old per-row term (0.35 + 0.12*i) added up to 3.9 radians
            // at full speed, turning the hierarchical chain almost upside down.
            float rowWeight = 0.75F + 0.08F * i;
            float speedTrail = speedFactor * MAX_SPEED_TRAIL_ANGLE * rowWeight / 5.7F;

            // Minecraft model Y points downward and the cape extends behind the
            // body along +Z. Keep a small positive rest curve; dynamic trailing
            // is deliberately restrained and distributed rather than multiplied
            // toward the bottom row.
            float target = 0.003F * (i + 1)
                + wind * (0.35F + 0.13F * i)
                + speedTrail
                + state.stopImpulse * (0.30F + 0.04F * i)
                + state.landingPulse * (0.20F + 0.03F * i);

            state.angles[i] = Mth.clamp(
                state.angles[i] + (target - state.angles[i]) * blend,
                0.0F, MAX_ROW_ANGLE
            );
            totalTrailAngle += state.angles[i];
        }

        // Since each row is a child of the previous row, local rotations add
        // along the chain. Clamp the cumulative angle as well as each joint;
        // this is the actual safeguard against the hem wrapping over the head.
        if (totalTrailAngle > MAX_TOTAL_TRAIL_ANGLE) {
            float scale = MAX_TOTAL_TRAIL_ANGLE / totalTrailAngle;
            for (int i = 0; i < ROW_COUNT; i++) state.angles[i] *= scale;
        }
        for (int i = 0; i < ROW_COUNT; i++) rows[i].xRot = state.angles[i];
    }

    private static MotionState createState(MatthiasEntity entity, float ageInTicks) {
        MotionState state = new MotionState();
        int hash = entity.getUUID().hashCode();
        state.phase = (hash & 0xFFFF) / 65535.0F * (float) (Math.PI * 2.0);
        state.windScale = 0.6F + ((hash >>> 16) & 0xFFFF) / 65535.0F * 0.4F;
        state.lastAge = ageInTicks;
        state.lastYaw = entity.getYRot();
        return state;
    }
}
