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

/**
 * Interface implemented by dedicated shader pipelines (e.g. Nvidium Shader Pipeline).
 * Provides interception hooks during the Nvidium frame rendering lifecycle.
 */
public interface INvidiumShaderHook {
    /**
     * Called at the start of Nvidium's frame setup before any passes.
     */
    void onSetupFrame(Viewport viewport, ChunkRenderMatrices crm, double px, double py, double pz);

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
     * Called right after primary terrain rasterization completes.
     * Ideal place to execute Deferred Lighting and Volumetric Fog passes.
     */
    void onPostRenderTerrain();

    /**
     * Called right before translucent terrain rasterization.
     */
    void onPreRenderTranslucent(int regionCount, long commandAddr);

    /**
     * Called right after translucent terrain rasterization completes.
     * Ideal place to execute Post-Processing (Bloom, Tone Mapping, Blit).
     */
    void onPostRenderTranslucent();
}
