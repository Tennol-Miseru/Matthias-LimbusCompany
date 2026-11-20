package com.laevatein.client;

import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.client.model.geom.PartPose;

/**
 * Minecraft PlayerModel attachment for Matthias's purple cloak and fur collar.
 *
 * The cloak uses the intentionally lightweight 5-column x 6-row segmented
 * construction from PlayerModelNPC_Cape_Spec.md. Each row is a child of the
 * previous row, so the six pivots form a spring chain. The collar is rebuilt
 * from the 17 axis-aligned cuboids in the supplied fur_collar.obj.
 *
 * Coordinates are converted from the supplied Blender export:
 *   MC X = Blender X * 16
 *   MC Y = 32 - Blender Z * 16
 *   MC Z = Blender Y * 16
 */
public final class MatthiasCapeModel {
    private static final int TEXTURE_WIDTH = 64;
    private static final int TEXTURE_HEIGHT = 64;

    private static final int CAPE_COLUMNS = 5;
    private static final int CAPE_ROWS = 6;
    private static final float ROW_HEIGHT = 3.6F;
    private static final float ROW_DEPTH_STEP = 1.2F;
    private static final float CAPE_TOP_WIDTH = 10.6F;
    private static final float CAPE_BOTTOM_WIDTH = 17.0F;
    private static final float CAPE_THICKNESS = 0.22F;

    // Small per-column depth offsets create the five vertical cloth folds.
    private static final float[] COLUMN_DEPTH_OFFSETS = {
        -0.24F, 0.09F, -0.08F, 0.21F, -0.12F
    };

    // Each entry is one OBJ cuboid converted to Minecraft model pixels:
    // x, y, z, width, height, depth.
    // The 17 groups are preserved as separate blocky fur tufts / collar pieces.
    private static final float[][] FUR_CUBOIDS = {
        {-1.3600F, -2.7200F,  3.8400F,  2.7200F,  4.3200F,  2.5600F},
        { 0.7312F, -0.9933F,  3.9054F,  2.7533F,  2.4921F,  2.5600F},
        { 2.1052F, -1.1286F,  2.5828F,  2.6535F,  3.9693F,  2.5600F},
        { 4.0322F, -1.6752F,  2.0054F,  2.8196F,  3.1262F,  2.5600F},
        { 4.9127F, -2.0527F,  0.5524F,  2.5875F,  3.1734F,  2.5600F},
        { 4.4630F, -1.3721F, -0.9600F,  2.8852F,  3.9311F,  2.5600F},
        { 5.3324F, -0.5827F, -2.5668F,  2.5224F,  2.5139F,  2.5600F},
        {-7.6060F, -2.1673F, -2.5357F,  2.2800F,  4.3151F,  2.5600F},
        {-8.0261F, -1.7396F, -0.9600F,  3.1874F,  2.4552F,  2.5600F},
        {-6.7794F, -1.5175F,  0.4208F,  2.2260F,  4.0409F,  2.5600F},
        {-7.2478F, -0.7732F,  2.1067F,  3.2397F,  3.0336F,  2.5600F},
        {-4.6734F, -1.9680F,  2.7415F,  2.1755F,  3.2692F,  2.5600F},
        {-3.5733F, -2.2217F,  3.4929F,  3.2884F,  3.8505F,  2.5600F},
        {-8.0000F,  0.0800F, -3.0400F,  6.4000F,  2.4000F,  7.6800F},
        {-5.7600F, -1.3600F,  0.8000F,  5.1200F,  2.7200F,  4.8000F},
        { 1.6000F,  0.0800F, -3.0400F,  6.4000F,  2.4000F,  7.6800F},
        { 0.6400F, -1.3600F,  0.8000F,  5.1200F,  2.7200F,  4.8000F},
    };

    private MatthiasCapeModel() {}

    public static LayerDefinition createLayer() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();

        // The spec's MC Y coordinates are measured from the top of the head
        // (head top = 0). Vanilla PlayerModel's body pivot is at Y = 8 in that
        // frame, so the shoulder-line cape anchor Y = 7.8 becomes -0.2 locally.
        PartDefinition capeRoot = root.addOrReplaceChild(
            "cape_root",
            CubeListBuilder.create(),
            PartPose.offset(0.0F, -0.2F, 2.0F)
        );

        PartDefinition parent = capeRoot;
        for (int row = 0; row < CAPE_ROWS; row++) {
            float t = row / (float) (CAPE_ROWS - 1);
            float width = CAPE_TOP_WIDTH + (CAPE_BOTTOM_WIDTH - CAPE_TOP_WIDTH) * t;
            float columnWidth = width / CAPE_COLUMNS;

            CubeListBuilder rowBuilder = CubeListBuilder.create();
            for (int column = 0; column < CAPE_COLUMNS; column++) {
                float x = -width * 0.5F + column * columnWidth;
                // A tiny overlap prevents bright seams between neighboring plates.
                float plateWidth = columnWidth + 0.08F;
                int u = column * 8;
                int v = row * 4;
                rowBuilder.texOffs(u, v).addBox(
                    x,
                    0.0F,
                    COLUMN_DEPTH_OFFSETS[column],
                    plateWidth,
                    ROW_HEIGHT + 0.08F,
                    CAPE_THICKNESS
                );
            }

            PartPose pose = row == 0
                ? PartPose.offset(0.0F, 0.0F, 0.0F)
                : PartPose.offset(0.0F, ROW_HEIGHT, ROW_DEPTH_STEP);
            parent = parent.addOrReplaceChild("row_" + row, rowBuilder, pose);
        }

        // Exact 17-cuboid silhouette from fur_collar.obj. All pieces use the
        // same dark fur atlas tile so the blocky geometry remains the focus.
        CubeListBuilder fur = CubeListBuilder.create();
        for (float[] box : FUR_CUBOIDS) {
            fur.texOffs(0, 32).addBox(
                box[0], box[1], box[2],
                box[3], box[4], box[5]
            );
        }
        root.addOrReplaceChild("fur_collar", fur, PartPose.offset(0.0F, 0.0F, 0.0F));

        return LayerDefinition.create(mesh, TEXTURE_WIDTH, TEXTURE_HEIGHT);
    }

    public static ModelPart bakeRoot() {
        return createLayer().bakeRoot();
    }
}
