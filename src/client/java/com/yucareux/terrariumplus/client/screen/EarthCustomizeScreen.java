package com.yucareux.terrariumplus.client.screen;

import com.yucareux.terrariumplus.Terrarium;
import com.yucareux.terrariumplus.client.preview.TerrainPreviewWidget;
import com.yucareux.terrariumplus.client.widget.CustomizationList;
import com.yucareux.terrariumplus.worldgen.EarthChunkGenerator;
import com.yucareux.terrariumplus.worldgen.EarthGeneratorSettings;
import com.mojang.serialization.Lifecycle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.DoubleFunction;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationContext;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.LayeredRegistryAccess;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.RegistryLayer;
import net.minecraft.server.packs.repository.KnownPack;
import net.minecraft.util.Mth;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.WorldDimensions;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

public class EarthCustomizeScreen extends Screen {
	private static final @NonNull Component TITLE = Objects.requireNonNull(
			Component.translatable("options.terrarium.customize_world_title.name"),
			"customizeTitle"
	);
	private static final @NonNull Component YES = Objects.requireNonNull(
			Component.translatable("gui.yes").withStyle(ChatFormatting.GREEN),
			"yesLabel"
	);
	private static final @NonNull Component NO = Objects.requireNonNull(
			Component.translatable("gui.no").withStyle(ChatFormatting.RED),
			"noLabel"
	);
	private static final @NonNull Component WORK_IN_PROGRESS = Objects.requireNonNull(
			Component.translatable("terrarium.customize.work_in_progress").withStyle(ChatFormatting.GRAY),
			"workInProgressLabel"
	);

	private static final int ENTRY_HEIGHT = 20;
	private static final int LIST_TOP = 40;
	private static final int LIST_BOTTOM_PADDING = 36;
	private static final int SIDE_PADDING = 10;
	private static final int PREVIEW_PADDING = 20;
	private static final long PREVIEW_DEBOUNCE_MS = 350L;
	private static final double AUTO_MAX_ALTITUDE = -1.0;
	private static final double AUTO_MIN_ALTITUDE = EarthGeneratorSettings.MIN_WORLD_Y - 16.0;
	private static final double ALTITUDE_AUTO_EPSILON = 0.5;
	private static final double AUTO_SEA_LEVEL = -64.0;
	private static final double SEA_LEVEL_AUTO_EPSILON = 0.5;
	private static final @NonNull Identifier DYNAMIC_DIMENSION_TYPE_ID =
			Objects.requireNonNull(Identifier.fromNamespaceAndPath("terrarium", "earth_dynamic"), "dynamicDimensionTypeId");
	private static final @NonNull ResourceKey<DimensionType> DYNAMIC_DIMENSION_TYPE_KEY =
			Objects.requireNonNull(ResourceKey.create(Registries.DIMENSION_TYPE, DYNAMIC_DIMENSION_TYPE_ID), "dynamicDimensionTypeKey");

	private final CreateWorldScreen parent;
	private final List<CategoryDefinition> categories;

	private CustomizationList list;
	private TerrainPreviewWidget previewWidget;
	private long previewDirtyAt = -1L;
	private double spawnLatitude = EarthGeneratorSettings.DEFAULT_SPAWN_LATITUDE;
	private double spawnLongitude = EarthGeneratorSettings.DEFAULT_SPAWN_LONGITUDE;
	private boolean cinematicMode;
	private @Nullable CategoryDefinition activeCategory;

	public EarthCustomizeScreen(CreateWorldScreen parent, WorldCreationContext worldCreationContext) {
		super(TITLE);
		this.parent = parent;
		this.categories = createCategories();
	}

	@Override
	protected void init() {
		int listTop = LIST_TOP;
		int listHeight = Math.max(0, this.height - LIST_BOTTOM_PADDING - listTop);
		int listWidth = Math.max(140, this.width / 2 - PREVIEW_PADDING);
		int previewWidth = Math.max(140, this.width - listWidth - PREVIEW_PADDING * 2);
		int previewHeight = Math.max(80, this.height - 80);

		this.list = new CustomizationList(this.minecraft, listWidth, listHeight, listTop, ENTRY_HEIGHT);
		this.list.setX(SIDE_PADDING);
		this.addRenderableWidget(this.list);

		int previewX = this.width - previewWidth - SIDE_PADDING;
		this.previewWidget = new TerrainPreviewWidget(previewX, listTop, previewWidth, previewHeight);
		this.addRenderableWidget(this.previewWidget);

		this.showCategories();
		this.previewWidget.requestRebuild(this.buildSettings());

		int buttonY = this.height - 28;
		Component spawnpointLabel = Objects.requireNonNull(
				Component.translatable("gui.earth.spawnpoint"),
				"spawnpointLabel"
		);
		this.addRenderableWidget(Button.builder(spawnpointLabel, button -> {
			if (this.minecraft != null) {
				this.minecraft.setScreen(new EarthSpawnpointScreen(this));
			}
		}).bounds(this.width / 2 - 155, buttonY, 150, ENTRY_HEIGHT).build());

		Component doneLabel = Objects.requireNonNull(Component.translatable("gui.done"), "doneLabel");
		this.addRenderableWidget(Button.builder(doneLabel, button -> this.onClose())
				.bounds(this.width / 2 + 5, buttonY, 150, ENTRY_HEIGHT)
				.build());
	}

	private void onSettingsChanged() {
		boolean cinematic = this.findToggleValue("cinematic_mode", false);
		if (cinematic != this.cinematicMode) {
			this.cinematicMode = cinematic;
			this.applyCinematicMode(cinematic);
			if (this.activeCategory != null) {
				this.showCategory(this.activeCategory);
			}
		}
		this.previewDirtyAt = System.currentTimeMillis();
	}

	public void applySpawnpoint(double latitude, double longitude) {
		this.spawnLatitude = latitude;
		this.spawnLongitude = longitude;
		this.previewDirtyAt = System.currentTimeMillis();
	}

	public double getSpawnLatitude() {
		return this.spawnLatitude;
	}

	public double getSpawnLongitude() {
		return this.spawnLongitude;
	}

	@Override
	public void onClose() {
		if (this.minecraft != null) {
			EarthGeneratorSettings settings = Objects.requireNonNull(this.buildSettings(), "generatorSettings");
			WorldCreationContext current = Objects.requireNonNull(this.parent.getUiState().getSettings(), "worldCreationContext");
			EarthGeneratorSettings.HeightLimits limits = Objects.requireNonNull(
					EarthGeneratorSettings.resolveHeightLimits(settings),
					"heightLimits"
			);
			WorldCreationContext updated = Objects.requireNonNull(
					updateWorldCreationContext(current, settings, limits),
					"updatedWorldContext"
			);
			this.parent.getUiState().setSettings(updated);
			this.minecraft.setScreen(this.parent);
		}
	}

	private static @NonNull WorldCreationContext updateWorldCreationContext(
			@NonNull WorldCreationContext current,
			@NonNull EarthGeneratorSettings settings,
			EarthGeneratorSettings.@NonNull HeightLimits limits
	) {
		WorldDimensions selectedDimensions = current.selectedDimensions();
		LevelStem overworldStem = selectedDimensions.get(LevelStem.OVERWORLD)
				.orElseThrow(() -> new IllegalStateException("Overworld settings missing"));
		Holder<DimensionType> baseType = Objects.requireNonNull(overworldStem.type(), "overworldDimensionType");
		DimensionType updatedType = Objects.requireNonNull(
				EarthGeneratorSettings.applyHeightLimits(baseType.value(), limits),
				"updatedDimensionType"
		);

		@NonNull ResourceKey<DimensionType> overworldKey = Objects.requireNonNull(
				overworldStem.type().unwrapKey().orElse(DYNAMIC_DIMENSION_TYPE_KEY),
				"overworldDimensionTypeKey"
		);
		RegistryUpdate registryUpdate = updateDimensionTypeRegistry(current.worldgenRegistries(), updatedType, overworldKey);
		LayeredRegistryAccess<RegistryLayer> registriesWithTypes = registryUpdate.registries();
		HolderLookup.RegistryLookup<DimensionType> dimensionTypes =
				registriesWithTypes.compositeAccess().lookupOrThrow(Registries.DIMENSION_TYPE);
		@NonNull Holder<DimensionType> overworldHolder = Objects.requireNonNull(
				registryUpdate.holder(),
				"overworldDimensionTypeHolder"
		);

		if (Terrarium.LOGGER.isInfoEnabled()) {
			DimensionType registryType = dimensionTypes.getOrThrow(overworldKey).value();
			Terrarium.LOGGER.info(
					"Terrarium world settings: scale={}, minAltitude={}, maxAltitude={}, heightOffset={}, limits=[minY={}, height={}, logicalHeight={}], overworldKey={}, updatedType=[{}], registryType=[{}]",
					settings.worldScale(),
					settings.minAltitude(),
					settings.maxAltitude(),
					settings.heightOffset(),
					limits.minY(),
					limits.height(),
					limits.logicalHeight(),
					overworldKey.identifier(),
					describeDimensionType(updatedType),
					describeDimensionType(registryType)
			);
		}

		ChunkGenerator generator =
				Objects.requireNonNull(EarthChunkGenerator.create(registriesWithTypes.compositeAccess(), settings), "overworldGenerator");
		WorldDimensions updatedDimensions = updateDimensions(
				selectedDimensions,
				overworldHolder,
				generator,
				dimensionTypes
		);
		Registry<LevelStem> updatedDatapackDimensions = updateDatapackDimensions(
				current.datapackDimensions(),
				overworldHolder,
				generator,
				dimensionTypes
		);
		LayeredRegistryAccess<RegistryLayer> updatedRegistries = updateWorldgenLevelStems(
				registriesWithTypes,
				updatedDatapackDimensions
		);

		return new WorldCreationContext(
				current.options(),
				updatedDatapackDimensions,
				updatedDimensions,
				updatedRegistries,
				current.dataPackResources(),
				current.dataConfiguration(),
				current.initialWorldCreationOptions()
		);
	}

	private static @NonNull Registry<LevelStem> updateDatapackDimensions(
			@NonNull Registry<LevelStem> source,
			@NonNull Holder<DimensionType> overworldHolder,
			@NonNull ChunkGenerator overworldGenerator,
			HolderLookup.RegistryLookup<DimensionType> dimensionTypes
	) {
		HolderLookup.RegistryLookup<DimensionType> dimensionTypesChecked =
				Objects.requireNonNull(dimensionTypes, "dimensionTypes");
		Lifecycle lifecycle = Objects.requireNonNull(
				source instanceof MappedRegistry<LevelStem> mapped
						? mapped.registryLifecycle()
						: Lifecycle.experimental(),
				"datapackDimensionsLifecycle"
		);
		MappedRegistry<LevelStem> copy = new MappedRegistry<>(Registries.LEVEL_STEM, lifecycle);
		List<Map.Entry<ResourceKey<LevelStem>, LevelStem>> entries = new ArrayList<>(source.entrySet());
		entries.sort(Comparator.comparingInt(entry -> source.getId(entry.getValue())));

		for (Map.Entry<ResourceKey<LevelStem>, LevelStem> entry : entries) {
			ResourceKey<LevelStem> key = Objects.requireNonNull(entry.getKey(), "dimensionStemKey");
			LevelStem stem = Objects.requireNonNull(entry.getValue(), "dimensionStem");
			LevelStem updatedStem;
			if (key.equals(LevelStem.OVERWORLD)) {
				updatedStem = new LevelStem(overworldHolder, overworldGenerator);
			} else {
					ResourceKey<DimensionType> typeKey = stem.type().unwrapKey().orElse(null);
					Holder<DimensionType> typeHolder = typeKey != null
							? Objects.requireNonNull(dimensionTypesChecked.getOrThrow(typeKey), "dimensionType")
							: Objects.requireNonNull(stem.type(), "stemDimensionType");
					updatedStem = new LevelStem(typeHolder, stem.generator());
				}
			RegistrationInfo info = Objects.requireNonNull(
					source.registrationInfo(key).orElse(RegistrationInfo.BUILT_IN),
					"dimensionStemRegistrationInfo"
			);
			copy.register(key, updatedStem, info);
		}

		return copy.freeze();
	}

	private static @NonNull LayeredRegistryAccess<RegistryLayer> updateWorldgenLevelStems(
			@NonNull LayeredRegistryAccess<RegistryLayer> registries,
			@NonNull Registry<LevelStem> updatedLevelStems
	) {
		@NonNull LayeredRegistryAccess<RegistryLayer> updated = registries;
		boolean updatedAny = false;
		for (RegistryLayer layer : RegistryLayer.values()) {
			RegistryAccess.Frozen layerAccess = updated.getLayer(layer);
			if (layerAccess.lookup(Registries.LEVEL_STEM).isEmpty()) {
				continue;
			}
			RegistryAccess.Frozen updatedLayer = replaceRegistry(layerAccess, Registries.LEVEL_STEM, updatedLevelStems);
			updated = replaceLayer(updated, layer, updatedLayer);
			updatedAny = true;
		}
		LayeredRegistryAccess<RegistryLayer> result = updatedAny ? updated : registries;
		return Objects.requireNonNull(result, "updatedRegistries");
	}

	private static @NonNull WorldDimensions updateDimensions(
			@NonNull WorldDimensions dimensions,
			@NonNull Holder<DimensionType> overworldHolder,
			@NonNull ChunkGenerator overworldGenerator,
			HolderLookup.RegistryLookup<DimensionType> dimensionTypes
	) {
		HolderLookup.RegistryLookup<DimensionType> dimensionTypesChecked =
				Objects.requireNonNull(dimensionTypes, "dimensionTypes");
		Map<ResourceKey<LevelStem>, LevelStem> updatedStems = new LinkedHashMap<>();
		dimensions.dimensions().forEach((key, stem) -> {
			Holder<DimensionType> typeHolder;
			if (key.equals(LevelStem.OVERWORLD)) {
				typeHolder = overworldHolder;
			} else {
				ResourceKey<DimensionType> typeKey = stem.type().unwrapKey().orElse(null);
				typeHolder = typeKey != null
						? Objects.requireNonNull(dimensionTypesChecked.getOrThrow(typeKey), "dimensionType")
						: Objects.requireNonNull(stem.type(), "stemDimensionType");
			}
			updatedStems.put(key, new LevelStem(typeHolder, stem.generator()));
		});

		return new WorldDimensions(WorldDimensions.withOverworld(
				updatedStems,
				overworldHolder,
				overworldGenerator
		));
	}

	@Override
	public void tick() {
		super.tick();
		if (this.previewWidget != null) {
			this.previewWidget.tick();
		}
		if (this.previewDirtyAt > 0L && System.currentTimeMillis() - this.previewDirtyAt >= PREVIEW_DEBOUNCE_MS) {
			this.previewDirtyAt = -1L;
			if (this.previewWidget != null) {
				this.previewWidget.requestRebuild(this.buildSettings());
			}
		}
	}

	@Override
	public void removed() {
		if (this.previewWidget != null) {
			this.previewWidget.close();
		}
		super.removed();
	}

	private @NonNull EarthGeneratorSettings buildSettings() {
		double worldScale = this.findSliderValue("world_scale", EarthGeneratorSettings.DEFAULT.worldScale());
		double terrestrialScale = this.findSliderValue("terrestrial_height_scale",
				EarthGeneratorSettings.DEFAULT.terrestrialHeightScale());
		double oceanicScale = this.findSliderValue("oceanic_height_scale", EarthGeneratorSettings.DEFAULT.oceanicHeightScale());
		int heightOffset = (int) Math.round(this.findSliderValue("height_offset", EarthGeneratorSettings.DEFAULT.heightOffset()));
		int seaLevel = this.resolveSeaLevelSetting("sea_level", AUTO_SEA_LEVEL);
		boolean cinematicMode = this.findToggleValue("cinematic_mode", false);
		int maxAltitude = this.resolveAltitudeSetting("max_altitude", AUTO_MAX_ALTITUDE);
		int minAltitude = this.resolveAltitudeSetting("min_altitude", AUTO_MIN_ALTITUDE);
		if (cinematicMode) {
			maxAltitude = EarthGeneratorSettings.AUTO_ALTITUDE;
			minAltitude = EarthGeneratorSettings.AUTO_ALTITUDE;
		}
		boolean caveCarvers = cinematicMode ? false : this.findToggleValue("cave_carvers", true);
		boolean largeCaves = cinematicMode ? false : this.findToggleValue("large_caves", true);
		boolean canyonCarvers = cinematicMode ? false : this.findToggleValue("canyon_carvers", true);
		boolean aquifers = cinematicMode ? false : this.findToggleValue("aquifers", true);
		boolean dripstone = cinematicMode ? false : this.findToggleValue("dripstone", true);
		boolean deepDark = cinematicMode ? false : this.findToggleValue("deep_dark", true);
		boolean oreDistribution = cinematicMode ? false : this.findToggleValue("ore_distribution", false);
		boolean lavaPools = cinematicMode ? false : this.findToggleValue("lava_pools", false);
		boolean addStrongholds = false;
		boolean addVillages = this.findToggleValue("add_villages", true);
		boolean addMineshafts = false;
		boolean addOceanMonuments = this.findToggleValue("add_ocean_monuments", true);
		boolean addWoodlandMansions = this.findToggleValue("add_woodland_mansions", true);
		boolean addDesertTemples = this.findToggleValue("add_desert_temples", true);
		boolean addJungleTemples = this.findToggleValue("add_jungle_temples", true);
		boolean addPillagerOutposts = this.findToggleValue("add_pillager_outposts", true);
		boolean addRuinedPortals = this.findToggleValue("add_ruined_portals", true);
		boolean addShipwrecks = this.findToggleValue("add_shipwrecks", true);
		boolean addOceanRuins = this.findToggleValue("add_ocean_ruins", true);
		boolean addBuriedTreasure = this.findToggleValue("add_buried_treasure", true);
		boolean addIgloos = this.findToggleValue("add_igloos", true);
		boolean addWitchHuts = this.findToggleValue("add_witch_huts", true);
		boolean addAncientCities = false;
		boolean addTrialChambers = false;
		boolean addTrailRuins = this.findToggleValue("add_trail_ruins", true);
		return new EarthGeneratorSettings(
				worldScale,
				terrestrialScale,
				oceanicScale,
				heightOffset,
				seaLevel,
				this.spawnLatitude,
				this.spawnLongitude,
				minAltitude,
				maxAltitude,
				cinematicMode,
				caveCarvers,
				largeCaves,
				canyonCarvers,
				aquifers,
				dripstone,
				deepDark,
				oreDistribution,
				lavaPools,
				addStrongholds,
				addVillages,
				addMineshafts,
				addOceanMonuments,
				addWoodlandMansions,
				addDesertTemples,
				addJungleTemples,
				addPillagerOutposts,
				addRuinedPortals,
				addShipwrecks,
				addOceanRuins,
				addBuriedTreasure,
				addIgloos,
				addWitchHuts,
				addAncientCities,
				addTrialChambers,
				addTrailRuins
		);
	}

	private double findSliderValue(String key, double fallback) {
		for (CategoryDefinition category : this.categories) {
			for (SettingDefinition setting : category.getSettings()) {
				if (setting instanceof SliderDefinition slider && slider.key.equals(key)) {
					return slider.value;
				}
			}
		}
		return fallback;
	}

	private boolean findToggleValue(String key, boolean fallback) {
		for (CategoryDefinition category : this.categories) {
			for (SettingDefinition setting : category.getSettings()) {
				if (setting instanceof ToggleDefinition toggle && toggle.key.equals(key)) {
					return toggle.value;
				}
			}
		}
		return fallback;
	}

	@Override
	public void render(@NonNull GuiGraphics graphics, int mouseX, int mouseY, float delta) {
		super.render(graphics, mouseX, mouseY, delta);
		graphics.drawCenteredString(this.font, this.title, this.width / 2, 20, 0xFFFFFF);
	}

	private static List<CategoryDefinition> createCategories() {
		List<CategoryDefinition> categories = new ArrayList<>();

		categories.add(new CategoryDefinition("world", List.of(
				slider("world_scale", 35.0, 1.0, 40000.0, 5.0)
						.withDisplay(EarthCustomizeScreen::formatWorldScale)
						.withScale(SliderScale.power(3.0)),
				slider("terrestrial_height_scale", 1.0, 0.0, 50.0, 0.5)
						.withDisplay(EarthCustomizeScreen::formatMultiplier)
						.withScale(SliderScale.power(3.0)),
				slider("oceanic_height_scale", 1.0, 0.0, 50.0, 0.5)
						.withDisplay(EarthCustomizeScreen::formatMultiplier)
						.withScale(SliderScale.power(3.0)),
				slider("height_offset", EarthGeneratorSettings.DEFAULT.heightOffset(), -63.0, 128.0, 1.0)
						.withDisplay(EarthCustomizeScreen::formatHeightOffset),
				slider("sea_level", AUTO_SEA_LEVEL, AUTO_SEA_LEVEL, 256.0, 1.0)
						.withDisplay(EarthCustomizeScreen::formatSeaLevel),
				slider("max_altitude", AUTO_MAX_ALTITUDE, AUTO_MAX_ALTITUDE, EarthGeneratorSettings.MAX_WORLD_Y, 16.0)
						.withDisplay(EarthCustomizeScreen::formatMaxAltitude),
				slider("min_altitude", AUTO_MIN_ALTITUDE, AUTO_MIN_ALTITUDE, EarthGeneratorSettings.MAX_WORLD_Y, 16.0)
						.withDisplay(EarthCustomizeScreen::formatMinAltitude),
				slider("terrain_smoothing", 25.0, 0.0, 100.0, 5.0)
						.withDisplay(EarthCustomizeScreen::formatPercent)
						.locked(true),
				slider("river_slope", 100.0, 0.0, 200.0, 5.0)
						.withDisplay(EarthCustomizeScreen::formatPercent)
						.locked(true),
				slider("lake_depth_curve", 100.0, 0.0, 200.0, 5.0)
						.withDisplay(EarthCustomizeScreen::formatPercent)
						.locked(true),
				slider("shoreline_blend", 30.0, 0.0, 200.0, 5.0)
						.withDisplay(EarthCustomizeScreen::formatMeters)
						.locked(true)
		)));

		categories.add(new CategoryDefinition("ecological", List.of(
				toggle("land_vegetation", true).locked(true),
				slider("land_vegetation_density", 100.0, 0.0, 200.0, 5.0)
						.withDisplay(EarthCustomizeScreen::formatPercent)
						.locked(true),
				slider("trees_density", 100.0, 0.0, 200.0, 5.0)
						.withDisplay(EarthCustomizeScreen::formatPercent)
						.locked(true),
				toggle("aquatic_vegetation", true).locked(true),
				toggle("crops_in_villages", true).locked(true)
		)));

		categories.add(new CategoryDefinition("geological", List.of(
				toggle("cave_carvers", false).locked(true),
				toggle("large_caves", false).locked(true),
				toggle("canyon_carvers", false).locked(true),
				toggle("aquifers", false).locked(true),
				toggle("dripstone", false).locked(true),
				toggle("deep_dark", false).locked(true),
				toggle("ore_distribution", false),
				toggle("lava_pools", false)
		)));

		categories.add(new CategoryDefinition("structure", List.of(
				toggle("add_strongholds", false).locked(true),
				toggle("add_villages", true),
				toggle("add_mineshafts", false).locked(true),
				toggle("add_ocean_monuments", true),
				toggle("add_woodland_mansions", true),
				toggle("add_desert_temples", true),
				toggle("add_jungle_temples", true),
				toggle("add_pillager_outposts", true),
				toggle("add_ruined_portals", true),
				toggle("add_shipwrecks", true),
				toggle("add_ocean_ruins", true),
				toggle("add_buried_treasure", true),
				toggle("add_igloos", true),
				toggle("add_witch_huts", true),
				toggle("add_ancient_cities", false).locked(true),
				toggle("add_trial_chambers", false).locked(true),
				toggle("add_trail_ruins", true)
		)));

		categories.add(new CategoryDefinition("compatibility", List.of(
				comingSoonButton()
		)));

		return categories;
	}

	private static SliderDefinition slider(
			String key,
			double defaultValue,
			double min,
			double max,
			double step
	) {
		return new SliderDefinition(key, defaultValue, min, max, step);
	}

	private static ToggleDefinition toggle(String key, boolean defaultValue) {
		return new ToggleDefinition(key, defaultValue);
	}

	private static ButtonDefinition comingSoonButton() {
		Component label = Objects.requireNonNull(Component.translatable("gui.terrarium.coming_soon"), "comingSoonLabel");
		Component tooltip = Objects.requireNonNull(
				Component.translatable("terrarium.customize.coming_soon")
						.withStyle(ChatFormatting.GRAY)
						.copy()
						.append(Component.literal(" "))
						.append(WORK_IN_PROGRESS),
				"comingSoonTooltip"
		);
		return new ButtonDefinition(label, tooltip, false);
	}

	private static String formatWorldScale(double value) {
		if (value < 1000.0) {
			return String.format(Locale.ROOT, "1:%.0fm", value);
		}
		return String.format(Locale.ROOT, "1:%.1fkm", value / 1000.0);
	}

	private static String formatMultiplier(double value) {
		return String.format(Locale.ROOT, "%.1fx", value);
	}

	private static String formatHeightOffset(double value) {
		return String.format(Locale.ROOT, "%.0f blocks", value);
	}

	private static String formatSeaLevel(double value) {
		if (value <= AUTO_SEA_LEVEL + SEA_LEVEL_AUTO_EPSILON) {
			return "Automatic";
		}
		return String.format(Locale.ROOT, "%.0f blocks", value);
	}

	private static String formatPercent(double value) {
		return String.format(Locale.ROOT, "%.0f%%", value);
	}

	private static String formatMeters(double value) {
		return String.format(Locale.ROOT, "%.0fm", value);
	}

	private static String formatMaxAltitude(double value) {
		return formatAltitude(value, AUTO_MAX_ALTITUDE);
	}

	private static String formatMinAltitude(double value) {
		return formatAltitude(value, AUTO_MIN_ALTITUDE);
	}

	private static String formatAltitude(double value, double autoValue) {
		if (value <= autoValue + ALTITUDE_AUTO_EPSILON) {
			return "Automatic";
		}
		return String.format(Locale.ROOT, "%.0f blocks", value);
	}

	private static @NonNull Component settingName(String key) {
		return Objects.requireNonNull(Component.translatable("property.terrarium." + key + ".name"), "settingName");
	}

	private static @NonNull Component settingTooltip(String key) {
		return Objects.requireNonNull(
				Component.translatable("property.terrarium." + key + ".tooltip").withStyle(ChatFormatting.GRAY),
				"settingTooltip"
		);
	}

	private static @NonNull Component workInProgressTooltip(String key) {
		return Objects.requireNonNull(settingTooltip(key), "settingTooltip")
				.copy()
				.append(Component.literal(" "))
				.append(WORK_IN_PROGRESS);
	}

	private static String describeDimensionType(DimensionType type) {
		return "minY=" + type.minY() + ",height=" + type.height() + ",logicalHeight=" + type.logicalHeight();
	}

	private int resolveAltitudeSetting(String key, double autoValue) {
		double value = this.findSliderValue(key, autoValue);
		if (value <= autoValue + ALTITUDE_AUTO_EPSILON) {
			return EarthGeneratorSettings.AUTO_ALTITUDE;
		}
		return (int) Math.round(value);
	}

	private int resolveSeaLevelSetting(String key, double autoValue) {
		double value = this.findSliderValue(key, autoValue);
		if (value <= autoValue + SEA_LEVEL_AUTO_EPSILON) {
			return EarthGeneratorSettings.AUTO_SEA_LEVEL;
		}
		return (int) Math.round(value);
	}

	private void applyCinematicMode(boolean cinematic) {
		for (CategoryDefinition category : this.categories) {
			for (SettingDefinition setting : category.getSettings()) {
				if (setting instanceof SliderDefinition slider) {
					if ("max_altitude".equals(slider.key)) {
						slider.locked = cinematic;
						if (cinematic) {
							slider.value = AUTO_MAX_ALTITUDE;
						}
					}
					if ("min_altitude".equals(slider.key)) {
						slider.locked = cinematic;
						if (cinematic) {
							slider.value = AUTO_MIN_ALTITUDE;
						}
					}
				}
			}
		}
	}

	private static @NonNull RegistryUpdate updateDimensionTypeRegistry(
			@NonNull LayeredRegistryAccess<RegistryLayer> registries,
			@NonNull DimensionType updatedType,
			@NonNull ResourceKey<DimensionType> targetKey
	) {
		LayeredRegistryAccess<RegistryLayer> updatedRegistries = registries;
		boolean updatedAny = false;
		for (RegistryLayer layer : RegistryLayer.values()) {
			RegistryAccess.Frozen layerAccess = updatedRegistries.getLayer(layer);
			if (layerAccess.lookup(Registries.DIMENSION_TYPE).isEmpty()) {
				continue;
			}
			Registry<DimensionType> source = layerAccess.lookupOrThrow(Registries.DIMENSION_TYPE);
			Lifecycle lifecycle = Objects.requireNonNull(
					source instanceof MappedRegistry<DimensionType> mapped
							? mapped.registryLifecycle()
							: Lifecycle.experimental(),
					"dimensionTypeLifecycle"
			);
			MappedRegistry<DimensionType> copy = new MappedRegistry<>(Registries.DIMENSION_TYPE, lifecycle);
			List<Map.Entry<ResourceKey<DimensionType>, DimensionType>> entries = new ArrayList<>(source.entrySet());
			entries.sort(Comparator.comparingInt(entry -> source.getId(entry.getValue())));

			for (Map.Entry<ResourceKey<DimensionType>, DimensionType> entry : entries) {
				ResourceKey<DimensionType> key = Objects.requireNonNull(entry.getKey(), "dimensionTypeKey");
				if (key.equals(targetKey)) {
					continue;
				}
				DimensionType value = Objects.requireNonNull(entry.getValue(), "dimensionType");
				RegistrationInfo info = Objects.requireNonNull(
						source.registrationInfo(key).orElse(RegistrationInfo.BUILT_IN),
						"registrationInfo"
				);
				copy.register(key, value, info);
			}

			@NonNull Optional<KnownPack> emptyKnownPack = Objects.requireNonNull(
					Optional.<KnownPack>empty(),
					"emptyKnownPack"
			);
			RegistrationInfo targetInfo = Objects.requireNonNull(
					source.registrationInfo(targetKey)
							.map(info -> new RegistrationInfo(
									emptyKnownPack,
									Objects.requireNonNull(info.lifecycle(), "dimensionTypeLifecycle")
							))
							.orElseGet(() -> new RegistrationInfo(
									emptyKnownPack,
									Objects.requireNonNull(Lifecycle.experimental(), "experimentalLifecycle")
							)),
					"dimensionTypeRegistrationInfo"
			);
			copy.register(
					targetKey,
					Objects.requireNonNull(updatedType, "updatedType"),
					targetInfo
			);
			Registry<DimensionType> frozen = copy.freeze();
			RegistryAccess.Frozen updatedLayer = replaceRegistry(layerAccess, Registries.DIMENSION_TYPE, frozen);
			updatedRegistries = replaceLayer(updatedRegistries, layer, updatedLayer);
			updatedAny = true;
		}

		if (!updatedAny) {
			throw new IllegalStateException("Dimension type registry missing");
		}

		HolderLookup.RegistryLookup<DimensionType> dimensionTypes =
				updatedRegistries.compositeAccess().lookupOrThrow(Registries.DIMENSION_TYPE);
		Holder<DimensionType> holder = Objects.requireNonNull(
				dimensionTypes.getOrThrow(targetKey),
				"dimensionTypeHolder"
		);
		return new RegistryUpdate(updatedRegistries, holder);
	}

	private static RegistryAccess.Frozen replaceRegistry(
			RegistryAccess.Frozen source,
			ResourceKey<? extends Registry<?>> registryKey,
			Registry<?> replacement
	) {
		Map<ResourceKey<? extends Registry<?>>, Registry<?>> registryMap = new LinkedHashMap<>();
		source.registries().forEach(entry -> registryMap.put(entry.key(), entry.value()));
		registryMap.put(registryKey, replacement);
		return new RegistryAccess.ImmutableRegistryAccess(registryMap).freeze();
	}

	private static @NonNull LayeredRegistryAccess<RegistryLayer> replaceLayer(
			@NonNull LayeredRegistryAccess<RegistryLayer> registries,
			@NonNull RegistryLayer target,
			RegistryAccess.Frozen replacement
	) {
		RegistryAccess.Frozen replacementChecked = Objects.requireNonNull(replacement, "replacement");
		RegistryLayer[] layers = RegistryLayer.values();
		List<RegistryAccess.Frozen> replacements = new ArrayList<>();
		boolean found = false;
		for (RegistryLayer layer : layers) {
			if (!found) {
				if (layer == target) {
					found = true;
					replacements.add(replacementChecked);
				}
				continue;
			}
			replacements.add(registries.getLayer(layer));
		}
		if (!found) {
			throw new IllegalStateException("Registry layer missing: " + target);
		}
		return registries.replaceFrom(target, replacements);
	}

	private record RegistryUpdate(LayeredRegistryAccess<RegistryLayer> registries, Holder<DimensionType> holder) {}

	private interface SettingDefinition {
		AbstractWidget createWidget(Runnable onChange);
	}

	private void showCategories() {
		this.list.clear();
		this.activeCategory = null;
		for (CategoryDefinition category : this.categories) {
			Component label = Objects.requireNonNull(category.getLabel(), "categoryLabel");
			Button button = Button.builder(label, btn -> this.showCategory(category))
					.bounds(0, 0, this.list.getRowWidth(), ENTRY_HEIGHT)
					.build();
			this.list.addWidget(button);
		}
		this.list.setScrollAmount(0.0);
	}

	private void showCategory(CategoryDefinition category) {
		this.list.clear();
		this.activeCategory = category;
		Component backLabel = Objects.requireNonNull(Component.translatable("gui.back"), "backLabel");
		Button back = Button.builder(backLabel, btn -> this.showCategories())
				.bounds(0, 0, this.list.getRowWidth(), ENTRY_HEIGHT)
				.build();
		this.list.addWidget(back);

		for (SettingDefinition setting : category.getSettings()) {
			this.list.addWidget(setting.createWidget(this::onSettingsChanged));
		}
		this.list.setScrollAmount(0.0);
	}

	private static final class CategoryDefinition {
		private final String id;
		private final List<SettingDefinition> settings;

		private CategoryDefinition(String id, List<SettingDefinition> settings) {
			this.id = id;
			this.settings = settings;
		}

		private @NonNull Component getLabel() {
			return this.getLabel(false);
		}

		private @NonNull Component getLabel(boolean selected) {
			Component base = Objects.requireNonNull(
					Component.translatable("category.terrarium." + this.id + ".name"),
					"categoryLabel"
			);
			if (!selected) {
				return base;
			}
			return Objects.requireNonNull(base.copy().withStyle(ChatFormatting.YELLOW), "selectedCategoryLabel");
		}

		private List<SettingDefinition> getSettings() {
			return this.settings;
		}

	}

	private static final class ToggleDefinition implements SettingDefinition {
		private final String key;
		private boolean value;
		private boolean locked;

		private ToggleDefinition(String key, boolean defaultValue) {
			this.key = key;
			this.value = defaultValue;
		}

		private ToggleDefinition locked(boolean locked) {
			this.locked = locked;
			return this;
		}

		@Override
		public AbstractWidget createWidget(Runnable onChange) {
			Component name = settingName(this.key);
			Component tooltip = this.locked
					? workInProgressTooltip(this.key)
					: settingTooltip(this.key);
			CycleButton.Builder<Boolean> builder = CycleButton.booleanBuilder(YES, NO, this.value)
					.withTooltip(value -> Tooltip.create(tooltip));
			CycleButton<Boolean> button = builder.create(0, 0, 0, ENTRY_HEIGHT, name, (btn, value) -> {
				this.value = value;
				onChange.run();
			});
			button.active = !this.locked;
			return button;
		}
	}

	private static final class ButtonDefinition implements SettingDefinition {
		private final @NonNull Component label;
		private final @Nullable Component tooltip;
		private final boolean active;

		private ButtonDefinition(Component label, Component tooltip, boolean active) {
			this.label = Objects.requireNonNull(label, "buttonLabel");
			this.tooltip = tooltip;
			this.active = active;
		}

		@Override
		public AbstractWidget createWidget(Runnable onChange) {
			Button button = Button.builder(this.label, btn -> {
			}).bounds(0, 0, 0, ENTRY_HEIGHT).build();
			button.active = this.active;
			if (this.tooltip != null) {
				button.setTooltip(Tooltip.create(this.tooltip));
			}
			return button;
		}
	}

	private static final class SliderDefinition implements SettingDefinition {
		private final String key;
		private final double min;
		private final double max;
		private final double step;
		private double value;
		private DoubleFunction<String> display;
		private SliderScale scale = SliderScale.linear();
		private boolean locked;

		private SliderDefinition(String key, double defaultValue, double min, double max, double step) {
			this.key = key;
			this.value = defaultValue;
			this.min = min;
			this.max = max;
			this.step = step;
		}

		private SliderDefinition withDisplay(DoubleFunction<String> display) {
			this.display = display;
			return this;
		}

		private SliderDefinition withScale(SliderScale scale) {
			this.scale = scale;
			return this;
		}

		private SliderDefinition locked(boolean locked) {
			this.locked = locked;
			return this;
		}

		@Override
		public AbstractWidget createWidget(Runnable onChange) {
			EarthSlider slider = new EarthSlider(0, 0, 0, ENTRY_HEIGHT, this, onChange);
			Component tooltip = this.locked
					? workInProgressTooltip(this.key)
					: settingTooltip(this.key);
			slider.setTooltip(Tooltip.create(tooltip));
			slider.active = !this.locked;
			return slider;
		}
	}

	private static final class EarthSlider extends AbstractSliderButton {
		private static final double EPSILON = 1.0e-6;

		private final SliderDefinition definition;
		private final Runnable onChange;

		private EarthSlider(int x, int y, int width, int height, SliderDefinition definition, Runnable onChange) {
			super(x, y, width, height, Component.empty(), 0.0);
			this.definition = definition;
			this.onChange = onChange;
			this.value = this.toPosition(definition.value);
			this.updateMessage();
		}

		@Override
		protected void updateMessage() {
			double value = this.toValue(this.value);
			String fallback = Objects.requireNonNull(String.format(Locale.ROOT, "%.2f", value), "formattedValue");
			String valueText = this.definition.display != null
					? Objects.requireNonNullElse(this.definition.display.apply(value), fallback)
					: fallback;
			MutableComponent message = settingName(this.definition.key)
					.copy()
					.append(": ")
					.append(Component.literal(Objects.requireNonNull(valueText, "valueText")));
			this.setMessage(message);
		}

		@Override
		protected void applyValue() {
			double rawValue = this.toValue(this.value);
			double snappedValue = this.snap(rawValue, this.definition.step);
			if (Math.abs(snappedValue - rawValue) > EPSILON) {
				this.value = this.toPosition(snappedValue);
			}
			if (Math.abs(this.definition.value - snappedValue) > EPSILON) {
				this.definition.value = snappedValue;
				this.onChange.run();
			}
		}

		private double snap(double value, double step) {
			if (step <= 0.0) {
				return Mth.clamp(value, this.definition.min, this.definition.max);
			}
			if ("world_scale".equals(this.definition.key)) {
				double firstStep = this.definition.min;
				double cutoff = (firstStep + step) * 0.5;
				if (value <= cutoff) {
					return firstStep;
				}
				double snapped = Math.round(value / step) * step;
				double adjusted = Math.max(step, snapped);
				return Mth.clamp(adjusted, this.definition.min, this.definition.max);
			}
			double snapped = this.definition.min + Math.round((value - this.definition.min) / step) * step;
			return Mth.clamp(snapped, this.definition.min, this.definition.max);
		}

		private double toPosition(double value) {
			double position = (Mth.clamp(value, this.definition.min, this.definition.max) - this.definition.min)
					/ (this.definition.max - this.definition.min);
			return this.definition.scale.reverse(position);
		}

		private double toValue(double position) {
			double scaled = this.definition.scale.apply(position);
			return this.definition.min + (this.definition.max - this.definition.min) * Mth.clamp(scaled, 0.0, 1.0);
		}
	}

	private interface SliderScale {
		double apply(double value);

		double reverse(double value);

		static SliderScale linear() {
			return new SliderScale() {
				@Override
				public double apply(double value) {
					return value;
				}

				@Override
				public double reverse(double value) {
					return value;
				}
			};
		}

		static SliderScale power(double power) {
			return new SliderScale() {
				@Override
				public double apply(double value) {
					return Math.pow(value, power);
				}

				@Override
				public double reverse(double value) {
					return Math.pow(value, 1.0 / power);
				}
			};
		}
	}
}
