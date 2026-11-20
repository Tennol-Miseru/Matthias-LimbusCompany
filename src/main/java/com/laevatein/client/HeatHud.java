package com.laevatein.client;

import com.laevatein.Laevatein;
import com.laevatein.LaevateinConfig;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/** 快捷栏左侧高温图标：不用长条，而是在图标右下角显示 0..99 层计数。 */
public final class HeatHud {
    private static final ResourceLocation ICON = Laevatein.id("textures/gui/heat_icon_hd.png");
    private static int ticks;
    private static boolean active;
    private static final int ICON_SIZE = 32;
    private static final int TEXTURE_SIZE = 128;
    private static final int HOTBAR_BOTTOM_PADDING = 1;
    /** 副手槽显示物品时，将热量图标向左移，避开副手栏。 */
    private static final int OFFHAND_CLEARANCE_SHIFT = 28;

    private HeatHud() {}

    public static void accept(int serverTicks, boolean hasReleasedSword) {
        ticks = Math.max(0, Math.min(LaevateinConfig.HEAT_MAX_TICKS, serverTicks));
        active = hasReleasedSword;
    }

    public static void clear() {
        ticks = 0;
        active = false;
    }

    public static void init() { HudRenderCallback.EVENT.register((graphics, deltaTracker) -> render(graphics)); }

    private static void render(GuiGraphics graphics) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui || !active) return;
        int w = graphics.guiWidth();
        int h = graphics.guiHeight();
        // 贴着快捷栏左下角，保留与生命/护甲 HUD 的间距；副手有物品时额外左移避让副手槽。
        int preferredX = w / 2 - 91 - (ICON_SIZE + 6);
        int offhandShift = mc.player.getOffhandItem().isEmpty() ? 0 : OFFHAND_CLEARANCE_SHIFT;
        int x = Math.max(4, preferredX - offhandShift);
        // 与快捷栏底边对齐：32 像素图标完整落在屏幕内，不再上浮。
        int y = h - ICON_SIZE - HOTBAR_BOTTOM_PADDING;
        // blit 的 width/height 不是目标缩放参数：原写法仅裁剪了高清图左上角 32x32。
        // 在绘制矩阵中按 1/4 缩放，先完整绘制 128x128，再恢复矩阵绘制计数。
        graphics.pose().pushPose();
        graphics.pose().translate(x, y, 0);
        float scale = (float) ICON_SIZE / TEXTURE_SIZE;
        graphics.pose().scale(scale, scale, 1.0f);
        graphics.blit(ICON, 0, 0, 0, 0, TEXTURE_SIZE, TEXTURE_SIZE, TEXTURE_SIZE, TEXTURE_SIZE);
        graphics.pose().popPose();

        int layers = Math.min(LaevateinConfig.HEAT_MAX_LAYERS,
            ticks * LaevateinConfig.HEAT_MAX_LAYERS / LaevateinConfig.HEAT_MAX_TICKS);
        String count = Integer.toString(layers);
        int tw = mc.font.width(count);
        int tx = x + ICON_SIZE - tw - 2;
        int ty = y + ICON_SIZE - 11;
        // 深色描底，提高在亮色图标与白天背景上的可读性。
        graphics.fill(tx - 2, ty - 1, x + ICON_SIZE - 1, ty + 9, 0xB8100B0E);
        int color = ticks >= LaevateinConfig.HEAT_MAX_TICKS ? 0xFFFF6250 : 0xFFFFE0A4;
        graphics.drawString(mc.font, count, tx, ty, color, true);
    }
}
