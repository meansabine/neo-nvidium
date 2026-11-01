/*
 * Nvidium - High performance rendering engine for Minecraft
 * Copyright (C) 2023 cortex
 *
 * Modified by 1Influence (2025) - Ported to NeoForge.
 * Licensed under LGPL-3.0-only
 */

package me.cortex.nvidium.mixin.sodium;

import me.cortex.nvidium.Nvidium;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

@Mixin(value = net.caffeinemc.mods.sodium.client.gl.shader.ShaderLoader.class, remap = false)
public class MixinSodiumShaderLoader {
    @Inject(method = "getShaderSource", at = @At("HEAD"), cancellable = true)
    private static void onGetShaderSource(ResourceLocation name, CallbackInfoReturnable<String> cir) {
        if ("nvidium".equals(name.getNamespace())) {
            String path = String.format("/assets/%s/shaders/%s", name.getNamespace(), name.getPath());
            try (InputStream in = Nvidium.class.getResourceAsStream(path)) {
                if (in == null) {
                    throw new RuntimeException("Shader not found in Nvidium resources: " + path);
                }
                cir.setReturnValue(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                cir.cancel();
            } catch (IOException e) {
                throw new RuntimeException("Failed to read shader in Nvidium: " + path, e);
            }
        }
    }
}
