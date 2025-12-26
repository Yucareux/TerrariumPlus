package com.yucareux.terrariumplus.worldgen;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.yucareux.terrariumplus.Terrarium;
import com.yucareux.terrariumplus.world.data.cover.TerrariumLandCoverSource;
import com.yucareux.terrariumplus.world.data.elevation.TerrariumElevationSource;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.util.Arrays;
import java.util.PriorityQueue;
import net.minecraft.util.Mth;

public final class WaterSurfaceResolver {
	private static final int ESA_NO_DATA = 0;
	private static final int ESA_WATER = 80;
	private static final byte WATER_NONE = 0;
	private static final byte WATER_INLAND = 1;
	private static final byte WATER_OCEAN = 2;
	private static final int REGION_SIZE = 64;
	private static final int MAX_REGION_CACHE = 256;
	private static final int MAX_SLOPE_STEP = 6;
	private static final double DEFAULT_SCALE = EarthGeneratorSettings.DEFAULT.worldScale();
	private static final int CINEMATIC_MAX_DISTANCE_BLOCKS = 256;

	private static final double SHORELINE_SHALLOW_METERS = 3.0 * DEFAULT_SCALE;
	private static final double SHORELINE_MEDIUM_METERS = 8.0 * DEFAULT_SCALE;
	private static final double SHORE_BLEND_METERS = 6.0 * DEFAULT_SCALE;
	private static final double DEPTH_SHALLOW_METERS = 1.0 * DEFAULT_SCALE;
	private static final double DEPTH_MEDIUM_METERS = 2.0 * DEFAULT_SCALE;
	private static final double MAX_WATER_DEPTH_METERS = 15.0 * DEFAULT_SCALE;
	private static final double RIVER_MIN_LENGTH_METERS = 750.0;
	private static final double RIVER_MAX_WIDTH_METERS = 400.0;
	private static final double RIVER_ASPECT_RATIO = 3.0;
	private static final double BORDER_HEIGHT_PERCENTILE = 0.10;
	private static final double OCEAN_NO_DATA_THRESHOLD_METERS = 5.0;
	private static final int LAKE_SMOOTH_PASSES = 1;

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
	private static final boolean DEBUG_WATER = Boolean.getBoolean("terrariumplus.debugWater");

	private final TerrariumLandCoverSource landCoverSource;
	private final TerrariumElevationSource elevationSource;
	private final EarthGeneratorSettings settings;
	private final boolean seaLevelAutomatic;
	private final int seaLevel;
	private final int datumSeaLevel;
	private final Cache<Long, WaterRegionData> regionCache;
	private final long regionSalt;
	private final int shorelineShallowDistance;
	private final int shorelineMediumDistance;
	private final int depthShallowBlocks;
	private final int depthMediumBlocks;
	private final int maxWaterDepth;
	private final int shoreBlendDistance;
	private final int riverMinLength;
	private final int riverMaxWidth;
	private final int oceanNoDataThreshold;
	private final int maxDistanceToShore;
	private final int regionMargin;
	private final WaterInfo oceanInfo;

	public WaterSurfaceResolver(
			TerrariumLandCoverSource landCoverSource,
			TerrariumElevationSource elevationSource,
			EarthGeneratorSettings settings
	) {
		this.landCoverSource = landCoverSource;
		this.elevationSource = elevationSource;
		this.settings = settings;
		this.seaLevelAutomatic = settings.isSeaLevelAutomatic();
		this.seaLevel = settings.resolveSeaLevel();
		this.datumSeaLevel = settings.heightOffset();

		this.shorelineShallowDistance = metersToBlocks(SHORELINE_SHALLOW_METERS);
		this.shorelineMediumDistance = Math.max(
				this.shorelineShallowDistance,
				metersToBlocks(SHORELINE_MEDIUM_METERS)
		);
		this.depthShallowBlocks = metersToBlocks(DEPTH_SHALLOW_METERS);
		this.depthMediumBlocks = Math.max(this.depthShallowBlocks, metersToBlocks(DEPTH_MEDIUM_METERS));
		this.maxWaterDepth = Math.max(this.depthMediumBlocks, metersToBlocks(MAX_WATER_DEPTH_METERS));
		this.shoreBlendDistance = metersToBlocks(SHORE_BLEND_METERS);
		this.riverMinLength = metersToBlocks(RIVER_MIN_LENGTH_METERS);
		this.riverMaxWidth = metersToBlocks(RIVER_MAX_WIDTH_METERS);
		this.oceanNoDataThreshold = metersToBlocks(OCEAN_NO_DATA_THRESHOLD_METERS);
		this.maxDistanceToShore = this.shorelineMediumDistance
				+ MAX_SLOPE_STEP * Math.max(0, this.maxWaterDepth - this.depthMediumBlocks);
		this.regionMargin = Math.max(this.maxDistanceToShore, this.shoreBlendDistance) + 2;

		this.regionCache = CacheBuilder.newBuilder()
				.maximumSize(MAX_REGION_CACHE)
				.build();
		this.regionSalt = Double.doubleToLongBits(settings.worldScale()) ^ 0x9E3779B97F4A7C15L;
		this.oceanInfo = new WaterInfo(true, true, this.seaLevel, this.seaLevel - 1);
	}

	public boolean isWaterClass(int coverClass) {
		return coverClass == ESA_WATER || coverClass == ESA_NO_DATA;
	}

	public WaterChunkData resolveChunkWaterData(int chunkX, int chunkZ) {
		WaterRegionData region = resolveRegionData(regionCoord(chunkX << 4), regionCoord(chunkZ << 4));
		return new WaterChunkData(chunkX, chunkZ, region);
	}

	public WaterInfo resolveWaterInfo(int blockX, int blockZ, int coverClass) {
		if (coverClass == ESA_NO_DATA) {
			return this.oceanInfo;
		}
		if (coverClass != ESA_WATER) {
			return WaterInfo.LAND;
		}
		WaterColumnData column = resolveColumnData(blockX, blockZ);
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
		WaterRegionData region = resolveRegionData(regionCoord(blockX), regionCoord(blockZ));
		return region.columnData(blockX, blockZ);
	}

	private WaterRegionData resolveRegionData(int regionX, int regionZ) {
		long key = pack(regionX, regionZ) ^ this.regionSalt;
		WaterRegionData cached = this.regionCache.getIfPresent(key);
		if (cached != null) {
			return cached;
		}
		WaterRegionData built = buildRegionData(regionX, regionZ);
		this.regionCache.put(key, built);
		return built;
	}

	private WaterRegionData buildRegionData(int regionX, int regionZ) {
		long startNanos = DEBUG_WATER ? System.nanoTime() : 0L;
		int regionMinX = regionX * REGION_SIZE;
		int regionMinZ = regionZ * REGION_SIZE;
		int gridSize = REGION_SIZE + this.regionMargin * 2;
		int gridMinX = regionMinX - this.regionMargin;
		int gridMinZ = regionMinZ - this.regionMargin;
		int gridArea = gridSize * gridSize;

		boolean[] baseWaterMask = new boolean[gridArea];
		boolean[] noDataMask = new boolean[gridArea];
		int[] surfaceHeights = new int[gridArea];

		for (int dz = 0; dz < gridSize; dz++) {
			int worldZ = gridMinZ + dz;
			int row = dz * gridSize;
			for (int dx = 0; dx < gridSize; dx++) {
				int worldX = gridMinX + dx;
				int coverClass = this.landCoverSource.sampleCoverClass(worldX, worldZ, this.settings.worldScale());
				boolean isWater = coverClass == ESA_WATER || coverClass == ESA_NO_DATA;
				int index = row + dx;
				baseWaterMask[index] = isWater;
				noDataMask[index] = coverClass == ESA_NO_DATA;
				surfaceHeights[index] = sampleSurfaceHeight(worldX, worldZ);
			}
		}

		int[] componentIds = new int[gridArea];
		Arrays.fill(componentIds, -1);
		ComponentData[] components = new ComponentData[gridArea];
		int componentCount = 0;

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
					surfaceHeights,
					componentIds
			);
			components[componentCount] = component;
			componentCount++;
		}

		if (useLegacyWaterBehavior()) {
			return buildLegacyRegionData(
					regionX,
					regionZ,
					regionMinX,
					regionMinZ,
					gridSize,
					gridArea,
					baseWaterMask,
					surfaceHeights,
					componentIds,
					components,
					componentCount,
					startNanos
			);
		}

		int[] waterSurface = new int[gridArea];
		int[] terrainSurface = new int[gridArea];
		byte[] waterFlags = new byte[gridArea];

		System.arraycopy(surfaceHeights, 0, terrainSurface, 0, gridArea);

		for (int i = 0; i < componentCount; i++) {
			ComponentData component = components[i];
			int spillHeight = component.borderHeights.isEmpty()
					? component.averageHeight()
					: percentile(component.borderHeights, BORDER_HEIGHT_PERCENTILE);
			boolean oceanByNoData = component.touchesNoData
					&& component.minHeight <= this.datumSeaLevel + this.oceanNoDataThreshold;
			boolean isOcean = oceanByNoData || spillHeight <= this.datumSeaLevel;
			component.isOcean = isOcean;
			if (isOcean) {
				fillComponentSurface(component, waterSurface, this.seaLevel);
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
			if (!riverShape && component.touchesEdge) {
				riverShape = maxDim >= this.riverMinLength;
			}
			component.isRiver = riverShape;

			int inlandSurface = spillHeight;
			if (!riverShape) {
				fillComponentSurface(component, waterSurface, inlandSurface);
				continue;
			}

			RiverSurface riverSurface = buildRiverSurface(component, inlandSurface, gridSize);
			for (int c = 0; c < component.cells.size(); c++) {
				int cell = component.cells.getInt(c);
				int x = cell % gridSize;
				int z = cell / gridSize;
				waterSurface[cell] = riverSurface.surfaceAt(x, z);
			}
		}

		boolean[] inlandWaterMask = new boolean[gridArea];
		boolean[] oceanComponentMask = new boolean[gridArea];
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

		boolean[] oceanFloodMask = new boolean[gridArea];
		if (this.seaLevel > this.datumSeaLevel) {
			for (int index = 0; index < gridArea; index++) {
				if (inlandWaterMask[index]) {
					continue;
				}
				if (surfaceHeights[index] > this.seaLevel) {
					continue;
				}
				oceanFloodMask[index] = true;
			}
		} else {
			IntArrayList oceanQueue = new IntArrayList();
			for (int index = 0; index < gridArea; index++) {
				if (!oceanComponentMask[index]) {
					continue;
				}
				if (surfaceHeights[index] > this.seaLevel) {
					continue;
				}
				oceanFloodMask[index] = true;
				oceanQueue.add(index);
			}
			for (int queueIndex = 0; queueIndex < oceanQueue.size(); queueIndex++) {
				int index = oceanQueue.getInt(queueIndex);
				int x = index % gridSize;
				int z = index / gridSize;
				for (int i = 0; i < NEIGHBOR_OFFSETS.length; i += 2) {
					int nx = x + NEIGHBOR_OFFSETS[i];
					int nz = z + NEIGHBOR_OFFSETS[i + 1];
					if (nx < 0 || nz < 0 || nx >= gridSize || nz >= gridSize) {
						continue;
					}
					int neighbor = nz * gridSize + nx;
					if (oceanFloodMask[neighbor] || inlandWaterMask[neighbor]) {
						continue;
					}
					if (surfaceHeights[neighbor] > this.seaLevel) {
						continue;
					}
					oceanFloodMask[neighbor] = true;
					oceanQueue.add(neighbor);
				}
			}
		}

		boolean[] waterMask = new boolean[gridArea];
		for (int index = 0; index < gridArea; index++) {
			waterMask[index] = oceanFloodMask[index] || inlandWaterMask[index];
		}
		boolean[] landMask = new boolean[gridArea];
		for (int index = 0; index < gridArea; index++) {
			landMask[index] = !waterMask[index];
		}

		IntArrayList shoreWater = new IntArrayList();
		for (int index = 0; index < gridArea; index++) {
			if (!waterMask[index]) {
				continue;
			}
			int x = index % gridSize;
			int z = index / gridSize;
			if (isShoreCell(x, z, gridSize, waterMask)) {
				shoreWater.add(index);
			}
		}

		int[] waterDistanceCost = new int[gridArea];
		computeWeightedDistance(
				waterDistanceCost,
				waterMask,
				shoreWater,
				gridSize,
				this.maxDistanceToShore,
				DIST_COST_CARDINAL
		);
		int maxDistanceCost = this.maxDistanceToShore * DIST_COST_CARDINAL;

		for (int i = 0; i < componentCount; i++) {
			ComponentData component = components[i];
			component.maxDistanceCost = 0;
			for (int c = 0; c < component.cells.size(); c++) {
				int cell = component.cells.getInt(c);
				int distanceCost = waterDistanceCost[cell];
				if (distanceCost == Integer.MAX_VALUE) {
					distanceCost = maxDistanceCost;
				}
				if (distanceCost > component.maxDistanceCost) {
					component.maxDistanceCost = distanceCost;
				}
			}
		}

		for (int index = 0; index < gridArea; index++) {
			if (!waterMask[index]) {
				continue;
			}
			if (oceanFloodMask[index]) {
				waterSurface[index] = this.seaLevel;
				waterFlags[index] = WATER_OCEAN;
				continue;
			}
			if (!inlandWaterMask[index]) {
				continue;
			}
			int componentId = componentIds[index];
			if (componentId < 0) {
				continue;
			}
			ComponentData component = components[componentId];
			int distanceCost = waterDistanceCost[index];
			if (distanceCost == Integer.MAX_VALUE) {
				distanceCost = maxDistanceCost;
			}
			double distance = distanceCost / (double) DIST_COST_CARDINAL;
			int depth = component.isRiver
					? computeDepth(distance, component.slopeStep)
					: computeLakeDepth(distance, component.maxDistanceCost / (double) DIST_COST_CARDINAL);
			int floor = waterSurface[index] - depth;
			if (floor >= waterSurface[index]) {
				floor = waterSurface[index] - 1;
			}
			terrainSurface[index] = floor;
			waterFlags[index] = WATER_INLAND;
		}

		if (this.shoreBlendDistance > 0 && !shoreWater.isEmpty()) {
			int[] landDistanceCost = new int[gridArea];
			int[] nearestSurface = new int[gridArea];
			boolean[] landSource = new boolean[gridArea];
			IntArrayList shoreLand = new IntArrayList();
			for (int i = 0; i < shoreWater.size(); i++) {
				int waterIndex = shoreWater.getInt(i);
				int x = waterIndex % gridSize;
				int z = waterIndex / gridSize;
				int sourceSurface = waterSurface[waterIndex];
				for (int n = 0; n < NEIGHBOR_OFFSETS.length; n += 2) {
					int nx = x + NEIGHBOR_OFFSETS[n];
					int nz = z + NEIGHBOR_OFFSETS[n + 1];
					if (nx < 0 || nz < 0 || nx >= gridSize || nz >= gridSize) {
						continue;
					}
					int neighbor = nz * gridSize + nx;
					if (!landMask[neighbor]) {
						continue;
					}
					if (!landSource[neighbor]) {
						landSource[neighbor] = true;
						nearestSurface[neighbor] = sourceSurface;
						shoreLand.add(neighbor);
					}
				}
			}
			computeWeightedDistanceWithSurface(
					landDistanceCost,
					nearestSurface,
					landMask,
					shoreLand,
					gridSize,
					this.shoreBlendDistance,
					DIST_COST_CARDINAL
			);
			int maxBlendCost = this.shoreBlendDistance * DIST_COST_CARDINAL;

			for (int index = 0; index < gridArea; index++) {
				if (waterMask[index]) {
					continue;
				}
				int distanceCost = landDistanceCost[index];
				if (distanceCost == Integer.MAX_VALUE || distanceCost > maxBlendCost) {
					continue;
				}
				double distance = distanceCost / (double) DIST_COST_CARDINAL;
				double t = distance / (double) this.shoreBlendDistance;
				int sourceSurface = nearestSurface[index];
				int baseSurface = surfaceHeights[index];
				int blended = (int) Math.round(Mth.lerp(t, sourceSurface, baseSurface));
				if (blended < baseSurface) {
					terrainSurface[index] = blended;
				}
			}
		}

		if (LAKE_SMOOTH_PASSES > 0) {
			smoothLakeBeds(
					terrainSurface,
					waterSurface,
					waterMask,
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
			Terrarium.LOGGER.info(
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

	private WaterRegionData buildLegacyRegionData(
			int regionX,
			int regionZ,
			int regionMinX,
			int regionMinZ,
			int gridSize,
			int gridArea,
			boolean[] waterMask,
			int[] surfaceHeights,
			int[] componentIds,
			ComponentData[] components,
			int componentCount,
			long startNanos
	) {
		boolean[] landMask = new boolean[gridArea];
		for (int index = 0; index < gridArea; index++) {
			landMask[index] = !waterMask[index];
		}

		int[] waterSurface = new int[gridArea];
		int[] terrainSurface = new int[gridArea];
		byte[] waterFlags = new byte[gridArea];

		for (int index = 0; index < gridArea; index++) {
			if (!waterMask[index]) {
				terrainSurface[index] = surfaceHeights[index];
			}
		}

		for (int i = 0; i < componentCount; i++) {
			ComponentData component = components[i];
			int spillHeight = component.borderHeights.isEmpty()
					? component.averageHeight()
					: percentile(component.borderHeights, BORDER_HEIGHT_PERCENTILE);
			boolean oceanByNoData = component.touchesNoData
					&& component.minHeight <= this.seaLevel + this.oceanNoDataThreshold;
			boolean isOcean = component.touchesSeaLevel || oceanByNoData || spillHeight <= this.seaLevel;
			component.isOcean = isOcean;
			if (isOcean) {
				fillComponentSurface(component, waterSurface, this.seaLevel);
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
			if (!riverShape && component.touchesEdge) {
				riverShape = maxDim >= this.riverMinLength;
			}
			component.isRiver = riverShape;

			int inlandSurface = Math.max(this.seaLevel + 1, spillHeight);
			if (!riverShape) {
				fillComponentSurface(component, waterSurface, inlandSurface);
				continue;
			}

			RiverSurface riverSurface = buildRiverSurface(component, inlandSurface, gridSize);
			for (int c = 0; c < component.cells.size(); c++) {
				int cell = component.cells.getInt(c);
				int x = cell % gridSize;
				int z = cell / gridSize;
				waterSurface[cell] = riverSurface.surfaceAt(x, z);
			}
		}

		IntArrayList shoreWater = new IntArrayList();
		for (int index = 0; index < gridArea; index++) {
			if (!waterMask[index]) {
				continue;
			}
			int x = index % gridSize;
			int z = index / gridSize;
			if (isShoreCell(x, z, gridSize, waterMask)) {
				shoreWater.add(index);
			}
		}

		int[] waterDistanceCost = new int[gridArea];
		computeWeightedDistance(
				waterDistanceCost,
				waterMask,
				shoreWater,
				gridSize,
				this.maxDistanceToShore,
				DIST_COST_CARDINAL
		);
		int maxDistanceCost = this.maxDistanceToShore * DIST_COST_CARDINAL;

		for (int i = 0; i < componentCount; i++) {
			ComponentData component = components[i];
			component.maxDistanceCost = 0;
			for (int c = 0; c < component.cells.size(); c++) {
				int cell = component.cells.getInt(c);
				int distanceCost = waterDistanceCost[cell];
				if (distanceCost == Integer.MAX_VALUE) {
					distanceCost = maxDistanceCost;
				}
				if (distanceCost > component.maxDistanceCost) {
					component.maxDistanceCost = distanceCost;
				}
			}
		}

		for (int index = 0; index < gridArea; index++) {
			if (!waterMask[index]) {
				continue;
			}
			int componentId = componentIds[index];
			ComponentData component = components[componentId];
			if (component.isOcean) {
				int floor = Math.min(surfaceHeights[index], waterSurface[index] - 1);
				terrainSurface[index] = floor;
				waterFlags[index] = WATER_OCEAN;
				continue;
			}
			int distanceCost = waterDistanceCost[index];
			if (distanceCost == Integer.MAX_VALUE) {
				distanceCost = maxDistanceCost;
			}
			double distance = distanceCost / (double) DIST_COST_CARDINAL;
			int depth = component.isRiver
					? computeDepth(distance, component.slopeStep)
					: computeLakeDepth(distance, component.maxDistanceCost / (double) DIST_COST_CARDINAL);
			int floor = waterSurface[index] - depth;
			if (floor >= waterSurface[index]) {
				floor = waterSurface[index] - 1;
			}
			terrainSurface[index] = floor;
			waterFlags[index] = WATER_INLAND;
		}

		if (this.shoreBlendDistance > 0 && !shoreWater.isEmpty()) {
			int[] landDistanceCost = new int[gridArea];
			int[] nearestSurface = new int[gridArea];
			boolean[] landSource = new boolean[gridArea];
			IntArrayList shoreLand = new IntArrayList();
			for (int i = 0; i < shoreWater.size(); i++) {
				int waterIndex = shoreWater.getInt(i);
				int x = waterIndex % gridSize;
				int z = waterIndex / gridSize;
				int sourceSurface = waterSurface[waterIndex];
				for (int n = 0; n < NEIGHBOR_OFFSETS.length; n += 2) {
					int nx = x + NEIGHBOR_OFFSETS[n];
					int nz = z + NEIGHBOR_OFFSETS[n + 1];
					if (nx < 0 || nz < 0 || nx >= gridSize || nz >= gridSize) {
						continue;
					}
					int neighbor = nz * gridSize + nx;
					if (!landMask[neighbor]) {
						continue;
					}
					if (!landSource[neighbor]) {
						landSource[neighbor] = true;
						nearestSurface[neighbor] = sourceSurface;
						shoreLand.add(neighbor);
					}
				}
			}
			computeWeightedDistanceWithSurface(
					landDistanceCost,
					nearestSurface,
					landMask,
					shoreLand,
					gridSize,
					this.shoreBlendDistance,
					DIST_COST_CARDINAL
			);
			int maxBlendCost = this.shoreBlendDistance * DIST_COST_CARDINAL;

			for (int index = 0; index < gridArea; index++) {
				if (waterMask[index]) {
					continue;
				}
				int distanceCost = landDistanceCost[index];
				if (distanceCost == Integer.MAX_VALUE || distanceCost > maxBlendCost) {
					continue;
				}
				double distance = distanceCost / (double) DIST_COST_CARDINAL;
				double t = distance / (double) this.shoreBlendDistance;
				int sourceSurface = nearestSurface[index];
				int baseSurface = surfaceHeights[index];
				int blended = (int) Math.round(Mth.lerp(t, sourceSurface, baseSurface));
				if (blended < baseSurface) {
					terrainSurface[index] = blended;
				}
			}
		}

		if (LAKE_SMOOTH_PASSES > 0) {
			smoothLakeBeds(
					terrainSurface,
					waterSurface,
					waterMask,
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
			int gridZ = worldZ - regionMinZ + this.regionMargin;
			int gridRow = gridZ * gridSize;
			int regionRow = dz * REGION_SIZE;
			for (int dx = 0; dx < REGION_SIZE; dx++) {
				int worldX = regionMinX + dx;
				int gridX = worldX - regionMinX + this.regionMargin;
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
			Terrarium.LOGGER.info(
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
			if (height <= this.seaLevel) {
				component.touchesSeaLevel = true;
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

		component.slopeStep = pickSlopeStep(gridMinX + (startIndex % gridSize), gridMinZ + (startIndex / gridSize));
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

	private int computeDepth(double distance, int slopeStep) {
		int clampedSlope = Mth.clamp(slopeStep, 1, MAX_SLOPE_STEP);
		if (distance <= this.shorelineShallowDistance) {
			return this.depthShallowBlocks;
		}
		if (distance <= this.shorelineMediumDistance) {
			return this.depthMediumBlocks;
		}
		double extra = distance - this.shorelineMediumDistance;
		int depth = this.depthMediumBlocks + (int) Math.floor((extra + clampedSlope - 1) / clampedSlope);
		return Math.min(this.maxWaterDepth, depth);
	}

	private int computeLakeDepth(double distance, double maxDistanceBlocks) {
		if (distance <= this.shorelineShallowDistance) {
			return this.depthShallowBlocks;
		}
		if (distance <= this.shorelineMediumDistance) {
			double span = Math.max(1.0, this.shorelineMediumDistance - this.shorelineShallowDistance);
			double t = (distance - this.shorelineShallowDistance) / span;
			double depth = Mth.lerp(t, this.depthShallowBlocks, this.depthMediumBlocks);
			return Mth.clamp((int) Math.round(depth), this.depthShallowBlocks, this.maxWaterDepth);
		}
		double maxDistance = Math.max(this.shorelineMediumDistance + 1.0, maxDistanceBlocks);
		double t = (distance - this.shorelineMediumDistance) / (maxDistance - this.shorelineMediumDistance);
		t = Mth.clamp(t, 0.0, 1.0);
		t = t * t * (3.0 - 2.0 * t);
		double depth = Mth.lerp(t, this.depthMediumBlocks, this.maxWaterDepth);
		return Mth.clamp((int) Math.round(depth), this.depthShallowBlocks, this.maxWaterDepth);
	}

	private void smoothLakeBeds(
			int[] terrainSurface,
			int[] waterSurface,
			boolean[] waterMask,
			int[] componentIds,
			ComponentData[] components,
			int componentCount,
			int[] waterDistanceCost,
			int gridSize
	) {
		int minSmoothCost = this.shorelineMediumDistance * DIST_COST_CARDINAL;
		for (int pass = 0; pass < LAKE_SMOOTH_PASSES; pass++) {
			int[] smoothed = Arrays.copyOf(terrainSurface, terrainSurface.length);
			for (int i = 0; i < componentCount; i++) {
				ComponentData component = components[i];
				if (component.isOcean || component.isRiver) {
					continue;
				}
				if (component.maxDistanceCost <= minSmoothCost + DIST_COST_CARDINAL) {
					continue;
				}
				for (int c = 0; c < component.cells.size(); c++) {
					int cell = component.cells.getInt(c);
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
						if (!waterMask[neighbor] || componentIds[neighbor] != component.id) {
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
		Arrays.fill(distances, Integer.MAX_VALUE);
		if (sources.isEmpty()) {
			return;
		}
		int maxCost = Math.max(0, maxDistanceBlocks) * DIST_COST_CARDINAL;
		PriorityQueue<Long> queue = new PriorityQueue<>();

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
				queue.add(packCost(cost, index));
			}
		}

		while (!queue.isEmpty()) {
			long entry = queue.poll();
			int cost = (int) (entry >>> 32);
			int index = (int) entry;
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
					queue.add(packCost(nextCost, neighbor));
				}
			}
		}
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
		Arrays.fill(distances, Integer.MAX_VALUE);
		if (sources.isEmpty()) {
			return;
		}
		int maxCost = Math.max(0, maxDistanceBlocks) * DIST_COST_CARDINAL;
		PriorityQueue<Long> queue = new PriorityQueue<>();

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
				queue.add(packCost(cost, index));
			}
		}

		while (!queue.isEmpty()) {
			long entry = queue.poll();
			int cost = (int) (entry >>> 32);
			int index = (int) entry;
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
					queue.add(packCost(nextCost, neighbor));
				}
			}
		}
	}

	private static long packCost(int cost, int index) {
		return ((long) cost << 32) | (index & 0xffffffffL);
	}

	private int sampleSurfaceHeight(double blockX, double blockZ) {
		double elevation = this.elevationSource.sampleElevationMeters(blockX, blockZ, this.settings.worldScale());
		double heightScale = elevation >= 0.0 ? this.settings.terrestrialHeightScale() : this.settings.oceanicHeightScale();
		double scaled = elevation * heightScale / this.settings.worldScale();
		int offset = this.settings.heightOffset();
		int height = elevation >= 0.0 ? Mth.ceil(scaled) : Mth.floor(scaled);
		return height + offset;
	}

	private int metersToBlocks(double meters) {
		double scale = Math.max(0.0001, this.settings.worldScale());
		int blocks = (int) Math.round(meters / scale);
		int clamped = Math.max(1, blocks);
		if (this.settings.cinematicMode()) {
			return Math.min(clamped, CINEMATIC_MAX_DISTANCE_BLOCKS);
		}
		return clamped;
	}

	private static int percentile(IntArrayList values, double percentile) {
		int[] sorted = values.toIntArray();
		Arrays.sort(sorted);
		int index = (int) Math.floor(percentile * (sorted.length - 1));
		index = Mth.clamp(index, 0, sorted.length - 1);
		return sorted[index];
	}

	private boolean useLegacyWaterBehavior() {
		return this.seaLevelAutomatic;
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
		private boolean touchesNoData;
		private boolean touchesSeaLevel;
		private boolean touchesEdge;
		private boolean isOcean;
		private boolean isRiver;
		private int maxDistanceCost;
		private int slopeStep;

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

	private int pickSlopeStep(int blockX, int blockZ) {
		long seed = seedFromCoords(blockX, 3, blockZ) ^ 0xD6E8FEB86659FD93L;
		return 1 + (int) Math.floorMod(seed, MAX_SLOPE_STEP);
	}

	private static long seedFromCoords(int x, int y, int z) {
		long seed = (long) (x * 3129871) ^ (long) z * 116129781L ^ (long) y;
		seed = seed * seed * 42317861L + seed * 11L;
		return seed >> 16;
	}
}
