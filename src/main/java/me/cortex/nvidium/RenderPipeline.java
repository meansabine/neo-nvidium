/*
 * Nvidium - High performance rendering engine for Minecraft
 * Copyright (C) 2023 cortex
 *
 * Modified by 1Influence (2025) - Ported to NeoForge.
 * Licensed under LGPL-3.0-only
 */

package me.cortex.nvidium;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import it.unimi.dsi.fastutil.ints.*;
import me.cortex.nvidium.api0.INvidiumShaderHook;
import me.cortex.nvidium.api0.NvidiumAPI;
import me.cortex.nvidium.config.StatisticsLoggingLevel;
import me.cortex.nvidium.config.TranslucencySortingLevel;
import me.cortex.nvidium.gl.RenderDevice;
import me.cortex.nvidium.gl.buffers.IDeviceMappedBuffer;
import me.cortex.nvidium.gl.shader.Shader;
import me.cortex.nvidium.managers.RegionManager;
import me.cortex.nvidium.managers.RegionVisibilityTracker;
import me.cortex.nvidium.managers.SectionManager;
import me.cortex.nvidium.renderers.*;
import me.cortex.nvidium.util.DownloadTaskStream;
import me.cortex.nvidium.util.TickableManager;
import me.cortex.nvidium.util.UploadingBufferStream;

import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import org.joml.*;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL12C;
import org.lwjgl.opengl.GL33;
import org.lwjgl.opengl.NVMeshShader;
import org.lwjgl.system.MemoryUtil;

import java.lang.Math;
import java.util.BitSet;
import java.util.List;

import static me.cortex.nvidium.gl.buffers.PersistentSparseAddressableBuffer.alignUp;
import static org.lwjgl.opengl.ARBDirectStateAccess.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL30C.GL_R8UI;
import static org.lwjgl.opengl.GL30C.GL_RED_INTEGER;
import static org.lwjgl.opengl.GL42.*;
import static org.lwjgl.opengl.GL43C.GL_SHADER_STORAGE_BARRIER_BIT;
import static org.lwjgl.opengl.NVRepresentativeFragmentTest.GL_REPRESENTATIVE_FRAGMENT_TEST_NV;
import static org.lwjgl.opengl.NVShaderBufferStore.GL_SHADER_GLOBAL_ACCESS_BARRIER_BIT_NV;
import static org.lwjgl.opengl.NVUniformBufferUnifiedMemory.GL_UNIFORM_BUFFER_ADDRESS_NV;
import static org.lwjgl.opengl.NVUniformBufferUnifiedMemory.GL_UNIFORM_BUFFER_UNIFIED_NV;
import static org.lwjgl.opengl.NVVertexBufferUnifiedMemory.*;

public class RenderPipeline {
    public static final int GL_DRAW_INDIRECT_UNIFIED_NV = 0x8F40;
    public static final int GL_DRAW_INDIRECT_ADDRESS_NV = 0x8F41;

    private final RenderDevice device;
    private final UploadingBufferStream uploadStream;
    private final DownloadTaskStream downloadStream;

    private final SectionManager sectionManager;

    public final RegionVisibilityTracker regionVisibilityTracking;

    private PrimaryTerrainRasterizer terrainRasterizer;
    private RegionRasterizer regionRasterizer;
    private SectionRasterizer sectionRasterizer;
    private TemporalTerrainRasterizer temporalRasterizer;
    private TranslucentTerrainRasterizer translucencyTerrainRasterizer;
    private SortRegionSectionPhase regionSectionSorter;

    private final IDeviceMappedBuffer sceneUniform;
    private static final int SCENE_SIZE = (int) alignUp(4*4*4+4*4+4*4+4+4*4+4*4+8*8+3*4+3+4+8+8+(4*4*4), 2);

    private final IDeviceMappedBuffer regionVisibility;
    private final IDeviceMappedBuffer sectionVisibility;
    private final IDeviceMappedBuffer terrainCommandBuffer;
    private final IDeviceMappedBuffer shadowCommandBuffer;
    private final IDeviceMappedBuffer translucencyCommandBuffer;
    private final IDeviceMappedBuffer regionSortingList;
    private final IDeviceMappedBuffer statisticsBuffer;
    private final IDeviceMappedBuffer transformationArray;
    private final IDeviceMappedBuffer originOffsetArray;

    private final BitSet regionVisibilityTracker;

    //Set of regions that need to be sorted
    private final IntSet regionsToSort = new IntOpenHashSet();

    private static final class Statistics {
        public int frustumCount;
        public int regionCount;
        public int sectionCount;
        public int quadCount;
    }

    private final Statistics stats;

    public RenderPipeline(RenderDevice device, UploadingBufferStream uploadStream, DownloadTaskStream downloadStream, SectionManager sectionManager) {
        this.device = device;
        this.uploadStream = uploadStream;
        this.downloadStream = downloadStream;
        this.sectionManager = sectionManager;
        this.compiledForFog = Nvidium.config.render_fog;

        terrainRasterizer = new PrimaryTerrainRasterizer();
        regionRasterizer = new RegionRasterizer();
        sectionRasterizer = new SectionRasterizer();
        temporalRasterizer = new TemporalTerrainRasterizer();
        translucencyTerrainRasterizer = new TranslucentTerrainRasterizer();
        regionSectionSorter = new SortRegionSectionPhase();

        int maxRegions = sectionManager.getRegionManager().maxRegions();

        sceneUniform = device.createDeviceOnlyMappedBuffer(SCENE_SIZE + maxRegions*2L);
        regionVisibility = device.createDeviceOnlyMappedBuffer(maxRegions);
        sectionVisibility = device.createDeviceOnlyMappedBuffer(maxRegions * 256L);
        terrainCommandBuffer = device.createDeviceOnlyMappedBuffer(maxRegions*8L);
        shadowCommandBuffer = device.createDeviceOnlyMappedBuffer(maxRegions*8L);
        translucencyCommandBuffer = device.createDeviceOnlyMappedBuffer(maxRegions*8L);
        regionSortingList = device.createDeviceOnlyMappedBuffer(maxRegions*2L);
        this.transformationArray = device.createDeviceOnlyMappedBuffer(RegionManager.MAX_TRANSFORMATION_COUNT * (4*4*4));
        this.originOffsetArray = device.createDeviceOnlyMappedBuffer(RegionManager.MAX_TRANSFORMATION_COUNT * 8);

        regionVisibilityTracker = new BitSet(maxRegions);
        regionVisibilityTracking = new RegionVisibilityTracker(downloadStream, maxRegions);

        statisticsBuffer = device.createDeviceOnlyMappedBuffer(4*4);
        stats = new Statistics();


        //Initialize the transformationArray buffer to the identity affine transform
        {
            long ptr = this.uploadStream.upload(this.transformationArray, 0, RegionManager.MAX_TRANSFORMATION_COUNT * (4*4*4));
            var transform = new Matrix4f().identity();
            for (int i = 0; i < RegionManager.MAX_TRANSFORMATION_COUNT; i++) {
                transform.getToAddress(ptr);
                ptr += 4*4*4;
            }
        }
        //Clear the origin offset
        nglClearNamedBufferData(this.originOffsetArray.getId(), GL_R8UI, GL_RED_INTEGER, GL_UNSIGNED_BYTE, 0);


    }

    public void setTransformation(int id, Matrix4fc transform) {
        if (id < 0 || id >= RegionManager.MAX_TRANSFORMATION_COUNT) {
            throw new IllegalArgumentException("Id out of bounds: " + id);
        }
        long ptr = this.uploadStream.upload(this.transformationArray, id * (4*4*4), 4*4*4);
        transform.getToAddress(ptr);
    }

    public void setOrigin(int id, int x, int y, int z) {
        if (id < 0 || id >= RegionManager.MAX_TRANSFORMATION_COUNT) {
            throw new IllegalArgumentException("Id out of bounds: " + id);
        }
        long ptr = this.uploadStream.upload(this.originOffsetArray, id * 8, 8);
        long pos = 0;
        pos |= x&0x1ffffff;
        pos |= ((long)(z&0x1ffffff))<<25;
        pos |= ((long)(y&0x3fff))<<50;

        MemoryUtil.memPutLong(ptr, pos);
    }

    private int prevRegionCount;
    private int shadowRegionCount;
    private int frameId;
    private boolean compiledForFog = false;
    private final Vector3f cameraChunkDelta = new Vector3f();
    private final Matrix4f mainTerrainMvp = new Matrix4f();
    private int shadowBlockSampler = 0;

    private void ensureShadowBlockSampler() {
        if (shadowBlockSampler == 0) {
            shadowBlockSampler = GL33.glGenSamplers();
            GL33.glSamplerParameteri(shadowBlockSampler, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_NEAREST_MIPMAP_LINEAR);
            GL33.glSamplerParameteri(shadowBlockSampler, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_NEAREST);
            GL33.glSamplerParameteri(shadowBlockSampler, GL12C.GL_TEXTURE_MIN_LOD, 0);
            GL33.glSamplerParameteri(shadowBlockSampler, GL12C.GL_TEXTURE_MAX_LOD, 4);
        }
    }

    private void rasterizeTerrainDepth(Shader depthShader, Matrix4fc cameraRelativeLightVP) {
        if (shadowRegionCount <= 0 || depthShader == null) {
            return;
        }
        ensureShadowBlockSampler();

        // Update MVP in sceneUniform (bytes 0..64) with lightVP translated by subchunk camera delta
        Matrix4f shadowMvp = new Matrix4f(cameraRelativeLightVP).translate(cameraChunkDelta);
        long addr = uploadStream.upload(sceneUniform, 0, 4 * 4 * 4);
        shadowMvp.getToAddress(addr);
        uploadStream.commit();
        glMemoryBarrier(GL_UNIFORM_BARRIER_BIT | GL_BUFFER_UPDATE_BARRIER_BIT);

        glBufferAddressRangeNV(GL_UNIFORM_BUFFER_ADDRESS_NV, 0, sceneUniform.getDeviceAddress(), SCENE_SIZE);
        glBufferAddressRangeNV(GL_DRAW_INDIRECT_ADDRESS_NV, 0, shadowCommandBuffer.getDeviceAddress(), shadowRegionCount * 8L);

        depthShader.bind();

        AbstractTexture blockAtlas = Minecraft.getInstance().getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS);
        GlStateManager._activeTexture(GL33.GL_TEXTURE0);
        GlStateManager._bindTexture(blockAtlas.getId());
        GL33.glBindSampler(0, shadowBlockSampler);

        NVMeshShader.glMultiDrawMeshTasksIndirectNV(0, shadowRegionCount, 0);

        GL33.glBindSampler(0, 0);

        // Restore main camera MVP in sceneUniform
        long restoreAddr = uploadStream.upload(sceneUniform, 0, 4 * 4 * 4);
        mainTerrainMvp.getToAddress(restoreAddr);
        uploadStream.commit();
        glMemoryBarrier(GL_UNIFORM_BARRIER_BIT | GL_BUFFER_UPDATE_BARRIER_BIT);
        glBufferAddressRangeNV(GL_UNIFORM_BUFFER_ADDRESS_NV, 0, sceneUniform.getDeviceAddress(), SCENE_SIZE);
    }

    //TODO FIXME: regions that where in frustum but are now out of frustum must have the visibility data cleared
    // this is due to funny issue of pain where the section was "visible" last frame cause it didnt get ticked
    public void renderFrame(Viewport frustum, ChunkRenderMatrices crm, double px, double py, double pz) {//NOTE: can use any of the command list rendering commands to basicly draw X indirects using the same shader, thus allowing for terrain to be rendered very efficently

        if (sectionManager.getRegionManager().regionCount() == 0) {
            return;
        }

        INvidiumShaderHook hook = NvidiumAPI.getShaderHook();
        if (hook != null) {
            hook.onSetupFrame(frustum, crm, px, py, pz);
        }

        final int DEBUG_RENDER_LEVEL = 0;//0: no debug, 1: region debug, 2: section debug
        final boolean WRITE_DEPTH = false;

        Vector3i blockPos = new Vector3i(((int)Math.floor(px)), ((int)Math.floor(py)), ((int)Math.floor(pz)));
        Vector3i chunkPos = new Vector3i(blockPos.x>>4,blockPos.y>>4,blockPos.z>>4);

        int screenWidth = Minecraft.getInstance().getWindow().getScreenWidth();
        int screenHeight = Minecraft.getInstance().getWindow().getScreenHeight();

        int visibleRegions = 0;

        long queryAddr = 0;
        var rm = sectionManager.getRegionManager();
        short[] regionMap;
        //Enqueue all the visible regions
        {

            //The region data indicies is located at the end of the sceneUniform
            IntSortedSet regions = new IntAVLTreeSet();
            IntArrayList shadowRegionList = hook != null ? new IntArrayList() : null;
            for (int i = 0; i < rm.maxRegionIndex(); i++) {
                if (!rm.regionExists(i)) continue;
                if ((Nvidium.config.region_keep_distance != 256 && Nvidium.config.region_keep_distance != 32) && !rm.withinSquare(Nvidium.config.region_keep_distance+4, i, chunkPos.x, chunkPos.y, chunkPos.z)) {
                    removeRegion(i);
                    continue;
                }

                // Collect all nearby regions (360 degrees around the player) for the shadow pass, independent of camera frustum
                if (shadowRegionList != null && rm.withinSquare(22, i, chunkPos.x, chunkPos.y, chunkPos.z)) {
                    int secCount = rm.getRegionSectionCount(i);
                    if (secCount > 0) {
                        shadowRegionList.add((secCount << 16) | (i & 0xFFFF));
                    }
                }

                if (rm.isRegionVisible(frustum, i)) {
                    //Note, its sorted like this because of overdraw, also the translucency command buffer is written to
                    // in a reverse order to this in the section_raster/task.glsl shader
                    regions.add(((rm.distance(i, chunkPos.x, chunkPos.y, chunkPos.z))<<16)|i);
                    visibleRegions++;
                    regionVisibilityTracker.set(i);

                    if (rm.isRegionInACameraAxis(i, px, py, pz)) {
                        regionsToSort.add(i);
                    }

                } else {
                    if (regionVisibilityTracker.get(i)) {//Going from visible to non visible
                        //Clear the visibility bits
                        if (Nvidium.config.enable_temporal_coherence) {
                            nglClearNamedBufferSubData(sectionVisibility.getId(), GL_R8UI, (long) i << 8, 255, GL_RED_INTEGER, GL_UNSIGNED_BYTE, 0);
                        }
                    }
                    regionVisibilityTracker.clear(i);
                }

            }

            regionMap = new short[regions.size()];
            if (visibleRegions == 0) {
                prevRegionCount = 0;
                shadowRegionCount = 0;
                if (hook != null) {
                    hook.onPreRenderTerrain(0, 0);
                    hook.onPostRenderTerrain();
                }
                return;
            }
            if (shadowRegionList != null && !shadowRegionList.isEmpty()) {
                shadowRegionCount = shadowRegionList.size();
                long shadowCmdUpload = uploadStream.upload(shadowCommandBuffer, 0, (long) shadowRegionCount * 8L);
                for (int k = 0; k < shadowRegionCount; k++) {
                    int packed = shadowRegionList.getInt(k);
                    int secCount = packed >>> 16;
                    int regId = packed & 0xFFFF;
                    MemoryUtil.memPutInt(shadowCmdUpload + ((long) k << 3), secCount);
                    MemoryUtil.memPutInt(shadowCmdUpload + ((long) k << 3) + 4, regId << 8);
                }
            } else {
                shadowRegionCount = 0;
            }
            long addr = uploadStream.upload(sceneUniform, SCENE_SIZE, visibleRegions*2);
            queryAddr = addr;//This is ungodly hacky
            int j = 0;
            for (int i : regions) {
                regionMap[j] = (short) i;
                MemoryUtil.memPutShort(addr+((long) j <<1), (short) i);
                j++;
            }

            if (Nvidium.config.statistics_level != StatisticsLoggingLevel.NONE) {
                stats.frustumCount = regions.size();
            }
        }

        {
            Vector3f delta = new Vector3f((float) (px-(chunkPos.x<<4)), (float) (py-(chunkPos.y<<4)), (float) (pz-(chunkPos.z<<4)));
            delta.negate();
            this.cameraChunkDelta.set(delta);
            long addr = uploadStream.upload(sceneUniform, 0, SCENE_SIZE);
            this.mainTerrainMvp.set(crm.projection())
                    .mul(crm.modelView())
                    .translate(delta);//Translate the subchunk position
            this.mainTerrainMvp.getToAddress(addr);
            addr += 4*4*4;
            if (this.compiledForFog) {
                new Matrix4f(crm.projection())
                        .mul(crm.modelView())
                        .invert()
                        .getToAddress(addr);
                addr += 4*4*4;
            }
            new Vector4i(chunkPos.x, chunkPos.y, chunkPos.z, 0).getToAddress(addr);//Chunk the camera is in
            addr += 16;
            new Vector4f(delta,0).getToAddress(addr);//Subchunk offset (note, delta is already negated)
            addr += 16;
            new Vector4f(RenderSystem.getShaderFogColor()).getToAddress(addr);
            addr += 16;
            MemoryUtil.memPutLong(addr, sceneUniform.getDeviceAddress() + SCENE_SIZE);//Put in the location of the region indexs
            addr += 8;
            MemoryUtil.memPutLong(addr, sectionManager.getRegionManager().getRegionBufferAddress());
            addr += 8;
            MemoryUtil.memPutLong(addr, sectionManager.getRegionManager().getSectionBufferAddress());
            addr += 8;
            MemoryUtil.memPutLong(addr, regionVisibility.getDeviceAddress());
            addr += 8;
            MemoryUtil.memPutLong(addr, sectionVisibility.getDeviceAddress());
            addr += 8;
            MemoryUtil.memPutLong(addr, terrainCommandBuffer.getDeviceAddress());
            addr += 8;
            MemoryUtil.memPutLong(addr, translucencyCommandBuffer.getDeviceAddress());
            addr += 8;
            MemoryUtil.memPutLong(addr, regionSortingList.getDeviceAddress());
            addr += 8;
            MemoryUtil.memPutLong(addr, sectionManager.terrainAreana.buffer.getDeviceAddress());
            addr += 8;
            MemoryUtil.memPutLong(addr, this.transformationArray.getDeviceAddress());
            addr += 8;
            MemoryUtil.memPutLong(addr, this.originOffsetArray.getDeviceAddress());
            addr += 8;
            MemoryUtil.memPutLong(addr, statisticsBuffer == null?0:statisticsBuffer.getDeviceAddress());//Logging buffer
            addr += 8;
            //Convert it into the expected size values and floats
            MemoryUtil.memPutFloat(addr, ((float)screenWidth)/2);
            addr += 4;
            MemoryUtil.memPutFloat(addr, ((float)screenHeight)/2);
            addr += 4;
            MemoryUtil.memPutFloat(addr, RenderSystem.getShaderFogStart());//FogStart
            addr += 4;
            MemoryUtil.memPutFloat(addr, RenderSystem.getShaderFogEnd());//FogEnd
            addr += 4;
            MemoryUtil.memPutInt(addr, RenderSystem.getShaderFogShape().ordinal());//IsSphericalFog
            addr += 4;
            MemoryUtil.memPutShort(addr, (short) visibleRegions);
            addr += 2;
            MemoryUtil.memPutByte(addr, (byte) (frameId++));
        }

        if (Nvidium.config.translucency_sorting_level == TranslucencySortingLevel.NONE) {
            regionsToSort.clear();
        }

        int regionSortSize = this.regionsToSort.size();

        if (regionSortSize != 0){
            long regionSortUpload = uploadStream.upload(regionSortingList, 0, regionSortSize * 2);
            for (int region : regionsToSort) {
                MemoryUtil.memPutShort(regionSortUpload, (short) region);
                regionSortUpload += 2;
            }
            regionsToSort.clear();
        }

        sectionManager.commitChanges();//Commit all uploads done to the terrain and meta data
        uploadStream.commit();

        TickableManager.TickAll();

        glEnableClientState(GL_UNIFORM_BUFFER_UNIFIED_NV);
        glEnableClientState(GL_VERTEX_ATTRIB_ARRAY_UNIFIED_NV);
        glEnableClientState(GL_ELEMENT_ARRAY_UNIFIED_NV);
        glEnableClientState(GL_DRAW_INDIRECT_UNIFIED_NV);
        //Bind the uniform, it doesnt get wiped between shader changes
        glBufferAddressRangeNV(GL_UNIFORM_BUFFER_ADDRESS_NV, 0, sceneUniform.getDeviceAddress(), SCENE_SIZE);

        if (hook != null && shadowRegionCount != 0) {
            final int shadowRegions = shadowRegionCount;
            final long shadowCmdAddr = shadowCommandBuffer.getDeviceAddress();
            hook.onShadowPass(new INvidiumShaderHook.ShadowPassContext() {
                @Override
                public int regionCount() {
                    return shadowRegions;
                }

                @Override
                public long commandBufferAddress() {
                    return shadowCmdAddr;
                }

                @Override
                public void drawOpaqueTerrainDepth(Shader depthShader, Matrix4fc cameraRelativeLightVP) {
                    rasterizeTerrainDepth(depthShader, cameraRelativeLightVP);
                }
            });
            glBufferAddressRangeNV(GL_UNIFORM_BUFFER_ADDRESS_NV, 0, sceneUniform.getDeviceAddress(), SCENE_SIZE);
        }

        if (hook != null) {
            hook.onPreRenderTerrain(prevRegionCount, terrainCommandBuffer.getDeviceAddress());
        }

        if (prevRegionCount != 0) {
            glEnable(GL_DEPTH_TEST);
            terrainRasterizer.raster(prevRegionCount, terrainCommandBuffer.getDeviceAddress());
            glMemoryBarrier(GL_FRAMEBUFFER_BARRIER_BIT);
        }

        //NOTE: For GL_REPRESENTATIVE_FRAGMENT_TEST_NV to work, depth testing must be disabled, or depthMask = false
        glEnable(GL_DEPTH_TEST);
        glDepthFunc(GL_LEQUAL);
        glDepthMask(false);
        if (DEBUG_RENDER_LEVEL == 1 && WRITE_DEPTH) {
            glDepthMask(true);
        }
        if (DEBUG_RENDER_LEVEL != 1) {
            glColorMask(false, false, false, false);
        }
        if (DEBUG_RENDER_LEVEL == 0)
        {
            glEnable(GL_REPRESENTATIVE_FRAGMENT_TEST_NV);
        }

        regionRasterizer.raster(visibleRegions);

        if (DEBUG_RENDER_LEVEL == 1) {
            glColorMask(false, false, false, false);
        }

        //glMemoryBarrier(GL_SHADER_GLOBAL_ACCESS_BARRIER_BIT_NV);
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);

        //glColorMask(true, true, true, true);

        if (DEBUG_RENDER_LEVEL == 2) {
            glColorMask(true, true, true, true);
        }
        if (DEBUG_RENDER_LEVEL == 2 && WRITE_DEPTH) {
            glDepthMask(true);
        }

        sectionRasterizer.raster(visibleRegions);
        glDisable(GL_REPRESENTATIVE_FRAGMENT_TEST_NV);
        glDepthMask(true);
        glColorMask(true, true, true, true);

        //glMemoryBarrier(GL_SHADER_GLOBAL_ACCESS_BARRIER_BIT_NV);
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);

        prevRegionCount = visibleRegions;

        //Do temporal rasterization
        if (Nvidium.config.enable_temporal_coherence) {
            glMemoryBarrier(GL_COMMAND_BARRIER_BIT);
            temporalRasterizer.raster(visibleRegions, terrainCommandBuffer.getDeviceAddress());
        }


        {//Do proper visibility tracking
            glDepthMask(false);
            glColorMask(false, false, false, false);
            glEnable(GL_REPRESENTATIVE_FRAGMENT_TEST_NV);

            regionVisibilityTracking.computeVisibility(visibleRegions, regionVisibility, regionMap);

            glDisable(GL_REPRESENTATIVE_FRAGMENT_TEST_NV);
            glDepthMask(true);
            glColorMask(true, true, true, true);
        }



        if (regionSortSize != 0) {
            glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
            regionSectionSorter.dispatch(regionSortSize);
            glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
        }

        glDisableClientState(GL_UNIFORM_BUFFER_UNIFIED_NV);
        glDisableClientState(GL_VERTEX_ATTRIB_ARRAY_UNIFIED_NV);
        glDisableClientState(GL_ELEMENT_ARRAY_UNIFIED_NV);
        glDisableClientState(GL_DRAW_INDIRECT_UNIFIED_NV);
        glDepthFunc(GL11C.GL_LEQUAL);
        glDisable(GL_DEPTH_TEST);
        if (hook != null) {
            hook.onPostRenderTerrain();
        }
    }

    void enqueueRegionSort(int regionId) {
        this.regionsToSort.add(regionId);
    }

    private void removeRegion(int id) {
        sectionManager.removeRegionById(id);
        regionVisibilityTracking.resetRegion(id);
    }

    public void removeARegion() {
        removeRegion(regionVisibilityTracking.findMostLikelyLeastSeenRegion(sectionManager.getRegionManager().maxRegionIndex()));
    }

    //Translucency is rendered in a very cursed and incorrect way
    // it hijacks the unassigned indirect command dispatch and uses that to dispatch the translucent chunks as well
    public void renderTranslucent() {
        glEnableClientState(GL_UNIFORM_BUFFER_UNIFIED_NV);
        glEnableClientState(GL_VERTEX_ATTRIB_ARRAY_UNIFIED_NV);
        glEnableClientState(GL_ELEMENT_ARRAY_UNIFIED_NV);
        glEnableClientState(GL_DRAW_INDIRECT_UNIFIED_NV);
        //Need to rebind the uniform since it might have been wiped
        glBufferAddressRangeNV(GL_UNIFORM_BUFFER_ADDRESS_NV, 0, sceneUniform.getDeviceAddress(), SCENE_SIZE);

        INvidiumShaderHook hook = NvidiumAPI.getShaderHook();
        if (hook != null) {
            hook.onPreRenderTranslucent(prevRegionCount, translucencyCommandBuffer.getDeviceAddress());
        }

        //Translucency sorting
        if (prevRegionCount != 0) {
            glEnable(GL_DEPTH_TEST);
            RenderSystem.enableBlend();
            RenderSystem.blendFuncSeparate(
                    GlStateManager.SourceFactor.SRC_ALPHA,
                    GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                    GlStateManager.SourceFactor.ONE,
                    GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA
            );
            translucencyTerrainRasterizer.raster(prevRegionCount, translucencyCommandBuffer.getDeviceAddress());
            RenderSystem.disableBlend();
            RenderSystem.defaultBlendFunc();
            glDisable(GL_DEPTH_TEST);
        }

        if (hook != null) {
            hook.onPostRenderTranslucent();
        }

        glDisableClientState(GL_UNIFORM_BUFFER_UNIFIED_NV);
        glDisableClientState(GL_VERTEX_ATTRIB_ARRAY_UNIFIED_NV);
        glDisableClientState(GL_ELEMENT_ARRAY_UNIFIED_NV);
        glDisableClientState(GL_DRAW_INDIRECT_UNIFIED_NV);




        //Download statistics
        if (Nvidium.config.statistics_level.ordinal() > StatisticsLoggingLevel.FRUSTUM.ordinal()){
            downloadStream.download(statisticsBuffer, 0, 4*4, (addr)-> {
                stats.regionCount = MemoryUtil.memGetInt(addr);
                stats.sectionCount = MemoryUtil.memGetInt(addr+4);
                stats.quadCount = MemoryUtil.memGetInt(addr+8);
            });
        }


        if (Nvidium.config.statistics_level.ordinal() > StatisticsLoggingLevel.FRUSTUM.ordinal()) {
            //glMemoryBarrier(GL_ALL_BARRIER_BITS);
            //Stupid bloody nvidia not following spec forcing me to use a upload stream
            long upload = this.uploadStream.upload(statisticsBuffer, 0, 4*4);
            MemoryUtil.memSet(upload, 0, 4*4);
            //glClearNamedBufferSubData(statisticsBuffer.getId(), GL_R32UI, 0, 4 * 4, GL_RED_INTEGER, GL_UNSIGNED_INT, new int[]{0});
        }
    }

    public void delete() {
        regionVisibilityTracking.delete();

        sceneUniform.delete();
        regionVisibility.delete();
        sectionVisibility.delete();
        terrainCommandBuffer.delete();
        shadowCommandBuffer.delete();
        translucencyCommandBuffer.delete();
        regionSortingList.delete();

        terrainRasterizer.delete();
        regionRasterizer.delete();
        sectionRasterizer.delete();
        temporalRasterizer.delete();
        translucencyTerrainRasterizer.delete();
        regionSectionSorter.delete();
        this.transformationArray.delete();
        this.originOffsetArray.delete();

        if (shadowBlockSampler != 0) {
            GL33.glDeleteSamplers(shadowBlockSampler);
            shadowBlockSampler = 0;
        }

        if (statisticsBuffer != null) {
            statisticsBuffer.delete();
        }
    }

    public void addDebugInfo(List<String> info) {
        if (Nvidium.config.statistics_level != StatisticsLoggingLevel.NONE) {
            StringBuilder builder = new StringBuilder();
            builder.append("Statistics: ");
            if (Nvidium.config.statistics_level.ordinal() >=  StatisticsLoggingLevel.FRUSTUM.ordinal()) {
                builder.append("F: ").append(stats.frustumCount);
            }
            if (Nvidium.config.statistics_level.ordinal() >=  StatisticsLoggingLevel.REGIONS.ordinal()) {
                builder.append(", R: ").append(stats.regionCount);
            }
            if (Nvidium.config.statistics_level.ordinal() >=  StatisticsLoggingLevel.SECTIONS.ordinal()) {
                builder.append(", S: ").append(stats.sectionCount);
            }
            if (Nvidium.config.statistics_level.ordinal() >=  StatisticsLoggingLevel.QUADS.ordinal()) {
                builder.append(", Q: ").append(stats.quadCount);
            }
            info.addAll(List.of(builder.toString().split("\n")));
        }
    }

    public void reloadShaders() {
        this.compiledForFog = Nvidium.config.render_fog;
        terrainRasterizer.delete();
        regionRasterizer.delete();
        sectionRasterizer.delete();
        temporalRasterizer.delete();
        translucencyTerrainRasterizer.delete();
        regionSectionSorter.delete();

        terrainRasterizer = new PrimaryTerrainRasterizer();
        regionRasterizer = new RegionRasterizer();
        sectionRasterizer = new SectionRasterizer();
        temporalRasterizer = new TemporalTerrainRasterizer();
        translucencyTerrainRasterizer = new TranslucentTerrainRasterizer();
        regionSectionSorter = new SortRegionSectionPhase();
    }
}
