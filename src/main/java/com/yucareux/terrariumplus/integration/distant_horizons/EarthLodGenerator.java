package com.yucareux.terrariumplus.integration.distant_horizons;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiDistantGeneratorMode;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiWorldGeneratorReturnType;
import com.seibel.distanthorizons.api.interfaces.block.IDhApiBiomeWrapper;
import com.seibel.distanthorizons.api.interfaces.block.IDhApiBlockStateWrapper;
import com.seibel.distanthorizons.api.interfaces.override.worldGenerator.IDhApiWorldGenerator;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import com.seibel.distanthorizons.api.objects.data.DhApiTerrainDataPoint;
import com.seibel.distanthorizons.api.objects.data.IDhApiFullDataSource;
import com.yucareux.terrariumplus.Terrarium;
import com.yucareux.terrariumplus.world.data.cover.TerrariumLandCoverSource;
import com.yucareux.terrariumplus.world.data.elevation.TerrariumElevationSource;
import com.yucareux.terrariumplus.worldgen.EarthChunkGenerator;
import com.yucareux.terrariumplus.worldgen.EarthGeneratorSettings;
import com.yucareux.terrariumplus.worldgen.WaterSurfaceResolver;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

public final class EarthLodGenerator implements IDhApiWorldGenerator {
    private static final int SURFACE_DEPTH = 4;

    private final IDhApiLevelWrapper levelWrapper;
    private final EarthGeneratorSettings settings;
    private final TerrariumElevationSource elevationSource;
    private final TerrariumLandCoverSource landCoverSource;
    private final WaterSurfaceResolver waterResolver;

    private final ThreadLocal<WrapperCache> wrapperCache;

    public EarthLodGenerator(
            final IDhApiLevelWrapper levelWrapper,
            final EarthGeneratorSettings settings,
            final TerrariumElevationSource elevationSource,
            final TerrariumLandCoverSource landCoverSource,
            final WaterSurfaceResolver waterResolver
    ) {
        this.levelWrapper = levelWrapper;
        this.settings = settings;
        this.elevationSource = elevationSource;
        this.landCoverSource = landCoverSource;
        this.waterResolver = waterResolver;
        this.wrapperCache = ThreadLocal.withInitial(() -> new WrapperCache(levelWrapper));
    }

    @Override
    public void preGeneratorTaskStart() {
    }

    @Override
    public byte getLargestDataDetailLevel() {
        return 24;
    }

    @Override
    public CompletableFuture<Void> generateLod(
            final int chunkPosMinX,
            final int chunkPosMinZ,
            final int lodPosX,
            final int lodPosZ,
            final byte detailLevel,
            final IDhApiFullDataSource pooledFullDataSource,
            final EDhApiDistantGeneratorMode generatorMode,
            final ExecutorService worldGeneratorThreadPool,
            final Consumer<IDhApiFullDataSource> resultConsumer
    ) {
        return CompletableFuture.runAsync(() -> {
            buildLod(pooledFullDataSource, chunkPosMinX, chunkPosMinZ, detailLevel);
            resultConsumer.accept(pooledFullDataSource);
        }, worldGeneratorThreadPool);
    }

    private void buildLod(final IDhApiFullDataSource output, final int chunkX, final int chunkZ, final byte detailLevel) {
        final WrapperCache wrappers = wrapperCache.get();

        final int minY = levelWrapper.getMinHeight();
        final int maxY = minY + levelWrapper.getMaxHeight();
        final int absoluteTop = maxY - minY;

        final int lodSizePoints = output.getWidthInDataColumns();
        final int blockSize = 1 << detailLevel;
        final int lodSizeBlocks = lodSizePoints * blockSize;

        final int worldOriginX = SectionPos.sectionToBlockCoord(chunkX);
        final int worldOriginZ = SectionPos.sectionToBlockCoord(chunkZ);

        final List<DhApiTerrainDataPoint> columnDataPoints = new ArrayList<>();

        for (int z = 0; z < lodSizePoints; z++) {
            for (int x = 0; x < lodSizePoints; x++) {
                int worldX = worldOriginX + (x * blockSize);
                int worldZ = worldOriginZ + (z * blockSize);

                int coverClass = landCoverSource.sampleCoverClass(worldX, worldZ, settings.worldScale());
                WaterSurfaceResolver.WaterInfo waterInfo = waterResolver.resolveFastWaterInfo(worldX, worldZ, coverClass);

                int surface = sampleSurfaceHeight(worldX, worldZ);
                if (settings.cinematicMode()) {
                    surface = resolveCinematicSurface(surface, waterInfo.surface(), waterInfo.isWater());
                }

                int waterSurface = waterInfo.surface();
                boolean hasWater = waterInfo.isWater();
                boolean underwater = hasWater && waterSurface > surface;

                Holder<Biome> biome = getNoiseBiome(worldX, surface, worldZ);
                IDhApiBiomeWrapper biomeWrapper = wrappers.getBiome(biome);

                SurfacePalette palette = selectSurfacePalette(biome, worldX, worldZ);
                BlockState topState = palette != null ? (underwater ? palette.underwaterTop : palette.top) : Blocks.STONE.defaultBlockState();
                IDhApiBlockStateWrapper topWrapper = wrappers.getBlockState(topState);

                int relativeSurface = Mth.clamp(surface - minY + 1, 0, absoluteTop);
                int lastLayerTop = 0;

                // Add solid ground/water logic
                if (hasWater && waterSurface > surface) {
                    // Solid ground under water
                    if (relativeSurface > 0) {
                        columnDataPoints.add(DhApiTerrainDataPoint.create(
                                detailLevel, 0, 0, 0, relativeSurface, topWrapper, biomeWrapper
                        ));
                        lastLayerTop = relativeSurface;
                    }

                    int relativeWater = Mth.clamp(waterSurface - minY + 1, 0, absoluteTop);
                    if (relativeWater > relativeSurface) {
                        IDhApiBlockStateWrapper waterWrapper = wrappers.getBlockState(Blocks.WATER.defaultBlockState());
                        // Water layer (full sky light)
                        columnDataPoints.add(DhApiTerrainDataPoint.create(
                                detailLevel, 0, 15, relativeSurface, relativeWater, waterWrapper, biomeWrapper
                        ));
                        lastLayerTop = relativeWater;
                    }
                } else {
                    // Just solid ground
                    if (relativeSurface > 0) {
                        // Subsurface
                        if (relativeSurface > 1) {
                            columnDataPoints.add(DhApiTerrainDataPoint.create(
                                    detailLevel, 0, 0, 0, relativeSurface - 1, topWrapper, biomeWrapper
                            ));
                        }
                        // Surface block (full sky light)
                        columnDataPoints.add(DhApiTerrainDataPoint.create(
                                detailLevel, 0, 15, relativeSurface - 1, relativeSurface, topWrapper, biomeWrapper
                        ));
                        lastLayerTop = relativeSurface;
                    }
                }

                if (lastLayerTop < absoluteTop) {
                    columnDataPoints.add(DhApiTerrainDataPoint.create(
                        detailLevel, 0, 15, lastLayerTop, absoluteTop, wrappers.airBlock(), biomeWrapper
                    ));
                }

                output.setApiDataPointColumn(x, z, columnDataPoints);
                columnDataPoints.clear();
            }
        }
    }

    private int sampleSurfaceHeight(double blockX, double blockZ) {
        double elevation = elevationSource.sampleElevationMeters(blockX, blockZ, settings.worldScale());
        double heightScale = elevation >= 0.0 ? settings.terrestrialHeightScale() : settings.oceanicHeightScale();
        double scaled = elevation * heightScale / settings.worldScale();
        int offset = settings.heightOffset();
        int height = elevation >= 0.0 ? Mth.ceil(scaled) : Mth.floor(scaled);
        return height + offset;
    }

    private int resolveCinematicSurface(int surface, int waterSurface, boolean hasWater) {
        if (!settings.cinematicMode() || !hasWater || waterSurface <= surface) {
            return surface;
        }
        int minSurface = waterSurface - 16;
        if (surface < minSurface) {
            surface = minSurface;
            if (surface >= waterSurface) {
                surface = waterSurface - 1;
            }
        }
        return surface;
    }

    private record SurfacePalette(BlockState top, BlockState underwaterTop, BlockState filler, int depth) {
        static SurfacePalette defaultOverworld() {
            BlockState dirt = Blocks.DIRT.defaultBlockState();
            return new SurfacePalette(Blocks.GRASS_BLOCK.defaultBlockState(), dirt, dirt, SURFACE_DEPTH);
        }

        static SurfacePalette desert() {
            BlockState sand = Blocks.SAND.defaultBlockState();
            return new SurfacePalette(sand, sand, Blocks.SANDSTONE.defaultBlockState(), SURFACE_DEPTH);
        }

        static SurfacePalette badlands() {
            BlockState redSand = Blocks.RED_SAND.defaultBlockState();
            return new SurfacePalette(redSand, redSand, Blocks.TERRACOTTA.defaultBlockState(), SURFACE_DEPTH);
        }

        static SurfacePalette beach() {
            BlockState sand = Blocks.SAND.defaultBlockState();
            return new SurfacePalette(sand, sand, sand, SURFACE_DEPTH);
        }

        static SurfacePalette ocean(BlockState top) {
            return new SurfacePalette(top, top, top, SURFACE_DEPTH);
        }

        static SurfacePalette snowy() {
            BlockState dirt = Blocks.DIRT.defaultBlockState();
            BlockState snow = Blocks.SNOW_BLOCK.defaultBlockState();
            return new SurfacePalette(snow, snow, dirt, SURFACE_DEPTH);
        }

        static SurfacePalette swamp() {
            BlockState dirt = Blocks.DIRT.defaultBlockState();
            return new SurfacePalette(Blocks.GRASS_BLOCK.defaultBlockState(), dirt, dirt, SURFACE_DEPTH);
        }

        static SurfacePalette mangrove() {
            BlockState mud = Blocks.MUD.defaultBlockState();
            return new SurfacePalette(mud, mud, Blocks.DIRT.defaultBlockState(), SURFACE_DEPTH);
        }

        static SurfacePalette stonyPeaks() {
            BlockState stone = Blocks.STONE.defaultBlockState();
            return new SurfacePalette(stone, stone, stone, SURFACE_DEPTH);
        }

        static SurfacePalette gravelly() {
            BlockState gravel = Blocks.GRAVEL.defaultBlockState();
            return new SurfacePalette(gravel, gravel, Blocks.STONE.defaultBlockState(), SURFACE_DEPTH);
        }
    }

    private SurfacePalette selectSurfacePalette(Holder<Biome> biome, int worldX, int worldZ) {
        if (biome.is(BiomeTags.IS_OCEAN) || biome.is(BiomeTags.IS_RIVER)) {
            return oceanFloorPalette(worldX, worldZ);
        }
        if (biome.is(BiomeTags.IS_BEACH)) {
            return SurfacePalette.beach();
        }
        if (biome.is(BiomeTags.IS_BADLANDS)) {
            return SurfacePalette.badlands();
        }
        if (biome.is(Biomes.DESERT)) {
            return SurfacePalette.desert();
        }
        if (biome.is(Biomes.MANGROVE_SWAMP)) {
            return SurfacePalette.mangrove();
        }
        if (biome.is(Biomes.SWAMP)) {
            return SurfacePalette.swamp();
        }
        if (biome.is(Biomes.STONY_PEAKS)) {
            return SurfacePalette.stonyPeaks();
        }
        if (biome.is(Biomes.WINDSWEPT_GRAVELLY_HILLS)) {
            return SurfacePalette.gravelly();
        }
        if (biome.is(Biomes.SNOWY_PLAINS)
                || biome.is(Biomes.SNOWY_TAIGA)
                || biome.is(Biomes.SNOWY_SLOPES)
                || biome.is(Biomes.GROVE)
                || biome.is(Biomes.ICE_SPIKES)
                || biome.is(Biomes.FROZEN_PEAKS)) {
            return SurfacePalette.snowy();
        }
        return SurfacePalette.defaultOverworld();
    }

    private SurfacePalette oceanFloorPalette(int worldX, int worldZ) {
        long seed = seedFromCoords(worldX, 0, worldZ) ^ 0x6F1D5E3A2B9C4D1EL;
        Random random = new Random(seed);
        int roll = random.nextInt(100);
        if (roll < 10) {
            return SurfacePalette.ocean(Blocks.GRAVEL.defaultBlockState());
        }
        if (roll < 15) {
            return SurfacePalette.ocean(Blocks.CLAY.defaultBlockState());
        }
        return SurfacePalette.ocean(Blocks.SAND.defaultBlockState());
    }

    private static long seedFromCoords(int x, int y, int z) {
        long seed = (long) (x * 3129871) ^ (long) z * 116129781L ^ (long) y;
        seed = seed * seed * 42317861L + seed * 11L;
        return seed >> 16;
    }

    private Holder<Biome> getNoiseBiome(int x, int y, int z) {
         if (levelWrapper.getWrappedMcObject() instanceof ServerLevel level) {
             return level.getChunkSource().getGenerator().getBiomeSource().getNoiseBiome(
                 x >> 2, y >> 2, z >> 2, 
                 level.getChunkSource().randomState().sampler()
             );
         }
         return null; 
    }

    @Override
    public EDhApiWorldGeneratorReturnType getReturnType() {
        return EDhApiWorldGeneratorReturnType.API_DATA_SOURCES;
    }

    @Override
    public boolean runApiValidation() {
        return FabricLoader.getInstance().isDevelopmentEnvironment();
    }

    @Override
    public void close() {
    }

    private static class WrapperCache {
        private final IDhApiLevelWrapper levelWrapper;
        private final IDhApiBlockStateWrapper airBlock;
        @Nullable
        private final IDhApiBiomeWrapper defaultBiome;
        private final Map<BlockState, IDhApiBlockStateWrapper> blockStates = new IdentityHashMap<>();
        private final Map<Holder<Biome>, IDhApiBiomeWrapper> biomes = new HashMap<>();

        private WrapperCache(final IDhApiLevelWrapper levelWrapper) {
            this.levelWrapper = levelWrapper;
            airBlock = DhApi.Delayed.wrapperFactory.getAirBlockStateWrapper();
            IDhApiBiomeWrapper temp = null;
            try {
                temp = lookupBiomeById(Biomes.THE_VOID);
            } catch (Exception e) {}
            defaultBiome = temp;
        }

        public IDhApiBlockStateWrapper airBlock() {
            return airBlock;
        }

        public IDhApiBlockStateWrapper getBlockState(final BlockState blockState) {
            return blockStates.computeIfAbsent(blockState, this::lookupBlockState);
        }

        private IDhApiBlockStateWrapper lookupBlockState(final BlockState blockState) {
            try {
                return DhApi.Delayed.wrapperFactory.getBlockStateWrapper(new BlockState[]{blockState}, levelWrapper);
            } catch (final Exception e) {
                throw new IllegalStateException(e);
            }
        }

        public IDhApiBiomeWrapper getBiome(final Holder<Biome> biome) {
            if (biome == null) return defaultBiome;
            return biomes.computeIfAbsent(biome, this::lookupBiome);
        }

        private IDhApiBiomeWrapper lookupBiome(final Holder<Biome> biome) {
            final IDhApiBiomeWrapper result = biome.unwrapKey().map(this::lookupBiomeById).orElse(null);
            if (result != null) {
                return result;
            }
            return defaultBiome;
        }

        @Nullable
        private IDhApiBiomeWrapper lookupBiomeById(final ResourceKey<Biome> biome) {
            try {
                return DhApi.Delayed.wrapperFactory.getBiomeWrapper(biome.identifier().toString(), levelWrapper);
            } catch (final IOException ignored) {
                return null;
            }
        }
    }
}
