/*
 * Nvidium - High performance rendering engine for Minecraft
 * Copyright (C) 2023 cortex
 *
 * Modified by 1Influence (2025) - Ported to NeoForge.
 * Licensed under LGPL-3.0-only
 */

package me.cortex.nvidium.renderers;

import com.mojang.blaze3d.platform.GlStateManager;
import me.cortex.nvidium.api0.INvidiumShaderHook;
import me.cortex.nvidium.api0.NvidiumAPI;
import me.cortex.nvidium.gl.shader.Shader;
import me.cortex.nvidium.mixin.minecraft.LightMapAccessor;
import me.cortex.nvidium.sodiumCompat.ShaderLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL12C;
import org.lwjgl.opengl.GL33;

import static me.cortex.nvidium.RenderPipeline.GL_DRAW_INDIRECT_ADDRESS_NV;
import static me.cortex.nvidium.gl.shader.ShaderType.*;
import static org.lwjgl.opengl.GL11C.*;
import static org.lwjgl.opengl.GL33.glBindSampler;
import static org.lwjgl.opengl.GL33.glGenSamplers;
import static org.lwjgl.opengl.GL33.glSamplerParameteri;
import static org.lwjgl.opengl.NVMeshShader.glMultiDrawMeshTasksIndirectNV;
import static org.lwjgl.opengl.NVVertexBufferUnifiedMemory.glBufferAddressRangeNV;

public class TranslucentTerrainRasterizer extends Phase {
    private final int blockSampler = glGenSamplers();
    private final int lightSampler = glGenSamplers();

    private final Shader shader = Shader.make()
            .addSource(TASK, ShaderLoader.parse(ResourceLocation.fromNamespaceAndPath("nvidium", "terrain/translucent/task.glsl")))
            .addSource(MESH, ShaderLoader.parse(ResourceLocation.fromNamespaceAndPath("nvidium", "terrain/translucent/mesh.glsl")))
            .addSource(FRAGMENT, ShaderLoader.parse(ResourceLocation.fromNamespaceAndPath("nvidium", "terrain/frag.frag"), builder -> { builder.add("TRANSLUCENT_PASS"); }))
            .compile();

    public TranslucentTerrainRasterizer() {
        glSamplerParameteri(blockSampler, GL11C.GL_TEXTURE_MIN_FILTER, GL_NEAREST_MIPMAP_LINEAR);
        glSamplerParameteri(blockSampler, GL11C.GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glSamplerParameteri(blockSampler, GL12C.GL_TEXTURE_MIN_LOD, 0);
        glSamplerParameteri(blockSampler, GL12C.GL_TEXTURE_MAX_LOD, 4);

        glSamplerParameteri(lightSampler, GL_TEXTURE_WRAP_T, GL12C.GL_CLAMP_TO_EDGE);
        glSamplerParameteri(lightSampler, GL_TEXTURE_WRAP_S, GL12C.GL_CLAMP_TO_EDGE);
        glSamplerParameteri(lightSampler, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glSamplerParameteri(lightSampler, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    }

    private static void setTexture(int texId, int bindingPoint) {
        GlStateManager._activeTexture(33984 + bindingPoint);
        GlStateManager._bindTexture(texId);
    }

    public void raster(int regionCount, long commandAddr) {
        INvidiumShaderHook hook = NvidiumAPI.getShaderHook();
        Shader activeShader = shader;
        if (hook != null && hook.getCustomTranslucentShader() != null) {
            activeShader = hook.getCustomTranslucentShader();
        }
        activeShader.bind();

        AbstractTexture blockTexture = Minecraft.getInstance().getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS);
        AbstractTexture lightTexture = ((LightMapAccessor) Minecraft.getInstance().gameRenderer.lightTexture()).getTexture();

        glBindSampler(0, blockSampler);
        glBindSampler(1, lightSampler);
        setTexture(blockTexture.getId(), 0);
        setTexture(lightTexture.getId(), 1);

        if (hook != null) {
            hook.onBindTranslucentTextures();
        }

        glBufferAddressRangeNV(GL_DRAW_INDIRECT_ADDRESS_NV, 0, commandAddr, regionCount * 8L);
        glMultiDrawMeshTasksIndirectNV(0, regionCount, 0);

        glBindSampler(0, 0);
        glBindSampler(1, 0);
    }

    public void delete() {
        GL33.glDeleteSamplers(blockSampler);
        GL33.glDeleteSamplers(lightSampler);
        shader.delete();
    }
}
