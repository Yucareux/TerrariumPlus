package com.yucareux.tellus.worldgen;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.yucareux.tellus.Tellus;
import com.yucareux.tellus.world.data.cover.TellusLandCoverSource;
import com.yucareux.tellus.world.data.elevation.TellusElevationSource;
import com.yucareux.tellus.world.data.mask.TellusLandMaskSource;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.util.Arrays;
import net.minecraft.util.Mth;

public final class WaterSurfaceResolver {
	private static final int ESA_NO_DATA = 0;
	private static final int ESA_WATER = 80;
	private static final byte WATER_NONE = 0;
	private static final byte WATER_INLAND = 1;
	private static final byte WATER_OCEAN = 2;
	private static final int REGION_SIZE = 64;
	private static final int MAX_REGION_CACHE = 512;

	private static final int INLAND_SHORE_DEPTH1_LIMIT = 5;
	private static final int INLAND_SHORE_DEPTH3_LIMIT = 8;
	private static final int INLAND_SHORE_DEPTH4_LIMIT = 10;
	private static final int INLAND_RANDOM_DEPTH_MIN = 3;
	private static final int INLAND_RANDOM_DEPTH_MAX = 6;
	private static final int INLAND_MAX_DEPTH = 30;
	private static final int INLAND_DEEP_DISTANCE_STEP = 6;
	private static final int OCEAN_MIN_DEPTH = 1;
	private static final int CLIFF_SLOPE_THRESHOLD = 5;
	private static final double RIVER_MIN_LENGTH_METERS = 750.0;
	private static final double RIVER_MAX_WIDTH_METERS = 400.0;
	private static final double RIVER_ASPECT_RATIO = 3.0;
	private static final double RIVER_LAKE_FILL_THRESHOLD = 0.6;
	private static final double RIVER_LAKE_ASPECT_FACTOR = 1.5;
	private static final double RIVER_LAKE_WIDTH_FACTOR = 0.75;
	private static final int RIVER_LAKE_MIN_WIDTH = 12;
	private static final double BORDER_HEIGHT_PERCENTILE = 0.10;
	private static final int SEA_LEVEL_TOLERANCE = 2;
	private static final double BELOW_SEA_CELL_RATIO = 0.9;
	private static final double LANDMASK_INLAND_RATIO = 0.6;
	private static final int COARSE_CONNECT_STEP = 8;
	private static final int LAKE_SMOOTH_PASSES = 1;
	private static final int MAX_REGION_MARGIN_BLOCKS = 512;
	private static final int DIST_COST_CARDINAL = 10;
	private static final int DIST_COST_DIAGONAL = 14;
	private static final int[] NEIGHBOR_OFFSETS = { 1, 0, -1, 0, 0, 1, 0, -1 };
	private static final int[] NEIGHBOR_OFFSETS_8 = {
			1, 0, -1, 0, 0, 1, 0, -1,
			1, 1, 1, -1, -1, 1, -1, -1
	};
	private static final int[] NEIGHBOR_COSTS_8 = {
			DIST_COST_CARDINAL, DIST_COST_CARDINAL, DIST_COST_CARDINAL, DIST_COST_CARDINAL,
			DIST_COST_DIAGONAL, DIST_COST_DIAGONAL, DIST_COST_DIAGONAL, DIST_COST_DIAGONAL
	};
	private static final boolean DEBUG_WATER = Boolean.getBoolean("tellus.debugWater");
	private static final ThreadLocal<RegionScratch> REGION_SCRATCH = ThreadLocal.withInitial(RegionScratch::new);

	private final TellusLandCoverSource landCoverSource;
	private final TellusLandMaskSource landMaskSource;
	private final TellusElevationSource elevationSource;
	private final EarthGeneratorSettings settings;
	private final int seaLevel;
	private final Cache<Long, WaterRegionData> regionCache;
	private final long regionSalt;
	private final int riverLakeBlendDistance;
	private final int oceanBlendDistance;
	private final int cliffSlopeThreshold;
	private final boolean limitShorelineBlendBySlope;
	private final int riverMinLength;
	private final int riverMaxWidth;
	private final int maxDistanceToShore;
	private final int regionMargin;
	private final boolean regionClamped;

	public WaterSurfaceResolver(
			TellusLandCoverSource landCoverSource,
			TellusLandMaskSource landMaskSource,
			TellusElevationSource elevationSource,
			EarthGeneratorSettings settings
	) {
		this.landCoverSource = landCoverSource;
		this.landMaskSource = landMaskSource;
		this.elevationSource = elevationSource;
		this.settings = settings;
		this.seaLevel = settings.resolveSeaLevel();

		this.riverLakeBlendDistance = clampBlend(settings.riverLakeShorelineBlend());
		this.oceanBlendDistance = clampBlend(settings.oceanShorelineBlend());
		double scale = Math.max(1.0, settings.worldScale());
		this.cliffSlopeThreshold = Math.max(2, (int) Math.round(CLIFF_SLOPE_THRESHOLD / Math.sqrt(scale)));
		this.limitShorelineBlendBySlope = settings.shorelineBlendCliffLimit();
		this.riverMinLength = metersToBlocks(RIVER_MIN_LENGTH_METERS);
		this.riverMaxWidth = metersToBlocks(RIVER_MAX_WIDTH_METERS);
		int maxDepthDistance = INLAND_SHORE_DEPTH4_LIMIT
				+ Math.max(0, INLAND_MAX_DEPTH - INLAND_RANDOM_DEPTH_MAX) * INLAND_DEEP_DISTANCE_STEP;
		this.maxDistanceToShore = Math.max(maxDepthDistance, Math.max(this.riverLakeBlendDistance, this.oceanBlendDistance));
		int rawRegionMargin = this.maxDistanceToShore + 2;
		this.regionMargin = Math.min(rawRegionMargin, MAX_REGION_MARGIN_BLOCKS);
		this.regionClamped = rawRegionMargin > this.regionMargin;

		this.regionCache = CacheBuilder.newBuilder()
				.maximumSize(MAX_REGION_CACHE)
				.build();
		this.regionSalt = Double.doubleToLongBits(settings.worldScale()) ^ 0x9E3779B97F4A7C15L;
	}

	public boolean isWaterClass(int coverClass) {
		return coverClass != ESA_WATER && coverClass != ESA_NO_DATA;
	}

	public WaterChunkData resolveChunkWaterData(int chunkX, int chunkZ) {
		int padding = Math.max(this.riverLakeBlendDistance, this.oceanBlendDistance);
		if (hasWaterNearChunk(chunkX, chunkZ, padding)) {
			return buildDryChunkData(chunkX, chunkZ);
		}
		WaterRegionData region = resolveRegionData(regionCoord(chunkX << 4), regionCoord(chunkZ << 4));
		return new WaterChunkData(chunkX, chunkZ, region);
	}

	public void prefetchRegionsForChunk(int chunkX, int chunkZ, int radius) {
		int padding = Math.max(this.riverLakeBlendDistance, this.oceanBlendDistance);
		if (hasWaterNearChunk(chunkX, chunkZ, padding)) {
			return;
		}
		int blockX = chunkX << 4;
		int blockZ = chunkZ << 4;
		prefetchRegionsForBlock(blockX, blockZ, radius);
	}

	public WaterInfo resolveWaterInfo(int blockX, int blockZ, int coverClass) {
		if (isWaterClass(coverClass)) {
			return WaterInfo.LAND;
		}
		WaterColumnData column = resolveColumnData(blockX, blockZ, coverClass);
		if (!column.hasWater()) {
			return WaterInfo.LAND;
		}
		return new WaterInfo(true, column.isOcean(), column.waterSurface(), column.terrainSurface());
	}

	public WaterInfo resolveFastWaterInfo(int blockX, int blockZ, int coverClass) {
		return resolveWaterInfo(blockX, blockZ, coverClass);
	}

	public WaterInfo resolveBlendedWaterInfo(int blockX, int blockZ, int coverClass) {
		return resolveWaterInfo(blockX, blockZ, coverClass);
	}

	public WaterColumnData resolveColumnData(int blockX, int blockZ) {
		int coverClass = this.landCoverSource.sampleCoverClass(blockX, blockZ, this.settings.worldScale());
		return resolveColumnData(blockX, blockZ, coverClass);
	}

	public WaterColumnData resolveColumnData(int blockX, int blockZ, int coverClass) {
		if (isWaterClass(coverClass)) {
			int surface = sampleSurfaceHeight(blockX, blockZ);
			return new WaterColumnData(false, false, surface, surface);
		}
		if (coverClass == ESA_NO_DATA) {
			int surface = sampleSurfaceHeight(blockX, blockZ);
			if (surface > this.seaLevel) {
				return new WaterColumnData(false, false, surface, surface);
			}
		}
		WaterRegionData region = resolveRegionData(regionCoord(blockX), regionCoord(blockZ));
		return region.columnData(blockX, blockZ);
	}

	public void prefetchRegionsForBlock(int blockX, int blockZ, int radius) {
		int regionX = regionCoord(blockX);
		int regionZ = regionCoord(blockZ);
		int clampedRadius = Math.max(0, radius);
		for (int dz = -clampedRadius; dz <= clampedRadius; dz++) {
			for (int dx = -clampedRadius; dx <= clampedRadius; dx++) {
				prefetchRegion(regionX + dx, regionZ + dz);
			}
		}
	}

	public void prefetchRegionsForArea(int minBlockX, int minBlockZ, int maxBlockX, int maxBlockZ) {
		int minX = Math.min(minBlockX, maxBlockX);
		int maxX = Math.max(minBlockX, maxBlockX);
		int minZ = Math.min(minBlockZ, maxBlockZ);
		int maxZ = Math.max(minBlockZ, maxBlockZ);
		int minRegionX = regionCoord(minX);
		int maxRegionX = regionCoord(maxX);
		int minRegionZ = regionCoord(minZ);
		int maxRegionZ = regionCoord(maxZ);
		for (int rz = minRegionZ; rz <= maxRegionZ; rz++) {
			for (int rx = minRegionX; rx <= maxRegionX; rx++) {
				prefetchRegion(rx, rz);
			}
		}
	}

	private void prefetchRegion(int regionX, int regionZ) {
		long key = pack(regionX, regionZ) ^ this.regionSalt;
		if (this.regionCache.getIfPresent(key) != null) {
			return;
		}
		try {
			this.regionCache.get(key, () -> buildRegionData(regionX, regionZ));
		} catch (Exception e) {
			Tellus.LOGGER.debug("Failed to prefetch water region {}:{}", regionX, regionZ, e);
		}
	}

	private WaterRegionData resolveRegionData(int regionX, int regionZ) {
		long key = pack(regionX, regionZ) ^ this.regionSalt;
		try {
			return this.regionCache.get(key, () -> buildRegionData(regionX, regionZ));
		} catch (Exception e) {
			Tellus.LOGGER.warn("Failed to build water region {}:{}", regionX, regionZ, e);
			return buildRegionData(regionX, regionZ);
		}
	}

	private boolean hasWaterNearChunk(int chunkX, int chunkZ, int padding) {
		int minX = (chunkX << 4) - padding;
		int minZ = (chunkZ << 4) - padding;
		int maxX = (chunkX << 4) + 15 + padding;
		int maxZ = (chunkZ << 4) + 15 + padding;
		for (int z = minZ; z <= maxZ; z++) {
			for (int x = minX; x <= maxX; x++) {
				int coverClass = this.landCoverSource.sampleCoverClass(x, z, this.settings.worldScale());
				if (coverClass == ESA_WATER) {
					return false;
				}
				if (coverClass == ESA_NO_DATA) {
					int surface = sampleSurfaceHeight(x, z);
					if (surface <= this.seaLevel) {
						return false;
					}
				}
			}
		}
		return true;
	}

	private WaterChunkData buildDryChunkData(int chunkX, int chunkZ) {
		int minX = chunkX << 4;
		int minZ = chunkZ << 4;
		int[] terrainSurface = new int[16 * 16];
		int[] waterSurface = new int[16 * 16];
		byte[] waterFlags = new byte[16 * 16];
		for (int dz = 0; dz < 16; dz++) {
			int worldZ = minZ + dz;
			int row = dz * 16;
			for (int dx = 0; dx < 16; dx++) {
				int worldX = minX + dx;
				int index = row + dx;
				int surface = sampleSurfaceHeight(worldX, worldZ);
				terrainSurface[index] = surface;
				waterSurface[index] = surface;
				waterFlags[index] = WATER_NONE;
			}
		}
		return new WaterChunkData(terrainSurface, waterSurface, waterFlags);
	}

	private WaterRegionData buildRegionData(int regionX, int regionZ) {
		long startNanos = DEBUG_WATER ? System.nanoTime() : 0L;
		int regionMinX = regionX * REGION_SIZE;
		int regionMinZ = regionZ * REGION_SIZE;
		int gridSize = REGION_SIZE + this.regionMargin * 2;
		int gridMinX = regionMinX - this.regionMargin;
		int gridMinZ = regionMinZ - this.regionMargin;
		int gridArea = gridSize * gridSize;
		RegionScratch scratch = REGION_SCRATCH.get();
		scratch.ensureCapacity(gridArea);
		scratch.resetLists();
		boolean[] baseWaterMask = scratch.baseWaterMask;
		boolean[] noDataMask = scratch.noDataMask;
		boolean[] landMaskLand = scratch.landMaskLand;
		int[] surfaceHeights = scratch.surfaceHeights;
		int coarseStep = COARSE_CONNECT_STEP;
		int inlandLevel = this.seaLevel + SEA_LEVEL_TOLERANCE;
		int coarseSize = (gridSize + coarseStep - 1) / coarseStep;
		int coarseArea = coarseSize * coarseSize;
		scratch.ensureCoarseCapacity(coarseArea);
		boolean[] coarseWater = scratch.coarseWater;
		boolean[] coarseInlandSeed = scratch.coarseInlandSeed;
		Arrays.fill(coarseWater, 0, coarseArea, false);
		Arrays.fill(coarseInlandSeed, 0, coarseArea, false);
		boolean hasWater = false;

		double worldScale = this.settings.worldScale();
		for (int dz = 0; dz < gridSize; dz++) {
			int worldZ = gridMinZ + dz;
			int row = dz * gridSize;
			int coarseZ = dz / coarseStep;
			int coarseRow = coarseZ * coarseSize;
			for (int dx = 0; dx < gridSize; dx++) {
				int worldX = gridMinX + dx;
				int coverClass = this.landCoverSource.sampleCoverClass(worldX, worldZ, worldScale);
				int surface = sampleSurfaceHeight(worldX, worldZ);
				boolean isNoData = coverClass == ESA_NO_DATA;
				TellusLandMaskSource.LandMaskSample landMaskSample =
						this.landMaskSource.sampleLandMask(worldX, worldZ, worldScale);
				boolean maskKnown = landMaskSample.known();
				boolean landMaskIsLand = maskKnown && landMaskSample.land();
				boolean oceanMask;
				if (maskKnown) {
					oceanMask = !landMaskIsLand && (isNoData || coverClass == ESA_WATER);
				} else {
					oceanMask = isNoData;
				}
				boolean isWater = coverClass == ESA_WATER || (oceanMask && surface <= this.seaLevel);
				int index = row + dx;
				baseWaterMask[index] = isWater;
				noDataMask[index] = oceanMask;
				landMaskLand[index] = landMaskIsLand;
				surfaceHeights[index] = surface;
				if (isWater) {
					hasWater = true;
					if (!oceanMask && surface <= inlandLevel) {
						int coarseIndex = coarseRow + (dx / coarseStep);
						coarseWater[coarseIndex] = true;
					}
				}
			}
		}

		if (!hasWater) {
			return buildDryRegionData(
					regionX,
					regionZ,
					regionMinX,
					regionMinZ,
					gridMinX,
					gridMinZ,
					gridSize,
					surfaceHeights,
					startNanos
			);
		}

		int[] componentIds = scratch.componentIds;
		Arrays.fill(componentIds, 0, gridArea, -1);
		ComponentData[] components = scratch.components;
		int componentCount = 0;

		for (int dz = 0; dz < gridSize; dz++) {
			int row = dz * gridSize;
			int coarseZ = dz / coarseStep;
			int coarseRow = coarseZ * coarseSize;
			for (int dx = 0; dx < gridSize; dx++) {
				int index = row + dx;
				if (!baseWaterMask[index] || noDataMask[index]) {
					continue;
				}
				if (surfaceHeights[index] > inlandLevel) {
					continue;
				}
				boolean touchesBelowSeaLand = false;
				for (int i = 0; i < NEIGHBOR_OFFSETS.length; i += 2) {
					int nx = dx + NEIGHBOR_OFFSETS[i];
					int nz = dz + NEIGHBOR_OFFSETS[i + 1];
					if (nx < 0 || nz < 0 || nx >= gridSize || nz >= gridSize) {
						continue;
					}
					int neighbor = nz * gridSize + nx;
					if (baseWaterMask[neighbor]) {
						continue;
					}
					if (surfaceHeights[neighbor] <= inlandLevel) {
						touchesBelowSeaLand = true;
						break;
					}
				}
				if (touchesBelowSeaLand) {
					int coarseIndex = coarseRow + (dx / coarseStep);
					coarseInlandSeed[coarseIndex] = true;
				}
			}
		}

		for (int index = 0; index < gridArea; index++) {
			if (!baseWaterMask[index] || componentIds[index] != -1) {
				continue;
			}
			ComponentData component = buildComponent(
					index,
					componentCount,
					gridSize,
					gridMinX,
					gridMinZ,
					baseWaterMask,
					noDataMask,
					landMaskLand,
					surfaceHeights,
					componentIds
			);
			components[componentCount] = component;
			componentCount++;
		}

		int[] waterSurface = scratch.waterSurface;
		int[] terrainSurface = scratch.terrainSurface;
		byte[] waterFlags = scratch.waterFlags;
		Arrays.fill(waterFlags, 0, gridArea, WATER_NONE);

		System.arraycopy(surfaceHeights, 0, terrainSurface, 0, gridArea);
		boolean[] inlandConnected = buildInlandConnectivity(scratch, coarseArea, coarseSize);

		for (int i = 0; i < componentCount; i++) {
			ComponentData component = components[i];
			int spillHeight = component.borderHeights.isEmpty()
					? component.averageHeight()
					: percentile(component.borderHeights, BORDER_HEIGHT_PERCENTILE);
			boolean belowSea = component.cellCount > 0
					&& component.belowSeaCellCount / (double) component.cellCount >= BELOW_SEA_CELL_RATIO;
			boolean inlandConnectedComponent = belowSea && componentTouchesInlandConnected(
					component,
					inlandConnected,
					coarseSize,
					coarseStep,
					gridSize
			);
			boolean landMaskInland = component.cellCount > 0
					&& component.landMaskLandCount / (double) component.cellCount >= LANDMASK_INLAND_RATIO;
			boolean isOcean = (!landMaskInland && component.touchesNoData)
					|| (!landMaskInland && belowSea && !inlandConnectedComponent);
			component.isOcean = isOcean;
			int componentSurface = isOcean ? this.seaLevel : spillHeight;
			fillComponentSurface(component, waterSurface, componentSurface);

			if (isOcean) {
			continue;
			}

			int width = component.maxX - component.minX + 1;
			int height = component.maxZ - component.minZ + 1;
			int maxDim = Math.max(width, height);
			int minDim = Math.max(1, Math.min(width, height));
			double aspect = maxDim / (double) minDim;
			boolean riverShape = maxDim >= this.riverMinLength
					&& minDim <= this.riverMaxWidth
					&& aspect >= RIVER_ASPECT_RATIO;
			if (!riverShape && component.touchesEdge && !this.regionClamped) {
				riverShape = maxDim >= this.riverMinLength;
			}
			if (riverShape && shouldTreatRiverAsLake(component, width, height, minDim, aspect)) {
				riverShape = false;
			}
		if (riverShape) {
			RiverSurface riverSurface = buildRiverSurface(component, componentSurface, gridSize);
			for (int c = 0; c < component.cells.size(); c++) {
					int cell = component.cells.getInt(c);
					int x = cell % gridSize;
					int z = cell / gridSize;
					waterSurface[cell] = riverSurface.surfaceAt(x, z);
				}
			}
		}

		boolean[] inlandWaterMask = scratch.inlandWaterMask;
		boolean[] oceanComponentMask = scratch.oceanComponentMask;
		Arrays.fill(inlandWaterMask, 0, gridArea, false);
		Arrays.fill(oceanComponentMask, 0, gridArea, false);
		for (int i = 0; i < componentCount; i++) {
			ComponentData component = components[i];
			boolean ocean = component.isOcean;
			for (int c = 0; c < component.cells.size(); c++) {
				int cell = component.cells.getInt(c);
				if (ocean) {
					oceanComponentMask[cell] = true;
				} else {
					inlandWaterMask[cell] = true;
				}
			}
		}

		boolean[] waterMask = scratch.waterMask;
		for (int index = 0; index < gridArea; index++) {
			waterMask[index] = oceanComponentMask[index] || inlandWaterMask[index];
		}
		boolean[] landMask = scratch.landMask;
		for (int index = 0; index < gridArea; index++) {
			landMask[index] = !waterMask[index];
		}

		boolean[] cliffLandMask = scratch.cliffLandMask;
		boolean[] cliffWaterMask = scratch.cliffWaterMask;
		Arrays.fill(cliffLandMask, 0, gridArea, false);
		Arrays.fill(cliffWaterMask, 0, gridArea, false);
		for (int index = 0; index < gridArea; index++) {
			if (!waterMask[index]) {
				continue;
			}
			int x = index % gridSize;
			int z = index / gridSize;
			int waterSurfaceY = waterSurface[index];
			for (int i = 0; i < NEIGHBOR_OFFSETS.length; i += 2) {
				int nx = x + NEIGHBOR_OFFSETS[i];
				int nz = z + NEIGHBOR_OFFSETS[i + 1];
				if (nx < 0 || nz < 0 || nx >= gridSize || nz >= gridSize) {
					continue;
				}
				int neighbor = nz * gridSize + nx;
				if (!landMask[neighbor]) {
					continue;
				}
				int landHeight = surfaceHeights[neighbor];
				if (landHeight - waterSurfaceY >= this.cliffSlopeThreshold) {
					cliffLandMask[neighbor] = true;
					cliffWaterMask[index] = true;
				}
			}
		}

		IntArrayList shoreWater = scratch.shoreWater;
		shoreWater.clear();
		for (int index = 0; index < gridArea; index++) {
			if (!inlandWaterMask[index]) {
				continue;
			}
			int x = index % gridSize;
			int z = index / gridSize;
			if (isShoreCell(x, z, gridSize, inlandWaterMask)) {
				shoreWater.add(index);
			}
		}

		int[] waterDistanceCost = scratch.waterDistanceCost;
		int maxDistanceBlocks = Math.min(this.maxDistanceToShore, this.regionMargin);
		computeWeightedDistance(
				waterDistanceCost,
				inlandWaterMask,
				shoreWater,
				gridSize,
				maxDistanceBlocks,
				DIST_COST_CARDINAL
		);
		int maxDistanceCost = maxDistanceBlocks * DIST_COST_CARDINAL;

		for (int index = 0; index < gridArea; index++) {
			if (oceanComponentMask[index]) {
				waterFlags[index] = WATER_OCEAN;
				int floor = surfaceHeights[index];
				int maxFloor = waterSurface[index] - OCEAN_MIN_DEPTH;
				if (floor > maxFloor) {
					floor = maxFloor;
				}
				terrainSurface[index] = floor;
				continue;
			}
			if (!inlandWaterMask[index]) {
				continue;
			}
			waterFlags[index] = WATER_INLAND;
			if (cliffWaterMask[index]) {
				int floor = surfaceHeights[index];
				int maxFloor = waterSurface[index] - 1;
				if (floor > maxFloor) {
					floor = maxFloor;
				}
				terrainSurface[index] = floor;
				continue;
			}
			int distanceCost = waterDistanceCost[index];
			if (distanceCost == Integer.MAX_VALUE) {
				distanceCost = maxDistanceCost;
			}
			int componentId = componentIds[index];
			if (componentId >= 0 && componentId < componentCount) {
				ComponentData component = components[componentId];
				if (component != null && !component.isOcean) {
					component.maxDistanceCost = Math.max(component.maxDistanceCost, distanceCost);
				}
			}
			double distance = distanceCost / (double) DIST_COST_CARDINAL;
			int x = index % gridSize;
			int z = index / gridSize;
			int depth = computeInlandDepth(distance, gridMinX + x, gridMinZ + z);
			int floor = waterSurface[index] - depth;
			if (floor >= waterSurface[index]) {
				floor = waterSurface[index] - 1;
			}
			terrainSurface[index] = floor;
		}

		applyShorelineBlend(
				terrainSurface,
				surfaceHeights,
				waterSurface,
				inlandWaterMask,
				landMask,
				cliffLandMask,
				gridSize,
				this.riverLakeBlendDistance
		);
		applyShorelineBlend(
				terrainSurface,
				surfaceHeights,
				waterSurface,
				oceanComponentMask,
				landMask,
				cliffLandMask,
				gridSize,
				this.oceanBlendDistance
		);

		if (LAKE_SMOOTH_PASSES > 0) {
			smoothLakeBeds(
					terrainSurface,
					waterSurface,
					inlandWaterMask,
					cliffWaterMask,
					componentIds,
					components,
					componentCount,
					waterDistanceCost,
					gridSize
			);
		}

		int[] regionTerrain = new int[REGION_SIZE * REGION_SIZE];
		int[] regionWater = new int[REGION_SIZE * REGION_SIZE];
		byte[] regionFlags = new byte[REGION_SIZE * REGION_SIZE];

		for (int dz = 0; dz < REGION_SIZE; dz++) {
			int worldZ = regionMinZ + dz;
			int gridZ = worldZ - gridMinZ;
			int gridRow = gridZ * gridSize;
			int regionRow = dz * REGION_SIZE;
			for (int dx = 0; dx < REGION_SIZE; dx++) {
				int worldX = regionMinX + dx;
				int gridX = worldX - gridMinX;
				int gridIndex = gridRow + gridX;
				int regionIndex = regionRow + dx;
				int terrain = terrainSurface[gridIndex];
				regionTerrain[regionIndex] = terrain;
				byte flag = waterFlags[gridIndex];
				regionFlags[regionIndex] = flag;
				regionWater[regionIndex] = flag == WATER_NONE ? terrain : waterSurface[gridIndex];
			}
		}

		if (DEBUG_WATER) {
			long elapsed = System.nanoTime() - startNanos;
			Tellus.LOGGER.info(
					"Water region {}:{} computed in {} ms (scale {}, margin {})",
					regionX,
					regionZ,
					elapsed / 1_000_000L,
					this.settings.worldScale(),
					this.regionMargin
			);
		}

		clearComponents(components, componentCount);
		return new WaterRegionData(regionMinX, regionMinZ, regionTerrain, regionWater, regionFlags);
	}

	private WaterRegionData buildDryRegionData(
			int regionX,
			int regionZ,
			int regionMinX,
			int regionMinZ,
			int gridMinX,
			int gridMinZ,
			int gridSize,
			int[] surfaceHeights,
			long startNanos
	) {
		int[] regionTerrain = new int[REGION_SIZE * REGION_SIZE];
		int[] regionWater = new int[REGION_SIZE * REGION_SIZE];
		byte[] regionFlags = new byte[REGION_SIZE * REGION_SIZE];

		for (int dz = 0; dz < REGION_SIZE; dz++) {
			int worldZ = regionMinZ + dz;
			int gridZ = worldZ - gridMinZ;
			int gridRow = gridZ * gridSize;
			int regionRow = dz * REGION_SIZE;
			for (int dx = 0; dx < REGION_SIZE; dx++) {
				int worldX = regionMinX + dx;
				int gridX = worldX - gridMinX;
				int gridIndex = gridRow + gridX;
				int regionIndex = regionRow + dx;
				int terrain = surfaceHeights[gridIndex];
				regionTerrain[regionIndex] = terrain;
				regionWater[regionIndex] = terrain;
				regionFlags[regionIndex] = WATER_NONE;
			}
		}

		if (DEBUG_WATER) {
			long elapsed = System.nanoTime() - startNanos;
			Tellus.LOGGER.info(
					"Water region {}:{} computed in {} ms (scale {}, margin {})",
					regionX,
					regionZ,
					elapsed / 1_000_000L,
					this.settings.worldScale(),
					this.regionMargin
			);
		}

		return new WaterRegionData(regionMinX, regionMinZ, regionTerrain, regionWater, regionFlags);
	}

	private ComponentData buildComponent(
			int startIndex,
			int componentId,
			int gridSize,
			int gridMinX,
			int gridMinZ,
			boolean[] waterMask,
			boolean[] noDataMask,
			boolean[] landMaskLand,
			int[] surfaceHeights,
			int[] componentIds
	) {
		IntArrayList cells = new IntArrayList();
		IntArrayList borderHeights = new IntArrayList();
		ComponentData component = new ComponentData(componentId, cells, borderHeights);
		cells.add(startIndex);
		componentIds[startIndex] = componentId;

		for (int queueIndex = 0; queueIndex < cells.size(); queueIndex++) {
			int index = cells.getInt(queueIndex);
			int x = index % gridSize;
			int z = index / gridSize;
			int height = surfaceHeights[index];

			component.heightSum += height;
			component.cellCount++;
			if (height <= this.seaLevel + SEA_LEVEL_TOLERANCE) {
				component.belowSeaCellCount++;
			}
			component.minX = Math.min(component.minX, x);
			component.maxX = Math.max(component.maxX, x);
			component.minZ = Math.min(component.minZ, z);
			component.maxZ = Math.max(component.maxZ, z);
			if (height < component.minHeight) {
				component.minHeight = height;
				component.minHeightIndex = index;
			}
			if (height > component.maxHeight) {
				component.maxHeight = height;
				component.maxHeightIndex = index;
			}

			if (noDataMask[index]) {
				component.touchesNoData = true;
			}
			if (landMaskLand[index]) {
				component.landMaskLandCount++;
			}
			if (x == 0 || z == 0 || x == gridSize - 1 || z == gridSize - 1) {
				component.touchesEdge = true;
			}

			for (int i = 0; i < NEIGHBOR_OFFSETS.length; i += 2) {
				int nx = x + NEIGHBOR_OFFSETS[i];
				int nz = z + NEIGHBOR_OFFSETS[i + 1];
				if (nx < 0 || nz < 0 || nx >= gridSize || nz >= gridSize) {
					component.touchesEdge = true;
					continue;
				}
				int neighbor = nz * gridSize + nx;
				if (!waterMask[neighbor]) {
					borderHeights.add(surfaceHeights[neighbor]);
					continue;
				}
				if (componentIds[neighbor] == -1) {
					componentIds[neighbor] = componentId;
					cells.add(neighbor);
				}
			}
		}

		return component;
	}

	private RiverSurface buildRiverSurface(ComponentData component, int inlandSurface, int gridSize) {
		int minIndex = component.minHeightIndex;
		int maxIndex = component.maxHeightIndex;
		int minX = minIndex % gridSize;
		int minZ = minIndex / gridSize;
		int maxX = maxIndex % gridSize;
		int maxZ = maxIndex / gridSize;
		double axisX = maxX - minX;
		double axisZ = maxZ - minZ;
		double axisLength = Math.sqrt(axisX * axisX + axisZ * axisZ);
		if (axisLength < 1.0) {
			return new RiverSurface(minX, minZ, 0.0, 0.0, 0.0, 1.0, inlandSurface, inlandSurface);
		}
		double ux = axisX / axisLength;
		double uz = axisZ / axisLength;
		double minProj = Double.POSITIVE_INFINITY;
		double maxProj = Double.NEGATIVE_INFINITY;

		for (int i = 0; i < component.cells.size(); i++) {
			int cell = component.cells.getInt(i);
			int x = cell % gridSize;
			int z = cell / gridSize;
			double proj = (x - minX) * ux + (z - minZ) * uz;
			minProj = Math.min(minProj, proj);
			maxProj = Math.max(maxProj, proj);
		}

		double length = Math.max(1.0, maxProj - minProj);
		int flatSurface = Math.min(inlandSurface, component.maxHeight);
		return new RiverSurface(minX, minZ, ux, uz, minProj, length, flatSurface, flatSurface);
	}

	private int computeInlandDepth(double distance, int worldX, int worldZ) {
		if (distance <= INLAND_SHORE_DEPTH1_LIMIT) {
			return 1;
		}
		if (distance <= INLAND_SHORE_DEPTH3_LIMIT) {
			return 3;
		}
		if (distance <= INLAND_SHORE_DEPTH4_LIMIT) {
			return 4;
		}
		long seed = seedFromCoords(worldX, 5, worldZ);
		int jitterRange = INLAND_RANDOM_DEPTH_MAX - INLAND_RANDOM_DEPTH_MIN + 1;
		int jitter = INLAND_RANDOM_DEPTH_MIN + (int) Math.floorMod(seed, jitterRange);
		int extra = (int) Math.floor(Math.max(0.0, distance - INLAND_SHORE_DEPTH4_LIMIT) / INLAND_DEEP_DISTANCE_STEP);
		int depth = jitter + extra;
		return Math.min(INLAND_MAX_DEPTH, depth);
	}

	private boolean[] buildInlandConnectivity(RegionScratch scratch, int coarseArea, int coarseSize) {
		boolean[] coarseWater = scratch.coarseWater;
		boolean[] coarseInlandSeed = scratch.coarseInlandSeed;
		boolean[] coarseInlandConnected = scratch.coarseInlandConnected;
		Arrays.fill(coarseInlandConnected, 0, coarseArea, false);
		IntArrayList queue = scratch.coarseQueue;
		queue.clear();
		for (int i = 0; i < coarseArea; i++) {
			if (coarseWater[i] && coarseInlandSeed[i]) {
				coarseInlandConnected[i] = true;
				queue.add(i);
			}
		}
		for (int qi = 0; qi < queue.size(); qi++) {
			int index = queue.getInt(qi);
			int x = index % coarseSize;
			int z = index / coarseSize;
			for (int i = 0; i < NEIGHBOR_OFFSETS.length; i += 2) {
				int nx = x + NEIGHBOR_OFFSETS[i];
				int nz = z + NEIGHBOR_OFFSETS[i + 1];
				if (nx < 0 || nz < 0 || nx >= coarseSize || nz >= coarseSize) {
					continue;
				}
				int neighbor = nz * coarseSize + nx;
				if (!coarseWater[neighbor] || coarseInlandConnected[neighbor]) {
					continue;
				}
				coarseInlandConnected[neighbor] = true;
				queue.add(neighbor);
			}
		}
		return coarseInlandConnected;
	}

	private boolean componentTouchesInlandConnected(
			ComponentData component,
			boolean[] inlandConnected,
			int coarseSize,
			int step,
			int gridSize
	) {
		for (int i = 0; i < component.cells.size(); i++) {
			int cell = component.cells.getInt(i);
			int x = cell % gridSize;
			int z = cell / gridSize;
			int coarseIndex = (z / step) * coarseSize + (x / step);
			if (inlandConnected[coarseIndex]) {
				return true;
			}
		}
		return false;
	}

	private void applyShorelineBlend(
			int[] terrainSurface,
			int[] baseSurface,
			int[] waterSurface,
			boolean[] waterMask,
			boolean[] landMask,
			boolean[] cliffLandMask,
			int gridSize,
			int blendDistance
	) {
		if (blendDistance <= 0) {
			return;
		}
		int gridArea = gridSize * gridSize;
		RegionScratch scratch = REGION_SCRATCH.get();
		int[] landDistanceCost = scratch.landDistanceCost;
		int[] nearestSurface = scratch.nearestSurface;
		boolean[] landSource = scratch.landSource;
		boolean[] blendLandMask = scratch.blendLandMask;
		Arrays.fill(landSource, 0, gridArea, false);
		for (int index = 0; index < gridArea; index++) {
			blendLandMask[index] = landMask[index]
					&& (!this.limitShorelineBlendBySlope || !cliffLandMask[index]);
		}
		IntArrayList shoreLand = scratch.shoreLand;
		shoreLand.clear();
		for (int index = 0; index < gridArea; index++) {
			if (!waterMask[index]) {
				continue;
			}
			int x = index % gridSize;
			int z = index / gridSize;
			int sourceSurface = waterSurface[index];
			for (int n = 0; n < NEIGHBOR_OFFSETS.length; n += 2) {
				int nx = x + NEIGHBOR_OFFSETS[n];
				int nz = z + NEIGHBOR_OFFSETS[n + 1];
				if (nx < 0 || nz < 0 || nx >= gridSize || nz >= gridSize) {
					continue;
				}
				int neighbor = nz * gridSize + nx;
				if (!blendLandMask[neighbor]) {
					continue;
				}
				if (!landSource[neighbor]) {
					landSource[neighbor] = true;
					nearestSurface[neighbor] = sourceSurface;
					shoreLand.add(neighbor);
				}
			}
		}
		if (shoreLand.isEmpty()) {
			return;
		}
		computeWeightedDistanceWithSurface(
				landDistanceCost,
				nearestSurface,
				blendLandMask,
				shoreLand,
				gridSize,
				blendDistance,
				DIST_COST_CARDINAL
		);
		int maxBlendCost = blendDistance * DIST_COST_CARDINAL;
		for (int index = 0; index < gridArea; index++) {
			if (!blendLandMask[index]) {
				continue;
			}
			int distanceCost = landDistanceCost[index];
			if (distanceCost == Integer.MAX_VALUE || distanceCost > maxBlendCost) {
				continue;
			}
			double distance = distanceCost / (double) DIST_COST_CARDINAL;
			double t = distance / (double) blendDistance;
			int sourceSurface = nearestSurface[index];
			int base = baseSurface[index];
			int blended = (int) Math.round(Mth.lerp(t, sourceSurface, base));
			if (blended < base) {
				terrainSurface[index] = blended;
			}
		}
	}

	private boolean shouldTreatRiverAsLake(
			ComponentData component,
			int width,
			int height,
			int minDim,
			double aspect
	) {
		if (minDim <= 0) {
			return false;
		}
		if (aspect >= RIVER_ASPECT_RATIO * RIVER_LAKE_ASPECT_FACTOR) {
			return false;
		}
		int minWidth = Math.max(RIVER_LAKE_MIN_WIDTH, (int) Math.round(this.riverMaxWidth * RIVER_LAKE_WIDTH_FACTOR));
		if (minDim < minWidth) {
			return false;
		}
		int area = width * height;
		if (area <= 0) {
			return false;
		}
		double fillRatio = component.cellCount / (double) area;
		return fillRatio >= RIVER_LAKE_FILL_THRESHOLD;
	}

	private void smoothLakeBeds(
			int[] terrainSurface,
			int[] waterSurface,
			boolean[] inlandWaterMask,
			boolean[] cliffWaterMask,
			int[] componentIds,
			ComponentData[] components,
			int componentCount,
			int[] waterDistanceCost,
			int gridSize
	) {
		int minSmoothCost = INLAND_SHORE_DEPTH4_LIMIT * DIST_COST_CARDINAL;
		RegionScratch scratch = REGION_SCRATCH.get();
		scratch.ensureCapacity(terrainSurface.length);
		int[] smoothed = scratch.smoothedTerrain;
		for (int pass = 0; pass < LAKE_SMOOTH_PASSES; pass++) {
			System.arraycopy(terrainSurface, 0, smoothed, 0, terrainSurface.length);
			for (int i = 0; i < componentCount; i++) {
				ComponentData component = components[i];
				if (component.isOcean) {
					continue;
				}
				if (component.maxDistanceCost <= minSmoothCost + DIST_COST_CARDINAL) {
					continue;
				}
				for (int c = 0; c < component.cells.size(); c++) {
					int cell = component.cells.getInt(c);
					if (cliffWaterMask[cell]) {
						continue;
					}
					int distanceCost = waterDistanceCost[cell];
					if (distanceCost == Integer.MAX_VALUE || distanceCost <= minSmoothCost) {
						continue;
					}
					int x = cell % gridSize;
					int z = cell / gridSize;
					int sum = terrainSurface[cell];
					int count = 1;
					for (int n = 0; n < NEIGHBOR_OFFSETS_8.length; n += 2) {
						int nx = x + NEIGHBOR_OFFSETS_8[n];
						int nz = z + NEIGHBOR_OFFSETS_8[n + 1];
						if (nx < 0 || nz < 0 || nx >= gridSize || nz >= gridSize) {
							continue;
						}
						int neighbor = nz * gridSize + nx;
						if (!inlandWaterMask[neighbor] || componentIds[neighbor] != component.id) {
							continue;
						}
						sum += terrainSurface[neighbor];
						count++;
					}
					int avg = (int) Math.round(sum / (double) count);
					int maxFloor = waterSurface[cell] - 1;
					if (avg > maxFloor) {
						avg = maxFloor;
					}
					smoothed[cell] = avg;
				}
			}
			System.arraycopy(smoothed, 0, terrainSurface, 0, terrainSurface.length);
		}
	}

	private static void clearComponents(ComponentData[] components, int count) {
		for (int i = 0; i < count; i++) {
			components[i] = null;
		}
	}

	private boolean isShoreCell(int x, int z, int gridSize, boolean[] waterMask) {
		for (int i = 0; i < NEIGHBOR_OFFSETS.length; i += 2) {
			int nx = x + NEIGHBOR_OFFSETS[i];
			int nz = z + NEIGHBOR_OFFSETS[i + 1];
			if (nx < 0 || nz < 0 || nx >= gridSize || nz >= gridSize) {
				return true;
			}
			int neighbor = nz * gridSize + nx;
			if (!waterMask[neighbor]) {
				return true;
			}
		}
		return false;
	}

	private void fillComponentSurface(ComponentData component, int[] waterSurface, int surface) {
		for (int i = 0; i < component.cells.size(); i++) {
			waterSurface[component.cells.getInt(i)] = surface;
		}
	}

	private void computeWeightedDistance(
			int[] distances,
			boolean[] allowed,
			IntArrayList sources,
			int gridSize,
			int maxDistanceBlocks,
			int initialCost
	) {
		int gridArea = gridSize * gridSize;
		Arrays.fill(distances, 0, gridArea, Integer.MAX_VALUE);
		if (sources.isEmpty()) {
			return;
		}
		int maxCost = Math.max(0, maxDistanceBlocks) * DIST_COST_CARDINAL;
		RegionScratch scratch = REGION_SCRATCH.get();
		scratch.ensureBucketCapacity(maxCost + 1);
		IntArrayList[] buckets = scratch.buckets;
		boolean[] bucketUsed = scratch.bucketUsed;
		IntArrayList usedBuckets = scratch.usedBuckets;
		usedBuckets.clear();

		int minCost = Integer.MAX_VALUE;
		for (int i = 0; i < sources.size(); i++) {
			int index = sources.getInt(i);
			if (!allowed[index]) {
				continue;
			}
			int cost = initialCost;
			if (cost > maxCost) {
				continue;
			}
			if (cost < distances[index]) {
				distances[index] = cost;
				addBucket(buckets, bucketUsed, usedBuckets, cost, index);
				if (cost < minCost) {
					minCost = cost;
				}
			}
		}

		if (minCost == Integer.MAX_VALUE) {
			clearBuckets(buckets, bucketUsed, usedBuckets);
			return;
		}

		for (int cost = minCost; cost <= maxCost; cost++) {
			IntArrayList bucket = buckets[cost];
			if (bucket == null || bucket.isEmpty()) {
				continue;
			}
			for (int bucketIndex = 0; bucketIndex < bucket.size(); bucketIndex++) {
				int index = bucket.getInt(bucketIndex);
				if (cost != distances[index]) {
					continue;
				}
				if (cost >= maxCost) {
					continue;
				}
				int x = index % gridSize;
				int z = index / gridSize;
				for (int i = 0; i < NEIGHBOR_OFFSETS_8.length; i += 2) {
					int nx = x + NEIGHBOR_OFFSETS_8[i];
					int nz = z + NEIGHBOR_OFFSETS_8[i + 1];
					if (nx < 0 || nz < 0 || nx >= gridSize || nz >= gridSize) {
						continue;
					}
					int neighbor = nz * gridSize + nx;
					if (!allowed[neighbor]) {
						continue;
					}
					int nextCost = cost + NEIGHBOR_COSTS_8[i / 2];
					if (nextCost < distances[neighbor] && nextCost <= maxCost) {
						distances[neighbor] = nextCost;
						addBucket(buckets, bucketUsed, usedBuckets, nextCost, neighbor);
					}
				}
			}
			bucket.clear();
		}
		clearBuckets(buckets, bucketUsed, usedBuckets);
	}

	private void computeWeightedDistanceWithSurface(
			int[] distances,
			int[] nearestSurface,
			boolean[] allowed,
			IntArrayList sources,
			int gridSize,
			int maxDistanceBlocks,
			int initialCost
	) {
		int gridArea = gridSize * gridSize;
		Arrays.fill(distances, 0, gridArea, Integer.MAX_VALUE);
		if (sources.isEmpty()) {
			return;
		}
		int maxCost = Math.max(0, maxDistanceBlocks) * DIST_COST_CARDINAL;
		RegionScratch scratch = REGION_SCRATCH.get();
		scratch.ensureBucketCapacity(maxCost + 1);
		IntArrayList[] buckets = scratch.buckets;
		boolean[] bucketUsed = scratch.bucketUsed;
		IntArrayList usedBuckets = scratch.usedBuckets;
		usedBuckets.clear();

		int minCost = Integer.MAX_VALUE;
		for (int i = 0; i < sources.size(); i++) {
			int index = sources.getInt(i);
			if (!allowed[index]) {
				continue;
			}
			int cost = initialCost;
			if (cost > maxCost) {
				continue;
			}
			if (cost < distances[index]) {
				distances[index] = cost;
				addBucket(buckets, bucketUsed, usedBuckets, cost, index);
				if (cost < minCost) {
					minCost = cost;
				}
			}
		}

		if (minCost == Integer.MAX_VALUE) {
			clearBuckets(buckets, bucketUsed, usedBuckets);
			return;
		}

		for (int cost = minCost; cost <= maxCost; cost++) {
			IntArrayList bucket = buckets[cost];
			if (bucket == null || bucket.isEmpty()) {
				continue;
			}
			for (int bucketIndex = 0; bucketIndex < bucket.size(); bucketIndex++) {
				int index = bucket.getInt(bucketIndex);
				if (cost != distances[index]) {
					continue;
				}
				if (cost >= maxCost) {
					continue;
				}
				int x = index % gridSize;
				int z = index / gridSize;
				int sourceSurface = nearestSurface[index];
				for (int i = 0; i < NEIGHBOR_OFFSETS_8.length; i += 2) {
					int nx = x + NEIGHBOR_OFFSETS_8[i];
					int nz = z + NEIGHBOR_OFFSETS_8[i + 1];
					if (nx < 0 || nz < 0 || nx >= gridSize || nz >= gridSize) {
						continue;
					}
					int neighbor = nz * gridSize + nx;
					if (!allowed[neighbor]) {
						continue;
					}
					int nextCost = cost + NEIGHBOR_COSTS_8[i / 2];
					if (nextCost < distances[neighbor] && nextCost <= maxCost) {
						distances[neighbor] = nextCost;
						nearestSurface[neighbor] = sourceSurface;
						addBucket(buckets, bucketUsed, usedBuckets, nextCost, neighbor);
					}
				}
			}
			bucket.clear();
		}
		clearBuckets(buckets, bucketUsed, usedBuckets);
	}

	private static void addBucket(
			IntArrayList[] buckets,
			boolean[] bucketUsed,
			IntArrayList usedBuckets,
			int cost,
			int index
	) {
		IntArrayList bucket = buckets[cost];
		if (bucket == null) {
			bucket = new IntArrayList();
			buckets[cost] = bucket;
		}
		if (!bucketUsed[cost]) {
			bucket.clear();
			bucketUsed[cost] = true;
			usedBuckets.add(cost);
		}
		bucket.add(index);
	}

	private static void clearBuckets(
			IntArrayList[] buckets,
			boolean[] bucketUsed,
			IntArrayList usedBuckets
	) {
		for (int i = 0; i < usedBuckets.size(); i++) {
			int cost = usedBuckets.getInt(i);
			IntArrayList bucket = buckets[cost];
			if (bucket != null) {
				bucket.clear();
			}
			bucketUsed[cost] = false;
		}
		usedBuckets.clear();
	}

	private int sampleSurfaceHeight(double blockX, double blockZ) {
		boolean oceanZoom = useOceanZoom(blockX, blockZ);
		double elevation = this.elevationSource.sampleElevationMeters(blockX, blockZ, this.settings.worldScale(), oceanZoom);
		double heightScale = elevation >= 0.0 ? this.settings.terrestrialHeightScale() : this.settings.oceanicHeightScale();
		double scaled = elevation * heightScale / this.settings.worldScale();
		int offset = this.settings.heightOffset();
		int height = elevation >= 0.0 ? Mth.ceil(scaled) : Mth.floor(scaled);
		return height + offset;
	}

	private boolean useOceanZoom(double blockX, double blockZ) {
		TellusLandMaskSource.LandMaskSample landSample =
				this.landMaskSource.sampleLandMask(blockX, blockZ, this.settings.worldScale());
		if (!landSample.known()) {
			return true;
		}
		if (landSample.land()) {
			return false;
		}
		int coverClass = this.landCoverSource.sampleCoverClass(blockX, blockZ, this.settings.worldScale());
		return coverClass == ESA_NO_DATA;
	}

	private int metersToBlocks(double meters) {
		double scale = Math.max(0.0001, this.settings.worldScale());
		int blocks = (int) Math.round(meters / scale);
		return Math.max(1, blocks);
	}

	private static int clampBlend(int blocks) {
		return Mth.clamp(blocks, 0, 10);
	}

	private static int percentile(IntArrayList values, double percentile) {
		int[] data = values.toIntArray();
		if (data.length == 0) {
			return 0;
		}
		int index = (int) Math.floor(percentile * (data.length - 1));
		index = Mth.clamp(index, 0, data.length - 1);
		return selectNth(data, index);
	}

	private static int selectNth(int[] data, int index) {
		int left = 0;
		int right = data.length - 1;
		while (left < right) {
			int pivotIndex = left + ((right - left) >>> 1);
			pivotIndex = partition(data, left, right, pivotIndex);
			if (index == pivotIndex) {
				return data[index];
			}
			if (index < pivotIndex) {
				right = pivotIndex - 1;
			} else {
				left = pivotIndex + 1;
			}
		}
		return data[left];
	}

	private static int partition(int[] data, int left, int right, int pivotIndex) {
		int pivotValue = data[pivotIndex];
		swap(data, pivotIndex, right);
		int storeIndex = left;
		for (int i = left; i < right; i++) {
			if (data[i] < pivotValue) {
				swap(data, storeIndex, i);
				storeIndex++;
			}
		}
		swap(data, right, storeIndex);
		return storeIndex;
	}

	private static void swap(int[] data, int left, int right) {
		if (left == right) {
			return;
		}
		int temp = data[left];
		data[left] = data[right];
		data[right] = temp;
	}

	private static final class RegionScratch {
		private int capacity;
		private boolean[] baseWaterMask;
		private boolean[] noDataMask;
		private boolean[] landMaskLand;
		private int[] surfaceHeights;
		private int[] componentIds;
		private ComponentData[] components;
		private int[] waterSurface;
		private int[] terrainSurface;
		private int[] smoothedTerrain;
		private byte[] waterFlags;
		private boolean[] inlandWaterMask;
		private boolean[] oceanComponentMask;
		private boolean[] waterMask;
		private boolean[] landMask;
		private boolean[] cliffLandMask;
		private boolean[] cliffWaterMask;
		private boolean[] blendLandMask;
		private int[] waterDistanceCost;
		private int[] landDistanceCost;
		private int[] nearestSurface;
		private boolean[] landSource;
		private final IntArrayList shoreWater = new IntArrayList();
		private final IntArrayList shoreLand = new IntArrayList();
		private int coarseCapacity;
		private boolean[] coarseWater;
		private boolean[] coarseInlandSeed;
		private boolean[] coarseInlandConnected;
		private final IntArrayList coarseQueue = new IntArrayList();
		private IntArrayList[] buckets;
		private boolean[] bucketUsed;
		private final IntArrayList usedBuckets = new IntArrayList();
		private int bucketCapacity;

		private void ensureCapacity(int size) {
			if (size <= this.capacity) {
				return;
			}
			this.capacity = size;
			this.baseWaterMask = new boolean[size];
			this.noDataMask = new boolean[size];
			this.landMaskLand = new boolean[size];
			this.surfaceHeights = new int[size];
			this.componentIds = new int[size];
			this.components = new ComponentData[size];
			this.waterSurface = new int[size];
			this.terrainSurface = new int[size];
			this.smoothedTerrain = new int[size];
			this.waterFlags = new byte[size];
			this.inlandWaterMask = new boolean[size];
			this.oceanComponentMask = new boolean[size];
			this.waterMask = new boolean[size];
			this.landMask = new boolean[size];
			this.cliffLandMask = new boolean[size];
			this.cliffWaterMask = new boolean[size];
			this.blendLandMask = new boolean[size];
			this.waterDistanceCost = new int[size];
			this.landDistanceCost = new int[size];
			this.nearestSurface = new int[size];
			this.landSource = new boolean[size];
		}

		private void ensureCoarseCapacity(int size) {
			if (size <= this.coarseCapacity) {
				return;
			}
			this.coarseCapacity = size;
			this.coarseWater = new boolean[size];
			this.coarseInlandSeed = new boolean[size];
			this.coarseInlandConnected = new boolean[size];
		}

		private void ensureBucketCapacity(int size) {
			if (size <= this.bucketCapacity) {
				return;
			}
			this.bucketCapacity = size;
			this.buckets = new IntArrayList[size];
			this.bucketUsed = new boolean[size];
		}

		private void resetLists() {
			this.shoreWater.clear();
			this.shoreLand.clear();
		}
	}

	private static int regionCoord(int blockCoord) {
		return Math.floorDiv(blockCoord, REGION_SIZE);
	}

	private static long pack(int x, int z) {
		return ((long) x << 32) ^ (z & 0xffffffffL);
	}

	public record WaterColumnData(boolean hasWater, boolean isOcean, int terrainSurface, int waterSurface) {
	}

	public record WaterInfo(boolean isWater, boolean isOcean, int surface, int terrainSurface) {
		static final WaterInfo LAND = new WaterInfo(false, false, Integer.MIN_VALUE, Integer.MIN_VALUE);
	}

	public static final class WaterChunkData {
		private final int[] terrainSurface;
		private final int[] waterSurface;
		private final byte[] waterFlags;

		private WaterChunkData(int[] terrainSurface, int[] waterSurface, byte[] waterFlags) {
			this.terrainSurface = terrainSurface;
			this.waterSurface = waterSurface;
			this.waterFlags = waterFlags;
		}

		private WaterChunkData(int chunkX, int chunkZ, WaterRegionData region) {
			int minX = chunkX << 4;
			int minZ = chunkZ << 4;
			this.terrainSurface = new int[16 * 16];
			this.waterSurface = new int[16 * 16];
			this.waterFlags = new byte[16 * 16];

			for (int dz = 0; dz < 16; dz++) {
				int worldZ = minZ + dz;
				int row = dz * 16;
				for (int dx = 0; dx < 16; dx++) {
					int worldX = minX + dx;
					int index = row + dx;
					this.terrainSurface[index] = region.terrainSurface(worldX, worldZ);
					this.waterSurface[index] = region.waterSurface(worldX, worldZ);
					this.waterFlags[index] = region.waterFlag(worldX, worldZ);
				}
			}
		}

		public int terrainSurface(int localX, int localZ) {
			return this.terrainSurface[localZ * 16 + localX];
		}

		public int waterSurface(int localX, int localZ) {
			return this.waterSurface[localZ * 16 + localX];
		}

		public boolean hasWater(int localX, int localZ) {
			return this.waterFlags[localZ * 16 + localX] != WATER_NONE;
		}

		public boolean isOcean(int localX, int localZ) {
			return this.waterFlags[localZ * 16 + localX] == WATER_OCEAN;
		}
	}

	private static final class WaterRegionData {
		private final int minX;
		private final int minZ;
		private final int[] terrainSurface;
		private final int[] waterSurface;
		private final byte[] waterFlags;

		private WaterRegionData(int minX, int minZ, int[] terrainSurface, int[] waterSurface, byte[] waterFlags) {
			this.minX = minX;
			this.minZ = minZ;
			this.terrainSurface = terrainSurface;
			this.waterSurface = waterSurface;
			this.waterFlags = waterFlags;
		}

		private WaterColumnData columnData(int blockX, int blockZ) {
			int index = index(blockX, blockZ);
			byte flag = this.waterFlags[index];
			return new WaterColumnData(flag != WATER_NONE, flag == WATER_OCEAN, this.terrainSurface[index], this.waterSurface[index]);
		}

		private int terrainSurface(int blockX, int blockZ) {
			return this.terrainSurface[index(blockX, blockZ)];
		}

		private int waterSurface(int blockX, int blockZ) {
			return this.waterSurface[index(blockX, blockZ)];
		}

		private byte waterFlag(int blockX, int blockZ) {
			return this.waterFlags[index(blockX, blockZ)];
		}

		private int index(int blockX, int blockZ) {
			int localX = blockX - this.minX;
			int localZ = blockZ - this.minZ;
			return localZ * REGION_SIZE + localX;
		}
	}

	private static final class ComponentData {
		private final int id;
		private final IntArrayList cells;
		private final IntArrayList borderHeights;
		private int minX = Integer.MAX_VALUE;
		private int maxX = Integer.MIN_VALUE;
		private int minZ = Integer.MAX_VALUE;
		private int maxZ = Integer.MIN_VALUE;
		private int minHeight = Integer.MAX_VALUE;
		private int maxHeight = Integer.MIN_VALUE;
		private int minHeightIndex = -1;
		private int maxHeightIndex = -1;
		private long heightSum;
		private int cellCount;
		private int landMaskLandCount;
		private int belowSeaCellCount;
		private boolean touchesNoData;
		private boolean touchesEdge;
		private boolean isOcean;
		private int maxDistanceCost;

		private ComponentData(int id, IntArrayList cells, IntArrayList borderHeights) {
			this.id = id;
			this.cells = cells;
			this.borderHeights = borderHeights;
		}

		private int averageHeight() {
			if (this.cellCount <= 0) {
				return this.minHeight == Integer.MAX_VALUE ? 0 : this.minHeight;
			}
			return (int) Math.round(this.heightSum / (double) this.cellCount);
		}
	}

	private static final class RiverSurface {
		private final int originX;
		private final int originZ;
		private final double ux;
		private final double uz;
		private final double minProj;
		private final double length;
		private final int lowSurface;
		private final int highSurface;

		private RiverSurface(
				int originX,
				int originZ,
				double ux,
				double uz,
				double minProj,
				double length,
				int lowSurface,
				int highSurface
		) {
			this.originX = originX;
			this.originZ = originZ;
			this.ux = ux;
			this.uz = uz;
			this.minProj = minProj;
			this.length = length;
			this.lowSurface = lowSurface;
			this.highSurface = highSurface;
		}

		private int surfaceAt(int x, int z) {
			double proj = (x - this.originX) * this.ux + (z - this.originZ) * this.uz;
			double t = (proj - this.minProj) / Math.max(1.0, this.length);
			t = Mth.clamp(t, 0.0, 1.0);
			double surface = this.lowSurface + t * (this.highSurface - this.lowSurface);
			int height = (int) Math.round(surface);
			return Mth.clamp(height, this.lowSurface, this.highSurface);
		}
	}

	private static long seedFromCoords(int x, int y, int z) {
		long seed = (x * 3129871L) ^ (long) z * 116129781L ^ (long) y;
		seed = seed * seed * 42317861L + seed * 11L;
		return seed >> 16;
	}
}
