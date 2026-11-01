/*
 * Nvidium - High performance rendering engine for Minecraft
 * Copyright (C) 2023 cortex
 *
 * Modified by 1Influence (2025) - Ported to NeoForge.
 * Licensed under LGPL-3.0-only
 */

package me.cortex.nvidium.sodiumCompat;

import net.neoforged.fml.ModList;
import net.irisshaders.iris.api.v0.IrisApi;

public class IrisCheck {
    public static final boolean IRIS_LOADED;

    static {
        boolean loaded = false;
        try {
            if (ModList.get() != null) {
                loaded = ModList.get().isLoaded("iris");
            }
        } catch (Throwable ignored) {}
        IRIS_LOADED = loaded;
    }

    private static boolean checkIrisShaders() {
        try {
            return IrisApi.getInstance().isShaderPackInUse();
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean checkIrisShouldDisable() {
        return !(IRIS_LOADED && checkIrisShaders());
    }
}
