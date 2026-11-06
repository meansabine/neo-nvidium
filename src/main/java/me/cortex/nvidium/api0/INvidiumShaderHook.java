/*
 * Nvidium - High performance rendering engine for Minecraft
 * Copyright (C) 2023 cortex
 *
 * Modified by 1Influence (2025) - Ported to NeoForge.
 * Licensed under LGPL-3.0-only
 */

package me.cortex.nvidium.api0;

import me.cortex.nvidium.gl.shader.Shader;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import net.minecraft.client.gui.screens.Screen;
import org.joml.Matrix4fc;

/**
 * Interface implemented by dedicated shader pipelines (e.g. Nvidium Shader Pipeline).
 * Provides interception hooks during the Nvidium frame rendering lifecycle.
 */
public interface INvidiumShaderHook {

    /**
     * Context passed to {@link #onShadowPass(ShadowPassContext)} allowing the shader pipeline
     * to dispatch Nvidium's mesh-shader terrain rasterizer for each shadow cascade with a
     * camera-relative light View-Projection matrix.
     */
    interface ShadowPassContext {
        int regionCount();
        long commandBufferAddress();
        void drawOpaqueTerrainDepth(Shader depthShader, Matrix4fc cameraRelativeLightVP);
    }

    /**
     * Called at the start of Nvidium's frame setup before any passes.
     */
    void onSetupFrame(Viewport viewport, ChunkRenderMatrices crm, double px, double py, double pz);

    /**
     * Called after visibility & command buffers are prepared, right before the main G-Buffer pass.
     * Allows rendering cascaded shadow maps using Nvidium's mesh-shader terrain geometry.
     */
    default void onShadowPass(ShadowPassContext context) {}

    /**
     * Called right before primary terrain rasterization.
     * Ideal place to bind the G-Buffer FBO (MRT) for writing.
     */
    void onPreRenderTerrain(int regionCount, long commandAddr);

    /**
     * Called when terrain texture samplers are bound.
     * Allows binding extra LabPBR textures (e.g. units 2 & 3: normals and specular).
     */
    void onBindTerrainTextures();

    /**
     * Returns a custom Shader to replace or enhance the default terrain frag/mesh shader,
     * or null to use the default Nvidium terrain shader.
     */
    default Shader getCustomTerrainShader() {
        return null;
    }

    /**
     * Returns a custom Shader to replace or enhance the temporal terrain rasterizer shader
     * ({@code terrain/temporal_task.glsl + terrain/mesh.glsl + custom frag}),
     * or null to use the default Nvidium temporal terrain shader.
     */
    default Shader getCustomTemporalTerrainShader() {
        return null;
    }

    /**
     * Called right after primary terrain rasterization completes.
     * Ideal place to execute Deferred Lighting and Volumetric Fog passes.
     */
    void onPostRenderTerrain();

    /**
     * Called right before translucent terrain rasterization.
     */
    void onPreRenderTranslucent(int regionCount, long commandAddr);

    /**
     * Returns a custom Shader to replace or enhance the default translucent terrain shader,
     * or null to use the default Nvidium translucent shader.
     */
    default Shader getCustomTranslucentShader() {
        return null;
    }

    /**
     * Called when translucent terrain texture samplers are bound.
     */
    default void onBindTranslucentTextures() {}

    /**
     * Called right after translucent terrain rasterization completes.
     * Ideal place to execute Post-Processing (Bloom, Tone Mapping, Blit).
     */
    void onPostRenderTranslucent();

    /**
     * Creates and returns the shader pack / pipeline settings screen for Video Settings integration,
     * or null if no custom screen is provided.
     */
    default Screen createShaderConfigScreen(Screen parent) {
        return null;
    }
}
