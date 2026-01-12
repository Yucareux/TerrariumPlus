package com.yucareux.tellus.integration.distant_horizons;

import com.mojang.logging.LogUtils;
import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiDistantGeneratorMode;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiWorldGeneratorReturnType;
import com.seibel.distanthorizons.api.interfaces.block.IDhApiBiomeWrapper;
import com.seibel.distanthorizons.api.interfaces.block.IDhApiBlockStateWrapper;
import com.seibel.distanthorizons.api.interfaces.override.worldGenerator.IDhApiWorldGenerator;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import com.seibel.distanthorizons.api.objects.data.DhApiTerrainDataPoint;
import com.seibel.distanthorizons.api.objects.data.IDhApiFullDataSource;
import com.yucareux.tellus.worldgen.EarthBiomeSource;
import com.yucareux.tellus.worldgen.EarthChunkGenerator;
import com.yucareux.tellus.worldgen.WaterSurfaceResolver;
import com.yucareux.tellus.world.realtime.TellusRealtimeState;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.BiomeTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;

public final class TellusLodGenerator implements IDhApiWorldGenerator {
	private static final Logger LOGGER = LogUtils.getLogger();
	private static final int SKY_LIGHT = 15;
	private static final int CANOPY_MAX_LIGHT = 15;
	private static final int CANOPY_GRID_SIZE = 8;
	private static final int CANOPY_GRID_SCALE_MAX = 8;
	private static final int CANOPY_DENSITY_NUM = 3;
	private static final int CANOPY_DENSITY_DEN = 2;
	private static final int CANOPY_DENSITY_MAX = 100;
	private static final int CANOPY_SALT = 0x6D2B79F5;
	private static final int CANOPY_VARIANT_SALT = 0x7F4A7C15;
	private static final int WATER_VEG_SALT = 0x3C6EF35F;
	private static final int WATER_VEG_MIN_DEPTH = 1;
	private static final int WATER_VEG_MAX_HEIGHT = 4;
	private static final int WATER_VEG_MAX_DETAIL = 4;
	private static final int ESA_NO_DATA = 0;
	private static final int ESA_TREE_COVER = 10;
	private static final int ESA_WATER = 80;
	private static final int ESA_MANGROVES = 95;
	private static final int BADLANDS_LOD_BAND_DEPTH = 16;
	private static final int BADLANDS_LOD_BAND_HEIGHT = 3;
	private static final int BADLANDS_LOD_SLOPE_DIFF = 3;
	private static final int LOD_SLOPE_STEP = 4;
	private static final int LOD_WATER_RESOLVER_MAX_DETAIL = 5;
	private static final int LOD_PREFETCH_GRID_MIN = 2;
	private static final int LOD_PREFETCH_GRID_MAX = 5;
	private static final int LOD_PREFETCH_GRID_DIVISOR = 8;
	private static final int LOD_DETAILED_WATER_STRIDE_DETAIL = 5;
	private static final int LOD_COVER_DOWNSAMPLE_START_DETAIL = 7;
	private static final int LOD_DOWNSAMPLE_MAX_STRIDE = 4;
	private static final Map<Holder<Biome>, CanopyProfile> CANOPY_PROFILES = new ConcurrentHashMap<>();
	private final IDhApiLevelWrapper levelWrapper;
	private final EarthChunkGenerator generator;
	private final EarthBiomeSource biomeSource;
	private final ThreadLocal<@NonNull WrapperCache> wrapperCache;

	public TellusLodGenerator(final IDhApiLevelWrapper levelWrapper, final EarthChunkGenerator generator) {
		this.levelWrapper = levelWrapper;
		this.generator = generator;
		this.biomeSource = (EarthBiomeSource) generator.getBiomeSource();
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
		prefetchLodResources(chunkPosMinX, chunkPosMinZ, detailLevel, pooledFullDataSource.getWidthInDataColumns());
		return CompletableFuture.runAsync(() -> {
			buildLod(pooledFullDataSource, chunkPosMinX, chunkPosMinZ, detailLevel);
			resultConsumer.accept(pooledFullDataSource);
		}, worldGeneratorThreadPool);
	}

	private void buildLod(
			final IDhApiFullDataSource output,
			final int chunkPosMinX,
			final int chunkPosMinZ,
			final byte detailLevel
	) {
		final int lodSizePoints = output.getWidthInDataColumns();
		final int cellSize = 1 << detailLevel;
		final int cellOffset = cellSize >> 1;
		final boolean baseDetailedWater = generator.settings().distantHorizonsWaterResolver()
				&& detailLevel <= LOD_WATER_RESOLVER_MAX_DETAIL;
		final int maxBlendBlocks = Math.max(
				generator.settings().riverLakeShorelineBlend(),
				generator.settings().oceanShorelineBlend()
		);
		final int blendCells = baseDetailedWater && maxBlendBlocks > 0
				? (maxBlendBlocks + cellSize - 1) / cellSize
				: 0;
		final TellusRealtimeState.PrecipitationMode precipitationMode = TellusRealtimeState.precipitationMode();
		final boolean snowActive = (TellusRealtimeState.isWeatherEnabled()
				&& precipitationMode == TellusRealtimeState.PrecipitationMode.SNOW)
				|| TellusRealtimeState.isHistoricalSnowEnabled();

		final int baseX = SectionPos.sectionToBlockCoord(chunkPosMinX);
		final int baseZ = SectionPos.sectionToBlockCoord(chunkPosMinZ);
		final int[] worldXs = new int[lodSizePoints];
		final int[] worldZs = new int[lodSizePoints];
		for (int i = 0; i < lodSizePoints; i++) {
			worldXs[i] = baseX + i * cellSize + cellOffset;
			worldZs[i] = baseZ + i * cellSize + cellOffset;
		}

		final int minY = levelWrapper.getMinHeight();
		final int maxY = minY + levelWrapper.getMaxHeight();
		final int absoluteTop = maxY - minY;
		final WrapperCache wrappers = wrapperCache.get();
		final IDhApiBlockStateWrapper waterBlock = wrappers.getBlockState(Blocks.WATER.defaultBlockState());
		final List<DhApiTerrainDataPoint> columnDataPoints = new ArrayList<>(8);
		final int coverStride = coverSampleStride(detailLevel, lodSizePoints);
		final int detailedWaterStride = detailedWaterStride(detailLevel, lodSizePoints);
		final boolean allowWaterVegetation = detailLevel <= WATER_VEG_MAX_DETAIL;
		final int area = lodSizePoints * lodSizePoints;
		final int[] surfaceYs = new int[area];
		final int[] vegetationSurfaceYs = new int[area];
		final int[] waterSurfaces = new int[area];
		final boolean[] underwaterFlags = new boolean[area];
		final int[] coverClasses = new int[area];
		final int[] fastSurfaceYs = new int[area];
		final boolean[] fastOceanFlags = new boolean[area];
		final IDhApiBiomeWrapper[] biomeWrappers = new IDhApiBiomeWrapper[area];
		@SuppressWarnings("unchecked")
		final Holder<Biome>[] biomeHolders = (Holder<Biome>[]) new Holder[area];
		BlockState lastTopState = null;
		BlockState lastFillerState = null;
		SurfaceWrapperPair lastSurfaceWrapper = null;
		boolean hasWaterInTile = false;

		for (int baseLocalZ = 0; baseLocalZ < lodSizePoints; baseLocalZ += coverStride) {
			for (int baseLocalX = 0; baseLocalX < lodSizePoints; baseLocalX += coverStride) {
				final int sampleWorldX = worldXs[baseLocalX];
				final int sampleWorldZ = worldZs[baseLocalZ];
				final int coverClass = generator.sampleCoverClass(sampleWorldX, sampleWorldZ);
				for (int dz = 0; dz < coverStride; dz++) {
					final int localZ = baseLocalZ + dz;
					if (localZ >= lodSizePoints) {
						continue;
					}
					final int worldZ = worldZs[localZ];
					for (int dx = 0; dx < coverStride; dx++) {
						final int localX = baseLocalX + dx;
						if (localX >= lodSizePoints) {
							continue;
						}
						final int worldX = worldXs[localX];
						final int index = localZ * lodSizePoints + localX;
						final WaterSurfaceResolver.WaterColumnData fastColumn =
								generator.resolveLodWaterColumn(worldX, worldZ, coverClass);
						final int surfaceY = Mth.clamp(fastColumn.terrainSurface(), minY, maxY - 1);
						final int waterSurface = Mth.clamp(fastColumn.waterSurface(), minY, maxY - 1);
						final boolean underwater = fastColumn.hasWater() && waterSurface > surfaceY;
						final int vegetationSurface = surfaceY;
						if (baseDetailedWater && fastColumn.hasWater()) {
							hasWaterInTile = true;
						}
						fastSurfaceYs[index] = surfaceY;
						fastOceanFlags[index] = fastColumn.isOcean();
						surfaceYs[index] = surfaceY;
						vegetationSurfaceYs[index] = Mth.clamp(vegetationSurface, minY, maxY - 1);
						waterSurfaces[index] = waterSurface;
						underwaterFlags[index] = underwater;
						coverClasses[index] = coverClass;
						Holder<Biome> biomeHolder = biomeSource.getBiomeAtBlock(worldX, worldZ);
						biomeHolders[index] = biomeHolder;
						biomeWrappers[index] = wrappers.getBiome(biomeHolder);
					}
				}
			}
		}

		boolean useDetailedWater = baseDetailedWater && hasWaterInTile;
		if (baseDetailedWater && !useDetailedWater && blendCells > 0) {
			useDetailedWater = hasWaterNearLodArea(baseX, baseZ, lodSizePoints, cellSize, cellOffset, blendCells, false);
		}

		if (useDetailedWater) {
			if (detailedWaterStride <= 1) {
				for (int localZ = 0; localZ < lodSizePoints; localZ++) {
					final int worldZ = worldZs[localZ];
					for (int localX = 0; localX < lodSizePoints; localX++) {
						final int worldX = worldXs[localX];
						final int index = localZ * lodSizePoints + localX;
						final int coverClass = coverClasses[index];
						if (!isWaterCoverClass(coverClass)) {
							continue;
						}
						final WaterSurfaceResolver.WaterColumnData detailedColumn =
								generator.resolveLodWaterColumn(worldX, worldZ, coverClass, true);
						final int surfaceY = Mth.clamp(detailedColumn.terrainSurface(), minY, maxY - 1);
						final int waterSurface = Mth.clamp(detailedColumn.waterSurface(), minY, maxY - 1);
						final boolean underwater = detailedColumn.hasWater() && waterSurface > surfaceY;
						final boolean isOcean = detailedColumn.isOcean() || fastOceanFlags[index];
						final int vegetationSurface = isOcean ? fastSurfaceYs[index] : surfaceY;
						surfaceYs[index] = surfaceY;
						vegetationSurfaceYs[index] = Mth.clamp(vegetationSurface, minY, maxY - 1);
						waterSurfaces[index] = waterSurface;
						underwaterFlags[index] = underwater;
					}
				}
			} else {
				for (int baseLocalZ = 0; baseLocalZ < lodSizePoints; baseLocalZ += detailedWaterStride) {
					for (int baseLocalX = 0; baseLocalX < lodSizePoints; baseLocalX += detailedWaterStride) {
						int sampleLocalX = -1;
						int sampleLocalZ = -1;
						for (int dz = 0; dz < detailedWaterStride && sampleLocalX < 0; dz++) {
							final int localZ = baseLocalZ + dz;
							if (localZ >= lodSizePoints) {
								continue;
							}
							for (int dx = 0; dx < detailedWaterStride; dx++) {
								final int localX = baseLocalX + dx;
								if (localX >= lodSizePoints) {
									continue;
								}
								final int index = localZ * lodSizePoints + localX;
								if (isWaterCoverClass(coverClasses[index])) {
									sampleLocalX = localX;
									sampleLocalZ = localZ;
									break;
								}
							}
						}
						if (sampleLocalX < 0) {
							continue;
						}
						final int sampleWorldX = worldXs[sampleLocalX];
						final int sampleWorldZ = worldZs[sampleLocalZ];
						final int sampleIndex = sampleLocalZ * lodSizePoints + sampleLocalX;
						final int sampleCover = coverClasses[sampleIndex];
						final WaterSurfaceResolver.WaterColumnData detailedColumn =
								generator.resolveLodWaterColumn(sampleWorldX, sampleWorldZ, sampleCover, true);
						final int surfaceY = Mth.clamp(detailedColumn.terrainSurface(), minY, maxY - 1);
						final int waterSurface = Mth.clamp(detailedColumn.waterSurface(), minY, maxY - 1);
						final boolean underwater = detailedColumn.hasWater() && waterSurface > surfaceY;
						for (int dz = 0; dz < detailedWaterStride; dz++) {
							final int localZ = baseLocalZ + dz;
							if (localZ >= lodSizePoints) {
								continue;
							}
							for (int dx = 0; dx < detailedWaterStride; dx++) {
								final int localX = baseLocalX + dx;
								if (localX >= lodSizePoints) {
									continue;
								}
								final int index = localZ * lodSizePoints + localX;
								if (!isWaterCoverClass(coverClasses[index])) {
									continue;
								}
								final boolean isOcean = detailedColumn.isOcean() || fastOceanFlags[index];
								final int vegetationSurface = isOcean ? fastSurfaceYs[index] : surfaceY;
								surfaceYs[index] = surfaceY;
								vegetationSurfaceYs[index] = Mth.clamp(vegetationSurface, minY, maxY - 1);
								waterSurfaces[index] = waterSurface;
								underwaterFlags[index] = underwater;
							}
						}
					}
				}
			}
		}

		for (int localZ = 0; localZ < lodSizePoints; localZ++) {
			final int worldZ = worldZs[localZ];
			for (int localX = 0; localX < lodSizePoints; localX++) {
				final int worldX = worldXs[localX];
				final int index = localZ * lodSizePoints + localX;
				final int surfaceY = surfaceYs[index];
				final int vegetationSurfaceY = vegetationSurfaceYs[index];
				final int waterSurface = waterSurfaces[index];
				final boolean underwater = underwaterFlags[index];
				final int coverClass = coverClasses[index];
				final Holder<Biome> biomeHolder = biomeHolders[index];
				final IDhApiBiomeWrapper biome = biomeWrappers[index];
				final CanopyProfile canopyProfile = canopyProfile(biomeHolder);
				final boolean isMangrove = canopyProfile.isMangrove() || coverClass == ESA_MANGROVES;
				final EarthChunkGenerator.LodSurface lodSurface =
						generator.resolveLodSurface(biomeHolder, worldX, worldZ, surfaceY, underwater, coverClass);
				final BlockState topState = lodSurface.top();
				final BlockState fillerState = lodSurface.filler();
				final SurfaceWrapperPair surfaceWrapper;
				if (topState == lastTopState && fillerState == lastFillerState && lastSurfaceWrapper != null) {
					surfaceWrapper = lastSurfaceWrapper;
				} else {
					surfaceWrapper = new SurfaceWrapperPair(
							wrappers.getBlockState(topState),
							wrappers.getBlockState(fillerState)
					);
					lastTopState = topState;
					lastFillerState = fillerState;
					lastSurfaceWrapper = surfaceWrapper;
				}
				final IDhApiBlockStateWrapper fillerBlock = surfaceWrapper.filler();
				IDhApiBlockStateWrapper topBlock = surfaceWrapper.top();
				if (!underwater && snowActive && TellusRealtimeState.shouldApplySnow(worldX, worldZ)) {
					topBlock = wrappers.getBlockState(Blocks.SNOW_BLOCK.defaultBlockState());
				}
				final int slopeDiff = lodSlopeDiff(surfaceYs, lodSizePoints, localX, localZ, cellSize);
				final boolean useBadlandsBands = !underwater
						&& slopeDiff >= BADLANDS_LOD_SLOPE_DIFF
						&& biomeHolder.is(BiomeTags.IS_BADLANDS);

				int lastLayerTop = 0;
				final int surfaceTop = toLayerTop(surfaceY, minY, absoluteTop);
				final int topLayerBase = Math.max(0, surfaceTop - 1);
				if (useBadlandsBands) {
					int bandDepth = Math.min(BADLANDS_LOD_BAND_DEPTH, surfaceY - minY + 1);
					int bandBottomY = Math.max(minY, surfaceY - bandDepth + 1);
					int bandBottomLayer = toLayerTop(bandBottomY, minY, absoluteTop);
					if (bandBottomLayer > lastLayerTop) {
						columnDataPoints.add(
								DhApiTerrainDataPoint.create((byte) 0, 0, SKY_LIGHT, lastLayerTop, bandBottomLayer, fillerBlock, biome)
						);
						lastLayerTop = bandBottomLayer;
					}
					while (lastLayerTop < topLayerBase) {
						int segmentTop = Math.min(topLayerBase, lastLayerTop + BADLANDS_LOD_BAND_HEIGHT);
						int bandY = minY + segmentTop - 1;
						IDhApiBlockStateWrapper bandBlock = wrappers.getBlockState(
								generator.resolveBadlandsBandBlock(worldX, worldZ, bandY)
						);
						columnDataPoints.add(
								DhApiTerrainDataPoint.create((byte) 0, 0, SKY_LIGHT, lastLayerTop, segmentTop, bandBlock, biome)
						);
						lastLayerTop = segmentTop;
					}
				} else if (topLayerBase > lastLayerTop) {
					columnDataPoints.add(
							DhApiTerrainDataPoint.create((byte) 0, 0, SKY_LIGHT, lastLayerTop, topLayerBase, fillerBlock, biome)
					);
					lastLayerTop = topLayerBase;
				}
				if (surfaceTop > lastLayerTop) {
					columnDataPoints.add(
							DhApiTerrainDataPoint.create((byte) 0, 0, SKY_LIGHT, lastLayerTop, surfaceTop, topBlock, biome)
					);
					lastLayerTop = surfaceTop;
				}

				final boolean allowCanopy = (coverClass == ESA_TREE_COVER && !underwater) || isMangrove;
				final CanopyColumn canopyColumn = allowCanopy
						? resolveCanopyColumn(canopyProfile, worldX, worldZ, cellSize)
						: null;
				final boolean deferMangroveCanopy = isMangrove && underwater;
				if (!deferMangroveCanopy) {
					lastLayerTop = appendCanopyColumn(
							canopyColumn,
							lastLayerTop,
							absoluteTop,
							wrappers,
							biome,
							columnDataPoints
					);
				}

				if (underwater) {
					final int waterTop = toLayerTop(waterSurface, minY, absoluteTop);
					if (waterTop > lastLayerTop) {
						final int waterDepth = waterSurface - vegetationSurfaceY;
						final WaterVegetationColumn vegetation = allowWaterVegetation
								? resolveWaterVegetationColumn(canopyProfile, worldX, worldZ, waterDepth)
								: null;
						if (vegetation != null) {
							int vegetationBaseTop = toLayerTop(vegetationSurfaceY, minY, absoluteTop);
							vegetationBaseTop = Mth.clamp(vegetationBaseTop, lastLayerTop, waterTop);
							if (vegetationBaseTop > lastLayerTop) {
								columnDataPoints.add(
										DhApiTerrainDataPoint.create((byte) 0, 0, SKY_LIGHT, lastLayerTop, vegetationBaseTop, waterBlock, biome)
								);
								lastLayerTop = vegetationBaseTop;
							}
							final int vegTop = Math.min(waterTop, lastLayerTop + vegetation.height);
							if (vegTop > lastLayerTop) {
								final IDhApiBlockStateWrapper vegBlock = wrappers.getBlockState(vegetation.blockState);
								columnDataPoints.add(
										DhApiTerrainDataPoint.create((byte) 0, 0, CANOPY_MAX_LIGHT, lastLayerTop, vegTop, vegBlock, biome)
								);
								lastLayerTop = vegTop;
							}
							if (waterTop > lastLayerTop) {
								columnDataPoints.add(
										DhApiTerrainDataPoint.create((byte) 0, 0, SKY_LIGHT, lastLayerTop, waterTop, waterBlock, biome)
								);
								lastLayerTop = waterTop;
							}
						} else {
							columnDataPoints.add(
									DhApiTerrainDataPoint.create((byte) 0, 0, SKY_LIGHT, lastLayerTop, waterTop, waterBlock, biome)
							);
							lastLayerTop = waterTop;
						}
					}
				}

				if (deferMangroveCanopy) {
					lastLayerTop = appendCanopyColumn(
							canopyColumn,
							lastLayerTop,
							absoluteTop,
							wrappers,
							biome,
							columnDataPoints
					);
				}

				if (lastLayerTop < absoluteTop) {
					columnDataPoints.add(DhApiTerrainDataPoint.create((byte) 0, 0, SKY_LIGHT, lastLayerTop, absoluteTop, wrappers.airBlock(), biome));
				}

				output.setApiDataPointColumn(localX, localZ, columnDataPoints);
				columnDataPoints.clear();
			}
		}
	}

	private static int toLayerTop(final int inclusiveTopY, final int minY, final int absoluteTop) {
		return Mth.clamp(inclusiveTopY - minY + 1, 0, absoluteTop);
	}

	private static int lodSlopeDiff(int[] surfaceYs, int gridSize, int x, int z, int cellSize) {
		int index = z * gridSize + x;
		int center = surfaceYs[index];
		int east = surfaceYs[z * gridSize + Math.min(gridSize - 1, x + 1)];
		int west = surfaceYs[z * gridSize + Math.max(0, x - 1)];
		int north = surfaceYs[Math.max(0, z - 1) * gridSize + x];
		int south = surfaceYs[Math.min(gridSize - 1, z + 1) * gridSize + x];
		int maxDiff = Math.max(
				Math.max(Math.abs(east - center), Math.abs(west - center)),
				Math.max(Math.abs(north - center), Math.abs(south - center))
		);
		int scaledStep = Math.max(1, cellSize);
		return (maxDiff * LOD_SLOPE_STEP) / scaledStep;
	}

	private void prefetchLodResources(
			final int chunkPosMinX,
			final int chunkPosMinZ,
			final byte detailLevel,
			final int lodSizePoints
	) {
		if (lodSizePoints <= 0) {
			return;
		}
		final int cellSize = 1 << detailLevel;
		final int cellOffset = cellSize >> 1;
		final boolean useDetailedWater = generator.settings().distantHorizonsWaterResolver()
				&& detailLevel <= LOD_WATER_RESOLVER_MAX_DETAIL;
		final int maxBlendBlocks = Math.max(
				generator.settings().riverLakeShorelineBlend(),
				generator.settings().oceanShorelineBlend()
		);
		final int blendCells = useDetailedWater && maxBlendBlocks > 0
				? (maxBlendBlocks + cellSize - 1) / cellSize
				: 0;
		final int baseX = SectionPos.sectionToBlockCoord(chunkPosMinX);
		final int baseZ = SectionPos.sectionToBlockCoord(chunkPosMinZ);
		final int minBlockX = baseX + cellOffset;
		final int minBlockZ = baseZ + cellOffset;
		final int maxBlockX = baseX + (lodSizePoints - 1) * cellSize + cellOffset;
		final int maxBlockZ = baseZ + (lodSizePoints - 1) * cellSize + cellOffset;

		int grid = Math.min(LOD_PREFETCH_GRID_MAX, Math.max(LOD_PREFETCH_GRID_MIN, lodSizePoints / LOD_PREFETCH_GRID_DIVISOR));
		if (grid <= 1) {
			grid = 2;
		}
		for (int gz = 0; gz < grid; gz++) {
			int worldZ = lerpBlock(minBlockZ, maxBlockZ, gz, grid);
			for (int gx = 0; gx < grid; gx++) {
				int worldX = lerpBlock(minBlockX, maxBlockX, gx, grid);
				prefetchAtBlock(worldX, worldZ);
			}
		}
		if (useDetailedWater
				&& hasWaterNearLodArea(baseX, baseZ, lodSizePoints, cellSize, cellOffset, blendCells, true)) {
			generator.prefetchLodWaterRegions(minBlockX, minBlockZ, maxBlockX, maxBlockZ);
		}
	}

	private boolean hasWaterNearLodArea(
			final int baseX,
			final int baseZ,
			final int lodSizePoints,
			final int cellSize,
			final int cellOffset,
			final int blendCells,
			final boolean includeInterior
	) {
		final int min = -blendCells;
		final int max = lodSizePoints - 1 + blendCells;
		int worldZ = baseZ + min * cellSize + cellOffset;
		for (int localZ = min; localZ <= max; localZ++, worldZ += cellSize) {
			final boolean zInside = localZ >= 0 && localZ < lodSizePoints;
			int worldX = baseX + min * cellSize + cellOffset;
			for (int localX = min; localX <= max; localX++, worldX += cellSize) {
				final boolean xInside = localX >= 0 && localX < lodSizePoints;
				if (!includeInterior && xInside && zInside) {
					continue;
				}
				final int coverClass = generator.sampleCoverClass(worldX, worldZ);
				if (coverClass == ESA_WATER || coverClass == ESA_MANGROVES) {
					return true;
				}
				if (coverClass == ESA_NO_DATA) {
					final WaterSurfaceResolver.WaterColumnData column =
							generator.resolveLodWaterColumn(worldX, worldZ, coverClass);
					if (column.hasWater()) {
						return true;
					}
				}
			}
		}
		return false;
	}

	private static boolean isWaterCoverClass(final int coverClass) {
		return coverClass == ESA_WATER || coverClass == ESA_NO_DATA || coverClass == ESA_MANGROVES;
	}

	private static int coverSampleStride(final int detailLevel, final int lodSizePoints) {
		if (detailLevel < LOD_COVER_DOWNSAMPLE_START_DETAIL) {
			return 1;
		}
		int shift = Math.min(2, detailLevel - LOD_COVER_DOWNSAMPLE_START_DETAIL + 1);
		int stride = 1 << shift;
		stride = Math.min(stride, LOD_DOWNSAMPLE_MAX_STRIDE);
		return Math.min(stride, lodSizePoints);
	}

	private static int detailedWaterStride(final int detailLevel, final int lodSizePoints) {
		if (detailLevel < LOD_DETAILED_WATER_STRIDE_DETAIL) {
			return 1;
		}
		int shift = Math.min(2, detailLevel - LOD_DETAILED_WATER_STRIDE_DETAIL + 1);
		int stride = 1 << shift;
		stride = Math.min(stride, LOD_DOWNSAMPLE_MAX_STRIDE);
		return Math.min(stride, lodSizePoints);
	}


	private void prefetchAtBlock(final int blockX, final int blockZ) {
		final int chunkX = SectionPos.blockToSectionCoord(blockX);
		final int chunkZ = SectionPos.blockToSectionCoord(blockZ);
		generator.prefetchForChunk(chunkX, chunkZ);
	}

	private static int lerpBlock(int min, int max, int index, int count) {
		if (count <= 1) {
			return min;
		}
		double t = index / (double) (count - 1);
		return (int) Math.round(min + (max - min) * t);
	}

	private static CanopyProfile canopyProfile(final Holder<Biome> biome) {
		return CANOPY_PROFILES.computeIfAbsent(biome, TellusLodGenerator::buildCanopyProfile);
	}

	private static CanopyProfile buildCanopyProfile(final Holder<Biome> biome) {
		final boolean isMangrove = biome.is(Biomes.MANGROVE_SWAMP);
		final boolean isDarkForest = biome.is(Biomes.DARK_FOREST);
		final boolean isBambooJungle = biome.is(Biomes.BAMBOO_JUNGLE);
		final boolean isSparseJungle = biome.is(Biomes.SPARSE_JUNGLE);
		final boolean isWindsweptForest = biome.is(Biomes.WINDSWEPT_FOREST);
		final boolean isWoodedBadlands = biome.is(Biomes.WOODED_BADLANDS);
		final boolean isWindsweptSavanna = biome.is(Biomes.WINDSWEPT_SAVANNA);
		final boolean isSavannaPlateau = biome.is(Biomes.SAVANNA_PLATEAU);
		final boolean isCherryGrove = biome.is(Biomes.CHERRY_GROVE);
		final boolean isSwamp = biome.is(Biomes.SWAMP);
		final boolean isWarmOcean = biome.is(Biomes.WARM_OCEAN);
		final boolean isLukewarmOcean = biome.is(Biomes.LUKEWARM_OCEAN);
		final boolean isDeepLukewarmOcean = biome.is(Biomes.DEEP_LUKEWARM_OCEAN);
		final boolean isJungle = biome.is(BiomeTags.IS_JUNGLE);
		final boolean isForest = biome.is(BiomeTags.IS_FOREST);
		final boolean isTaiga = biome.is(BiomeTags.IS_TAIGA);
		final boolean isSavanna = biome.is(BiomeTags.IS_SAVANNA);
		final boolean isOcean = biome.is(BiomeTags.IS_OCEAN);
		final boolean isRiver = biome.is(BiomeTags.IS_RIVER);

		final int canopyBaseChance;
		if (isMangrove) {
			canopyBaseChance = 85;
		} else if (isDarkForest) {
			canopyBaseChance = 80;
		} else if (isBambooJungle) {
			canopyBaseChance = 75;
		} else if (isSparseJungle) {
			canopyBaseChance = 50;
		} else if (isWindsweptForest) {
			canopyBaseChance = 45;
		} else if (isWoodedBadlands) {
			canopyBaseChance = 40;
		} else if (isWindsweptSavanna) {
			canopyBaseChance = 35;
		} else if (isSavannaPlateau) {
			canopyBaseChance = 45;
		} else if (isJungle) {
			canopyBaseChance = 75;
		} else if (isForest) {
			canopyBaseChance = 70;
		} else if (isTaiga) {
			canopyBaseChance = 65;
		} else if (isCherryGrove) {
			canopyBaseChance = 60;
		} else if (isSwamp) {
			canopyBaseChance = 55;
		} else if (isSavanna) {
			canopyBaseChance = 50;
		} else {
			canopyBaseChance = 0;
		}

		final int canopyBaseRadius;
		if (isMangrove) {
			canopyBaseRadius = 5;
		} else if (isSparseJungle) {
			canopyBaseRadius = 3;
		} else if (isBambooJungle) {
			canopyBaseRadius = 4;
		} else if (isJungle) {
			canopyBaseRadius = 5;
		} else if (isDarkForest) {
			canopyBaseRadius = 4;
		} else if (isWindsweptForest || isWoodedBadlands) {
			canopyBaseRadius = 2;
		} else if (isWindsweptSavanna || isSavannaPlateau) {
			canopyBaseRadius = 2;
		} else if (isForest || isTaiga || isCherryGrove || isSwamp) {
			canopyBaseRadius = 3;
		} else if (isSavanna) {
			canopyBaseRadius = 2;
		} else {
			canopyBaseRadius = 0;
		}

		final boolean isTallCanopy = isMangrove || isDarkForest || isJungle;
		final int canopyBaseHeight;
		if (isMangrove) {
			canopyBaseHeight = 4;
		} else if (isJungle) {
			canopyBaseHeight = 4;
		} else if (isTallCanopy) {
			canopyBaseHeight = 3;
		} else if (isTaiga) {
			canopyBaseHeight = 3;
		} else {
			canopyBaseHeight = 2;
		}

		final int canopyMaxHeight;
		if (isMangrove) {
			canopyMaxHeight = 5;
		} else if (isJungle) {
			canopyMaxHeight = 5;
		} else if (isTallCanopy || isTaiga) {
			canopyMaxHeight = 4;
		} else {
			canopyMaxHeight = 3;
		}

		final int waterVegetationChance;
		if (isWarmOcean || isLukewarmOcean) {
			waterVegetationChance = 19;
		} else if (isDeepLukewarmOcean) {
			waterVegetationChance = 18;
		} else if (isMangrove) {
			waterVegetationChance = 17;
		} else if (isSwamp) {
			waterVegetationChance = 14;
		} else if (isOcean) {
			waterVegetationChance = 15;
		} else if (isRiver) {
			waterVegetationChance = 12;
		} else {
			waterVegetationChance = 10;
		}

		return new CanopyProfile(
				isMangrove,
				isDarkForest,
				isBambooJungle,
				isSparseJungle,
				isWindsweptForest,
				isWoodedBadlands,
				isWindsweptSavanna,
				isSavannaPlateau,
				isCherryGrove,
				isSwamp,
				isJungle,
				isForest,
				isTaiga,
				isSavanna,
				isOcean,
				isRiver,
				isWarmOcean,
				isLukewarmOcean,
				isDeepLukewarmOcean,
				canopyBaseChance,
				canopyBaseRadius,
				canopyBaseHeight,
				canopyMaxHeight,
				waterVegetationChance
		);
	}

	private static CanopyColumn resolveCanopyColumn(
			final CanopyProfile profile,
			final int worldX,
			final int worldZ,
			final int cellSize
	) {
		final int baseChance = canopyCenterChancePercent(profile);
		final int chance = boostCanopyChancePercent(baseChance);
		if (chance <= 0) {
			return null;
		}

		final int gridSize = canopyGridSize(cellSize);
		final int cellX = Math.floorDiv(worldX, gridSize);
		final int cellZ = Math.floorDiv(worldZ, gridSize);

		int bestDist = Integer.MAX_VALUE;
		int bestRadius = 0;
		int bestHash = 0;
		boolean bestCenter = false;

		for (int dz = -1; dz <= 1; dz++) {
			final int testCellZ = cellZ + dz;
			for (int dx = -1; dx <= 1; dx++) {
				final int testCellX = cellX + dx;
				final int centerHash = mixHash(testCellX, testCellZ, CANOPY_SALT);
				if (!hasCanopyCenter(centerHash, chance)) {
					continue;
				}

				final int offsetX = centerOffset(centerHash, gridSize);
				final int offsetZ = centerOffset(centerHash >>> 8, gridSize);
				final int centerX = testCellX * gridSize + offsetX;
				final int centerZ = testCellZ * gridSize + offsetZ;
				final int dist = Math.abs(worldX - centerX) + Math.abs(worldZ - centerZ);
				final int radius = canopyRadius(profile, centerHash, gridSize);

				if (dist <= radius && dist < bestDist) {
					bestDist = dist;
					bestRadius = radius;
					bestHash = centerHash;
					bestCenter = dist == 0;
				}
			}
		}

		if (bestDist == Integer.MAX_VALUE) {
			return null;
		}

		int crownHeight = profile.canopyBaseHeight();
		final int falloff = bestRadius - bestDist;
		if (falloff >= 2) {
			crownHeight++;
		}
		if (falloff >= 4) {
			crownHeight++;
		}
		final int maxHeight = profile.canopyMaxHeight();
		crownHeight += (bestHash >>> 19) & 1;
		if (bestCenter) {
			crownHeight++;
		}
		crownHeight = Math.min(crownHeight, maxHeight);
		if (crownHeight <= 0) {
			return null;
		}

		final int centerTrunkHeight = canopyTrunkHeight(profile, bestHash);
		final int trunkHeight = bestCenter ? centerTrunkHeight : 0;
		final int leafLift = canopyLeafLift(profile, bestCenter, centerTrunkHeight, bestDist, bestHash);

		final BlockState leavesBlock = selectCanopyBlock(profile, worldX, worldZ);
		if (leavesBlock == null) {
			return null;
		}
		final BlockState trunkBlock = trunkHeight > 0 ? selectTrunkBlock(profile, worldX, worldZ, bestHash) : null;

		return new CanopyColumn(trunkHeight, leafLift, crownHeight, leavesBlock, trunkBlock);
	}

	private static int appendCanopyColumn(
			final CanopyColumn canopyColumn,
			final int lastLayerTop,
			final int absoluteTop,
			final WrapperCache wrappers,
			final IDhApiBiomeWrapper biome,
			final List<DhApiTerrainDataPoint> columnDataPoints
	) {
		if (canopyColumn == null || lastLayerTop >= absoluteTop) {
			return lastLayerTop;
		}

		int layerTop = lastLayerTop;
		if (canopyColumn.trunkHeight > 0 && canopyColumn.trunkBlock != null) {
			final int trunkTop = Math.min(absoluteTop, layerTop + canopyColumn.trunkHeight);
			if (trunkTop > layerTop) {
				final IDhApiBlockStateWrapper trunkBlock = wrappers.getBlockState(canopyColumn.trunkBlock);
				columnDataPoints.add(
						DhApiTerrainDataPoint.create((byte) 0, 0, CANOPY_MAX_LIGHT, layerTop, trunkTop, trunkBlock, biome)
				);
				layerTop = trunkTop;
			}
		}

		if (canopyColumn.leafLift > 0) {
			final int liftTop = Math.min(absoluteTop, layerTop + canopyColumn.leafLift);
			if (liftTop > layerTop) {
				columnDataPoints.add(
						DhApiTerrainDataPoint.create((byte) 0, 0, CANOPY_MAX_LIGHT, layerTop, liftTop, wrappers.airBlock(), biome)
				);
				layerTop = liftTop;
			}
		}

		if (canopyColumn.leavesHeight > 0 && canopyColumn.leavesBlock != null) {
			final int canopyTop = Math.min(absoluteTop, layerTop + canopyColumn.leavesHeight);
			if (canopyTop > layerTop) {
				final IDhApiBlockStateWrapper canopyBlock = wrappers.getBlockState(canopyColumn.leavesBlock);
				columnDataPoints.add(
						DhApiTerrainDataPoint.create((byte) 0, 0, CANOPY_MAX_LIGHT, layerTop, canopyTop, canopyBlock, biome)
				);
				layerTop = canopyTop;
			}
		}

		return layerTop;
	}

	private static int canopyCenterChancePercent(final CanopyProfile profile) {
		return profile.canopyBaseChance();
	}

	private static int boostCanopyChancePercent(final int baseChance) {
		final int boosted = (baseChance * CANOPY_DENSITY_NUM + (CANOPY_DENSITY_DEN - 1)) / CANOPY_DENSITY_DEN;
		return Math.min(CANOPY_DENSITY_MAX, boosted);
	}

	private static int canopyGridSize(final int cellSize) {
		final int detailLevel = Math.max(0, Integer.numberOfTrailingZeros(cellSize));
		final int scale = Math.min(CANOPY_GRID_SCALE_MAX, Math.max(0, detailLevel - 2));
		final int gridFromDetail = CANOPY_GRID_SIZE + (scale << 1);
		final int gridFromCell = CANOPY_GRID_SIZE + Math.max(-2, (cellSize - 8) / 4);
		final int maxGrid = CANOPY_GRID_SIZE + (CANOPY_GRID_SCALE_MAX << 1);
		return Mth.clamp(Math.min(gridFromDetail, gridFromCell), 6, maxGrid);
	}

	private static int canopyRadius(final CanopyProfile profile, final int centerHash, final int gridSize) {
		final int baseRadius = profile.canopyBaseRadius();
		if (baseRadius == 0) {
			return 0;
		}
		int scaledRadius = Math.max(1, (baseRadius * gridSize) / CANOPY_GRID_SIZE);
		scaledRadius = Math.min(scaledRadius, gridSize - 1);
		return scaledRadius + ((centerHash >>> 16) & 1);
	}

	private static boolean hasCanopyCenter(final int centerHash, final int chancePercent) {
		final int roll = (centerHash >>> 24) & 0xFF;
		final int threshold = (chancePercent * 255) / 100;
		return roll < threshold;
	}

	private static int canopyTrunkHeight(final CanopyProfile profile, final int centerHash) {
		int jitter = (centerHash >>> 21) & 0x3;
		if (jitter == 3) {
			jitter = 2;
		}
		if (profile.isMangrove()) {
			return 6 + jitter + ((centerHash >>> 19) & 1);
		}
		if (profile.isJungle()) {
			int height = 10 + jitter;
			if (((centerHash >>> 18) & 0x7) == 0) {
				height += 8;
			}
			return height;
		}
		int height = 3 + jitter;
		if (profile.isTallCanopy()) {
			height = Math.min(5, height + 1);
		}
		return height;
	}

	private static int canopyLeafLift(
			final CanopyProfile profile,
			final boolean isCenter,
			final int centerTrunkHeight,
			final int bestDist,
			final int centerHash
	) {
		if (isCenter) {
			return 0;
		}

		final int baseLift = Math.max(1, centerTrunkHeight - Math.max(0, bestDist - 1));
		int lift = profile.isTallCanopy() ? Math.max(2, baseLift) : Math.max(1, baseLift);
		if (bestDist > 1 && ((centerHash >>> 20) & 1) == 0) {
			lift = Math.max(1, lift - 1);
		}
		return lift;
	}

	private static WaterVegetationColumn resolveWaterVegetationColumn(
			final CanopyProfile profile,
			final int worldX,
			final int worldZ,
			final int waterDepth
	) {
		if (waterDepth < WATER_VEG_MIN_DEPTH) {
			return null;
		}
		final int chance = waterVegetationChancePercent(profile);
		if (chance <= 0) {
			return null;
		}
		final int hash = mixHash(worldX, worldZ, WATER_VEG_SALT);
		if (!hasClusterCenter(hash, chance)) {
			return null;
		}
		final boolean kelp = shouldUseKelp(profile, waterDepth, hash);
		final BlockState blockState = kelp
				? Blocks.KELP_PLANT.defaultBlockState()
				: Blocks.SEAGRASS.defaultBlockState();
		final int maxHeight = Math.min(WATER_VEG_MAX_HEIGHT, Math.max(1, waterDepth - 1));
		if (maxHeight <= 0) {
			return null;
		}
		int height = 1 + ((hash >>> 12) & 0x3);
		height = Math.min(height, maxHeight);
		if (height <= 0) {
			return null;
		}
		return new WaterVegetationColumn(height, blockState);
	}

	private static int waterVegetationChancePercent(final CanopyProfile profile) {
		return profile.waterVegetationChance();
	}

	private static boolean shouldUseKelp(final CanopyProfile profile, final int waterDepth, final int centerHash) {
		if (profile.isRiver()) {
			return false;
		}
		if (waterDepth < 6) {
			return false;
		}
		final int chance;
		if (profile.isWarmOcean()) {
			chance = 15;
		} else if (profile.isLukewarmOcean() || profile.isDeepLukewarmOcean()) {
			chance = 25;
		} else if (profile.isOcean()) {
			chance = 35;
		} else {
			chance = 0;
		}
		final int roll = (centerHash >>> 18) & 0xFF;
		final int threshold = (chance * 255) / 100;
		return roll < threshold;
	}

	private static int centerOffset(final int hash, final int gridSize) {
		return Math.floorMod(hash, gridSize);
	}

	private static BlockState selectCanopyBlock(final CanopyProfile profile, final int worldX, final int worldZ) {
		if (profile.isWindsweptForest()) {
			return Blocks.SPRUCE_LEAVES.defaultBlockState();
		}
		if (profile.isWoodedBadlands()) {
			return Blocks.OAK_LEAVES.defaultBlockState();
		}
		if (profile.isWindsweptSavanna() || profile.isSavannaPlateau()) {
			return Blocks.ACACIA_LEAVES.defaultBlockState();
		}
		if (profile.isSparseJungle() || profile.isBambooJungle()) {
			return Blocks.JUNGLE_LEAVES.defaultBlockState();
		}
		if (profile.isMangrove()) {
			return Blocks.MANGROVE_LEAVES.defaultBlockState();
		}
		if (profile.isDarkForest()) {
			return Blocks.DARK_OAK_LEAVES.defaultBlockState();
		}
		if (profile.isCherryGrove()) {
			return Blocks.CHERRY_LEAVES.defaultBlockState();
		}
		if (profile.isJungle()) {
			return Blocks.JUNGLE_LEAVES.defaultBlockState();
		}
		if (profile.isTaiga()) {
			return Blocks.SPRUCE_LEAVES.defaultBlockState();
		}
		if (profile.isSavanna()) {
			return Blocks.ACACIA_LEAVES.defaultBlockState();
		}
		if (profile.isSwamp()) {
			return Blocks.OAK_LEAVES.defaultBlockState();
		}
		if (profile.isForest()) {
			final int hash = mixHash(worldX, worldZ, CANOPY_VARIANT_SALT);
			return ((hash >>> 28) & 0x3) == 0
					? Blocks.BIRCH_LEAVES.defaultBlockState()
					: Blocks.OAK_LEAVES.defaultBlockState();
		}
		return null;
	}

	private static BlockState selectTrunkBlock(
			final CanopyProfile profile,
			final int worldX,
			final int worldZ,
			final int centerHash
	) {
		if (profile.isWindsweptForest()) {
			return Blocks.SPRUCE_LOG.defaultBlockState();
		}
		if (profile.isWoodedBadlands()) {
			return Blocks.OAK_LOG.defaultBlockState();
		}
		if (profile.isWindsweptSavanna() || profile.isSavannaPlateau()) {
			return Blocks.ACACIA_LOG.defaultBlockState();
		}
		if (profile.isSparseJungle() || profile.isBambooJungle()) {
			return Blocks.JUNGLE_LOG.defaultBlockState();
		}
		if (profile.isMangrove()) {
			return Blocks.MANGROVE_LOG.defaultBlockState();
		}
		if (profile.isDarkForest()) {
			return Blocks.DARK_OAK_LOG.defaultBlockState();
		}
		if (profile.isCherryGrove()) {
			return Blocks.CHERRY_LOG.defaultBlockState();
		}
		if (profile.isJungle()) {
			return Blocks.JUNGLE_LOG.defaultBlockState();
		}
		if (profile.isTaiga()) {
			return Blocks.SPRUCE_LOG.defaultBlockState();
		}
		if (profile.isSavanna()) {
			return Blocks.ACACIA_LOG.defaultBlockState();
		}
		if (profile.isSwamp()) {
			return Blocks.OAK_LOG.defaultBlockState();
		}
		if (profile.isForest()) {
			final int hash = mixHash(worldX, worldZ, CANOPY_VARIANT_SALT) ^ centerHash;
			return ((hash >>> 28) & 0x3) == 0
					? Blocks.BIRCH_LOG.defaultBlockState()
					: Blocks.OAK_LOG.defaultBlockState();
		}
		return Blocks.OAK_LOG.defaultBlockState();
	}

	private static int mixHash(final int worldX, final int worldZ, final int seed) {
		int h = worldX * 0x1F1F1F1F ^ worldZ * 0x9E3779B9 ^ (seed * 0x27D4EB2D);
		h ^= h >>> 15;
		h *= 0x85EBCA6B;
		h ^= h >>> 13;
		h *= 0xC2B2AE35;
		h ^= h >>> 16;
		return h;
	}

	private static boolean hasClusterCenter(final int centerHash, final int chancePercent) {
		final int roll = (centerHash >>> 24) & 0xFF;
		final int threshold = (chancePercent * 255) / 100;
		return roll < threshold;
	}

	private record CanopyProfile(
			boolean isMangrove,
			boolean isDarkForest,
			boolean isBambooJungle,
			boolean isSparseJungle,
			boolean isWindsweptForest,
			boolean isWoodedBadlands,
			boolean isWindsweptSavanna,
			boolean isSavannaPlateau,
			boolean isCherryGrove,
			boolean isSwamp,
			boolean isJungle,
			boolean isForest,
			boolean isTaiga,
			boolean isSavanna,
			boolean isOcean,
			boolean isRiver,
			boolean isWarmOcean,
			boolean isLukewarmOcean,
			boolean isDeepLukewarmOcean,
			int canopyBaseChance,
			int canopyBaseRadius,
			int canopyBaseHeight,
			int canopyMaxHeight,
			int waterVegetationChance
	) {
		private boolean isTallCanopy() {
			return isMangrove || isDarkForest || isJungle;
		}
	}

	private static final class CanopyColumn {
		private final int trunkHeight;
		private final int leafLift;
		private final int leavesHeight;
		private final BlockState leavesBlock;
		private final BlockState trunkBlock;

		private CanopyColumn(
				final int trunkHeight,
				final int leafLift,
				final int leavesHeight,
				final BlockState leavesBlock,
				final BlockState trunkBlock
		) {
			this.trunkHeight = trunkHeight;
			this.leafLift = leafLift;
			this.leavesHeight = leavesHeight;
			this.leavesBlock = leavesBlock;
			this.trunkBlock = trunkBlock;
		}
	}

	private static final class WaterVegetationColumn {
		private final int height;
		private final BlockState blockState;

		private WaterVegetationColumn(final int height, final BlockState blockState) {
			this.height = height;
			this.blockState = blockState;
		}
	}

	private record SurfaceWrapperPair(IDhApiBlockStateWrapper top, IDhApiBlockStateWrapper filler) {
	}


	@Override
	public EDhApiWorldGeneratorReturnType getReturnType() {
		return EDhApiWorldGeneratorReturnType.API_DATA_SOURCES;
	}

	@Override
	public boolean runApiValidation() {
		return false;
	}

	@Override
	public void close() {
	}

	private static class WrapperCache {
		private final IDhApiLevelWrapper levelWrapper;

		private final IDhApiBlockStateWrapper airBlock;
		private final IDhApiBiomeWrapper defaultBiome;

		private final Map<BlockState, IDhApiBlockStateWrapper> blockStates = new IdentityHashMap<>();
		private final Map<Holder<Biome>, IDhApiBiomeWrapper> biomes = new HashMap<>();

		private WrapperCache(final IDhApiLevelWrapper levelWrapper) {
			this.levelWrapper = levelWrapper;
			airBlock = DhApi.Delayed.wrapperFactory.getAirBlockStateWrapper();
			defaultBiome = lookupBiomeById(Biomes.PLAINS);
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
			} catch (final ClassCastException e) {
				throw new IllegalStateException(e);
			}
		}

		public IDhApiBiomeWrapper getBiome(final Holder<Biome> biome) {
			return biomes.computeIfAbsent(biome, this::lookupBiome);
		}

		private IDhApiBiomeWrapper lookupBiome(final Holder<Biome> biome) {
			final IDhApiBiomeWrapper result = biome.unwrapKey().map(this::lookupBiomeById).orElse(null);
			if (result != null) {
				return result;
			}
			return Objects.requireNonNull(defaultBiome, "No default biome available");
		}

		private IDhApiBiomeWrapper lookupBiomeById(final ResourceKey<Biome> biome) {
			try {
				return DhApi.Delayed.wrapperFactory.getBiomeWrapper(biome.identifier().toString(), levelWrapper);
			} catch (final IOException ignored) {
				LOGGER.warn("Could not find biome with id {}, will not use for LODs", biome.identifier());
				return null;
			}
		}
	}
}
