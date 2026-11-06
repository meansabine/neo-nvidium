/*
 * Nvidium - High performance rendering engine for Minecraft
 * Copyright (C) 2023 cortex
 *
 * Modified by 1Influence (2025) - Ported to NeoForge.
 * Licensed under LGPL-3.0-only
 */

package me.cortex.nvidium.sodiumCompat;

import net.caffeinemc.mods.sodium.api.util.ColorABGR;
import net.caffeinemc.mods.sodium.api.util.ColorARGB;
import net.caffeinemc.mods.sodium.api.util.ColorU8;
import net.caffeinemc.mods.sodium.client.gl.attribute.GlVertexFormat;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexType;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.impl.DefaultChunkMeshAttributes;
import net.minecraft.util.Mth;
import org.lwjgl.system.MemoryUtil;

public class NvidiumCompactChunkVertex implements ChunkVertexType {

    public static final int STRIDE = 16;
    public static final NvidiumCompactChunkVertex INSTANCE = new NvidiumCompactChunkVertex();

    public static final GlVertexFormat VERTEX_FORMAT = GlVertexFormat.builder(STRIDE)
            .addElement(DefaultChunkMeshAttributes.POSITION, 0, 0)
            .addElement(DefaultChunkMeshAttributes.LIGHT_MATERIAL_INDEX, 1, 4)
            .addElement(DefaultChunkMeshAttributes.COLOR, 2, 8)
            .addElement(DefaultChunkMeshAttributes.TEXTURE, 3, 12)
            .build();

    private static final int POSITION_MAX_VALUE = 65536;
    public static final int TEXTURE_MAX_VALUE = 32768;

    private static final float MODEL_ORIGIN = 8.0f;
    private static final float MODEL_RANGE = 32.0f;
    private static final float MODEL_SCALE = MODEL_RANGE / POSITION_MAX_VALUE;
    private static final float MODEL_SCALE_INV = POSITION_MAX_VALUE / MODEL_RANGE;
    private static final float TEXTURE_SCALE = (1.0f / TEXTURE_MAX_VALUE);

    private static final ThreadLocal<Integer> CURRENT_MATERIAL_ID = ThreadLocal.withInitial(() -> 0);

    public static void setCurrentMaterialId(int materialId) {
        CURRENT_MATERIAL_ID.set(materialId & 0x1F);
    }

    public static int getCurrentMaterialId() {
        return CURRENT_MATERIAL_ID.get();
    }

    @Override
    public GlVertexFormat getVertexFormat() {
        return VERTEX_FORMAT;
    }

    @Override
    public ChunkVertexEncoder getEncoder() {
        return (ptr, materialBits, vertices, sectionIndex) -> {
            int matId = CURRENT_MATERIAL_ID.get() & 0x1F;
            int packedMaterial = (materialBits & 0x07) | (matId << 3);
            for (int i = 0; i < 4; i++) {
                var vertex = vertices[i];
                int light = compactLight(vertex.light);

                MemoryUtil.memPutInt(ptr + 0, (encodePosition(vertex.x) << 0) | (encodePosition(vertex.y) << 16));
                MemoryUtil.memPutInt(ptr + 4, (encodePosition(vertex.z) << 0) | ((packedMaterial & 0xFF) << 16) | ((light & 0xFF) << 24));
                MemoryUtil.memPutInt(ptr + 8, (encodeColor(vertex.color, vertex.ao) << 0) | (((light >> 8) & 0xFF) << 24));
                MemoryUtil.memPutInt(ptr + 12, encodeTexture(vertex.u, vertex.v));

                ptr += STRIDE;
            }
            return ptr;
        };
    }

    private static int compactLight(int light) {
        int sky = Mth.clamp((light >>> 16) & 0xFF, 8, 248);
        int block = Mth.clamp((light >>> 0) & 0xFF, 8, 248);

        return (block << 0) | (sky << 8);
    }

    private static int encodePosition(float v) {
        return (int) ((MODEL_ORIGIN + v) * MODEL_SCALE_INV);
    }

    private static int encodeColor(int color, float ao) {
        return ColorARGB.mulRGB(color, ao) & 0x00FFFFFF;
    }

    private static int encodeTexture(float u, float v) {
        return ((Math.round(u * TEXTURE_MAX_VALUE) & 0xFFFF) << 0) |
                ((Math.round(v * TEXTURE_MAX_VALUE) & 0xFFFF) << 16);
    }
}
