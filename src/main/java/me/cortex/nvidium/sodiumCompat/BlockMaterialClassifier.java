package me.cortex.nvidium.sodiumCompat;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.HalfTransparentBlock;
import net.minecraft.world.level.block.HoneyBlock;
import net.minecraft.world.level.block.IceBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.SlimeBlock;
import net.minecraft.world.level.block.StainedGlassBlock;
import net.minecraft.world.level.block.StainedGlassPaneBlock;
import net.minecraft.world.level.block.TransparentBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.WaterlilyBlock;
import net.minecraft.world.level.block.WebBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

import java.util.Locale;

/**
 * Classifies Minecraft blocks and fluids into a 5-bit material ID (0..31) stored in the upper
 * 5 bits of the material byte in {@link NvidiumCompactChunkVertex} (byte offset +6).
 *
 * <p>This allows Nvidium mesh and fragment shaders to know the exact semantic material of every
 * terrain and translucent quad without guessing from texture colors.
 */
public final class BlockMaterialClassifier {
    public static final int MAT_SOLID          = 0;
    public static final int MAT_WATER          = 1;
    public static final int MAT_LAVA           = 2;
    public static final int MAT_FOLIAGE_WAVING = 3;
    public static final int MAT_LEAVES         = 4;
    public static final int MAT_GLASS          = 5;
    public static final int MAT_ICE            = 6;
    public static final int MAT_EMISSIVE       = 7;
    public static final int MAT_METAL          = 8;
    public static final int MAT_GEM            = 9;
    public static final int MAT_POLISHED       = 10;
    public static final int MAT_WET            = 11;
    public static final int MAT_SAND_SNOW      = 12;
    public static final int MAT_PORTAL         = 13;
    public static final int MAT_SLIME_HONEY    = 14;
    public static final int MAT_ORE            = 15;

    private BlockMaterialClassifier() {}

    public static int classifyFluid(FluidState fluidState) {
        if (fluidState == null || fluidState.isEmpty()) {
            return MAT_SOLID;
        }
        if (fluidState.is(FluidTags.LAVA)) {
            return MAT_LAVA;
        }
        if (fluidState.is(FluidTags.WATER)) {
            return MAT_WATER;
        }
        ResourceLocation key = BuiltInRegistries.FLUID.getKey(fluidState.getType());
        if (key != null) {
            String path = key.getPath().toLowerCase(Locale.ROOT);
            if (path.contains("lava") || path.contains("magma") || path.contains("molten")) {
                return MAT_LAVA;
            }
        }
        return MAT_WATER;
    }

    public static int classifyBlock(BlockState state) {
        if (state == null) {
            return MAT_SOLID;
        }

        Block block = state.getBlock();

        // 1. Fluids and waterlogged/liquid blocks
        if (block == Blocks.WATER || block == Blocks.BUBBLE_COLUMN) {
            return MAT_WATER;
        }
        if (block == Blocks.LAVA || block == Blocks.MAGMA_BLOCK) {
            return MAT_LAVA;
        }
        if (block instanceof LiquidBlock) {
            FluidState fluid = state.getFluidState();
            if (!fluid.isEmpty()) {
                return classifyFluid(fluid);
            }
        }

        // 2. Portals
        if (block == Blocks.NETHER_PORTAL || block == Blocks.END_PORTAL || block == Blocks.END_GATEWAY) {
            return MAT_PORTAL;
        }

        // 3. Slime & Honey
        if (block instanceof SlimeBlock || block instanceof HoneyBlock || block == Blocks.HONEYCOMB_BLOCK) {
            return MAT_SLIME_HONEY;
        }

        // 4. Ice variants
        if (block instanceof IceBlock
                || state.is(BlockTags.ICE)
                || block == Blocks.ICE
                || block == Blocks.PACKED_ICE
                || block == Blocks.BLUE_ICE
                || block == Blocks.FROSTED_ICE) {
            return MAT_ICE;
        }

        // 5. Glass & Tinted/Stained Glass
        if (block instanceof TransparentBlock
                || block instanceof StainedGlassBlock
                || block instanceof StainedGlassPaneBlock
                || block == Blocks.GLASS
                || block == Blocks.GLASS_PANE
                || block == Blocks.TINTED_GLASS) {
            return MAT_GLASS;
        }

        // 6. Leaves
        if (block instanceof LeavesBlock || state.is(BlockTags.LEAVES)) {
            return MAT_LEAVES;
        }

        // 7. Waving Foliage / Plants / Crops / Flowers / Vines
        if (state.is(BlockTags.FLOWERS)
                || state.is(BlockTags.SMALL_FLOWERS)
                || state.is(BlockTags.TALL_FLOWERS)
                || state.is(BlockTags.SAPLINGS)
                || state.is(BlockTags.CROPS)
                || state.is(BlockTags.REPLACEABLE_BY_TREES)
                || state.is(BlockTags.CORAL_PLANTS)
                || block instanceof BushBlock
                || block instanceof VineBlock
                || block instanceof WaterlilyBlock
                || block instanceof WebBlock
                || block == Blocks.SUGAR_CANE
                || block == Blocks.KELP
                || block == Blocks.KELP_PLANT
                || block == Blocks.SEAGRASS
                || block == Blocks.TALL_SEAGRASS
                || block == Blocks.BAMBOO
                || block == Blocks.HANGING_ROOTS
                || block == Blocks.SPORE_BLOSSOM
                || block == Blocks.GLOW_LICHEN
                || block == Blocks.WEEPING_VINES
                || block == Blocks.WEEPING_VINES_PLANT
                || block == Blocks.TWISTING_VINES
                || block == Blocks.TWISTING_VINES_PLANT
                || block == Blocks.CAVE_VINES
                || block == Blocks.CAVE_VINES_PLANT) {
            if (state.getLightEmission() >= 6) {
                return MAT_EMISSIVE;
            }
            return MAT_FOLIAGE_WAVING;
        }

        // 8. Emissive blocks (torches, lanterns, glowstone, sea lantern, shroomlight, froglights, sculk, etc.)
        if (state.getLightEmission() >= 4
                || block == Blocks.GLOWSTONE
                || block == Blocks.SEA_LANTERN
                || block == Blocks.SHROOMLIGHT
                || block == Blocks.OCHRE_FROGLIGHT
                || block == Blocks.VERDANT_FROGLIGHT
                || block == Blocks.PEARLESCENT_FROGLIGHT
                || block == Blocks.CRYING_OBSIDIAN
                || block == Blocks.RESPAWN_ANCHOR
                || block == Blocks.BEACON
                || block == Blocks.END_ROD
                || block == Blocks.TORCH
                || block == Blocks.WALL_TORCH
                || block == Blocks.SOUL_TORCH
                || block == Blocks.SOUL_WALL_TORCH
                || block == Blocks.REDSTONE_TORCH
                || block == Blocks.REDSTONE_WALL_TORCH
                || block == Blocks.LANTERN
                || block == Blocks.SOUL_LANTERN
                || block == Blocks.JACK_O_LANTERN
                || block == Blocks.CAMPFIRE
                || block == Blocks.SOUL_CAMPFIRE
                || block == Blocks.FIRE
                || block == Blocks.SOUL_FIRE
                || block == Blocks.SCULK_CATALYST
                || block == Blocks.SCULK_SENSOR
                || block == Blocks.CALIBRATED_SCULK_SENSOR
                || block == Blocks.SCULK_SHRIEKER) {
            return MAT_EMISSIVE;
        }

        // 9. Path-based classification for Metals, Gems, Ores, Polished stones, Wet blocks, Sand/Snow
        ResourceLocation key = BuiltInRegistries.BLOCK.getKey(block);
        if (key != null) {
            String path = key.getPath().toLowerCase(Locale.ROOT);

            if (path.contains("glass") || path.contains("pane")) {
                if (!path.contains("wood") && !path.contains("iron_bars")) {
                    return MAT_GLASS;
                }
            }

            if (path.endsWith("_ore") || path.startsWith("raw_") || path.equals("ancient_debris") || path.contains("gilded_blackstone")) {
                return MAT_ORE;
            }

            if (path.contains("amethyst")
                    || path.contains("diamond_block")
                    || path.contains("emerald_block")
                    || path.contains("lapis_block")
                    || path.contains("redstone_block")
                    || path.contains("quartz")
                    || path.contains("purpur")
                    || path.contains("prismarine_bricks")
                    || path.contains("dark_prismarine")) {
                return MAT_GEM;
            }

            if (path.contains("iron_block")
                    || path.contains("gold_block")
                    || path.contains("netherite_block")
                    || path.contains("copper")
                    || path.contains("iron_bars")
                    || path.contains("iron_door")
                    || path.contains("iron_trapdoor")
                    || path.contains("heavy_weighted")
                    || path.contains("light_weighted")
                    || path.contains("anvil")
                    || path.contains("chain")
                    || path.contains("cauldron")
                    || path.contains("hopper")
                    || path.contains("lightning_rod")
                    || path.contains("bell")
                    || path.contains("lodestone")
                    || path.contains("rail")) {
                return MAT_METAL;
            }

            if (path.startsWith("polished_")
                    || path.startsWith("smooth_")
                    || path.contains("glazed_terracotta")
                    || path.contains("obsidian")
                    || path.contains("chiseled_")
                    || path.contains("stone_bricks")
                    || path.contains("deepslate_bricks")
                    || path.contains("deepslate_tiles")
                    || path.contains("nether_bricks")
                    || path.contains("end_stone_bricks")
                    || path.contains("mud_bricks")) {
                return MAT_POLISHED;
            }

            if (path.contains("mud")
                    || path.contains("clay")
                    || path.contains("slime")
                    || path.contains("wet_sponge")
                    || path.contains("sculk")
                    || path.contains("prismarine")) {
                return MAT_WET;
            }

            if (state.is(BlockTags.SAND)
                    || state.is(BlockTags.SNOW)
                    || path.contains("sand")
                    || path.contains("snow")
                    || path.contains("gravel")
                    || path.contains("concrete_powder")) {
                return MAT_SAND_SNOW;
            }
        }

        if (block instanceof HalfTransparentBlock) {
            return MAT_GLASS;
        }

        return MAT_SOLID;
    }
}
