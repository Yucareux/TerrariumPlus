package com.yucareux.terrariumplus.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.MapDecoder;
import com.mojang.serialization.MapEncoder;
import com.mojang.serialization.MapLike;
import com.mojang.serialization.RecordBuilder;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;
import net.minecraft.util.Mth;
import net.minecraft.world.level.dimension.DimensionType;

public record EarthGeneratorSettings(
		double worldScale,
		double terrestrialHeightScale,
		double oceanicHeightScale,
		int heightOffset,
		int seaLevel,
		double spawnLatitude,
		double spawnLongitude,
		int minAltitude,
		int maxAltitude,
		boolean cinematicMode,
		boolean caveCarvers,
		boolean largeCaves,
		boolean canyonCarvers,
		boolean aquifers,
		boolean dripstone,
		boolean deepDark,
		boolean oreDistribution,
		boolean lavaPools,
		boolean addStrongholds,
		boolean addVillages,
		boolean addMineshafts,
		boolean addOceanMonuments,
		boolean addWoodlandMansions,
		boolean addDesertTemples,
		boolean addJungleTemples,
		boolean addPillagerOutposts,
		boolean addRuinedPortals,
		boolean addShipwrecks,
		boolean addOceanRuins,
		boolean addBuriedTreasure,
		boolean addIgloos,
		boolean addWitchHuts,
		boolean addAncientCities,
		boolean addTrialChambers,
		boolean addTrailRuins
) {
	public static final double DEFAULT_SPAWN_LATITUDE = 27.9881;
	public static final double DEFAULT_SPAWN_LONGITUDE = 86.9250;
	public static final int AUTO_ALTITUDE = Integer.MIN_VALUE;
	public static final int AUTO_SEA_LEVEL = Integer.MIN_VALUE + 1;

	public static final int MIN_WORLD_Y = -2032;
	public static final int MAX_WORLD_HEIGHT = 4064;
	public static final int MAX_WORLD_Y = MIN_WORLD_Y + MAX_WORLD_HEIGHT - 1;

	private static final int ALTITUDE_TOLERANCE = 100;
	private static final int HEIGHT_ALIGNMENT = 16;
	private static final double EVEREST_ELEVATION_METERS = 8848.0;
	private static final double MARIANA_TRENCH_METERS = -11034.0;

	public static final EarthGeneratorSettings DEFAULT = new EarthGeneratorSettings(
			35.0,
			1.0,
			1.0,
			64,
			AUTO_SEA_LEVEL,
			DEFAULT_SPAWN_LATITUDE,
			DEFAULT_SPAWN_LONGITUDE,
			AUTO_ALTITUDE,
			AUTO_ALTITUDE,
			false,
			true,
			true,
			true,
			true,
			true,
			true,
			false,
			false,
			false,
			true,
			false,
			true,
			true,
			true,
			true,
			true,
			true,
			true,
			true,
			true,
			true,
			true,
			false,
			false,
			true
	);

	private static final MapCodec<SettingsBase> BASE_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
			Codec.DOUBLE.fieldOf("world_scale").orElse(DEFAULT.worldScale()).forGetter(SettingsBase::worldScale),
			Codec.DOUBLE.fieldOf("terrestrial_height_scale").orElse(DEFAULT.terrestrialHeightScale())
					.forGetter(SettingsBase::terrestrialHeightScale),
			Codec.DOUBLE.fieldOf("oceanic_height_scale").orElse(DEFAULT.oceanicHeightScale())
					.forGetter(SettingsBase::oceanicHeightScale),
			Codec.INT.fieldOf("height_offset").orElse(DEFAULT.heightOffset()).forGetter(SettingsBase::heightOffset),
			Codec.DOUBLE.fieldOf("spawn_latitude").orElse(DEFAULT.spawnLatitude()).forGetter(SettingsBase::spawnLatitude),
			Codec.DOUBLE.fieldOf("spawn_longitude").orElse(DEFAULT.spawnLongitude()).forGetter(SettingsBase::spawnLongitude),
			Codec.INT.fieldOf("min_altitude").orElse(DEFAULT.minAltitude()).forGetter(SettingsBase::minAltitude),
			Codec.INT.fieldOf("max_altitude").orElse(DEFAULT.maxAltitude()).forGetter(SettingsBase::maxAltitude),
			Codec.BOOL.fieldOf("cinematic_mode").orElse(DEFAULT.cinematicMode()).forGetter(SettingsBase::cinematicMode),
			Codec.BOOL.fieldOf("cave_carvers").orElse(DEFAULT.caveCarvers()).forGetter(SettingsBase::caveCarvers),
			Codec.BOOL.fieldOf("large_caves").orElse(DEFAULT.largeCaves()).forGetter(SettingsBase::largeCaves),
			Codec.BOOL.fieldOf("canyon_carvers").orElse(DEFAULT.canyonCarvers()).forGetter(SettingsBase::canyonCarvers),
			Codec.BOOL.fieldOf("aquifers").orElse(DEFAULT.aquifers()).forGetter(SettingsBase::aquifers),
			Codec.BOOL.fieldOf("dripstone").orElse(DEFAULT.dripstone()).forGetter(SettingsBase::dripstone),
			Codec.BOOL.fieldOf("deep_dark").orElse(DEFAULT.deepDark()).forGetter(SettingsBase::deepDark),
			Codec.BOOL.fieldOf("ore_distribution").orElse(DEFAULT.oreDistribution()).forGetter(SettingsBase::oreDistribution)
	).apply(instance, EarthGeneratorSettings::createSettingsBase));

	private static final MapCodec<Optional<Integer>> SEA_LEVEL_CODEC =
			Codec.INT.optionalFieldOf("sea_level");

	private static final MapCodec<Boolean> LAVA_POOLS_CODEC =
			Codec.BOOL.fieldOf("lava_pools").orElse(DEFAULT.lavaPools());

	private static final MapCodec<StructureSettings> STRUCTURE_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
			Codec.BOOL.fieldOf("add_strongholds").orElse(DEFAULT.addStrongholds()).forGetter(StructureSettings::addStrongholds),
			Codec.BOOL.fieldOf("add_villages").orElse(DEFAULT.addVillages()).forGetter(StructureSettings::addVillages),
			Codec.BOOL.fieldOf("add_mineshafts").orElse(DEFAULT.addMineshafts()).forGetter(StructureSettings::addMineshafts),
			Codec.BOOL.fieldOf("add_ocean_monuments").orElse(DEFAULT.addOceanMonuments()).forGetter(StructureSettings::addOceanMonuments),
			Codec.BOOL.fieldOf("add_woodland_mansions").orElse(DEFAULT.addWoodlandMansions()).forGetter(StructureSettings::addWoodlandMansions),
			Codec.BOOL.fieldOf("add_desert_temples").orElse(DEFAULT.addDesertTemples()).forGetter(StructureSettings::addDesertTemples),
			Codec.BOOL.fieldOf("add_jungle_temples").orElse(DEFAULT.addJungleTemples()).forGetter(StructureSettings::addJungleTemples),
			Codec.BOOL.fieldOf("add_pillager_outposts").orElse(DEFAULT.addPillagerOutposts()).forGetter(StructureSettings::addPillagerOutposts),
			Codec.BOOL.fieldOf("add_ruined_portals").orElse(DEFAULT.addRuinedPortals()).forGetter(StructureSettings::addRuinedPortals),
			Codec.BOOL.fieldOf("add_shipwrecks").orElse(DEFAULT.addShipwrecks()).forGetter(StructureSettings::addShipwrecks),
			Codec.BOOL.fieldOf("add_ocean_ruins").orElse(DEFAULT.addOceanRuins()).forGetter(StructureSettings::addOceanRuins),
			Codec.BOOL.fieldOf("add_buried_treasure").orElse(DEFAULT.addBuriedTreasure()).forGetter(StructureSettings::addBuriedTreasure),
			Codec.BOOL.fieldOf("add_igloos").orElse(DEFAULT.addIgloos()).forGetter(StructureSettings::addIgloos),
			Codec.BOOL.fieldOf("add_witch_huts").orElse(DEFAULT.addWitchHuts()).forGetter(StructureSettings::addWitchHuts),
			Codec.BOOL.fieldOf("add_ancient_cities").orElse(DEFAULT.addAncientCities()).forGetter(StructureSettings::addAncientCities),
			Codec.BOOL.fieldOf("add_trial_chambers").orElse(DEFAULT.addTrialChambers()).forGetter(StructureSettings::addTrialChambers)
	).apply(instance, EarthGeneratorSettings::createStructureSettings));

	private static final MapCodec<Boolean> TRAIL_RUINS_CODEC =
			Codec.BOOL.fieldOf("add_trail_ruins").orElse(DEFAULT.addTrailRuins());

	private static final MapCodec<EarthGeneratorSettings> MAP_CODEC = MapCodec.of(
			new MapEncoder.Implementation<>() {
				@Override
				public <T> RecordBuilder<T> encode(EarthGeneratorSettings input, DynamicOps<T> ops, RecordBuilder<T> prefix) {
					RecordBuilder<T> builder = BASE_CODEC.encode(SettingsBase.fromSettings(input), ops, prefix);
					Optional<Integer> seaLevel = input.seaLevel() == AUTO_SEA_LEVEL
							? Optional.empty()
							: Optional.of(input.seaLevel());
					builder = SEA_LEVEL_CODEC.encode(seaLevel, ops, builder);
					builder = LAVA_POOLS_CODEC.encode(input.lavaPools(), ops, builder);
					builder = STRUCTURE_CODEC.encode(StructureSettings.fromSettings(input), ops, builder);
					return TRAIL_RUINS_CODEC.encode(input.addTrailRuins(), ops, builder);
				}

				@Override
				public <T> Stream<T> keys(DynamicOps<T> ops) {
					Stream<T> baseKeys = Stream.concat(BASE_CODEC.keys(ops), SEA_LEVEL_CODEC.keys(ops));
					baseKeys = Stream.concat(baseKeys, LAVA_POOLS_CODEC.keys(ops));
					Stream<T> structureKeys = Stream.concat(baseKeys, STRUCTURE_CODEC.keys(ops));
					return Stream.concat(structureKeys, TRAIL_RUINS_CODEC.keys(ops));
				}
			},
			new MapDecoder.Implementation<>() {
				@Override
				public <T> DataResult<EarthGeneratorSettings> decode(DynamicOps<T> ops, MapLike<T> input) {
					DataResult<SettingsBase> base = BASE_CODEC.decode(ops, input);
					DataResult<Optional<Integer>> seaLevel = SEA_LEVEL_CODEC.decode(ops, input);
					DataResult<Boolean> lavaPools = LAVA_POOLS_CODEC.decode(ops, input);
					DataResult<StructureSettings> structures = STRUCTURE_CODEC.decode(ops, input);
					DataResult<Boolean> trailRuins = TRAIL_RUINS_CODEC.decode(ops, input);
					DataResult<SettingsBase> withSeaLevel = base.apply2(EarthGeneratorSettings::applySeaLevel, seaLevel);
					DataResult<EarthGeneratorSettings> settings = withSeaLevel.apply2(EarthGeneratorSettings::applyLavaPools, lavaPools);
					settings = settings.apply2(EarthGeneratorSettings::withStructureSettings, structures);
					return settings.apply2(EarthGeneratorSettings::applyTrailRuins, trailRuins);
				}

				@Override
				public <T> Stream<T> keys(DynamicOps<T> ops) {
					Stream<T> baseKeys = Stream.concat(BASE_CODEC.keys(ops), SEA_LEVEL_CODEC.keys(ops));
					baseKeys = Stream.concat(baseKeys, LAVA_POOLS_CODEC.keys(ops));
					Stream<T> structureKeys = Stream.concat(baseKeys, STRUCTURE_CODEC.keys(ops));
					return Stream.concat(structureKeys, TRAIL_RUINS_CODEC.keys(ops));
				}
			}
	);

	public static final Codec<EarthGeneratorSettings> CODEC = MAP_CODEC.codec();

	public boolean isSeaLevelAutomatic() {
		return this.seaLevel == AUTO_SEA_LEVEL;
	}

	public int resolveSeaLevel() {
		if (this.seaLevel == AUTO_SEA_LEVEL) {
			return this.heightOffset;
		}
		return this.seaLevel;
	}

	private static StructureSettings createStructureSettings(
			Boolean addStrongholds,
			Boolean addVillages,
			Boolean addMineshafts,
			Boolean addOceanMonuments,
			Boolean addWoodlandMansions,
			Boolean addDesertTemples,
			Boolean addJungleTemples,
			Boolean addPillagerOutposts,
			Boolean addRuinedPortals,
			Boolean addShipwrecks,
			Boolean addOceanRuins,
			Boolean addBuriedTreasure,
			Boolean addIgloos,
			Boolean addWitchHuts,
			Boolean addAncientCities,
			Boolean addTrialChambers
	) {
		return new StructureSettings(
				Objects.requireNonNull(addStrongholds, "addStrongholds").booleanValue(),
				Objects.requireNonNull(addVillages, "addVillages").booleanValue(),
				Objects.requireNonNull(addMineshafts, "addMineshafts").booleanValue(),
				Objects.requireNonNull(addOceanMonuments, "addOceanMonuments").booleanValue(),
				Objects.requireNonNull(addWoodlandMansions, "addWoodlandMansions").booleanValue(),
				Objects.requireNonNull(addDesertTemples, "addDesertTemples").booleanValue(),
				Objects.requireNonNull(addJungleTemples, "addJungleTemples").booleanValue(),
				Objects.requireNonNull(addPillagerOutposts, "addPillagerOutposts").booleanValue(),
				Objects.requireNonNull(addRuinedPortals, "addRuinedPortals").booleanValue(),
				Objects.requireNonNull(addShipwrecks, "addShipwrecks").booleanValue(),
				Objects.requireNonNull(addOceanRuins, "addOceanRuins").booleanValue(),
				Objects.requireNonNull(addBuriedTreasure, "addBuriedTreasure").booleanValue(),
				Objects.requireNonNull(addIgloos, "addIgloos").booleanValue(),
				Objects.requireNonNull(addWitchHuts, "addWitchHuts").booleanValue(),
				Objects.requireNonNull(addAncientCities, "addAncientCities").booleanValue(),
				Objects.requireNonNull(addTrialChambers, "addTrialChambers").booleanValue()
		);
	}

	private static SettingsBase createSettingsBase(
			Double worldScale,
			Double terrestrialHeightScale,
			Double oceanicHeightScale,
			Integer heightOffset,
			Double spawnLatitude,
			Double spawnLongitude,
			Integer minAltitude,
			Integer maxAltitude,
			Boolean cinematicMode,
			Boolean caveCarvers,
			Boolean largeCaves,
			Boolean canyonCarvers,
			Boolean aquifers,
			Boolean dripstone,
			Boolean deepDark,
			Boolean oreDistribution
	) {
		int resolvedHeightOffset = Objects.requireNonNull(heightOffset, "heightOffset").intValue();
		int resolvedSeaLevel = AUTO_SEA_LEVEL;
		return new SettingsBase(
				Objects.requireNonNull(worldScale, "worldScale").doubleValue(),
				Objects.requireNonNull(terrestrialHeightScale, "terrestrialHeightScale").doubleValue(),
				Objects.requireNonNull(oceanicHeightScale, "oceanicHeightScale").doubleValue(),
				resolvedHeightOffset,
				resolvedSeaLevel,
				Objects.requireNonNull(spawnLatitude, "spawnLatitude").doubleValue(),
				Objects.requireNonNull(spawnLongitude, "spawnLongitude").doubleValue(),
				Objects.requireNonNull(minAltitude, "minAltitude").intValue(),
				Objects.requireNonNull(maxAltitude, "maxAltitude").intValue(),
				Objects.requireNonNull(cinematicMode, "cinematicMode").booleanValue(),
				Objects.requireNonNull(caveCarvers, "caveCarvers").booleanValue(),
				Objects.requireNonNull(largeCaves, "largeCaves").booleanValue(),
				Objects.requireNonNull(canyonCarvers, "canyonCarvers").booleanValue(),
				Objects.requireNonNull(aquifers, "aquifers").booleanValue(),
				Objects.requireNonNull(dripstone, "dripstone").booleanValue(),
				Objects.requireNonNull(deepDark, "deepDark").booleanValue(),
				Objects.requireNonNull(oreDistribution, "oreDistribution").booleanValue()
		);
	}

	private record SettingsBase(
			double worldScale,
			double terrestrialHeightScale,
			double oceanicHeightScale,
			int heightOffset,
			int seaLevel,
			double spawnLatitude,
			double spawnLongitude,
			int minAltitude,
			int maxAltitude,
			boolean cinematicMode,
			boolean caveCarvers,
			boolean largeCaves,
			boolean canyonCarvers,
			boolean aquifers,
			boolean dripstone,
			boolean deepDark,
			boolean oreDistribution
	) {
		private static SettingsBase fromSettings(EarthGeneratorSettings settings) {
			return new SettingsBase(
					settings.worldScale(),
					settings.terrestrialHeightScale(),
					settings.oceanicHeightScale(),
					settings.heightOffset(),
					settings.seaLevel(),
					settings.spawnLatitude(),
					settings.spawnLongitude(),
					settings.minAltitude(),
					settings.maxAltitude(),
					settings.cinematicMode(),
					settings.caveCarvers(),
					settings.largeCaves(),
					settings.canyonCarvers(),
					settings.aquifers(),
					settings.dripstone(),
					settings.deepDark(),
					settings.oreDistribution()
			);
		}

		private SettingsBase withSeaLevel(int seaLevel) {
			return new SettingsBase(
					this.worldScale,
					this.terrestrialHeightScale,
					this.oceanicHeightScale,
					this.heightOffset,
					seaLevel,
					this.spawnLatitude,
					this.spawnLongitude,
					this.minAltitude,
					this.maxAltitude,
					this.cinematicMode,
					this.caveCarvers,
					this.largeCaves,
					this.canyonCarvers,
					this.aquifers,
					this.dripstone,
					this.deepDark,
					this.oreDistribution
			);
		}

		private EarthGeneratorSettings withLavaPools(boolean lavaPools) {
			return new EarthGeneratorSettings(
					this.worldScale,
					this.terrestrialHeightScale,
					this.oceanicHeightScale,
					this.heightOffset,
					this.seaLevel,
					this.spawnLatitude,
					this.spawnLongitude,
					this.minAltitude,
					this.maxAltitude,
					this.cinematicMode,
					this.caveCarvers,
					this.largeCaves,
					this.canyonCarvers,
					this.aquifers,
					this.dripstone,
					this.deepDark,
					this.oreDistribution,
					lavaPools,
					DEFAULT.addStrongholds(),
					DEFAULT.addVillages(),
					DEFAULT.addMineshafts(),
					DEFAULT.addOceanMonuments(),
					DEFAULT.addWoodlandMansions(),
					DEFAULT.addDesertTemples(),
					DEFAULT.addJungleTemples(),
					DEFAULT.addPillagerOutposts(),
					DEFAULT.addRuinedPortals(),
					DEFAULT.addShipwrecks(),
					DEFAULT.addOceanRuins(),
					DEFAULT.addBuriedTreasure(),
					DEFAULT.addIgloos(),
					DEFAULT.addWitchHuts(),
					DEFAULT.addAncientCities(),
					DEFAULT.addTrialChambers(),
					DEFAULT.addTrailRuins()
			);
		}
	}

	private static EarthGeneratorSettings applyLavaPools(SettingsBase settings, Boolean lavaPools) {
		return settings.withLavaPools(Objects.requireNonNull(lavaPools, "lavaPools").booleanValue());
	}

	private static SettingsBase applySeaLevel(SettingsBase settings, Optional<Integer> seaLevel) {
		Optional<Integer> value = Objects.requireNonNull(seaLevel, "seaLevel");
		if (value.isEmpty()) {
			return settings;
		}
		int resolved = value.get();
		if (resolved == AUTO_SEA_LEVEL) {
			return settings.withSeaLevel(AUTO_SEA_LEVEL);
		}
		return settings.withSeaLevel(resolved);
	}

	private record StructureSettings(
			boolean addStrongholds,
			boolean addVillages,
			boolean addMineshafts,
			boolean addOceanMonuments,
			boolean addWoodlandMansions,
			boolean addDesertTemples,
			boolean addJungleTemples,
			boolean addPillagerOutposts,
			boolean addRuinedPortals,
			boolean addShipwrecks,
			boolean addOceanRuins,
			boolean addBuriedTreasure,
			boolean addIgloos,
			boolean addWitchHuts,
			boolean addAncientCities,
			boolean addTrialChambers
	) {
		private static StructureSettings fromSettings(EarthGeneratorSettings settings) {
			return new StructureSettings(
					settings.addStrongholds(),
					settings.addVillages(),
					settings.addMineshafts(),
					settings.addOceanMonuments(),
					settings.addWoodlandMansions(),
					settings.addDesertTemples(),
					settings.addJungleTemples(),
					settings.addPillagerOutposts(),
					settings.addRuinedPortals(),
					settings.addShipwrecks(),
					settings.addOceanRuins(),
					settings.addBuriedTreasure(),
					settings.addIgloos(),
					settings.addWitchHuts(),
					settings.addAncientCities(),
					settings.addTrialChambers()
			);
		}
	}

	private EarthGeneratorSettings withStructureSettings(StructureSettings structures) {
		return new EarthGeneratorSettings(
				this.worldScale,
				this.terrestrialHeightScale,
				this.oceanicHeightScale,
				this.heightOffset,
				this.seaLevel,
				this.spawnLatitude,
				this.spawnLongitude,
				this.minAltitude,
				this.maxAltitude,
				this.cinematicMode,
				this.caveCarvers,
				this.largeCaves,
				this.canyonCarvers,
				this.aquifers,
				this.dripstone,
				this.deepDark,
				this.oreDistribution,
				this.lavaPools,
				structures.addStrongholds(),
				structures.addVillages(),
				structures.addMineshafts(),
				structures.addOceanMonuments(),
				structures.addWoodlandMansions(),
				structures.addDesertTemples(),
				structures.addJungleTemples(),
				structures.addPillagerOutposts(),
				structures.addRuinedPortals(),
				structures.addShipwrecks(),
				structures.addOceanRuins(),
				structures.addBuriedTreasure(),
				structures.addIgloos(),
				structures.addWitchHuts(),
				structures.addAncientCities(),
				structures.addTrialChambers(),
				this.addTrailRuins
		);
	}

	private static EarthGeneratorSettings applyTrailRuins(EarthGeneratorSettings settings, Boolean addTrailRuins) {
		return settings.withTrailRuins(Objects.requireNonNull(addTrailRuins, "addTrailRuins").booleanValue());
	}

	private EarthGeneratorSettings withTrailRuins(boolean addTrailRuins) {
		return new EarthGeneratorSettings(
				this.worldScale,
				this.terrestrialHeightScale,
				this.oceanicHeightScale,
				this.heightOffset,
				this.seaLevel,
				this.spawnLatitude,
				this.spawnLongitude,
				this.minAltitude,
				this.maxAltitude,
				this.cinematicMode,
				this.caveCarvers,
				this.largeCaves,
				this.canyonCarvers,
				this.aquifers,
				this.dripstone,
				this.deepDark,
				this.oreDistribution,
				this.lavaPools,
				this.addStrongholds,
				this.addVillages,
				this.addMineshafts,
				this.addOceanMonuments,
				this.addWoodlandMansions,
				this.addDesertTemples,
				this.addJungleTemples,
				this.addPillagerOutposts,
				this.addRuinedPortals,
				this.addShipwrecks,
				this.addOceanRuins,
				this.addBuriedTreasure,
				this.addIgloos,
				this.addWitchHuts,
				this.addAncientCities,
				this.addTrialChambers,
				addTrailRuins
		);
	}

	public static HeightLimits resolveHeightLimits(EarthGeneratorSettings settings) {
		int autoMin = computeAutoMinAltitude(settings);
		int autoMax = computeAutoMaxAltitude(settings);
		boolean autoMinEnabled = settings.minAltitude() == AUTO_ALTITUDE || settings.cinematicMode();
		boolean autoMaxEnabled = settings.maxAltitude() == AUTO_ALTITUDE || settings.cinematicMode();

		if ((autoMinEnabled && autoMin < MIN_WORLD_Y) || (autoMaxEnabled && autoMax > MAX_WORLD_Y)) {
			return HeightLimits.maxRange();
		}

		int resolvedMin = autoMinEnabled ? autoMin : settings.minAltitude();
		int resolvedMax = autoMaxEnabled ? autoMax : settings.maxAltitude();

		if (resolvedMin > resolvedMax) {
			int swap = resolvedMin;
			resolvedMin = resolvedMax;
			resolvedMax = swap;
		}

		resolvedMin = Mth.clamp(resolvedMin, MIN_WORLD_Y, MAX_WORLD_Y);
		resolvedMax = Mth.clamp(resolvedMax, MIN_WORLD_Y, MAX_WORLD_Y);

		int alignedMin = alignDown(resolvedMin, HEIGHT_ALIGNMENT);
		int alignedTop = alignUp(resolvedMax + 1, HEIGHT_ALIGNMENT);
		int height = alignedTop - alignedMin;

		if (alignedMin < MIN_WORLD_Y || alignedTop - 1 > MAX_WORLD_Y || height > MAX_WORLD_HEIGHT) {
			return HeightLimits.maxRange();
		}
		if (height <= 0) {
			height = HEIGHT_ALIGNMENT;
			alignedTop = alignedMin + height;
			if (alignedTop - 1 > MAX_WORLD_Y) {
				return HeightLimits.maxRange();
			}
		}

		return new HeightLimits(alignedMin, height, height);
	}


	private static int computeAutoMaxAltitude(EarthGeneratorSettings settings) {
		if (settings.worldScale() <= 0.0) {
			return settings.heightOffset();
		}
		double scaled = EVEREST_ELEVATION_METERS * settings.terrestrialHeightScale() / settings.worldScale();
		int maxSurface = Mth.ceil(scaled) + settings.heightOffset();
		return maxSurface + ALTITUDE_TOLERANCE;
	}

	private static int computeAutoMinAltitude(EarthGeneratorSettings settings) {
		if (settings.worldScale() <= 0.0) {
			return settings.heightOffset();
		}
		double scaled = MARIANA_TRENCH_METERS * settings.oceanicHeightScale() / settings.worldScale();
		int minSurface = Mth.floor(scaled) + settings.heightOffset();
		return minSurface - ALTITUDE_TOLERANCE;
	}

	private static int alignDown(int value, int alignment) {
		int remainder = Math.floorMod(value, alignment);
		return value - remainder;
	}

	private static int alignUp(int value, int alignment) {
		int remainder = Math.floorMod(value, alignment);
		return remainder == 0 ? value : value + (alignment - remainder);
	}

	public record HeightLimits(int minY, int height, int logicalHeight) {
		public static HeightLimits maxRange() {
			return new HeightLimits(MIN_WORLD_Y, MAX_WORLD_HEIGHT, MAX_WORLD_HEIGHT);
		}
	}

	public static DimensionType applyHeightLimits(DimensionType base, HeightLimits limits) {
		return new DimensionType(
				base.hasFixedTime(),
				base.hasSkyLight(),
				base.hasCeiling(),
				base.coordinateScale(),
				limits.minY(),
				limits.height(),
				limits.logicalHeight(),
				base.infiniburn(),
				base.ambientLight(),
				base.monsterSettings(),
				base.skybox(),
				base.cardinalLightType(),
				base.attributes(),
				base.timelines()
		);
	}

}
