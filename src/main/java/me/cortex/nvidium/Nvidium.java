/*
 * Nvidium - High performance rendering engine for Minecraft
 * Copyright (C) 2023 cortex
 *
 * Modified by 1Influence (2025) - Ported to NeoForge.
 * Licensed under LGPL-3.0-only
 */

package me.cortex.nvidium;

import me.cortex.nvidium.config.NvidiumConfig;
import net.minecraft.Util;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(value = "nvidium", dist = Dist.CLIENT)
public class Nvidium {
    public static final String MOD_VERSION;
    public static final Logger LOGGER = LoggerFactory.getLogger("Nvidium");
    public static boolean IS_COMPATIBLE = false;
    public static boolean IS_ENABLED = false;
    public static boolean IS_DEBUG = Boolean.parseBoolean(System.getProperty("nvidium.isDebug", "false"));
    public static boolean SUPPORTS_PERSISTENT_SPARSE_ADDRESSABLE_BUFFER = true;
    public static boolean FORCE_DISABLE = false;

    public static NvidiumConfig config = NvidiumConfig.loadOrCreate();

    static {
        String version = "0.3.0";
        try {
            if (ModList.get() != null) {
                var mod = ModList.get().getModContainerById("nvidium");
                if (mod.isPresent()) {
                    version = mod.get().getModInfo().getVersion().toString();
                }
            }
        } catch (Throwable ignored) {}
        MOD_VERSION = version + "-unknown";
    }

    public Nvidium() {
        LOGGER.info("Nvidium initialized on NeoForge (version {})", MOD_VERSION);
    }

    public static void checkSystemIsCapable() {
        GLCapabilities cap = GL.getCapabilities();
        boolean supported = cap.GL_NV_mesh_shader &&
                cap.GL_NV_uniform_buffer_unified_memory &&
                cap.GL_NV_vertex_buffer_unified_memory &&
                cap.GL_NV_representative_fragment_test &&
                cap.GL_ARB_sparse_buffer &&
                cap.GL_NV_bindless_multi_draw_indirect;

        IS_COMPATIBLE = supported;

        if (IS_COMPATIBLE) {
            LOGGER.info("All capabilities met");
        } else {
            LOGGER.warn("Not all requirements met, disabling Nvidium");
        }

        if (IS_COMPATIBLE && Util.getPlatform() == Util.OS.LINUX) {
            LOGGER.warn("Linux currently uses fallback terrain buffer due to driver inconsistencies, expect increased VRAM usage");
            SUPPORTS_PERSISTENT_SPARSE_ADDRESSABLE_BUFFER = false;
        }

        if (IS_COMPATIBLE) {
            LOGGER.info("Enabling Nvidium");
        }

        IS_ENABLED = IS_COMPATIBLE;
    }
}
