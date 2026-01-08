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
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
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

		final int baseX = SectionPos.sectionToBlockCoord(chunkPosMinX);
		final int baseZ = SectionPos.sectionToBlockCoord(chunkPosMinZ);

		final int minY = levelWrapper.getMinHeight();
		final int maxY = minY + levelWrapper.getMaxHeight();
		final int absoluteTop = maxY - minY;
		final WrapperCache wrappers = wrapperCache.get();
		final IDhApiBlockStateWrapper waterBlock = wrappers.getBlockState(Blocks.WATER.defaultBlockState());
		final List<DhApiTerrainDataPoint> columnDataPoints = new ArrayList<>();
		final Map<SurfaceWrapperKey, SurfaceWrapperPair> surfaceWrapperCache = new HashMap<>();
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
		boolean hasWaterInTile = false;

		for (int baseLocalZ = 0; baseLocalZ < lodSizePoints; baseLocalZ += coverStride) {
			for (int baseLocalX = 0; baseLocalX < lodSizePoints; baseLocalX += coverStride) {
				final int sampleWorldX = baseX + baseLocalX * cellSize + cellOffset;
				final int sampleWorldZ = baseZ + baseLocalZ * cellSize + cellOffset;
				final int coverClass = generator.sampleCoverClass(sampleWorldX, sampleWorldZ);
				for (int dz = 0; dz < coverStride; dz++) {
					final int localZ = baseLocalZ + dz;
					if (localZ >= lodSizePoints) {
						continue;
					}
					final int worldZ = baseZ + localZ * cellSize + cellOffset;
					for (int dx = 0; dx < coverStride; dx++) {
						final int localX = baseLocalX + dx;
						if (localX >= lodSizePoints) {
							continue;
						}
						final int worldX = baseX + localX * cellSize + cellOffset;
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
					final int worldZ = baseZ + localZ * cellSize + cellOffset;
					for (int localX = 0; localX < lodSizePoints; localX++) {
						final int worldX = baseX + localX * cellSize + cellOffset;
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
						final int sampleWorldX = baseX + sampleLocalX * cellSize + cellOffset;
						final int sampleWorldZ = baseZ + sampleLocalZ * cellSize + cellOffset;
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
			final int worldZ = baseZ + localZ * cellSize + cellOffset;
			for (int localX = 0; localX < lodSizePoints; localX++) {
				final int worldX = baseX + localX * cellSize + cellOffset;
				final int index = localZ * lodSizePoints + localX;
				final int surfaceY = surfaceYs[index];
				final int vegetationSurfaceY = vegetationSurfaceYs[index];
				final int waterSurface = waterSurfaces[index];
				final boolean underwater = underwaterFlags[index];
				final int coverClass = coverClasses[index];
				final Holder<Biome> biomeHolder = biomeHolders[index];
				final IDhApiBiomeWrapper biome = biomeWrappers[index];
				final EarthChunkGenerator.LodSurface lodSurface =
						generator.resolveLodSurface(biomeHolder, worldX, worldZ, surfaceY, underwater, coverClass);
				final SurfaceWrapperPair surfaceWrapper = surfaceWrapperCache.computeIfAbsent(
						new SurfaceWrapperKey(lodSurface.top(), lodSurface.filler()),
						key -> new SurfaceWrapperPair(
								wrappers.getBlockState(key.top()),
								wrappers.getBlockState(key.filler())
						)
				);
				final IDhApiBlockStateWrapper fillerBlock = surfaceWrapper.filler();
				final IDhApiBlockStateWrapper topBlock = surfaceWrapper.top();
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

				final boolean allowCanopy = !underwater && coverClass == ESA_TREE_COVER;
				final CanopyColumn canopyColumn = allowCanopy
						? resolveCanopyColumn(biomeHolder, worldX, worldZ, cellSize)
						: null;
				if (canopyColumn != null && lastLayerTop < absoluteTop) {
					if (canopyColumn.trunkHeight > 0 && canopyColumn.trunkBlock != null) {
						final int trunkTop = Math.min(absoluteTop, lastLayerTop + canopyColumn.trunkHeight);
						if (trunkTop > lastLayerTop) {
							final IDhApiBlockStateWrapper trunkBlock = wrappers.getBlockState(canopyColumn.trunkBlock);
							columnDataPoints.add(
									DhApiTerrainDataPoint.create((byte) 0, 0, CANOPY_MAX_LIGHT, lastLayerTop, trunkTop, trunkBlock, biome)
							);
							lastLayerTop = trunkTop;
						}
					}

					if (canopyColumn.leafLift > 0) {
						final int liftTop = Math.min(absoluteTop, lastLayerTop + canopyColumn.leafLift);
						if (liftTop > lastLayerTop) {
							columnDataPoints.add(
									DhApiTerrainDataPoint.create((byte) 0, 0, CANOPY_MAX_LIGHT, lastLayerTop, liftTop, wrappers.airBlock(), biome)
							);
							lastLayerTop = liftTop;
						}
					}

					if (canopyColumn.leavesHeight > 0 && canopyColumn.leavesBlock != null) {
						final int canopyTop = Math.min(absoluteTop, lastLayerTop + canopyColumn.leavesHeight);
						if (canopyTop > lastLayerTop) {
							final IDhApiBlockStateWrapper canopyBlock = wrappers.getBlockState(canopyColumn.leavesBlock);
							columnDataPoints.add(
									DhApiTerrainDataPoint.create((byte) 0, 0, CANOPY_MAX_LIGHT, lastLayerTop, canopyTop, canopyBlock, biome)
							);
							lastLayerTop = canopyTop;
						}
					}
				}

				if (underwater) {
					final int waterTop = toLayerTop(waterSurface, minY, absoluteTop);
					if (waterTop > lastLayerTop) {
						final int waterDepth = waterSurface - vegetationSurfaceY;
						final WaterVegetationColumn vegetation = allowWaterVegetation
								? resolveWaterVegetationColumn(biomeHolder, worldX, worldZ, waterDepth)
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
		for (int localZ = min; localZ <= max; localZ++) {
			final boolean zInside = localZ >= 0 && localZ < lodSizePoints;
			final int worldZ = baseZ + localZ * cellSize + cellOffset;
			for (int localX = min; localX <= max; localX++) {
				final boolean xInside = localX >= 0 && localX < lodSizePoints;
				if (!includeInterior && xInside && zInside) {
					continue;
				}
				final int worldX = baseX + localX * cellSize + cellOffset;
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

	private static CanopyColumn resolveCanopyColumn(
			final Holder<Biome> biome,
			final int worldX,
			final int worldZ,
			final int cellSize
	) {
		final int baseChance = canopyCenterChancePercent(biome);
		final int chance = boostCanopyChancePercent(baseChance);
		if (chance <= 0) {
			return null;
		}

		final int gridSize = canopyGridSize(biome, cellSize);
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
				final int radius = canopyRadius(biome, centerHash, gridSize);

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

		int crownHeight = canopyBaseHeight(biome);
		final int falloff = bestRadius - bestDist;
		if (falloff >= 2) {
			crownHeight++;
		}
		if (falloff >= 4) {
			crownHeight++;
		}
		final int maxHeight = canopyMaxHeight(biome);
		crownHeight += (bestHash >>> 19) & 1;
		if (bestCenter) {
			crownHeight++;
		}
		crownHeight = Math.min(crownHeight, maxHeight);
		if (crownHeight <= 0) {
			return null;
		}

		final int centerTrunkHeight = canopyTrunkHeight(biome, bestHash);
		final int trunkHeight = bestCenter ? centerTrunkHeight : 0;
		final int leafLift = canopyLeafLift(biome, bestCenter, centerTrunkHeight, bestDist, bestHash);

		final BlockState leavesBlock = selectCanopyBlock(biome, worldX, worldZ);
		if (leavesBlock == null) {
			return null;
		}
		final BlockState trunkBlock = trunkHeight > 0 ? selectTrunkBlock(biome, worldX, worldZ, bestHash) : null;

		return new CanopyColumn(trunkHeight, leafLift, crownHeight, leavesBlock, trunkBlock);
	}

	private static int canopyCenterChancePercent(final Holder<Biome> biome) {
		if (biome.is(Biomes.MANGROVE_SWAMP)) {
			return 85;
		}
		if (biome.is(Biomes.DARK_FOREST)) {
			return 80;
		}
		if (biome.is(Biomes.BAMBOO_JUNGLE)) {
			return 75;
		}
		if (biome.is(Biomes.SPARSE_JUNGLE)) {
			return 50;
		}
		if (biome.is(Biomes.WINDSWEPT_FOREST)) {
			return 45;
		}
		if (biome.is(Biomes.WOODED_BADLANDS)) {
			return 40;
		}
		if (biome.is(Biomes.WINDSWEPT_SAVANNA)) {
			return 35;
		}
		if (biome.is(Biomes.SAVANNA_PLATEAU)) {
			return 45;
		}
		if (biome.is(BiomeTags.IS_JUNGLE)) {
			return 75;
		}
		if (biome.is(BiomeTags.IS_FOREST)) {
			return 70;
		}
		if (biome.is(BiomeTags.IS_TAIGA)) {
			return 65;
		}
		if (biome.is(Biomes.CHERRY_GROVE)) {
			return 60;
		}
		if (biome.is(Biomes.SWAMP)) {
			return 55;
		}
		if (biome.is(BiomeTags.IS_SAVANNA)) {
			return 50;
		}
		return 0;
	}

	private static int boostCanopyChancePercent(final int baseChance) {
		final int boosted = (baseChance * CANOPY_DENSITY_NUM + (CANOPY_DENSITY_DEN - 1)) / CANOPY_DENSITY_DEN;
		return Math.min(CANOPY_DENSITY_MAX, boosted);
	}

	private static int canopyGridSize(final Holder<Biome> biome, final int cellSize) {
		final int detailLevel = Math.max(0, Integer.numberOfTrailingZeros(cellSize));
		final int scale = Math.min(CANOPY_GRID_SCALE_MAX, Math.max(0, detailLevel - 2));
		final int gridFromDetail = CANOPY_GRID_SIZE + (scale << 1);
		final int gridFromCell = CANOPY_GRID_SIZE + Math.max(-2, (cellSize - 8) / 4);
		final int maxGrid = CANOPY_GRID_SIZE + (CANOPY_GRID_SCALE_MAX << 1);
		return Mth.clamp(Math.min(gridFromDetail, gridFromCell), 6, maxGrid);
	}

	private static int canopyRadius(final Holder<Biome> biome, final int centerHash, final int gridSize) {
		int baseRadius;
		if (biome.is(Biomes.SPARSE_JUNGLE)) {
			baseRadius = 3;
		} else if (biome.is(Biomes.BAMBOO_JUNGLE)) {
			baseRadius = 4;
		} else if (biome.is(Biomes.MANGROVE_SWAMP) || biome.is(Biomes.DARK_FOREST) || biome.is(BiomeTags.IS_JUNGLE)) {
			baseRadius = 4;
		} else if (biome.is(Biomes.WINDSWEPT_FOREST) || biome.is(Biomes.WOODED_BADLANDS)) {
			baseRadius = 2;
		} else if (biome.is(Biomes.WINDSWEPT_SAVANNA) || biome.is(Biomes.SAVANNA_PLATEAU)) {
			baseRadius = 2;
		} else if (biome.is(BiomeTags.IS_FOREST) || biome.is(BiomeTags.IS_TAIGA) || biome.is(Biomes.CHERRY_GROVE)
				|| biome.is(Biomes.SWAMP)) {
			baseRadius = 3;
		} else if (biome.is(BiomeTags.IS_SAVANNA)) {
			baseRadius = 2;
		} else {
			baseRadius = 0;
		}
		if (baseRadius == 0) {
			return 0;
		}
		int scaledRadius = Math.max(1, (baseRadius * gridSize) / CANOPY_GRID_SIZE);
		scaledRadius = Math.min(scaledRadius, gridSize - 1);
		return scaledRadius + ((centerHash >>> 16) & 1);
	}

	private static int canopyBaseHeight(final Holder<Biome> biome) {
		if (isJungleBiome(biome)) {
			return 3;
		}
		if (isTallCanopyBiome(biome)) {
			return 3;
		}
		if (biome.is(BiomeTags.IS_TAIGA)) {
			return 3;
		}
		return 2;
	}

	private static int canopyMaxHeight(final Holder<Biome> biome) {
		if (isJungleBiome(biome)) {
			return 3;
		}
		if (isTallCanopyBiome(biome) || biome.is(BiomeTags.IS_TAIGA)) {
			return 4;
		}
		return 3;
	}

	private static boolean hasCanopyCenter(final int centerHash, final int chancePercent) {
		final int roll = (centerHash >>> 24) & 0xFF;
		final int threshold = (chancePercent * 255) / 100;
		return roll < threshold;
	}

	private static int canopyTrunkHeight(final Holder<Biome> biome, final int centerHash) {
		int jitter = (centerHash >>> 21) & 0x3;
		if (jitter == 3) {
			jitter = 2;
		}
		if (isJungleBiome(biome)) {
			return 13 + jitter;
		}
		int height = 3 + jitter;
		if (isTallCanopyBiome(biome)) {
			height = Math.min(5, height + 1);
		}
		return height;
	}

	private static int canopyLeafLift(
			final Holder<Biome> biome,
			final boolean isCenter,
			final int centerTrunkHeight,
			final int bestDist,
			final int centerHash
	) {
		if (isCenter) {
			return 0;
		}

		final int baseLift = Math.max(1, centerTrunkHeight - Math.max(0, bestDist - 1));
		int lift = isTallCanopyBiome(biome) ? Math.max(2, baseLift) : Math.max(1, baseLift);
		if (bestDist > 1 && ((centerHash >>> 20) & 1) == 0) {
			lift = Math.max(1, lift - 1);
		}
		return lift;
	}

	private static boolean isTallCanopyBiome(final Holder<Biome> biome) {
		return biome.is(Biomes.MANGROVE_SWAMP)
				|| biome.is(Biomes.DARK_FOREST)
				|| biome.is(BiomeTags.IS_JUNGLE);
	}

	private static boolean isJungleBiome(final Holder<Biome> biome) {
		return biome.is(BiomeTags.IS_JUNGLE);
	}

	private static WaterVegetationColumn resolveWaterVegetationColumn(
			final Holder<Biome> biome,
			final int worldX,
			final int worldZ,
			final int waterDepth
	) {
		if (waterDepth < WATER_VEG_MIN_DEPTH) {
			return null;
		}
		final int chance = waterVegetationChancePercent(biome);
		if (chance <= 0) {
			return null;
		}
		final int hash = mixHash(worldX, worldZ, WATER_VEG_SALT);
		if (!hasClusterCenter(hash, chance)) {
			return null;
		}
		final boolean kelp = shouldUseKelp(biome, waterDepth, hash);
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

	private static int waterVegetationChancePercent(final Holder<Biome> biome) {
		if (biome.is(Biomes.WARM_OCEAN) || biome.is(Biomes.LUKEWARM_OCEAN)) {
			return 19;
		}
		if (biome.is(Biomes.DEEP_LUKEWARM_OCEAN)) {
			return 18;
		}
		if (biome.is(Biomes.MANGROVE_SWAMP)) {
			return 17;
		}
		if (biome.is(Biomes.SWAMP)) {
			return 14;
		}
		if (biome.is(BiomeTags.IS_OCEAN)) {
			return 15;
		}
		if (biome.is(BiomeTags.IS_RIVER)) {
			return 12;
		}
		return 10;
	}

	private static boolean shouldUseKelp(final Holder<Biome> biome, final int waterDepth, final int centerHash) {
		if (biome.is(BiomeTags.IS_RIVER)) {
			return false;
		}
		if (waterDepth < 6) {
			return false;
		}
		final int chance;
		if (biome.is(Biomes.WARM_OCEAN)) {
			chance = 15;
		} else if (biome.is(Biomes.LUKEWARM_OCEAN) || biome.is(Biomes.DEEP_LUKEWARM_OCEAN)) {
			chance = 25;
		} else if (biome.is(BiomeTags.IS_OCEAN)) {
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

	private static BlockState selectCanopyBlock(final Holder<Biome> biome, final int worldX, final int worldZ) {
		if (biome.is(Biomes.WINDSWEPT_FOREST)) {
			return Blocks.SPRUCE_LEAVES.defaultBlockState();
		}
		if (biome.is(Biomes.WOODED_BADLANDS)) {
			return Blocks.OAK_LEAVES.defaultBlockState();
		}
		if (biome.is(Biomes.WINDSWEPT_SAVANNA) || biome.is(Biomes.SAVANNA_PLATEAU)) {
			return Blocks.ACACIA_LEAVES.defaultBlockState();
		}
		if (biome.is(Biomes.SPARSE_JUNGLE) || biome.is(Biomes.BAMBOO_JUNGLE)) {
			return Blocks.JUNGLE_LEAVES.defaultBlockState();
		}
		if (biome.is(Biomes.MANGROVE_SWAMP)) {
			return Blocks.MANGROVE_LEAVES.defaultBlockState();
		}
		if (biome.is(Biomes.DARK_FOREST)) {
			return Blocks.DARK_OAK_LEAVES.defaultBlockState();
		}
		if (biome.is(Biomes.CHERRY_GROVE)) {
			return Blocks.CHERRY_LEAVES.defaultBlockState();
		}
		if (biome.is(BiomeTags.IS_JUNGLE)) {
			return Blocks.JUNGLE_LEAVES.defaultBlockState();
		}
		if (biome.is(BiomeTags.IS_TAIGA)) {
			return Blocks.SPRUCE_LEAVES.defaultBlockState();
		}
		if (biome.is(BiomeTags.IS_SAVANNA)) {
			return Blocks.ACACIA_LEAVES.defaultBlockState();
		}
		if (biome.is(Biomes.SWAMP)) {
			return Blocks.OAK_LEAVES.defaultBlockState();
		}
		if (biome.is(BiomeTags.IS_FOREST)) {
			final int hash = mixHash(worldX, worldZ, CANOPY_VARIANT_SALT);
			return ((hash >>> 28) & 0x3) == 0
					? Blocks.BIRCH_LEAVES.defaultBlockState()
					: Blocks.OAK_LEAVES.defaultBlockState();
		}
		return null;
	}

	private static BlockState selectTrunkBlock(final Holder<Biome> biome, final int worldX, final int worldZ, final int centerHash) {
		if (biome.is(Biomes.WINDSWEPT_FOREST)) {
			return Blocks.SPRUCE_LOG.defaultBlockState();
		}
		if (biome.is(Biomes.WOODED_BADLANDS)) {
			return Blocks.OAK_LOG.defaultBlockState();
		}
		if (biome.is(Biomes.WINDSWEPT_SAVANNA) || biome.is(Biomes.SAVANNA_PLATEAU)) {
			return Blocks.ACACIA_LOG.defaultBlockState();
		}
		if (biome.is(Biomes.SPARSE_JUNGLE) || biome.is(Biomes.BAMBOO_JUNGLE)) {
			return Blocks.JUNGLE_LOG.defaultBlockState();
		}
		if (biome.is(Biomes.MANGROVE_SWAMP)) {
			return Blocks.MANGROVE_LOG.defaultBlockState();
		}
		if (biome.is(Biomes.DARK_FOREST)) {
			return Blocks.DARK_OAK_LOG.defaultBlockState();
		}
		if (biome.is(Biomes.CHERRY_GROVE)) {
			return Blocks.CHERRY_LOG.defaultBlockState();
		}
		if (biome.is(BiomeTags.IS_JUNGLE)) {
			return Blocks.JUNGLE_LOG.defaultBlockState();
		}
		if (biome.is(BiomeTags.IS_TAIGA)) {
			return Blocks.SPRUCE_LOG.defaultBlockState();
		}
		if (biome.is(BiomeTags.IS_SAVANNA)) {
			return Blocks.ACACIA_LOG.defaultBlockState();
		}
		if (biome.is(Biomes.SWAMP)) {
			return Blocks.OAK_LOG.defaultBlockState();
		}
		if (biome.is(BiomeTags.IS_FOREST)) {
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

	private record SurfaceWrapperKey(BlockState top, BlockState filler) {
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
