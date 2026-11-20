package com.laevatein.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.laevatein.Laevatein;
import com.laevatein.registry.ModItems;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import org.joml.Vector3f;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Independent first-person kick leg pass. PAL's THIRD_PERSON_MODEL first-person mode
 * normally draws the arms only: the animated player legs cannot be seen.
 *
 * Instead of a disconnected canned foot sprite, sample the RIGHT LEG rotation
 * keyframes from the very same player_animations JSON that PAL uses in third person.
 * It is drawn only during a server-confirmed local player's kick animation.
 */
public final class FirstPersonKickRenderer {
    private static final ResourceLocation PLAYER_ANIMS =
        Laevatein.id("player_animations/laevatein_player.json");
    private static PlayerModel<LocalPlayer> playerModel;
    private static LocalPlayer owner;
    private static String action;
    private static float startTick;
    private static float duration;
    private static boolean holdChase;
    private static List<Key> rightLeg = List.of();

    private record Key(float time, Vector3f degrees, String easing) {}

    private FirstPersonKickRenderer() {}

    public static void clear() {
        owner = null;
        action = null;
        rightLeg = List.of();
    }

    /** Called from the same S2C action message that starts/stops the PAL animation. */
    public static void onPlayerAnimation(int entityId, String animation, boolean stop) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || player.getId() != entityId) return;
        if (stop || !isKick(animation)) {
            clear();
            return;
        }
        try (Reader reader = mc.getResourceManager().openAsReader(PLAYER_ANIMS)) {
            JsonObject animations = JsonParser.parseReader(reader).getAsJsonObject().getAsJsonObject("animations");
            JsonObject anim = animations.getAsJsonObject(animation);
            if (anim == null) { clear(); return; }
            List<Key> keys = parseKeys(anim.getAsJsonObject("bones").getAsJsonObject("right_leg"));
            if (keys.isEmpty()) { clear(); return; }
            owner = player;
            action = animation;
            rightLeg = keys;
            startTick = player.tickCount;
            duration = anim.get("animation_length").getAsFloat();
            holdChase = animation.equals("quad_kick_chase");
        } catch (IOException | RuntimeException e) {
            Laevatein.LOGGER.warn("Cannot load first-person kick keyframes for {}", animation, e);
            clear();
        }
    }

    private static boolean isKick(String id) {
        return id != null && (id.matches("quad_kick_[1-4]")
            || id.equals("quad_kick_chase") || id.equals("dash_kick"));
    }

    private static List<Key> parseKeys(JsonObject leg) {
        if (leg == null || !leg.has("rotation")) return List.of();
        List<Key> parsed = new ArrayList<>();
        for (var entry : leg.getAsJsonObject("rotation").entrySet()) {
            JsonObject keyframe = entry.getValue().getAsJsonObject();
            var vec = keyframe.getAsJsonArray("vector");
            String easing = keyframe.has("easing") ? keyframe.get("easing").getAsString() : "linear";
            parsed.add(new Key(Float.parseFloat(entry.getKey()),
                new Vector3f(vec.get(0).getAsFloat(), vec.get(1).getAsFloat(), vec.get(2).getAsFloat()), easing));
        }
        parsed.sort(Comparator.comparingDouble(Key::time));
        return List.copyOf(parsed);
    }

    private static float ease(float t, String type) {
        t = Math.max(0f, Math.min(1f, t));
        return switch (type) {
            case "easeInOutCubic" -> t < .5f ? 4f * t * t * t
                : 1f - (float) Math.pow(-2f * t + 2f, 3) / 2f;
            case "easeOutQuad" -> 1f - (1f - t) * (1f - t);
            case "easeInExpo" -> t == 0f ? 0f : (float) Math.pow(2f, 10f * t - 10f);
            default -> t;
        };
    }

    private static Vector3f sample(List<Key> keys, float seconds) {
        if (seconds <= keys.getFirst().time()) return new Vector3f(keys.getFirst().degrees());
        for (int i = 1; i < keys.size(); i++) {
            Key previous = keys.get(i - 1), next = keys.get(i);
            if (seconds <= next.time()) {
                float t = ease((seconds - previous.time()) / (next.time() - previous.time()), next.easing());
                return new Vector3f(previous.degrees()).lerp(next.degrees(), t);
            }
        }
        return new Vector3f(keys.getLast().degrees());
    }

    /** Invoked at the RETURN of vanilla ItemInHandRenderer#renderHandsWithItems. */
    public static void render(float partialTick, PoseStack matrices, MultiBufferSource.BufferSource buffers,
                              LocalPlayer player, int light) {
        Minecraft mc = Minecraft.getInstance();
        if (owner != player || action == null || rightLeg.isEmpty()
            || !mc.options.getCameraType().isFirstPerson() || !player.isAlive()
            || player.isSpectator() || player.isInvisible()
            || !player.getMainHandItem().is(ModItems.LAEVATEIN)) return;

        float elapsedTicks = player.tickCount + partialTick - startTick;
        if (elapsedTicks < 0f) return;
        float elapsed = elapsedTicks / 20f;
        if ((!holdChase && elapsed > duration + .10f)
            || (holdChase && elapsed > 2.0f)) { // never leave a ghost leg if a stop packet is lost
            clear();
            return;
        }
        if (playerModel == null) {
            playerModel = new PlayerModel<>(mc.getEntityModels().bakeLayer(ModelLayers.PLAYER), false);
        }
        Vector3f rotation = sample(rightLeg, Math.min(elapsed, duration));
        // Ease in/out to avoid hard cuts when the animation starts and stops.
        float fadeIn = Math.min(1f, .20f + elapsed / .07f);
        float fadeOut = holdChase ? 1f : Math.min(1f, Math.max(0f, duration + .10f - elapsed) / .10f);
        float alpha = Math.max(0f, Math.min(fadeIn, fadeOut));
        if (alpha < .02f) return;

        ModelPart leg = playerModel.rightLeg;
        ModelPart pants = playerModel.rightPants;
        float rx = (float) Math.toRadians(rotation.x());
        float ry = (float) Math.toRadians(rotation.y());
        float rz = (float) Math.toRadians(rotation.z());
        // Rebase both model parts at the hip; third-person coordinates use x=-1.9, y=12.
        leg.setPos(0f, 0f, 0f);
        pants.setPos(0f, 0f, 0f);
        leg.setRotation(rx, ry, rz);
        pants.setRotation(rx, ry, rz);
        pants.visible = player.isModelPartShown(net.minecraft.world.entity.player.PlayerModelPart.RIGHT_PANTS_LEG);

        matrices.pushPose();
        // Camera-relative position: the foot swings from lower-right toward the reticle.
        matrices.translate(.22, -.69, -.73);
        matrices.scale(-alpha, -alpha, alpha);
        VertexConsumer vertices = buffers.getBuffer(RenderType.entityTranslucent(player.getSkin().texture()));
        leg.render(matrices, vertices, light, OverlayTexture.NO_OVERLAY);
        if (pants.visible) pants.render(matrices, vertices, light, OverlayTexture.NO_OVERLAY);
        matrices.popPose();
    }
}
