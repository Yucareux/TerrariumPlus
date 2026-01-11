package com.yucareux.tellus.worldgen;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.yucareux.tellus.Tellus;
import com.yucareux.tellus.world.data.cover.TellusLandCoverSource;
import com.yucareux.tellus.world.data.elevation.TellusElevationSource;
import com.yucareux.tellus.world.data.mask.TellusLandMaskSource;
import com.yucareux.tellus.worldgen.geology.TellusGeologyGenerator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.StructureTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.carver.ConfiguredWorldCarver;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.TerrainAdjustment;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.tags.BlockTags;
import org.jspecify.annotations.NonNull;

public final class EarthChunkGenerator extends ChunkGenerator {
	public static final MapCodec<EarthChunkGenerator> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
			BiomeSource.CODEC.fieldOf("biome_source").forGetter(generator -> generator.biomeSource),
			EarthGeneratorSettings.CODEC.fieldOf("settings").forGetter(EarthChunkGenerator::settings)
	).apply(instance, EarthChunkGenerator::new));

	private static final double EQUATOR_CIRCUMFERENCE = 40075017.0;
	private static final TellusElevationSource ELEVATION_SOURCE = TellusWorldgenSources.elevation();
	private static final TellusLandCoverSource LAND_COVER_SOURCE = TellusWorldgenSources.landCover();
	private static final TellusLandMaskSource LAND_MASK_SOURCE = TellusWorldgenSources.landMask();
	private static final int COVER_ROLL_RANGE = 200;
	private static final int SNOW_ICE_CHANCE = 3;
	private static final int POWDER_SNOW_CHANCE = 30;
	private static final int MAX_POWDER_DEPTH = 5;
	private static final int ESA_NO_DATA = 0;
	private static final int ESA_TREE_COVER = 10;
	private static final int ESA_SNOW_ICE = 70;
	private static final int ESA_WATER = 80;
	private static final int ESA_MANGROVES = 95;
	private static final int TREE_CELL_SIZE = 5;
	private static final int SURFACE_DEPTH = 4;
	private static final int SLOPE_SAMPLE_STEP = 4;
	private static final int STONY_SLOPE_DIFF = 3;
	private static final int SNOW_SLOPE_DIFF = 4;
	private static final int BADLANDS_BAND_SLOPE_DIFF = 3;
	private static final int BADLANDS_BAND_HEIGHT = 3;
	private static final int BADLANDS_BAND_DEPTH = 16;
	private static final int BADLANDS_BAND_OFFSET_CELL = 32;
	private static final @NonNull BlockState[] BADLANDS_BANDS = {
			Blocks.TERRACOTTA.defaultBlockState(),
			Blocks.ORANGE_TERRACOTTA.defaultBlockState(),
			Blocks.YELLOW_TERRACOTTA.defaultBlockState(),
			Blocks.BROWN_TERRACOTTA.defaultBlockState(),
			Blocks.RED_TERRACOTTA.defaultBlockState(),
			Blocks.LIGHT_GRAY_TERRACOTTA.defaultBlockState(),
			Blocks.WHITE_TERRACOTTA.defaultBlockState()
	};
	private static final int LOD_MIN_WATER_DEPTH = 25;
	private static final AtomicBoolean LOGGED_CHUNK_LAYOUT = new AtomicBoolean(false);

	private static final Map<BiomeSettingsKey, BiomeGenerationSettings> FILTERED_SETTINGS = new ConcurrentHashMap<>();
	private static final Map<Holder<Biome>, List<ConfiguredFeature<?, ?>>> TREE_FEATURES = new ConcurrentHashMap<>();

	private final EarthGeneratorSettings settings;
	private final int seaLevel;
	private final int minY;
	private final int height;
	private final WaterSurfaceResolver waterResolver;
	private volatile TellusGeologyGenerator geologyGenerator;
	private volatile long geologySeed = Long.MIN_VALUE;

	public EarthChunkGenerator(BiomeSource biomeSource, EarthGeneratorSettings settings) {
		super(biomeSource, biome -> generationSettingsForBiome(biome, settings));
		this.settings = settings;
		this.seaLevel = settings.resolveSeaLevel();
		EarthGeneratorSettings.HeightLimits limits = EarthGeneratorSettings.resolveHeightLimits(settings);
		this.minY = limits.minY();
		this.height = limits.height();
		this.waterResolver = TellusWorldgenSources.waterResolver(settings);
		if (Tellus.LOGGER.isInfoEnabled()) {

		}
	}

	public static EarthChunkGenerator create(HolderLookup.Provider registries, EarthGeneratorSettings settings) {
		return new EarthChunkGenerator(new EarthBiomeSource(registries.lookupOrThrow(Registries.BIOME), settings), settings);
	}

	public EarthGeneratorSettings settings() {
		return this.settings;
	}

	@Override
	public @NonNull ChunkGeneratorStructureState createState(
			@NonNull HolderLookup<StructureSet> structureSets,
			@NonNull RandomState randomState,
			long seed
	) {
		HolderLookup<StructureSet> filtered = new FilteredStructureLookup(structureSets, this::isStructureSetEnabled);
		return ChunkGeneratorStructureState.createForNormal(randomState, seed, this.biomeSource, filtered);
	}

	public BlockPos getSpawnPosition(LevelHeightAccessor heightAccessor) {
		return getSurfacePosition(heightAccessor, this.settings.spawnLatitude(), this.settings.spawnLongitude());
	}

	public BlockPos getSurfacePosition(LevelHeightAccessor heightAccessor, double latitude, double longitude) {
		double blocksPerDegree = blocksPerDegree(this.settings.worldScale());
		int spawnX = Mth.floor(longitude * blocksPerDegree);
		int spawnZ = Mth.floor(-latitude * blocksPerDegree);
		WaterSurfaceResolver.WaterColumnData column = this.waterResolver.resolveColumnData(spawnX, spawnZ);
		int surface = column.terrainSurface();
		if (column.hasWater()) {
			surface = Math.max(surface, column.waterSurface());
		}
		int maxY = heightAccessor.getMaxY() - 1;
		int spawnY = Mth.clamp(surface + 1, heightAccessor.getMinY(), maxY);
		return new BlockPos(spawnX, spawnY, spawnZ);
	}

	public double longitudeFromBlock(double blockX) {
		return blockX / blocksPerDegree(this.settings.worldScale());
	}

	public double latitudeFromBlock(double blockZ) {
		return -blockZ / blocksPerDegree(this.settings.worldScale());
	}

	private static double blocksPerDegree(double worldScale) {
		if (worldScale <= 0.0) {
			return 0.0;
		}
		return (EQUATOR_CIRCUMFERENCE / 360.0) / worldScale;
	}

	@Override
	protected @NonNull MapCodec<? extends ChunkGenerator> codec() {
		return Objects.requireNonNull(CODEC, "CODEC");
	}

	@Override
	public void applyCarvers(
			@NonNull WorldGenRegion level,
			long seed,
			@NonNull RandomState random,
			@NonNull BiomeManager biomeManager,
			@NonNull StructureManager structures,
			@NonNull ChunkAccess chunk
	) {
		if (SharedConstants.DEBUG_DISABLE_CARVERS) {
			return;
		}
		ChunkPos chunkPos = chunk.getPos();
		if (!this.settings.caveCarvers() && !this.settings.largeCaves() && !this.settings.canyonCarvers()) {
			return;
		}
		TellusGeologyGenerator geology = getGeologyGenerator(seed);
		int chunkX = chunkPos.getMinBlockX() >> 4;
		int chunkZ = chunkPos.getMinBlockZ() >> 4;
		WaterSurfaceResolver.WaterChunkData waterData = this.waterResolver.resolveChunkWaterData(chunkX, chunkZ);
		geology.carveChunk(chunk, waterData);
	}

	@Override
	public void buildSurface(
			@NonNull WorldGenRegion level,
			@NonNull StructureManager structures,
			@NonNull RandomState random,
			@NonNull ChunkAccess chunk
	) {
	}

	@Override
	public void spawnOriginalMobs(@NonNull WorldGenRegion level) {
	}

	@Override
	public void applyBiomeDecoration(
			@NonNull WorldGenLevel level,
			@NonNull ChunkAccess chunk,
			@NonNull StructureManager structures
	) {
		super.applyBiomeDecoration(level, chunk, structures);
		placeTrees(level, chunk);
	}

	@Override
	public void createStructures(
			@NonNull RegistryAccess registryAccess,
			@NonNull ChunkGeneratorStructureState structureState,
			@NonNull StructureManager structures,
			@NonNull ChunkAccess chunk,
			@NonNull StructureTemplateManager templates,
			@NonNull ResourceKey<Level> levelKey
	) {
		super.createStructures(registryAccess, structureState, structures, chunk, templates, levelKey);
		if (this.settings.structureSettings().addIgloos()
				&& !isFrozenPeaksChunk(chunk.getPos(), structureState.randomState())) {
			stripIglooStarts(registryAccess, chunk);
		}
        if (this.settings.villageSettings().flatVillages()) {
            stripVillagesOnSteepTerrain(registryAccess, chunk);
        }
	}

	@Override
	public void createReferences(
			@NonNull WorldGenLevel level,
			@NonNull StructureManager structures,
			@NonNull ChunkAccess chunk
	) {
		super.createReferences(level, structures, chunk);
	}

	@Override
	public @NonNull CompletableFuture<ChunkAccess> fillFromNoise(
			@NonNull Blender blender,
			@NonNull RandomState random,
			@NonNull StructureManager structures,
			@NonNull ChunkAccess chunk
	) {
		ChunkPos pos = chunk.getPos();
		TellusWorldgenSources.prefetchForChunk(pos, this.settings);
		int chunkMinY = chunk.getMinY();
		int chunkHeight = chunk.getHeight();
		int chunkMaxY = chunkMinY + chunkHeight;
		if (LOGGED_CHUNK_LAYOUT.compareAndSet(false, true) && Tellus.LOGGER.isInfoEnabled()) {

		}
		int chunkX = pos.getMinBlockX() >> 4;
		int chunkZ = pos.getMinBlockZ() >> 4;
		WaterSurfaceResolver.WaterChunkData waterData = this.waterResolver.resolveChunkWaterData(chunkX, chunkZ);
		BlockState stone = Blocks.STONE.defaultBlockState();
		BlockState water = Blocks.WATER.defaultBlockState();
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

		int step = SLOPE_SAMPLE_STEP;
		int gridSize = 16 + step * 2;
		int[] heightGrid = new int[gridSize * gridSize];
		int gridMinX = pos.getMinBlockX() - step;
		int gridMinZ = pos.getMinBlockZ() - step;
		for (int dz = 0; dz < gridSize; dz++) {
			int worldZ = gridMinZ + dz;
			int row = dz * gridSize;
			for (int dx = 0; dx < gridSize; dx++) {
				int worldX = gridMinX + dx;
				heightGrid[row + dx] = sampleSurfaceHeight(worldX, worldZ);
			}
		}

		int[] coverClasses = new int[16 * 16];
		int[] terrainSurfaces = new int[16 * 16];
		int[] waterSurfaces = new int[16 * 16];
		boolean[] waterFlags = new boolean[16 * 16];

		int chunkMinX = pos.getMinBlockX();
		int chunkMinZ = pos.getMinBlockZ();
		int bedrockY = this.minY;
		boolean bedrockInChunk = bedrockY >= chunkMinY && bedrockY < chunkMaxY;
		for (int localX = 0; localX < 16; localX++) {
			int worldX = chunkMinX + localX;
				for (int localZ = 0; localZ < 16; localZ++) {
					int worldZ = chunkMinZ + localZ;
					int index = localZ * 16 + localX;
				int coverClass = LAND_COVER_SOURCE.sampleCoverClass(worldX, worldZ, this.settings.worldScale());
				int gridIndex = (localZ + step) * gridSize + (localX + step);
				int cachedSurface = heightGrid[gridIndex];
				ColumnHeights column = resolveColumnHeights(
						worldX,
						worldZ,
						localX,
						localZ,
						chunkMinY,
						chunkMaxY,
						coverClass,
						waterData,
						cachedSurface
				);
				int surface = column.terrainSurface();
				coverClasses[index] = coverClass;
				terrainSurfaces[index] = surface;
				waterSurfaces[index] = column.waterSurface();
				waterFlags[index] = column.hasWater();
			}
		}

		int[] structureCaps = resolveStructureSurfaceCaps(chunk, chunkMinY);
		boolean[] structureAdjusted = null;
		if (structureCaps != null) {
			structureAdjusted = new boolean[16 * 16];
			for (int i = 0; i < terrainSurfaces.length; i++) {
				int cap = structureCaps[i];
				if (cap != Integer.MAX_VALUE && terrainSurfaces[i] > cap) {
					terrainSurfaces[i] = cap;
					structureAdjusted[i] = true;
				}
			}
		}

		int[] slopeDiffs = new int[16 * 16];
		@SuppressWarnings("unchecked")
		Holder<Biome>[] biomeCache = (Holder<Biome>[]) new Holder[16 * 16];
		for (int localX = 0; localX < 16; localX++) {
			int worldX = chunkMinX + localX;
			for (int localZ = 0; localZ < 16; localZ++) {
				int worldZ = chunkMinZ + localZ;
				int index = localZ * 16 + localX;
				int surface = terrainSurfaces[index];
				int gridIndex = (localZ + step) * gridSize + (localX + step);
				int slopeDiff = sampleSlopeDiffCached(heightGrid, gridSize, step, gridIndex, surface);
				if (structureAdjusted != null && structureAdjusted[index]) {
					slopeDiff = 0;
				}
				slopeDiffs[index] = slopeDiff;
				biomeCache[index] = this.biomeSource.getNoiseBiome(
						QuartPos.fromBlock(worldX),
						QuartPos.fromBlock(surface),
						QuartPos.fromBlock(worldZ),
						random.sampler()
				);
			}
		}

		for (int localX = 0; localX < 16; localX++) {
			int worldX = chunkMinX + localX;
				for (int localZ = 0; localZ < 16; localZ++) {
					int worldZ = chunkMinZ + localZ;
					int index = localZ * 16 + localX;
					int surface = terrainSurfaces[index];
					int waterSurface = waterSurfaces[index];
					boolean hasWater = waterFlags[index];
					int coverClass = coverClasses[index];
					Holder<Biome> biome = biomeCache[index];

				for (int y = chunkMinY; y <= surface; y++) {
					cursor.set(worldX, y, worldZ);
					chunk.setBlockState(cursor, stone);
				}
					if (bedrockInChunk) {
						cursor.set(worldX, bedrockY, worldZ);
						chunk.setBlockState(cursor, Blocks.BEDROCK.defaultBlockState());
					}
					if (hasWater && surface < waterSurface) {
						for (int y = surface + 1; y <= waterSurface; y++) {
							cursor.set(worldX, y, worldZ);
							chunk.setBlockState(cursor, water);
						}
					}
					boolean underwater = hasWater && waterSurface > surface;
					int slopeDiff = slopeDiffs[index];
					applySurface(chunk, cursor, worldX, worldZ, surface, chunkMinY, underwater, biome, slopeDiff, coverClass);
					if (surface >= this.seaLevel && coverClass == ESA_SNOW_ICE) {
						if (slopeDiff < SNOW_SLOPE_DIFF) {
							boolean reduceIce = biome.is(Biomes.FROZEN_PEAKS);
							applySnowCover(chunk, cursor, worldX, worldZ, surface, chunkMinY, reduceIce);
						}
					}
			}
		}

		return Objects.requireNonNull(CompletableFuture.<ChunkAccess>completedFuture(chunk), "completedFuture");
	}

	private int[] resolveStructureSurfaceCaps(ChunkAccess chunk, int minY) {
		Map<Structure, StructureStart> starts = chunk.getAllStarts();
		if (starts.isEmpty()) {
			return null;
		}
		int[] caps = new int[16 * 16];
		Arrays.fill(caps, Integer.MAX_VALUE);
		boolean touched = false;
		ChunkPos pos = chunk.getPos();
		int chunkMinX = pos.getMinBlockX();
		int chunkMinZ = pos.getMinBlockZ();
		int chunkMaxX = chunkMinX + 15;
		int chunkMaxZ = chunkMinZ + 15;

		for (StructureStart start : starts.values()) {
			if (start == null || !start.isValid()) {
				continue;
			}
			Structure structure = start.getStructure();
			if (!shouldApplyStructureTerrainAdjustment(structure.terrainAdaptation())) {
				continue;
			}
			for (StructurePiece piece : start.getPieces()) {
				BoundingBox box = piece.getBoundingBox();
				if (!box.intersects(chunkMinX, chunkMinZ, chunkMaxX, chunkMaxZ)) {
					continue;
				}
				int minX = Math.max(chunkMinX, box.minX());
				int maxX = Math.min(chunkMaxX, box.maxX());
				int minZ = Math.max(chunkMinZ, box.minZ());
				int maxZ = Math.min(chunkMaxZ, box.maxZ());
				int cap = Math.max(minY, box.minY() - 1);
				for (int z = minZ; z <= maxZ; z++) {
					int row = (z - chunkMinZ) * 16;
					for (int x = minX; x <= maxX; x++) {
						int index = row + (x - chunkMinX);
						if (cap < caps[index]) {
							caps[index] = cap;
						}
					}
				}
				touched = true;
			}
		}

		return touched ? caps : null;
	}

	private static boolean shouldApplyStructureTerrainAdjustment(TerrainAdjustment adjustment) {
		return adjustment == TerrainAdjustment.BEARD_THIN || adjustment == TerrainAdjustment.BEARD_BOX;
	}

	@Override
	public int getGenDepth() {
		return this.height;
	}

	@Override
	public int getSeaLevel() {
		return this.seaLevel;
	}

	@Override
	public int getMinY() {
		return this.minY;
	}

	@Override
	public int getBaseHeight(
			int x,
			int z,
			Heightmap.@NonNull Types heightmapType,
			@NonNull LevelHeightAccessor heightAccessor,
			@NonNull RandomState random
	) {
		int coverClass = LAND_COVER_SOURCE.sampleCoverClass(x, z, this.settings.worldScale());
		ColumnHeights column = resolveFastColumnHeights(x, z, heightAccessor.getMinY(), heightAccessor.getMaxY(), coverClass);
		int surface = column.terrainSurface();
		if (heightmapType == Heightmap.Types.OCEAN_FLOOR_WG || heightmapType == Heightmap.Types.OCEAN_FLOOR) {
			return surface + 1;
		}
		if (column.hasWater()) {
			return Math.max(surface, column.waterSurface()) + 1;
		}
		return surface + 1;
	}

	@Override
	public @NonNull NoiseColumn getBaseColumn(
			int x,
			int z,
			@NonNull LevelHeightAccessor heightAccessor,
			@NonNull RandomState random
	) {
		int minY = heightAccessor.getMinY();
		int height = heightAccessor.getHeight();
		BlockState[] states = new BlockState[height];
		Arrays.fill(states, Blocks.AIR.defaultBlockState());

		int coverClass = LAND_COVER_SOURCE.sampleCoverClass(x, z, this.settings.worldScale());
		ColumnHeights column = resolveFastColumnHeights(x, z, minY, minY + height, coverClass);
		int surface = column.terrainSurface();
		int surfaceIndex = surface - minY;
		for (int i = 0; i <= surfaceIndex; i++) {
			if (i >= 0 && i < states.length) {
				states[i] = Blocks.STONE.defaultBlockState();
			}
		}

		if (column.hasWater()) {
			int waterTop = column.waterSurface();
			int waterIndex = waterTop - minY;
			for (int i = surfaceIndex + 1; i <= waterIndex; i++) {
				states[i] = Blocks.WATER.defaultBlockState();
			}
		}
		int bedrockIndex = this.minY - minY;
		if (bedrockIndex >= 0 && bedrockIndex < states.length) {
			states[bedrockIndex] = Blocks.BEDROCK.defaultBlockState();
		}

		return Objects.requireNonNull(new NoiseColumn(minY, states), "noiseColumn");
	}

	@Override
	public void addDebugScreenInfo(@NonNull List<String> info, @NonNull RandomState random, @NonNull BlockPos pos) {
		info.add(String.format("Tellus scale:" + this.settings.worldScale()));
	}

	private void placeTrees(WorldGenLevel level, ChunkAccess chunk) {
		ChunkPos pos = chunk.getPos();
		int chunkMinX = pos.getMinBlockX();
		int chunkMinZ = pos.getMinBlockZ();
		int chunkMaxX = chunkMinX + 15;
		int chunkMaxZ = chunkMinZ + 15;
		int shorelineBlendRadius = Math.max(
				this.settings.riverLakeShorelineBlend(),
				this.settings.oceanShorelineBlend()
		);
		int cellMinX = Math.floorDiv(chunkMinX, TREE_CELL_SIZE);
		int cellMaxX = Math.floorDiv(chunkMaxX, TREE_CELL_SIZE);
		int cellMinZ = Math.floorDiv(chunkMinZ, TREE_CELL_SIZE);
		int cellMaxZ = Math.floorDiv(chunkMaxZ, TREE_CELL_SIZE);

		long worldSeed = level.getSeed();
		for (int cellX = cellMinX; cellX <= cellMaxX; cellX++) {
			for (int cellZ = cellMinZ; cellZ <= cellMaxZ; cellZ++) {
				long seed = seedFromCoords(cellX, 0, cellZ) ^ worldSeed;
				RandomSource random = RandomSource.create(seed);
				int worldX = cellX * TREE_CELL_SIZE + random.nextInt(TREE_CELL_SIZE);
				int worldZ = cellZ * TREE_CELL_SIZE + random.nextInt(TREE_CELL_SIZE);
				if (worldX < chunkMinX || worldX > chunkMaxX || worldZ < chunkMinZ || worldZ > chunkMaxZ) {
					continue;
				}
				int coverClass = LAND_COVER_SOURCE.sampleCoverClass(worldX, worldZ, this.settings.worldScale());
				if (coverClass != ESA_TREE_COVER) {
					continue;
				}
				if (shorelineBlendRadius > 0 && isNearWater(worldX, worldZ, shorelineBlendRadius)) {
					continue;
				}
				int surface = this.sampleSurfaceHeight(worldX, worldZ);
				if (surface < this.seaLevel) {
					continue;
				}
				BlockPos position = new BlockPos(worldX, surface + 1, worldZ);
				Holder<Biome> biome = level.getBiome(position);
				if (biome.is(Biomes.MANGROVE_SWAMP)) {
					continue;
				}
				List<ConfiguredFeature<?, ?>> features = treeFeaturesForBiome(biome);
				if (features.isEmpty()) {
					continue;
				}
				BlockPos ground = position.below();
				BlockState groundState = level.getBlockState(ground);
				if (!groundState.is(BlockTags.DIRT)) {
					level.setBlock(ground, Blocks.GRASS_BLOCK.defaultBlockState(), Block.UPDATE_NONE);
				}
				ConfiguredFeature<?, ?> feature = features.get(random.nextInt(features.size()));
				feature.place(level, this, random, position);
			}
		}
	}

	private boolean isNearWater(int worldX, int worldZ, int radius) {
		for (int dz = -radius; dz <= radius; dz++) {
			int z = worldZ + dz;
			for (int dx = -radius; dx <= radius; dx++) {
				int x = worldX + dx;
				int coverClass = LAND_COVER_SOURCE.sampleCoverClass(x, z, this.settings.worldScale());
				WaterSurfaceResolver.WaterInfo info = this.waterResolver.resolveWaterInfo(x, z, coverClass);
				if (info.isWater()) {
					return true;
				}
			}
		}
		return false;
	}

	private int sampleSurfaceHeight(int blockX, int blockZ) {
		boolean oceanZoom = useOceanZoom(blockX, blockZ);
		double elevation = ELEVATION_SOURCE.sampleElevationMeters(blockX, blockZ, this.settings.worldScale(), oceanZoom);
		double heightScale = elevation >= 0.0 ? this.settings.terrestrialHeightScale() : this.settings.oceanicHeightScale();
		double scaled = elevation * heightScale / this.settings.worldScale();
		int offset = this.settings.heightOffset();
		int height = elevation >= 0.0 ? Mth.ceil(scaled) : Mth.floor(scaled);
		return height + offset;
	}

	private boolean useOceanZoom(double blockX, double blockZ) {
		TellusLandMaskSource.LandMaskSample landSample =
				LAND_MASK_SOURCE.sampleLandMask(blockX, blockZ, this.settings.worldScale());
		if (!landSample.known()) {
			return true;
		}
		if (landSample.land()) {
			return false;
		}
		int coverClass = LAND_COVER_SOURCE.sampleCoverClass(blockX, blockZ, this.settings.worldScale());
		return coverClass == ESA_NO_DATA;
	}

	private int sampleSlopeDiff(int worldX, int worldZ, int surface) {
		int step = SLOPE_SAMPLE_STEP;
		int east = sampleSurfaceHeight(worldX + step, worldZ);
		int west = sampleSurfaceHeight(worldX - step, worldZ);
		int north = sampleSurfaceHeight(worldX, worldZ - step);
		int south = sampleSurfaceHeight(worldX, worldZ + step);

		int maxDiff = Math.max(
				Math.max(Math.abs(east - surface), Math.abs(west - surface)),
				Math.max(Math.abs(north - surface), Math.abs(south - surface))
		);
		return maxDiff;
	}

	private ColumnHeights resolveColumnHeights(
			int worldX,
			int worldZ,
			int localX,
			int localZ,
			int minY,
			int maxYExclusive,
			int coverClass,
			WaterSurfaceResolver.WaterChunkData waterData,
			int cachedSurface
	) {
		int maxY = Math.max(minY, maxYExclusive - 1);
		if (coverClass == ESA_MANGROVES) {
			int surface = cachedSurface == Integer.MIN_VALUE
					? this.sampleSurfaceHeight(worldX, worldZ)
					: cachedSurface;
			surface = Mth.clamp(surface, minY, maxY);
			int waterSurface = resolveMangroveWaterSurface(worldX, worldZ, maxY);
			boolean hasWater = waterSurface > surface;
			return new ColumnHeights(surface, waterSurface, hasWater);
		}
		int surface = Mth.clamp(waterData.terrainSurface(localX, localZ), minY, maxY);
		int waterSurface = Mth.clamp(waterData.waterSurface(localX, localZ), minY, maxY);
		boolean hasWater = waterData.hasWater(localX, localZ);
		if (!hasWater) {
			return new ColumnHeights(surface, surface, false);
		}
		return new ColumnHeights(surface, waterSurface, true);
	}

	private ColumnHeights resolveFastColumnHeights(int worldX, int worldZ, int minY, int maxYExclusive, int coverClass) {
		int maxY = Math.max(minY, maxYExclusive - 1);
		if (coverClass == ESA_MANGROVES) {
			int surface = Mth.clamp(this.sampleSurfaceHeight(worldX, worldZ), minY, maxY);
			int waterSurface = resolveMangroveWaterSurface(worldX, worldZ, maxY);
			boolean hasWater = waterSurface > surface;
			return new ColumnHeights(surface, waterSurface, hasWater);
		}
		WaterSurfaceResolver.WaterColumnData column = this.waterResolver.resolveColumnData(worldX, worldZ);
		int surface = Mth.clamp(column.terrainSurface(), minY, maxY);
		int waterSurface = Mth.clamp(column.waterSurface(), minY, maxY);
		return new ColumnHeights(surface, waterSurface, column.hasWater());
	}

	private int resolveMangroveWaterSurface(int worldX, int worldZ, int maxY) {
		long seed = seedFromCoords(worldX, 1, worldZ) ^ 0x9E3779B97F4A7C15L;
		Random columnRandom = new Random(seed);
		int offset = 1 + columnRandom.nextInt(3);
		int waterTop = Math.min(this.seaLevel, maxY);
		return Math.min(waterTop, this.seaLevel - offset);
	}

	private static long seedFromCoords(int x, int y, int z) {
		long seed = (long) (x * 3129871) ^ (long) z * 116129781L ^ (long) y;
		seed = seed * seed * 42317861L + seed * 11L;
		return seed >> 16;
	}

	private void applySurface(
			ChunkAccess chunk,
			BlockPos.MutableBlockPos cursor,
			int worldX,
			int worldZ,
			int surface,
			int minY,
			boolean underwater,
			Holder<Biome> biome,
			int slopeDiff,
			int coverClass
	) {
		if (surface < minY) {
			return;
		}
		SurfacePalette palette = selectSurfacePalette(biome, worldX, worldZ, surface, underwater, slopeDiff, coverClass);
		if (palette == null) {
			return;
		}
		if (!underwater && biome.is(BiomeTags.IS_BADLANDS) && slopeDiff >= BADLANDS_BAND_SLOPE_DIFF) {
			applyBadlandsBands(chunk, cursor, worldX, worldZ, surface, minY, palette);
			return;
		}
		@NonNull BlockState top = underwater ? palette.underwaterTop() : palette.top();
		@NonNull BlockState filler = palette.filler();
		int depth = palette.depth();
		int bottom = Math.max(minY, surface - depth + 1);

		for (int y = surface; y >= bottom; y--) {
			cursor.set(worldX, y, worldZ);
			chunk.setBlockState(cursor, y == surface ? top : filler);
		}
	}

	private static void applyBadlandsBands(
			ChunkAccess chunk,
			BlockPos.MutableBlockPos cursor,
			int worldX,
			int worldZ,
			int surface,
			int minY,
			SurfacePalette palette
	) {
		int depth = Math.max(palette.depth(), BADLANDS_BAND_DEPTH);
		int bottom = Math.max(minY, surface - depth + 1);
		int offset = badlandsBandOffset(worldX, worldZ);
		@NonNull BlockState top = palette.top();
		for (int y = surface; y >= bottom; y--) {
			cursor.set(worldX, y, worldZ);
			@NonNull BlockState state = y == surface ? top : badlandsBand(y, offset);
			chunk.setBlockState(cursor, state);
		}
	}

	private static int badlandsBandOffset(int worldX, int worldZ) {
		int cellX = Math.floorDiv(worldX, BADLANDS_BAND_OFFSET_CELL);
		int cellZ = Math.floorDiv(worldZ, BADLANDS_BAND_OFFSET_CELL);
		long seed = seedFromCoords(cellX, 2, cellZ) ^ 0x2E2B9E9B4A7C15L;
		int range = BADLANDS_BAND_HEIGHT * BADLANDS_BANDS.length;
		return Math.floorMod((int) seed, range);
	}

	private static @NonNull BlockState badlandsBand(int y, int offset) {
		int index = Math.floorDiv(y + offset, BADLANDS_BAND_HEIGHT);
		int bandIndex = Math.floorMod(index, BADLANDS_BANDS.length);
		return BADLANDS_BANDS[bandIndex];
	}

	private @NonNull BlockState resolveSurfaceTop(
			@NonNull RandomState random,
			int worldX,
			int worldZ,
			int surface,
			boolean underwater,
			int coverClass
	) {
		Holder<Biome> biome = this.biomeSource.getNoiseBiome(
				QuartPos.fromBlock(worldX),
				QuartPos.fromBlock(surface),
				QuartPos.fromBlock(worldZ),
				random.sampler()
		);
		SurfacePalette palette = selectSurfacePalette(biome, worldX, worldZ, surface, underwater, coverClass);
		if (palette == null) {
			return Blocks.STONE.defaultBlockState();
		}
		return underwater ? palette.underwaterTop() : palette.top();
	}

	private @NonNull BlockState resolveSurfaceTop(
			Holder<Biome> biome,
			int worldX,
			int worldZ,
			int surface,
			boolean underwater,
			int slopeDiff,
			int coverClass
	) {
		SurfacePalette palette = selectSurfacePalette(biome, worldX, worldZ, surface, underwater, slopeDiff, coverClass);
		if (palette == null) {
			return Blocks.STONE.defaultBlockState();
		}
		return underwater ? palette.underwaterTop() : palette.top();
	}

	public @NonNull BlockState resolveLodSurfaceBlock(int worldX, int worldZ, int surface, boolean underwater) {
		if (this.biomeSource instanceof EarthBiomeSource earthBiomeSource) {
			Holder<Biome> biome = earthBiomeSource.getBiomeAtBlock(worldX, worldZ);
			return resolveLodSurface(biome, worldX, worldZ, surface, underwater).top();
		}
		return Blocks.STONE.defaultBlockState();
	}

	public @NonNull BlockState resolveLodFillerBlock(int worldX, int worldZ, int surface, boolean underwater) {
		if (this.biomeSource instanceof EarthBiomeSource earthBiomeSource) {
			Holder<Biome> biome = earthBiomeSource.getBiomeAtBlock(worldX, worldZ);
			return resolveLodSurface(biome, worldX, worldZ, surface, underwater).filler();
		}
		return Blocks.STONE.defaultBlockState();
	}

	public @NonNull LodSurface resolveLodSurface(Holder<Biome> biome, int worldX, int worldZ, int surface, boolean underwater) {
		int coverClass = sampleCoverClass(worldX, worldZ);
		return resolveLodSurface(biome, worldX, worldZ, surface, underwater, coverClass);
	}

	public @NonNull LodSurface resolveLodSurface(
			Holder<Biome> biome,
			int worldX,
			int worldZ,
			int surface,
			boolean underwater,
			int coverClass
	) {
		SurfacePalette palette = selectSurfacePalette(biome, worldX, worldZ, surface, underwater, coverClass);
		if (palette == null) {
			BlockState stone = Blocks.STONE.defaultBlockState();
			return new LodSurface(stone, stone);
		}
		BlockState top = underwater ? palette.underwaterTop() : palette.top();
		return new LodSurface(top, palette.filler());
	}

	public void prefetchForChunk(int chunkX, int chunkZ) {
		TellusWorldgenSources.prefetchForChunk(new ChunkPos(chunkX, chunkZ), this.settings);
	}

	public int sampleCoverClass(int worldX, int worldZ) {
		return LAND_COVER_SOURCE.sampleCoverClass(worldX, worldZ, this.settings.worldScale());
	}

	public WaterSurfaceResolver.WaterColumnData resolveLodWaterColumn(int worldX, int worldZ) {
		int coverClass = sampleCoverClass(worldX, worldZ);
		return resolveLodWaterColumn(worldX, worldZ, coverClass);
	}

	public WaterSurfaceResolver.WaterColumnData resolveLodWaterColumn(int worldX, int worldZ, int coverClass) {
		// LODs use a lightweight water approximation to avoid the full resolver cost.
		int surface = sampleSurfaceHeight(worldX, worldZ);
		boolean noData = coverClass == ESA_NO_DATA;
		boolean hasWater = coverClass == ESA_WATER
				|| coverClass == ESA_MANGROVES
				|| (noData && surface <= this.seaLevel);
		if (!hasWater) {
			return new WaterSurfaceResolver.WaterColumnData(false, false, surface, surface);
		}
		int waterSurface = Math.max(surface + 1, this.seaLevel);
		boolean isOcean = noData && surface <= this.seaLevel;
		if (!isOcean) {
			int targetSurface = waterSurface - Math.max(1, LOD_MIN_WATER_DEPTH);
			if (surface > targetSurface) {
				surface = targetSurface;
			}
			if (surface >= waterSurface) {
				surface = waterSurface - 1;
			}
		}
		return new WaterSurfaceResolver.WaterColumnData(true, isOcean, surface, waterSurface);
	}

	public WaterSurfaceResolver.WaterColumnData resolveLodWaterColumn(
			int worldX,
			int worldZ,
			int coverClass,
			boolean useDetailedResolver
	) {
		if (!useDetailedResolver) {
			return resolveLodWaterColumn(worldX, worldZ, coverClass);
		}
		if (coverClass == ESA_MANGROVES) {
			int surface = sampleSurfaceHeight(worldX, worldZ);
			int waterSurface = resolveMangroveWaterSurface(worldX, worldZ, this.seaLevel);
			boolean hasWater = waterSurface > surface;
			return new WaterSurfaceResolver.WaterColumnData(hasWater, false, surface, waterSurface);
		}
		if (coverClass != ESA_WATER && coverClass != ESA_NO_DATA) {
			int surface = sampleSurfaceHeight(worldX, worldZ);
			return new WaterSurfaceResolver.WaterColumnData(false, false, surface, surface);
		}
		return this.waterResolver.resolveColumnData(worldX, worldZ, coverClass);
	}

	public void prefetchLodWaterRegions(int minBlockX, int minBlockZ, int maxBlockX, int maxBlockZ) {
		this.waterResolver.prefetchRegionsForArea(minBlockX, minBlockZ, maxBlockX, maxBlockZ);
	}

	public @NonNull BlockState resolveBadlandsBandBlock(int worldX, int worldZ, int y) {
		int offset = badlandsBandOffset(worldX, worldZ);
		return badlandsBand(y, offset);
	}

	private SurfacePalette selectSurfacePalette(
			Holder<Biome> biome,
			int worldX,
			int worldZ,
			int surface,
			boolean underwater,
			int coverClass
	) {
		SurfacePalette palette = selectBaseSurfacePalette(biome, worldX, worldZ);
		if (palette == null) {
			return null;
		}
		if (underwater || !isSoilPalette(palette) || coverClass == ESA_TREE_COVER) {
			return palette;
		}
		int slopeDiff = sampleSlopeDiff(worldX, worldZ, surface);
		if (slopeDiff >= STONY_SLOPE_DIFF) {
			return SurfacePalette.stonyPeaks();
		}
		return palette;
	}

	private SurfacePalette selectSurfacePalette(
			Holder<Biome> biome,
			int worldX,
			int worldZ,
			int surface,
			boolean underwater,
			int slopeDiff,
			int coverClass
	) {
		SurfacePalette palette = selectBaseSurfacePalette(biome, worldX, worldZ);
		if (palette == null) {
			return null;
		}
		if (underwater || !isSoilPalette(palette) || coverClass == ESA_TREE_COVER) {
			return palette;
		}
		if (slopeDiff >= STONY_SLOPE_DIFF) {
			return SurfacePalette.stonyPeaks();
		}
		return palette;
	}

	private static int sampleSlopeDiffCached(int[] heightGrid, int gridSize, int step, int centerIndex, int surface) {
		int east = heightGrid[centerIndex + step];
		int west = heightGrid[centerIndex - step];
		int north = heightGrid[centerIndex - step * gridSize];
		int south = heightGrid[centerIndex + step * gridSize];

		return Math.max(
				Math.max(Math.abs(east - surface), Math.abs(west - surface)),
				Math.max(Math.abs(north - surface), Math.abs(south - surface))
		);
	}

	private static boolean isSoilPalette(SurfacePalette palette) {
		BlockState filler = palette.filler();
		return filler.is(BlockTags.DIRT) || filler.is(Blocks.MUD);
	}

	private SurfacePalette selectBaseSurfacePalette(Holder<Biome> biome, int worldX, int worldZ) {
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

	private static BiomeGenerationSettings generationSettingsForBiome(Holder<Biome> biome, EarthGeneratorSettings settings) {
		boolean keepTrees = biome.is(Biomes.MANGROVE_SWAMP);
		int flags = geologyFlags(settings, keepTrees);
		BiomeSettingsKey key = new BiomeSettingsKey(biome, flags);
		return FILTERED_SETTINGS.computeIfAbsent(key, cached -> filterGenerationSettings(biome, settings, keepTrees));
	}

	private static BiomeGenerationSettings filterGenerationSettings(
			Holder<Biome> biome,
			EarthGeneratorSettings settings,
			boolean keepTrees
	) {
		BiomeGenerationSettings original = biome.value().getGenerationSettings();
		BiomeGenerationSettings.PlainBuilder builder = new BiomeGenerationSettings.PlainBuilder();
		for (Holder<ConfiguredWorldCarver<?>> carver : original.getCarvers()) {
			Holder<ConfiguredWorldCarver<?>> safeCarver = Objects.requireNonNull(carver, "carver");
			if (shouldKeepCarver(safeCarver, settings)) {
				builder.addCarver(safeCarver);
			}
		}
		List<HolderSet<PlacedFeature>> features = original.features();
		for (int step = 0; step < features.size(); step++) {
			for (Holder<PlacedFeature> feature : features.get(step)) {
				Holder<PlacedFeature> safeFeature = Objects.requireNonNull(feature, "feature");
				if (!keepTrees && isTreeFeature(safeFeature.value())) {
					continue;
				}
				if (!shouldKeepFeature(safeFeature, settings)) {
					continue;
				}
				builder.addFeature(step, safeFeature);
			}
		}
		return builder.build();
	}

	private static int geologyFlags(EarthGeneratorSettings settings, boolean keepTrees) {
		int flags = 0;
		if (settings.caveCarvers()) {
			flags |= 1 << 0;
		}
		if (settings.largeCaves()) {
			flags |= 1 << 1;
		}
		if (settings.canyonCarvers()) {
			flags |= 1 << 2;
		}
		if (settings.aquifers()) {
			flags |= 1 << 3;
		}
		if (settings.dripstone()) {
			flags |= 1 << 4;
		}
		if (settings.deepDark()) {
			flags |= 1 << 5;
		}
		if (settings.oreDistribution()) {
			flags |= 1 << 6;
		}
		if (settings.lavaPools()) {
			flags |= 1 << 7;
		}
		if (settings.geodes()) {
			flags |= 1 << 9;
		}
		if (keepTrees) {
			flags |= 1 << 8;
		}
		return flags;
	}

	private static boolean shouldKeepCarver(Holder<ConfiguredWorldCarver<?>> carver, EarthGeneratorSettings settings) {
		return carver.unwrapKey()
				.map(ResourceKey::identifier)
				.map(id ->  shouldKeepCarverId(id.getPath(), settings))
				.orElse(Boolean.TRUE);
	}

	private static boolean shouldKeepCarverId(String path, EarthGeneratorSettings settings) {
		if (!settings.caveCarvers() && path.equals("cave")) {
			return false;
		}
		if (!settings.largeCaves() && path.equals("cave_extra_underground")) {
			return false;
		}
		if (!settings.canyonCarvers() && path.equals("canyon")) {
			return false;
		}
		return true;
	}

	private static boolean shouldKeepFeature(Holder<PlacedFeature> feature, EarthGeneratorSettings settings) {
		return feature.unwrapKey()
				.map(ResourceKey::identifier)
				.map(id -> shouldKeepFeatureId(id.getPath(), settings))
				.orElse(Boolean.TRUE);
	}

	private static boolean shouldKeepFeatureId(String path, EarthGeneratorSettings settings) {
		if (path.equals("freeze_top_layer") || path.equals("snow_and_freeze")) {
			return false;
		}
		if (!settings.oreDistribution() && path.startsWith("ore_")) {
			return false;
		}
		if (!settings.geodes() && path.contains("geode")) {
			return false;
		}
		if (!settings.dripstone() && path.contains("dripstone")) {
			return false;
		}
		if (!settings.deepDark() && (path.contains("sculk") || path.contains("deep_dark"))) {
			return false;
		}
		if (!settings.aquifers() && path.startsWith("spring_water")) {
			return false;
		}
		if (!settings.lavaPools() && (path.startsWith("lake_lava") || path.startsWith("spring_lava"))) {
			return false;
		}
		return true;
	}

	private static boolean isUndergroundStep(int step) {
		GenerationStep.Decoration[] values = GenerationStep.Decoration.values();
		if (step < 0 || step >= values.length) {
			return false;
		}
		GenerationStep.Decoration decoration = values[step];
		return decoration == GenerationStep.Decoration.UNDERGROUND_STRUCTURES
				|| decoration == GenerationStep.Decoration.STRONGHOLDS
				|| decoration == GenerationStep.Decoration.UNDERGROUND_ORES
				|| decoration == GenerationStep.Decoration.UNDERGROUND_DECORATION
				|| decoration == GenerationStep.Decoration.FLUID_SPRINGS;
	}

	private boolean isStructureSetEnabled(Holder<StructureSet> structureSet) {
		for (StructureSet.StructureSelectionEntry entry : structureSet.value().structures()) {
			if (!isStructureEnabled(entry.structure())) {
				return false;
			}
		}
		return true;
	}

	private boolean isStructureEnabled(Holder<Structure> structure) {
		return structure.unwrapKey()
				.map(ResourceKey::identifier)
				.map(id -> isStructureEnabled(id.getPath()))
				.orElse(Boolean.TRUE);
	}

	private boolean isStructureEnabled(String path) {
        switch (path) {
            case "stronghold" -> {
                return this.settings.structureSettings().addStrongholds();
            }
            case "igloo" -> {
                return this.settings.structureSettings().addIgloos();
            }
            case "ocean_monument" -> {
                return this.settings.structureSettings().addOceanMonuments();
            }
            case "woodland_mansion" -> {
                return this.settings.structureSettings().addWoodlandMansions();
            }
            case "desert_pyramid", "desert_temple" -> {
                return this.settings.structureSettings().addDesertTemples();
            }
            case "jungle_pyramid", "jungle_temple" -> {
                return this.settings.structureSettings().addJungleTemples();
            }
            case "pillager_outpost" -> {
                return this.settings.structureSettings().addPillagerOutposts();
            }
            case "buried_treasure" -> {
                return this.settings.structureSettings().addBuriedTreasure();
            }
            case "swamp_hut", "witch_hut" -> {
                return this.settings.structureSettings().addWitchHuts();
            }
            case "ancient_city" -> {
                return this.settings.structureSettings().addAncientCities();
            }
            case "trial_chambers" -> {
                return this.settings.structureSettings().addTrialChambers();
            }
        }

        if (path.startsWith("ruined_portal")) {
			return this.settings.structureSettings().addRuinedPortals();
		}
		if (path.startsWith("shipwreck")) {
			return this.settings.structureSettings().addShipwrecks();
		}
		if (path.startsWith("ocean_ruin")) {
			return this.settings.structureSettings().addOceanRuins();
		}
        if (path.startsWith("village")) {
            return this.settings.structureSettings().addVillages();
        }
        if (path.startsWith("mineshaft")) {
            return this.settings.structureSettings().addMineshafts();
        }
		if (path.startsWith("trail_ruins")) {
			return this.settings.addTrailRuins();
		}
		return true;
	}

	private boolean isFrozenPeaksChunk(ChunkPos pos, RandomState randomState) {
		int centerX = pos.getMinBlockX() + 8;
		int centerZ = pos.getMinBlockZ() + 8;
		Holder<Biome> biome = this.biomeSource.getNoiseBiome(
				QuartPos.fromBlock(centerX),
				0,
				QuartPos.fromBlock(centerZ),
				randomState.sampler()
		);
		return biome.is(Biomes.FROZEN_PEAKS);
	}

	private void stripIglooStarts(RegistryAccess registryAccess, ChunkAccess chunk) {
		Registry<Structure> registry = registryAccess.lookupOrThrow(Registries.STRUCTURE);
		Structure igloo = Objects.requireNonNull(
				registry.getValueOrThrow(BuiltinStructures.IGLOO),
				"iglooStructure"
		);
		StructureStart start = chunk.getStartForStructure(igloo);
		if (start == null || !start.isValid()) {
			return;
		}
		chunk.setStartForStructure(igloo, StructureStart.INVALID_START);
		chunk.getAllReferences().remove(igloo);
	}

    private void stripVillagesOnSteepTerrain(RegistryAccess registryAccess, ChunkAccess chunk) {
        Registry<Structure> registry = registryAccess.lookupOrThrow(Registries.STRUCTURE);

        // Get all village structures using the tag
        HolderSet.Named<Structure> villageTag = registry.getOrThrow(StructureTags.VILLAGE);

        // Check if any village is present
        boolean hasVillage = false;
        for (Holder<Structure> villageHolder : villageTag) {
            if (hasValidStructure(chunk, villageHolder.value())) {
                hasVillage = true;
                break;
            }
        }

        // Only check flatness if a village exists
        if (hasVillage) {
            ChunkPos pos = chunk.getPos();
            int centerX = pos.getMinBlockX() + 8;
            int centerZ = pos.getMinBlockZ() + 8;

            if (!isGroundFlat(centerX, centerZ, settings.villageSettings().radius(), settings.villageSettings().heightRange())) {
                // Remove all villages in the tag
                for (Holder<Structure> villageHolder : villageTag) {
                    removeStructureIfPresent(chunk, villageHolder.value());
                }
            }
        }
    }

    private boolean hasValidStructure(ChunkAccess chunk, Structure structure) {
        StructureStart start = chunk.getStartForStructure(structure);
        return start != null && start.isValid();
    }

    private void removeStructureIfPresent(ChunkAccess chunk, Structure structure) {
        StructureStart start = chunk.getStartForStructure(structure);
        if (start != null && start.isValid()) {
            chunk.setStartForStructure(structure, StructureStart.INVALID_START);
        }
    }

	private static List<ConfiguredFeature<?, ?>> treeFeaturesForBiome(Holder<Biome> biome) {
		return TREE_FEATURES.computeIfAbsent(biome, holder -> {
			List<ConfiguredFeature<?, ?>> result = new ArrayList<>();
			for (HolderSet<PlacedFeature> set : holder.value().getGenerationSettings().features()) {
				for (Holder<PlacedFeature> feature : set) {
					PlacedFeature placed = feature.value();
					if (!isTreeFeature(placed)) {
						continue;
					}
					result.add(placed.feature().value());
				}
			}
			return List.copyOf(result);
		});
	}

	private static boolean isTreeFeature(PlacedFeature feature) {
		return feature.getFeatures().anyMatch(configured -> {
			Feature<?> type = configured.feature();
			return type == Feature.TREE || type == Feature.FALLEN_TREE;
		});
	}

	private record BiomeSettingsKey(Holder<Biome> biome, int flags) {
	}

	private static final class FilteredStructureLookup implements HolderLookup<StructureSet> {
		private final HolderLookup<StructureSet> delegate;
		private final Predicate<Holder<StructureSet>> predicate;

		private FilteredStructureLookup(HolderLookup<StructureSet> delegate, Predicate<Holder<StructureSet>> predicate) {
			this.delegate = delegate;
			this.predicate = predicate;
		}

		@Override
		public @NonNull Stream<Holder.Reference<StructureSet>> listElements() {
			return Objects.requireNonNull(
					this.delegate.listElements().filter(this.predicate),
					"listElements"
			);
		}

		@Override
		public @NonNull Stream<HolderSet.Named<StructureSet>> listTags() {
			return Objects.requireNonNull(this.delegate.listTags(), "listTags");
		}

		@Override
		public @NonNull Optional<Holder.Reference<StructureSet>> get(@NonNull ResourceKey<StructureSet> key) {
			return Objects.requireNonNull(this.delegate.get(key), "getStructureSet");
		}

		@Override
		public @NonNull Optional<HolderSet.Named<StructureSet>> get(@NonNull TagKey<StructureSet> tag) {
			return Objects.requireNonNull(this.delegate.get(tag), "getStructureSetTag");
		}
	}

    private boolean isGroundFlat(int centerX, int centerZ, int radius, int maxHeightDifference) {
        int centerHeight = sampleSurfaceHeight(centerX, centerZ);
        int minHeight = centerHeight;
        int maxHeight = centerHeight;

        // Sample points in a grid around the center
        int step = 4; // Sample every 4 blocks
        for (int dx = -radius; dx <= radius; dx += step) {
            for (int dz = -radius; dz <= radius; dz += step) {
                int height = sampleSurfaceHeight(centerX + dx, centerZ + dz);
                minHeight = Math.min(minHeight, height);
                maxHeight = Math.max(maxHeight, height);
            }
        }

        return (maxHeight - minHeight) <= maxHeightDifference;
    }

	private TellusGeologyGenerator getGeologyGenerator(long seed) {
		TellusGeologyGenerator cached = this.geologyGenerator;
		if (cached != null && this.geologySeed == seed) {
			return cached;
		}
		synchronized (this) {
			cached = this.geologyGenerator;
			if (cached == null || this.geologySeed != seed) {
				cached = new TellusGeologyGenerator(this.settings, this.minY, this.height, this.seaLevel, seed);
				this.geologyGenerator = cached;
				this.geologySeed = seed;
			}
		}
		return cached;
	}

	private static void applySnowCover(
			ChunkAccess chunk,
			BlockPos.MutableBlockPos cursor,
			int worldX,
			int worldZ,
			int surface,
			int minY,
			boolean reduceIce
	) {
		long seed = seedFromCoords(worldX, 0, worldZ) ^ 0x5DEECE66DL;
		Random random = new Random(seed);
		int roll = random.nextInt(COVER_ROLL_RANGE);
		if (roll < SNOW_ICE_CHANCE && (!reduceIce || random.nextBoolean())) {
			cursor.set(worldX, surface, worldZ);
			chunk.setBlockState(cursor, Blocks.ICE.defaultBlockState());
			return;
		}
		if (roll < SNOW_ICE_CHANCE + POWDER_SNOW_CHANCE) {
			int depth = 1 + random.nextInt(MAX_POWDER_DEPTH);
			for (int i = 0; i < depth; i++) {
				int y = surface - i;
				if (y < minY) {
					break;
				}
				cursor.set(worldX, y, worldZ);
				chunk.setBlockState(cursor, Blocks.POWDER_SNOW.defaultBlockState());
			}
			return;
		}
		cursor.set(worldX, surface, worldZ);
		chunk.setBlockState(cursor, Blocks.SNOW_BLOCK.defaultBlockState());
	}

	private record ColumnHeights(int terrainSurface, int waterSurface, boolean hasWater) {
	}

	public record LodSurface(@NonNull BlockState top, @NonNull BlockState filler) {
	}

	private record SurfacePalette(@NonNull BlockState top, @NonNull BlockState underwaterTop, @NonNull BlockState filler, int depth) {
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

		static SurfacePalette ocean(@NonNull BlockState top) {
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
}
