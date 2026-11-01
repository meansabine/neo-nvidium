/*
 * Nvidium - High performance rendering engine for Minecraft
 * Copyright (C) 2023 cortex
 *
 * Modified by 1Influence (2025) - Ported to NeoForge.
 * Licensed under LGPL-3.0-only
 */

package me.cortex.nvidium.mixin.sodium;

import me.cortex.nvidium.Nvidium;
import net.caffeinemc.mods.sodium.client.gui.SodiumGameOptions;
import net.caffeinemc.mods.sodium.client.render.chunk.translucent_sorting.SortBehavior;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = SodiumGameOptions.DebugSettings.class, remap = false)
public class MixinSodiumGameOptionsDebugSettings {
    @Inject(method = "getSortBehavior", at = @At("HEAD"), cancellable = true)
    private void disableSodiumSortBehaviorWhenNvidiumEnabled(CallbackInfoReturnable<SortBehavior> cir) {
        if (Nvidium.IS_ENABLED) {
            cir.setReturnValue(SortBehavior.OFF);
        }
    }
}
