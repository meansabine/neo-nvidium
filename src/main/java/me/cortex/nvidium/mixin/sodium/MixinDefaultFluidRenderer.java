package me.cortex.nvidium.mixin.sodium;

import me.cortex.nvidium.sodiumCompat.BlockMaterialClassifier;
import me.cortex.nvidium.sodiumCompat.NvidiumCompactChunkVertex;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildBuffers;
import net.caffeinemc.mods.sodium.client.render.chunk.translucent_sorting.TranslucentGeometryCollector;
import net.caffeinemc.mods.sodium.client.world.LevelSlice;
import net.caffeinemc.mods.sodium.neoforge.render.FluidRendererImpl;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = FluidRendererImpl.class, remap = false)
public class MixinDefaultFluidRenderer {
    @Inject(method = "render", at = @At("HEAD"))
    private void nvidium$onPreRenderFluid(
            LevelSlice level,
            BlockState blockState,
            FluidState fluidState,
            BlockPos blockPos,
            BlockPos offset,
            TranslucentGeometryCollector collector,
            ChunkBuildBuffers buffers,
            CallbackInfo ci) {
        NvidiumCompactChunkVertex.setCurrentMaterialId(BlockMaterialClassifier.classifyFluid(fluidState));
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void nvidium$onPostRenderFluid(
            LevelSlice level,
            BlockState blockState,
            FluidState fluidState,
            BlockPos blockPos,
            BlockPos offset,
            TranslucentGeometryCollector collector,
            ChunkBuildBuffers buffers,
            CallbackInfo ci) {
        NvidiumCompactChunkVertex.setCurrentMaterialId(BlockMaterialClassifier.MAT_SOLID);
    }
}
