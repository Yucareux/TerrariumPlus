package com.yucareux.terrariumplus.worldgen;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.yucareux.terrariumplus.Terrarium;
import com.yucareux.terrariumplus.world.data.cover.TerrariumLandCoverSource;
import com.yucareux.terrariumplus.world.data.elevation.TerrariumElevationSource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
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
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.carver.ConfiguredWorldCarver;
import net.minecraft.world.level.levelgen.carver.CarvingContext;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.CarvingMask;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.RandomSupport;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.dimension.DimensionType;
import org.jspecify.annotations.NonNull;

public final class EarthChunkGenerator extends ChunkGenerator {
	public static final MapCodec<EarthChunkGenerator> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
			BiomeSource.CODEC.fieldOf("biome_source").forGetter(generator -> generator.biomeSource),
			EarthGeneratorSettings.CODEC.fieldOf("settings").forGetter(EarthChunkGenerator::settings)
	).apply(instance, EarthChunkGenerator::new));

	private static final double EQUATOR_CIRCUMFERENCE = 40075017.0;
	private static final TerrariumElevationSource ELEVATION_SOURCE = new TerrariumElevationSource();
	private static final TerrariumLandCoverSource LAND_COVER_SOURCE = new TerrariumLandCoverSource();
	private static final int COVER_ROLL_RANGE = 200;
	private static final int SNOW_ICE_CHANCE = 3;
	private static final int POWDER_SNOW_CHANCE = 30;
	private static final int MAX_POWDER_DEPTH = 5;
	private static final int ESA_TREE_COVER = 10;
	private static final int ESA_SNOW_ICE = 70;
	private static final int ESA_MANGROVES = 95;
	private static final int TREE_CELL_SIZE = 5;
	private static final int SURFACE_DEPTH = 4;
	private static final int CINEMATIC_MAX_WATER_DEPTH = 16;
	private static final int CARVER_RANGE = 8;
	private static final int NOISE_CAVE_MIN_ROOF = 8;
	private static final int NOISE_CAVE_MAX_DEPTH = 96;
	private static final int NOISE_CAVE_STEP_Y = 4;
	private static final int NOISE_CAVE_DEPTH_FALLOFF = 48;
	private static final double NOISE_CAVE_THRESHOLD_SHALLOW = -0.9;
	private static final double NOISE_CAVE_THRESHOLD_DEEP = -0.6;
	private static final AtomicBoolean LOGGED_CHUNK_LAYOUT = new AtomicBoolean(false);

	private static final Map<BiomeSettingsKey, BiomeGenerationSettings> FILTERED_SETTINGS = new ConcurrentHashMap<>();
	private static final Map<Holder<Biome>, List<ConfiguredFeature<?, ?>>> TREE_FEATURES = new ConcurrentHashMap<>();

	private final EarthGeneratorSettings settings;
	private final int seaLevel;
	private final int minY;
	private final int height;
	private final WaterSurfaceResolver waterResolver;
	private volatile CarverGeneratorState carverState;
	private volatile long carverSeed = Long.MIN_VALUE;

	public EarthChunkGenerator(BiomeSource biomeSource, EarthGeneratorSettings settings) {
		super(biomeSource, biome -> generationSettingsForBiome(biome, settings));
		this.settings = settings;
		this.seaLevel = settings.resolveSeaLevel();
		EarthGeneratorSettings.HeightLimits limits = EarthGeneratorSettings.resolveHeightLimits(settings);
		this.minY = limits.minY();
		this.height = limits.height();
		this.waterResolver = new WaterSurfaceResolver(LAND_COVER_SOURCE, ELEVATION_SOURCE, settings);
		if (Terrarium.LOGGER.isInfoEnabled()) {
			Terrarium.LOGGER.info(
					"EarthChunkGenerator init: scale={}, minAltitude={}, maxAltitude={}, heightOffset={}, limits=[minY={}, height={}, logicalHeight={}], seaLevel={}",
					settings.worldScale(),
					settings.minAltitude(),
					settings.maxAltitude(),
					settings.heightOffset(),
					limits.minY(),
					limits.height(),
					limits.logicalHeight(),
					this.seaLevel
			);
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
		if (this.settings.cinematicMode()) {
			return;
		}
		if (SharedConstants.DEBUG_DISABLE_CARVERS) {
			return;
		}
		CarverGeneratorState carver = getCarverState(level, seed);
		RandomState carverRandom = Objects.requireNonNull(carver.randomState(), "carverRandom");
		BiomeManager carverBiomeManager = biomeManager.withDifferentSource(
				(x, y, z) -> this.biomeSource.getNoiseBiome(x, y, z, carverRandom.sampler())
		);
		Function<@NonNull BlockPos, Holder<Biome>> biomeGetter =
				pos -> carverBiomeManager.getBiome(Objects.requireNonNull(pos, "biomePos"));
		WorldgenRandom worldgenRandom = new WorldgenRandom(new LegacyRandomSource(RandomSupport.generateUniqueSeed()));
		ChunkPos chunkPos = chunk.getPos();
		Blender blender = Blender.of(level);
		NoiseChunk noiseChunk = chunk.getOrCreateNoiseChunk(
				access -> createNoiseChunk(Objects.requireNonNull(access, "noiseChunk"), structures, blender, carver)
		);
		Aquifer aquifer = noiseChunk.aquifer();
		CarvingContext context = new CarvingContext(
				carver.generator(),
				level.registryAccess(),
				chunk.getHeightAccessorForGeneration(),
				noiseChunk,
				carverRandom,
				carver.settings().surfaceRule()
		);
		CarvingMask carvingMask = ((ProtoChunk) chunk).getOrCreateCarvingMask();

		for (int offsetX = -CARVER_RANGE; offsetX <= CARVER_RANGE; offsetX++) {
			for (int offsetZ = -CARVER_RANGE; offsetZ <= CARVER_RANGE; offsetZ++) {
				ChunkPos offsetPos = new ChunkPos(chunkPos.x + offsetX, chunkPos.z + offsetZ);
				BiomeGenerationSettings generationSettings = resolveCarverBiomeSettings(offsetPos, carverRandom);
				int index = 0;
				for (Holder<ConfiguredWorldCarver<?>> holder : generationSettings.getCarvers()) {
					ConfiguredWorldCarver<?> carverConfig = holder.value();
					worldgenRandom.setLargeFeatureSeed(seed + index, offsetPos.x, offsetPos.z);
					if (carverConfig.isStartChunk(worldgenRandom)) {
						carverConfig.carve(
								context,
								chunk,
								biomeGetter,
								worldgenRandom,
								aquifer,
								offsetPos,
								carvingMask
						);
					}
					index++;
				}
			}
		}

		if (this.settings.largeCaves()) {
			int chunkX = chunkPos.getMinBlockX() >> 4;
			int chunkZ = chunkPos.getMinBlockZ() >> 4;
			WaterSurfaceResolver.WaterChunkData waterData = this.waterResolver.resolveChunkWaterData(chunkX, chunkZ);
			applyNoiseCaves(chunk, aquifer, waterData, carverRandom);
		}
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
		if (this.settings.cinematicMode()) {
			return;
		}
		super.createStructures(registryAccess, structureState, structures, chunk, templates, levelKey);
		if (this.settings.addIgloos()
				&& !isFrozenPeaksChunk(chunk.getPos(), structureState.randomState())) {
			stripIglooStarts(registryAccess, chunk);
		}
	}

	@Override
	public void createReferences(
			@NonNull WorldGenLevel level,
			@NonNull StructureManager structures,
			@NonNull ChunkAccess chunk
	) {
		if (this.settings.cinematicMode()) {
			return;
		}
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
		int chunkMinY = chunk.getMinY();
		int chunkHeight = chunk.getHeight();
		int chunkMaxY = chunkMinY + chunkHeight;
		if (LOGGED_CHUNK_LAYOUT.compareAndSet(false, true) && Terrarium.LOGGER.isInfoEnabled()) {
			Terrarium.LOGGER.info(
					"fillFromNoise layout: chunkPos={}, minY={}, height={}, maxY={}, sections={}, genMinY={}, genHeight={}, seaLevel={}, settingsMinAlt={}, settingsMaxAlt={}",
					pos,
					chunkMinY,
					chunkHeight,
					chunkMinY + chunkHeight - 1,
					chunkHeight >> 4,
					this.minY,
					this.height,
					this.seaLevel,
					this.settings.minAltitude(),
					this.settings.maxAltitude()
			);
		}
		int chunkX = pos.getMinBlockX() >> 4;
		int chunkZ = pos.getMinBlockZ() >> 4;
		WaterSurfaceResolver.WaterChunkData waterData = this.waterResolver.resolveChunkWaterData(chunkX, chunkZ);
		if (this.settings.cinematicMode()) {
			return fillFromNoiseCinematic(random, chunk, pos, chunkMinY, chunkMaxY, waterData);
		}
		BlockState stone = Blocks.STONE.defaultBlockState();
		BlockState water = Blocks.WATER.defaultBlockState();
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

		for (int localX = 0; localX < 16; localX++) {
			int worldX = pos.getMinBlockX() + localX;
			for (int localZ = 0; localZ < 16; localZ++) {
				int worldZ = pos.getMinBlockZ() + localZ;
				int coverClass = LAND_COVER_SOURCE.sampleCoverClass(worldX, worldZ, this.settings.worldScale());
				ColumnHeights column = resolveColumnHeights(
						worldX,
						worldZ,
						localX,
						localZ,
						chunkMinY,
						chunkMaxY,
						coverClass,
						waterData
				);
				int surface = column.terrainSurface();
				int waterSurface = column.waterSurface();
				boolean hasWater = column.hasWater();

				for (int y = chunkMinY; y <= surface; y++) {
					cursor.set(worldX, y, worldZ);
					chunk.setBlockState(cursor, stone);
				}
				if (hasWater && surface < waterSurface) {
					for (int y = surface + 1; y <= waterSurface; y++) {
						cursor.set(worldX, y, worldZ);
						chunk.setBlockState(cursor, water);
					}
				}
				boolean underwater = hasWater && waterSurface > surface;
				applySurface(chunk, cursor, worldX, worldZ, surface, chunkMinY, random, underwater);
				if (surface >= this.seaLevel && coverClass == ESA_SNOW_ICE) {
					boolean reduceIce = isFrozenPeaksColumn(random, worldX, worldZ, surface);
					applySnowCover(chunk, cursor, worldX, worldZ, surface, chunkMinY, false, reduceIce);
				}
			}
		}

		return Objects.requireNonNull(CompletableFuture.<ChunkAccess>completedFuture(chunk), "completedFuture");
	}

	private @NonNull CompletableFuture<ChunkAccess> fillFromNoiseCinematic(
			@NonNull RandomState random,
			@NonNull ChunkAccess chunk,
			@NonNull ChunkPos pos,
			int chunkMinY,
			int chunkMaxY,
			WaterSurfaceResolver.WaterChunkData waterData
	) {
		BlockState stone = Blocks.STONE.defaultBlockState();
		BlockState bedrock = Blocks.BEDROCK.defaultBlockState();
		BlockState water = Blocks.WATER.defaultBlockState();
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

		for (int localX = 0; localX < 16; localX++) {
			int worldX = pos.getMinBlockX() + localX;
			for (int localZ = 0; localZ < 16; localZ++) {
				int worldZ = pos.getMinBlockZ() + localZ;
				int coverClass = LAND_COVER_SOURCE.sampleCoverClass(worldX, worldZ, this.settings.worldScale());
				ColumnHeights column = resolveColumnHeights(
						worldX,
						worldZ,
						localX,
						localZ,
						chunkMinY,
						chunkMaxY,
						coverClass,
						waterData
				);
				int waterSurface = column.waterSurface();
				boolean hasWater = column.hasWater();
				int surface = resolveCinematicSurface(column.terrainSurface(), waterSurface, hasWater);
				boolean underwater = hasWater && waterSurface > surface;

				if (surface >= chunkMinY && surface < chunkMaxY) {
					BlockState top = resolveSurfaceTop(random, worldX, worldZ, surface, underwater);
					cursor.set(worldX, surface, worldZ);
					chunk.setBlockState(cursor, top);
				}
				if (surface - 1 >= chunkMinY && surface - 1 < chunkMaxY) {
					cursor.set(worldX, surface - 1, worldZ);
					chunk.setBlockState(cursor, stone);
				}
				if (surface - 2 >= chunkMinY && surface - 2 < chunkMaxY) {
					cursor.set(worldX, surface - 2, worldZ);
					chunk.setBlockState(cursor, bedrock);
				}

				if (hasWater && surface < waterSurface) {
					int startY = Math.max(surface + 1, chunkMinY);
					int endY = Math.min(waterSurface, chunkMaxY - 1);
					for (int y = startY; y <= endY; y++) {
						cursor.set(worldX, y, worldZ);
						chunk.setBlockState(cursor, water);
					}
				}
				if (surface >= this.seaLevel && coverClass == ESA_SNOW_ICE) {
					boolean reduceIce = isFrozenPeaksColumn(random, worldX, worldZ, surface);
					applySnowCover(chunk, cursor, worldX, worldZ, surface, chunkMinY, true, reduceIce);
				}
			}
		}

		return Objects.requireNonNull(CompletableFuture.<ChunkAccess>completedFuture(chunk), "completedFuture");
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
		if (this.settings.cinematicMode()) {
			surface = resolveCinematicSurface(surface, column.waterSurface(), column.hasWater());
		}
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
		if (this.settings.cinematicMode()) {
			surface = resolveCinematicSurface(surface, column.waterSurface(), column.hasWater());
		}
		int surfaceIndex = surface - minY;
		if (this.settings.cinematicMode()) {
			boolean underwater = column.hasWater() && column.waterSurface() > surface;
			if (surfaceIndex >= 0 && surfaceIndex < states.length) {
				BlockState top = resolveSurfaceTop(random, x, z, surface, underwater);
				states[surfaceIndex] = top;
			}
			if (surfaceIndex - 1 >= 0 && surfaceIndex - 1 < states.length) {
				states[surfaceIndex - 1] = Blocks.STONE.defaultBlockState();
			}
			if (surfaceIndex - 2 >= 0 && surfaceIndex - 2 < states.length) {
				states[surfaceIndex - 2] = Blocks.BEDROCK.defaultBlockState();
			}
		} else {
			for (int i = 0; i <= surfaceIndex; i++) {
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

		return Objects.requireNonNull(new NoiseColumn(minY, states), "noiseColumn");
	}

	@Override
	public void addDebugScreenInfo(@NonNull List<String> info, @NonNull RandomState random, @NonNull BlockPos pos) {
		info.add(String.format("Terrarium scale: %.1f", this.settings.worldScale()));
	}

	private void placeTrees(WorldGenLevel level, ChunkAccess chunk) {
		ChunkPos pos = chunk.getPos();
		int chunkMinX = pos.getMinBlockX();
		int chunkMinZ = pos.getMinBlockZ();
		int chunkMaxX = chunkMinX + 15;
		int chunkMaxZ = chunkMinZ + 15;
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

	private int sampleSurfaceHeight(int blockX, int blockZ) {
		double elevation = ELEVATION_SOURCE.sampleElevationMeters(blockX, blockZ, this.settings.worldScale());
		double heightScale = elevation >= 0.0 ? this.settings.terrestrialHeightScale() : this.settings.oceanicHeightScale();
		double scaled = elevation * heightScale / this.settings.worldScale();
		int offset = this.settings.heightOffset();
		int height = elevation >= 0.0 ? Mth.ceil(scaled) : Mth.floor(scaled);
		return height + offset;
	}

	private ColumnHeights resolveColumnHeights(
			int worldX,
			int worldZ,
			int localX,
			int localZ,
			int minY,
			int maxYExclusive,
			int coverClass,
			WaterSurfaceResolver.WaterChunkData waterData
	) {
		int maxY = Math.max(minY, maxYExclusive - 1);
		if (coverClass == ESA_MANGROVES) {
			int surface = Mth.clamp(this.sampleSurfaceHeight(worldX, worldZ), minY, maxY);
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

	private int resolveCinematicSurface(int surface, int waterSurface, boolean hasWater) {
		if (!this.settings.cinematicMode() || !hasWater || waterSurface <= surface) {
			return surface;
		}
		int minSurface = waterSurface - CINEMATIC_MAX_WATER_DEPTH;
		if (surface < minSurface) {
			surface = minSurface;
			if (surface >= waterSurface) {
				surface = waterSurface - 1;
			}
		}
		return surface;
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
			RandomState random,
			boolean underwater
	) {
		if (surface < minY) {
			return;
		}
		Holder<Biome> biome = this.biomeSource.getNoiseBiome(
				QuartPos.fromBlock(worldX),
				QuartPos.fromBlock(surface),
				QuartPos.fromBlock(worldZ),
				random.sampler()
		);
		SurfacePalette palette = selectSurfacePalette(biome, worldX, worldZ);
		if (palette == null) {
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

	private @NonNull BlockState resolveSurfaceTop(
			@NonNull RandomState random,
			int worldX,
			int worldZ,
			int surface,
			boolean underwater
	) {
		Holder<Biome> biome = this.biomeSource.getNoiseBiome(
				QuartPos.fromBlock(worldX),
				QuartPos.fromBlock(surface),
				QuartPos.fromBlock(worldZ),
				random.sampler()
		);
		SurfacePalette palette = selectSurfacePalette(biome, worldX, worldZ);
		if (palette == null) {
			return Blocks.STONE.defaultBlockState();
		}
		return underwater ? palette.underwaterTop() : palette.top();
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
			if (settings.cinematicMode() && isUndergroundStep(step)) {
				continue;
			}
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
		if (settings.cinematicMode()) {
			return keepTrees ? 1 << 8 : 0;
		}
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
		if (keepTrees) {
			flags |= 1 << 8;
		}
		return flags;
	}

	private static boolean shouldKeepCarver(Holder<ConfiguredWorldCarver<?>> carver, EarthGeneratorSettings settings) {
		return carver.unwrapKey()
				.map(ResourceKey::identifier)
				.map(id -> shouldKeepCarverId(id.getPath(), settings))
				.orElse(true);
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
				.orElse(true);
	}

	private static boolean shouldKeepFeatureId(String path, EarthGeneratorSettings settings) {
		if (path.equals("freeze_top_layer") || path.equals("snow_and_freeze")) {
			return false;
		}
		if (!settings.oreDistribution() && path.startsWith("ore_")) {
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
				.orElse(true);
	}

	private boolean isStructureEnabled(String path) {
		if (path.startsWith("village")) {
			return this.settings.addVillages();
		}
		if (path.equals("stronghold")) {
			return this.settings.addStrongholds();
		}
		if (path.startsWith("mineshaft")) {
			return this.settings.addMineshafts();
		}
		if (path.equals("igloo")) {
			return this.settings.addIgloos();
		}
		if (path.equals("ocean_monument")) {
			return this.settings.addOceanMonuments();
		}
		if (path.equals("woodland_mansion")) {
			return this.settings.addWoodlandMansions();
		}
		if (path.equals("desert_pyramid") || path.equals("desert_temple")) {
			return this.settings.addDesertTemples();
		}
		if (path.equals("jungle_pyramid") || path.equals("jungle_temple")) {
			return this.settings.addJungleTemples();
		}
		if (path.equals("pillager_outpost")) {
			return this.settings.addPillagerOutposts();
		}
		if (path.startsWith("ruined_portal")) {
			return this.settings.addRuinedPortals();
		}
		if (path.startsWith("shipwreck")) {
			return this.settings.addShipwrecks();
		}
		if (path.startsWith("ocean_ruin")) {
			return this.settings.addOceanRuins();
		}
		if (path.equals("buried_treasure")) {
			return this.settings.addBuriedTreasure();
		}
		if (path.equals("igloo")) {
			return this.settings.addIgloos();
		}
		if (path.equals("swamp_hut") || path.equals("witch_hut")) {
			return this.settings.addWitchHuts();
		}
		if (path.equals("ancient_city")) {
			return this.settings.addAncientCities();
		}
		if (path.equals("trial_chambers")) {
			return this.settings.addTrialChambers();
		}
		if (path.startsWith("trail_ruins")) {
			return this.settings.addTrailRuins();
		}
		return true;
	}

	private boolean isFrozenPeaksColumn(RandomState randomState, int worldX, int worldZ, int surface) {
		Holder<Biome> biome = this.biomeSource.getNoiseBiome(
				QuartPos.fromBlock(worldX),
				QuartPos.fromBlock(surface),
				QuartPos.fromBlock(worldZ),
				randomState.sampler()
		);
		return biome.is(Biomes.FROZEN_PEAKS);
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
		@NonNull Structure igloo = Objects.requireNonNull(
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

	private record CarverGeneratorState(
			@NonNull NoiseBasedChunkGenerator generator,
			NoiseGeneratorSettings settings,
			Aquifer.FluidPicker fluidPicker,
			RandomState randomState
	) {
	}

	private CarverGeneratorState getCarverState(WorldGenRegion level, long seed) {
		CarverGeneratorState cached = this.carverState;
		if (cached != null && this.carverSeed == seed) {
			return cached;
		}
		synchronized (this) {
			cached = this.carverState;
			if (cached == null || this.carverSeed != seed) {
				cached = buildCarverState(level, seed);
				this.carverState = cached;
				this.carverSeed = seed;
			}
		}
		return cached;
	}

	private CarverGeneratorState buildCarverState(WorldGenRegion level, long seed) {
		Registry<NoiseGeneratorSettings> registry = level.registryAccess().lookupOrThrow(Registries.NOISE_SETTINGS);
		NoiseGeneratorSettings base = registry.getValueOrThrow(NoiseGeneratorSettings.OVERWORLD);
		NoiseSettings baseNoise = base.noiseSettings();
		NoiseSettings noise = NoiseSettings.create(this.minY, this.height, baseNoise.noiseSizeHorizontal(), baseNoise.noiseSizeVertical());
			NoiseGeneratorSettings carverSettings = new NoiseGeneratorSettings(
					noise,
					base.defaultBlock(),
					base.defaultFluid(),
					base.noiseRouter(),
					base.surfaceRule(),
					base.spawnTarget(),
					this.seaLevel,
					false,
					this.settings.aquifers(),
					base.oreVeinsEnabled(),
					base.useLegacyRandomSource()
			);
		Registry<NormalNoise.NoiseParameters> noiseRegistry = level.registryAccess().lookupOrThrow(Registries.NOISE);
		RandomState carverRandom = RandomState.create(carverSettings, noiseRegistry, seed);
		NoiseBasedChunkGenerator generator = new NoiseBasedChunkGenerator(this.biomeSource, Holder.direct(carverSettings));
		return new CarverGeneratorState(generator, carverSettings, createFluidPicker(carverSettings), carverRandom);
	}

	private BiomeGenerationSettings resolveCarverBiomeSettings(ChunkPos chunkPos, RandomState random) {
		Holder<Biome> biome = this.biomeSource.getNoiseBiome(
				QuartPos.fromBlock(chunkPos.getMinBlockX()),
				0,
				QuartPos.fromBlock(chunkPos.getMinBlockZ()),
				random.sampler()
		);
		return generationSettingsForBiome(biome, this.settings);
	}

	private NoiseChunk createNoiseChunk(
			@NonNull ChunkAccess chunk,
			@NonNull StructureManager structures,
			@NonNull Blender blender,
			@NonNull CarverGeneratorState carver
	) {
		return NoiseChunk.forChunk(
				Objects.requireNonNull(chunk, "chunk"),
				Objects.requireNonNull(carver.randomState(), "carverRandom"),
				Beardifier.forStructuresInChunk(Objects.requireNonNull(structures, "structures"), chunk.getPos()),
				Objects.requireNonNull(carver.settings(), "carverSettings"),
				Objects.requireNonNull(carver.fluidPicker(), "fluidPicker"),
				Objects.requireNonNull(blender, "blender")
		);
	}

	private void applyNoiseCaves(
			ChunkAccess chunk,
			Aquifer aquifer,
			WaterSurfaceResolver.WaterChunkData waterData,
			RandomState random
	) {
		DensityFunction density = random.router().finalDensity();
		ChunkPos pos = chunk.getPos();
		int minY = chunk.getMinY();
		int maxY = minY + chunk.getHeight() - 1;
		MutableDensityContext context = new MutableDensityContext();
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

		for (int localX = 0; localX < 16; localX++) {
			int worldX = pos.getMinBlockX() + localX;
			for (int localZ = 0; localZ < 16; localZ++) {
				int worldZ = pos.getMinBlockZ() + localZ;
				int surface = Mth.clamp(waterData.terrainSurface(localX, localZ), minY, maxY);
				int carveTop = Math.min(surface - NOISE_CAVE_MIN_ROOF, maxY);
				int carveBottom = Math.max(minY, surface - NOISE_CAVE_MAX_DEPTH);
				if (carveTop <= carveBottom) {
					continue;
				}
				for (int y = carveBottom; y <= carveTop; y += NOISE_CAVE_STEP_Y) {
					int sampleY = Math.min(y + (NOISE_CAVE_STEP_Y / 2), carveTop);
					int depth = surface - sampleY;
					double depthT = Mth.clamp(depth / (double) NOISE_CAVE_DEPTH_FALLOFF, 0.0, 1.0);
					double threshold = Mth.lerp(depthT, NOISE_CAVE_THRESHOLD_SHALLOW, NOISE_CAVE_THRESHOLD_DEEP);
					context.set(worldX, sampleY, worldZ);
					double value = density.compute(context);
					if (value >= threshold) {
						continue;
					}
					for (int dy = 0; dy < NOISE_CAVE_STEP_Y && (y + dy) <= carveTop; dy++) {
						int carveY = y + dy;
						cursor.set(worldX, carveY, worldZ);
						BlockState state = chunk.getBlockState(cursor);
						if (state.isAir()) {
							continue;
						}
						context.set(worldX, carveY, worldZ);
						BlockState fluid = aquifer.computeSubstance(context, value);
						chunk.setBlockState(cursor, fluid == null ? Blocks.AIR.defaultBlockState() : fluid);
					}
				}
			}
		}
	}

	private static final class MutableDensityContext implements DensityFunction.FunctionContext {
		private int x;
		private int y;
		private int z;

		private void set(int x, int y, int z) {
			this.x = x;
			this.y = y;
			this.z = z;
		}

		@Override
		public int blockX() {
			return this.x;
		}

		@Override
		public int blockY() {
			return this.y;
		}

		@Override
		public int blockZ() {
			return this.z;
		}
	}

	private static Aquifer.FluidPicker createFluidPicker(NoiseGeneratorSettings settings) {
		Aquifer.FluidStatus lava = new Aquifer.FluidStatus(-54, Blocks.LAVA.defaultBlockState());
		int seaLevel = settings.seaLevel();
		Aquifer.FluidStatus sea = new Aquifer.FluidStatus(seaLevel, settings.defaultFluid());
		Aquifer.FluidStatus air = new Aquifer.FluidStatus(DimensionType.MIN_Y * 2, Blocks.AIR.defaultBlockState());
		return (x, y, z) -> {
			if (SharedConstants.DEBUG_DISABLE_FLUID_GENERATION) {
				return air;
			}
			return y < Math.min(-54, seaLevel) ? lava : sea;
		};
	}

	private static void applySnowCover(
			ChunkAccess chunk,
			BlockPos.MutableBlockPos cursor,
			int worldX,
			int worldZ,
			int surface,
			int minY,
			boolean cinematic,
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
			if (cinematic) {
				cursor.set(worldX, surface, worldZ);
				chunk.setBlockState(cursor, Blocks.POWDER_SNOW.defaultBlockState());
				return;
			}
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
