package com.yucareux.tellus.worldgen;

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
		int riverLakeShorelineBlend,
		int oceanShorelineBlend,
		boolean shorelineBlendCliffLimit,
		boolean caveCarvers,
		boolean largeCaves,
		boolean canyonCarvers,
		boolean aquifers,
		boolean dripstone,
		boolean deepDark,
		boolean oreDistribution,
		boolean geodes,
		boolean lavaPools,
		StructureSettings structureSettings,
        boolean addTrailRuins,
		boolean distantHorizonsWaterResolver,
		DistantHorizonsRenderMode distantHorizonsRenderMode,
        VillageSettings villageSettings

) {
	public static final double DEFAULT_SPAWN_LATITUDE = 27.9881;
	public static final double DEFAULT_SPAWN_LONGITUDE = 86.9250;
	public static final int AUTO_ALTITUDE = Integer.MIN_VALUE;
	public static final int AUTO_SEA_LEVEL = Integer.MIN_VALUE + 1;

	public static final int MIN_WORLD_Y = -2032;
	public static final int MAX_WORLD_HEIGHT = 4064;
	public static final int MAX_WORLD_Y = MIN_WORLD_Y + MAX_WORLD_HEIGHT - 1;

	private static final int ALTITUDE_TOLERANCE = 50;
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
			-64,
			AUTO_ALTITUDE,
			5,
			5,
			true,
			false,
			false,
			false,
			false,
			false,
			false,
			false,
			false,
			false,
			StructureSettings.DEFAULT,
            true,
			true,
			DistantHorizonsRenderMode.FAST,
            VillageSettings.DEFAULT
	);

	private static final MapCodec<BaseToggles> BASE_TOGGLES_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
			Codec.BOOL.fieldOf("cave_carvers").orElse(DEFAULT.caveCarvers()).forGetter(BaseToggles::caveCarvers),
			Codec.BOOL.fieldOf("large_caves").orElse(DEFAULT.largeCaves()).forGetter(BaseToggles::largeCaves),
			Codec.BOOL.fieldOf("canyon_carvers").orElse(DEFAULT.canyonCarvers()).forGetter(BaseToggles::canyonCarvers),
			Codec.BOOL.fieldOf("aquifers").orElse(DEFAULT.aquifers()).forGetter(BaseToggles::aquifers),
			Codec.BOOL.fieldOf("dripstone").orElse(DEFAULT.dripstone()).forGetter(BaseToggles::dripstone),
			Codec.BOOL.fieldOf("deep_dark").orElse(DEFAULT.deepDark()).forGetter(BaseToggles::deepDark),
			Codec.BOOL.fieldOf("ore_distribution").orElse(DEFAULT.oreDistribution()).forGetter(BaseToggles::oreDistribution)
	).apply(instance, (caveCarvers, largeCaves, canyonCarvers, aquifers, dripstone, deepDark, oreDistribution) -> new BaseToggles(
            Objects.requireNonNull(caveCarvers, "caveCarvers"),
            Objects.requireNonNull(largeCaves, "largeCaves"),
            Objects.requireNonNull(canyonCarvers, "canyonCarvers"),
            Objects.requireNonNull(aquifers, "aquifers"),
            Objects.requireNonNull(dripstone, "dripstone"),
            Objects.requireNonNull(deepDark, "deepDark"),
            Objects.requireNonNull(oreDistribution, "oreDistribution")
	)));

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
			Codec.INT.fieldOf("river_lake_shoreline_blend").orElse(DEFAULT.riverLakeShorelineBlend())
					.forGetter(SettingsBase::riverLakeShorelineBlend),
			Codec.INT.fieldOf("ocean_shoreline_blend").orElse(DEFAULT.oceanShorelineBlend())
					.forGetter(SettingsBase::oceanShorelineBlend),
			Codec.BOOL.fieldOf("shoreline_blend_cliff_limit").orElse(DEFAULT.shorelineBlendCliffLimit())
					.forGetter(SettingsBase::shorelineBlendCliffLimit),
			BASE_TOGGLES_CODEC.forGetter(settings -> new BaseToggles(
					settings.caveCarvers(),
					settings.largeCaves(),
					settings.canyonCarvers(),
					settings.aquifers(),
					settings.dripstone(),
					settings.deepDark(),
					settings.oreDistribution()
			))
	).apply(instance, (worldScale, terrestrialHeightScale, oceanicHeightScale, heightOffset, spawnLatitude, spawnLongitude,
			minAltitude, maxAltitude, riverLakeShorelineBlend, oceanShorelineBlend, shorelineBlendCliffLimit, toggles) -> createSettingsBase(
			worldScale,
			terrestrialHeightScale,
			oceanicHeightScale,
			heightOffset,
			spawnLatitude,
			spawnLongitude,
			minAltitude,
			maxAltitude,
			riverLakeShorelineBlend,
			oceanShorelineBlend,
			shorelineBlendCliffLimit,
			toggles.caveCarvers(),
			toggles.largeCaves(),
			toggles.canyonCarvers(),
			toggles.aquifers(),
			toggles.dripstone(),
			toggles.deepDark(),
			toggles.oreDistribution()
	)));

	private static final MapCodec<Optional<Integer>> SEA_LEVEL_CODEC =
			Codec.INT.optionalFieldOf("sea_level");

	private static final MapCodec<DistantHorizonsRenderMode> DISTANT_HORIZONS_RENDER_MODE_CODEC =
			DistantHorizonsRenderMode.CODEC.fieldOf("distant_horizons_render_mode")
					.orElse(DEFAULT.distantHorizonsRenderMode());

	private static final MapCodec<Boolean> DISTANT_HORIZONS_WATER_RESOLVER_CODEC =
			Codec.BOOL.fieldOf("distant_horizons_water_resolver").orElse(DEFAULT.distantHorizonsWaterResolver());

	private static final MapCodec<Boolean> GEODES_CODEC =
			Codec.BOOL.fieldOf("geodes").orElse(DEFAULT.geodes());

	private static final MapCodec<Boolean> LAVA_POOLS_CODEC =
			Codec.BOOL.fieldOf("lava_pools").orElse(DEFAULT.lavaPools());

	private static final MapCodec<StructureSettings> STRUCTURE_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
			Codec.BOOL.fieldOf("add_strongholds").orElse(DEFAULT.structureSettings.addStrongholds()).forGetter(StructureSettings::addStrongholds),
			Codec.BOOL.fieldOf("add_villages").orElse(DEFAULT.structureSettings.addVillages()).forGetter(StructureSettings::addVillages),
			Codec.BOOL.fieldOf("add_mineshafts").orElse(DEFAULT.structureSettings.addMineshafts()).forGetter(StructureSettings::addMineshafts),
			Codec.BOOL.fieldOf("add_ocean_monuments").orElse(DEFAULT.structureSettings.addOceanMonuments()).forGetter(StructureSettings::addOceanMonuments),
			Codec.BOOL.fieldOf("add_woodland_mansions").orElse(DEFAULT.structureSettings.addWoodlandMansions()).forGetter(StructureSettings::addWoodlandMansions),
			Codec.BOOL.fieldOf("add_desert_temples").orElse(DEFAULT.structureSettings.addDesertTemples()).forGetter(StructureSettings::addDesertTemples),
			Codec.BOOL.fieldOf("add_jungle_temples").orElse(DEFAULT.structureSettings.addJungleTemples()).forGetter(StructureSettings::addJungleTemples),
			Codec.BOOL.fieldOf("add_pillager_outposts").orElse(DEFAULT.structureSettings.addPillagerOutposts()).forGetter(StructureSettings::addPillagerOutposts),
			Codec.BOOL.fieldOf("add_ruined_portals").orElse(DEFAULT.structureSettings.addRuinedPortals()).forGetter(StructureSettings::addRuinedPortals),
			Codec.BOOL.fieldOf("add_shipwrecks").orElse(DEFAULT.structureSettings.addShipwrecks()).forGetter(StructureSettings::addShipwrecks),
			Codec.BOOL.fieldOf("add_ocean_ruins").orElse(DEFAULT.structureSettings.addOceanRuins()).forGetter(StructureSettings::addOceanRuins),
			Codec.BOOL.fieldOf("add_buried_treasure").orElse(DEFAULT.structureSettings.addBuriedTreasure()).forGetter(StructureSettings::addBuriedTreasure),
			Codec.BOOL.fieldOf("add_igloos").orElse(DEFAULT.structureSettings.addIgloos()).forGetter(StructureSettings::addIgloos),
			Codec.BOOL.fieldOf("add_witch_huts").orElse(DEFAULT.structureSettings.addWitchHuts()).forGetter(StructureSettings::addWitchHuts),
			Codec.BOOL.fieldOf("add_ancient_cities").orElse(DEFAULT.structureSettings.addAncientCities()).forGetter(StructureSettings::addAncientCities),
			Codec.BOOL.fieldOf("add_trial_chambers").orElse(DEFAULT.structureSettings.addTrialChambers()).forGetter(StructureSettings::addTrialChambers)
	).apply(instance, StructureSettings::createStructureSettings));

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
					builder = DISTANT_HORIZONS_RENDER_MODE_CODEC.encode(input.distantHorizonsRenderMode(), ops, builder);
					builder = DISTANT_HORIZONS_WATER_RESOLVER_CODEC.encode(input.distantHorizonsWaterResolver(), ops, builder);
					builder = GEODES_CODEC.encode(input.geodes(), ops, builder);
					builder = LAVA_POOLS_CODEC.encode(input.lavaPools(), ops, builder);
					builder = STRUCTURE_CODEC.encode(StructureSettings.fromSettings(input), ops, builder);
					return TRAIL_RUINS_CODEC.encode(input.addTrailRuins(), ops, builder);
				}

				@Override
				public <T> Stream<T> keys(DynamicOps<T> ops) {
					Stream<T> baseKeys = Stream.concat(BASE_CODEC.keys(ops), SEA_LEVEL_CODEC.keys(ops));
					baseKeys = Stream.concat(baseKeys, DISTANT_HORIZONS_RENDER_MODE_CODEC.keys(ops));
					baseKeys = Stream.concat(baseKeys, DISTANT_HORIZONS_WATER_RESOLVER_CODEC.keys(ops));
					baseKeys = Stream.concat(baseKeys, GEODES_CODEC.keys(ops));
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
					DataResult<DistantHorizonsRenderMode> distantHorizonsRenderMode =
							DISTANT_HORIZONS_RENDER_MODE_CODEC.decode(ops, input);
					DataResult<Boolean> distantHorizonsWaterResolver =
							DISTANT_HORIZONS_WATER_RESOLVER_CODEC.decode(ops, input);
					DataResult<Boolean> geodes = GEODES_CODEC.decode(ops, input);
					DataResult<Boolean> lavaPools = LAVA_POOLS_CODEC.decode(ops, input);
					DataResult<StructureSettings> structures = STRUCTURE_CODEC.decode(ops, input);
					DataResult<Boolean> trailRuins = TRAIL_RUINS_CODEC.decode(ops, input);
					DataResult<SettingsBase> withSeaLevel = base.apply2(EarthGeneratorSettings::applySeaLevel, seaLevel);
					DataResult<SettingsBase> withRenderMode = withSeaLevel.apply2(
							EarthGeneratorSettings::applyDistantHorizonsRenderMode,
							distantHorizonsRenderMode
					);
					DataResult<SettingsBase> withWaterResolver = withRenderMode.apply2(
							EarthGeneratorSettings::applyDistantHorizonsWaterResolver,
							distantHorizonsWaterResolver
					);
					DataResult<SettingsBase> withGeodes = withWaterResolver.apply2(EarthGeneratorSettings::applyGeodes, geodes);
					DataResult<EarthGeneratorSettings> settings = withGeodes.apply2(EarthGeneratorSettings::applyLavaPools, lavaPools);
					settings = settings.apply2(EarthGeneratorSettings::withStructureSettings, structures);
					return settings.apply2(EarthGeneratorSettings::applyTrailRuins, trailRuins);
				}

				@Override
				public <T> Stream<T> keys(DynamicOps<T> ops) {
					Stream<T> baseKeys = Stream.concat(BASE_CODEC.keys(ops), SEA_LEVEL_CODEC.keys(ops));
					baseKeys = Stream.concat(baseKeys, DISTANT_HORIZONS_RENDER_MODE_CODEC.keys(ops));
					baseKeys = Stream.concat(baseKeys, DISTANT_HORIZONS_WATER_RESOLVER_CODEC.keys(ops));
					baseKeys = Stream.concat(baseKeys, GEODES_CODEC.keys(ops));
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



    private static SettingsBase createSettingsBase(
            Double worldScale,
            Double terrestrialHeightScale,
            Double oceanicHeightScale,
            Integer heightOffset,
            Double spawnLatitude,
            Double spawnLongitude,
            Integer minAltitude,
            Integer maxAltitude,
            Integer riverLakeShorelineBlend,
            Integer oceanShorelineBlend,
            Boolean shorelineBlendCliffLimit,
            Boolean caveCarvers,
            Boolean largeCaves,
            Boolean canyonCarvers,
            Boolean aquifers,
            Boolean dripstone,
            Boolean deepDark,
            Boolean oreDistribution
    ) {
        int resolvedHeightOffset = Objects.requireNonNull(heightOffset, "heightOffset");
        return new SettingsBase(
                Objects.requireNonNull(worldScale, "worldScale"),
                Objects.requireNonNull(terrestrialHeightScale, "terrestrialHeightScale"),
                Objects.requireNonNull(oceanicHeightScale, "oceanicHeightScale"),
                resolvedHeightOffset,
                AUTO_SEA_LEVEL,
                Objects.requireNonNull(spawnLatitude, "spawnLatitude"),
                Objects.requireNonNull(spawnLongitude, "spawnLongitude"),
                Objects.requireNonNull(minAltitude, "minAltitude"),
                Objects.requireNonNull(maxAltitude, "maxAltitude"),
                Objects.requireNonNull(riverLakeShorelineBlend, "riverLakeShorelineBlend"),
                Objects.requireNonNull(oceanShorelineBlend, "oceanShorelineBlend"),
                Objects.requireNonNull(shorelineBlendCliffLimit, "shorelineBlendCliffLimit"),
                Objects.requireNonNull(caveCarvers, "caveCarvers"),
                Objects.requireNonNull(largeCaves, "largeCaves"),
                Objects.requireNonNull(canyonCarvers, "canyonCarvers"),
                Objects.requireNonNull(aquifers, "aquifers"),
                Objects.requireNonNull(dripstone, "dripstone"),
                Objects.requireNonNull(deepDark, "deepDark"),
                Objects.requireNonNull(oreDistribution, "oreDistribution"),
                DEFAULT.distantHorizonsWaterResolver(),
                DEFAULT.distantHorizonsRenderMode(),
                DEFAULT.geodes()
        );
    }


    public record VillageSettings(
            boolean flatVillages,
            int radius,
            int heightRange
    ){
        public static final VillageSettings DEFAULT = new VillageSettings(
                false,
                 64,
                 24
        );
    }

    public record StructureSettings(
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
        public static final StructureSettings DEFAULT = new StructureSettings(
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
                false
        );

        static StructureSettings createStructureSettings(
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
                    Objects.requireNonNull(addStrongholds, "addStrongholds"),
                    Objects.requireNonNull(addVillages, "addVillages"),
                    Objects.requireNonNull(addMineshafts, "addMineshafts"),
                    Objects.requireNonNull(addOceanMonuments, "addOceanMonuments"),
                    Objects.requireNonNull(addWoodlandMansions, "addWoodlandMansions"),
                    Objects.requireNonNull(addDesertTemples, "addDesertTemples"),
                    Objects.requireNonNull(addJungleTemples, "addJungleTemples"),
                    Objects.requireNonNull(addPillagerOutposts, "addPillagerOutposts"),
                    Objects.requireNonNull(addRuinedPortals, "addRuinedPortals"),
                    Objects.requireNonNull(addShipwrecks, "addShipwrecks"),
                    Objects.requireNonNull(addOceanRuins, "addOceanRuins"),
                    Objects.requireNonNull(addBuriedTreasure, "addBuriedTreasure"),
                    Objects.requireNonNull(addIgloos, "addIgloos"),
                    Objects.requireNonNull(addWitchHuts, "addWitchHuts"),
                    Objects.requireNonNull(addAncientCities, "addAncientCities"),
                    Objects.requireNonNull(addTrialChambers, "addTrialChambers")
            );
        }
        static StructureSettings fromSettings(EarthGeneratorSettings settings) {
            return new StructureSettings(
                    settings.structureSettings().addStrongholds(),
                    settings.structureSettings().addVillages(),
                    settings.structureSettings().addMineshafts(),
                    settings.structureSettings().addOceanMonuments(),
                    settings.structureSettings().addWoodlandMansions(),
                    settings.structureSettings().addDesertTemples(),
                    settings.structureSettings().addJungleTemples(),
                    settings.structureSettings().addPillagerOutposts(),
                    settings.structureSettings().addRuinedPortals(),
                    settings.structureSettings().addShipwrecks(),
                    settings.structureSettings().addOceanRuins(),
                    settings.structureSettings().addBuriedTreasure(),
                    settings.structureSettings().addIgloos(),
                    settings.structureSettings().addWitchHuts(),
                    settings.structureSettings().addAncientCities(),
                    settings.structureSettings().addTrialChambers()
            );
        }
    }

    private record BaseToggles(
            boolean caveCarvers,
            boolean largeCaves,
            boolean canyonCarvers,
            boolean aquifers,
            boolean dripstone,
            boolean deepDark,
            boolean oreDistribution
    ) {
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
            int riverLakeShorelineBlend,
            int oceanShorelineBlend,
            boolean shorelineBlendCliffLimit,
            boolean caveCarvers,
            boolean largeCaves,
            boolean canyonCarvers,
            boolean aquifers,
            boolean dripstone,
            boolean deepDark,
            boolean oreDistribution,
            boolean distantHorizonsWaterResolver,
            DistantHorizonsRenderMode distantHorizonsRenderMode,
            boolean geodes
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
                    settings.riverLakeShorelineBlend(),
                    settings.oceanShorelineBlend(),
                    settings.shorelineBlendCliffLimit(),
                    settings.caveCarvers(),
                    settings.largeCaves(),
                    settings.canyonCarvers(),
                    settings.aquifers(),
                    settings.dripstone(),
                    settings.deepDark(),
                    settings.oreDistribution(),
                    settings.distantHorizonsWaterResolver(),
                    settings.distantHorizonsRenderMode(),
                    settings.geodes()
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
                    this.riverLakeShorelineBlend,
                    this.oceanShorelineBlend,
                    this.shorelineBlendCliffLimit,
                    this.caveCarvers,
                    this.largeCaves,
                    this.canyonCarvers,
                    this.aquifers,
                    this.dripstone,
                    this.deepDark,
                    this.oreDistribution,
                    this.distantHorizonsWaterResolver,
                    this.distantHorizonsRenderMode,
                    this.geodes
            );
        }

        private SettingsBase withGeodes(boolean geodes) {
            return new SettingsBase(
                    this.worldScale,
                    this.terrestrialHeightScale,
                    this.oceanicHeightScale,
                    this.heightOffset,
                    this.seaLevel,
                    this.spawnLatitude,
                    this.spawnLongitude,
                    this.minAltitude,
                    this.maxAltitude,
                    this.riverLakeShorelineBlend,
                    this.oceanShorelineBlend,
                    this.shorelineBlendCliffLimit,
                    this.caveCarvers,
                    this.largeCaves,
                    this.canyonCarvers,
                    this.aquifers,
                    this.dripstone,
                    this.deepDark,
                    this.oreDistribution,
                    this.distantHorizonsWaterResolver,
                    this.distantHorizonsRenderMode,
                    geodes
            );
        }

        private SettingsBase withDistantHorizonsWaterResolver(boolean enabled) {
            return new SettingsBase(
                    this.worldScale,
                    this.terrestrialHeightScale,
                    this.oceanicHeightScale,
                    this.heightOffset,
                    this.seaLevel,
                    this.spawnLatitude,
                    this.spawnLongitude,
                    this.minAltitude,
                    this.maxAltitude,
                    this.riverLakeShorelineBlend,
                    this.oceanShorelineBlend,
                    this.shorelineBlendCliffLimit,
                    this.caveCarvers,
                    this.largeCaves,
                    this.canyonCarvers,
                    this.aquifers,
                    this.dripstone,
                    this.deepDark,
                    this.oreDistribution,
                    enabled,
                    this.distantHorizonsRenderMode,
                    this.geodes
            );
        }

        private SettingsBase withDistantHorizonsRenderMode(DistantHorizonsRenderMode renderMode) {
            return new SettingsBase(
                    this.worldScale,
                    this.terrestrialHeightScale,
                    this.oceanicHeightScale,
                    this.heightOffset,
                    this.seaLevel,
                    this.spawnLatitude,
                    this.spawnLongitude,
                    this.minAltitude,
                    this.maxAltitude,
                    this.riverLakeShorelineBlend,
                    this.oceanShorelineBlend,
                    this.shorelineBlendCliffLimit,
                    this.caveCarvers,
                    this.largeCaves,
                    this.canyonCarvers,
                    this.aquifers,
                    this.dripstone,
                    this.deepDark,
                    this.oreDistribution,
                    this.distantHorizonsWaterResolver,
                    renderMode,
                    this.geodes
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
                    this.riverLakeShorelineBlend,
                    this.oceanShorelineBlend,
                    this.shorelineBlendCliffLimit,
                    this.caveCarvers,
                    this.largeCaves,
                    this.canyonCarvers,
                    this.aquifers,
                    this.dripstone,
                    this.deepDark,
                    this.oreDistribution,
                    this.geodes,
                    lavaPools,
                    DEFAULT.structureSettings(),
                    DEFAULT.addTrailRuins(),
                    this.distantHorizonsWaterResolver,
                    this.distantHorizonsRenderMode,
                    DEFAULT.villageSettings()
            );
        }
    }

    private static EarthGeneratorSettings applyLavaPools(SettingsBase settings, Boolean lavaPools) {
        return settings.withLavaPools(Objects.requireNonNull(lavaPools, "lavaPools"));
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

    private static SettingsBase applyGeodes(SettingsBase settings, Boolean geodes) {
        return settings.withGeodes(Objects.requireNonNull(geodes, "geodes"));
    }

    private static SettingsBase applyDistantHorizonsRenderMode(
            SettingsBase settings,
            DistantHorizonsRenderMode renderMode
    ) {
        return settings.withDistantHorizonsRenderMode(Objects.requireNonNull(renderMode, "renderMode"));
    }

    private static SettingsBase applyDistantHorizonsWaterResolver(SettingsBase settings, Boolean enabled) {
        return settings.withDistantHorizonsWaterResolver(Objects.requireNonNull(enabled, "distantHorizonsWaterResolver"));
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
				this.riverLakeShorelineBlend,
				this.oceanShorelineBlend,
				this.shorelineBlendCliffLimit,
				this.caveCarvers,
				this.largeCaves,
				this.canyonCarvers,
				this.aquifers,
				this.dripstone,
				this.deepDark,
				this.oreDistribution,
				this.geodes,
				this.lavaPools,
				this.structureSettings,
                this.addTrailRuins,
				this.distantHorizonsWaterResolver,
				this.distantHorizonsRenderMode,
                this.villageSettings
		);
	}

	private static EarthGeneratorSettings applyTrailRuins(EarthGeneratorSettings settings, Boolean addTrailRuins) {
		return settings.withTrailRuins(Objects.requireNonNull(addTrailRuins, "addTrailRuins"));
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
				this.riverLakeShorelineBlend,
				this.oceanShorelineBlend,
				this.shorelineBlendCliffLimit,
				this.caveCarvers,
				this.largeCaves,
				this.canyonCarvers,
				this.aquifers,
				this.dripstone,
				this.deepDark,
				this.oreDistribution,
				this.geodes,
				this.lavaPools,
				this.structureSettings,
                addTrailRuins,
				this.distantHorizonsWaterResolver,
				this.distantHorizonsRenderMode,
                this.villageSettings
		);
	}

	public enum DistantHorizonsRenderMode {
		FAST("fast"),
		DETAILED("detailed");

		public static final Codec<DistantHorizonsRenderMode> CODEC = Codec.STRING.xmap(
				DistantHorizonsRenderMode::fromId,
				DistantHorizonsRenderMode::id
		);

		private final String id;

		DistantHorizonsRenderMode(String id) {
			this.id = Objects.requireNonNull(id, "id");
		}

		public String id() {
			return this.id;
		}

		public static DistantHorizonsRenderMode fromId(String id) {
			if (id == null) {
				return FAST;
			}
			for (DistantHorizonsRenderMode mode : values()) {
				if (mode.id.equalsIgnoreCase(id)) {
					return mode;
				}
			}
			return FAST;
		}
	}

	public static HeightLimits resolveHeightLimits(EarthGeneratorSettings settings) {
		int autoMin = computeAutoMinAltitude(settings);
		int autoMax = computeAutoMaxAltitude(settings);
		boolean autoMinEnabled = settings.minAltitude() == AUTO_ALTITUDE;
		boolean autoMaxEnabled = settings.maxAltitude() == AUTO_ALTITUDE;

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



