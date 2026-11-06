package me.cortex.nvidium.mixin.sodium;

import me.cortex.nvidium.sodiumCompat.BlockMaterialClassifier;
import me.cortex.nvidium.sodiumCompat.NvidiumCompactChunkVertex;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline.BlockRenderer;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = BlockRenderer.class, remap = false)
public class MixinBlockRenderer {
    @Inject(method = "renderModel", at = @At("HEAD"))
    private void nvidium$onPreRenderModel(BakedModel model, BlockState state, BlockPos pos, BlockPos origin, CallbackInfo ci) {
        NvidiumCompactChunkVertex.setCurrentMaterialId(BlockMaterialClassifier.classifyBlock(state));
    }

    @Inject(method = "renderModel", at = @At("RETURN"))
    private void nvidium$onPostRenderModel(BakedModel model, BlockState state, BlockPos pos, BlockPos origin, CallbackInfo ci) {
        NvidiumCompactChunkVertex.setCurrentMaterialId(BlockMaterialClassifier.MAT_SOLID);
    }
}
