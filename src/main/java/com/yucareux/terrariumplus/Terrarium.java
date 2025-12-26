package com.yucareux.terrariumplus;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import com.yucareux.terrariumplus.network.GeoTpOpenMapPayload;
import com.yucareux.terrariumplus.network.GeoTpTeleportPayload;
import com.yucareux.terrariumplus.worldgen.EarthBiomeSource;
import com.yucareux.terrariumplus.worldgen.EarthChunkGenerator;
import com.yucareux.terrariumplus.worldgen.EarthGeneratorSettings;
import java.util.Objects;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.DataPackConfig;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.WorldData;
import net.minecraft.server.packs.PackType;
import net.minecraft.SharedConstants;
import net.minecraft.util.Mth;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class Terrarium implements ModInitializer {
	public static final @NonNull String MOD_ID = "terrarium";
	private static final String DYNAMIC_DIMENSION_PACK_NAME = "terrarium_dynamic_dimension";
	private static final String DYNAMIC_DIMENSION_PACK_ID = "file/" + DYNAMIC_DIMENSION_PACK_NAME;
	private static final @NonNull Identifier EARTH_DIMENSION_ID = Objects.requireNonNull(
			Identifier.fromNamespaceAndPath(MOD_ID, "earth"),
			"earthDimensionId"
	);
	private static final @NonNull Identifier DYNAMIC_DIMENSION_ID = Objects.requireNonNull(
			Identifier.fromNamespaceAndPath(MOD_ID, "earth_dynamic"),
			"dynamicDimensionId"
	);
	private static final @NonNull ResourceKey<DimensionType> EARTH_DIMENSION_KEY = Objects.requireNonNull(
			ResourceKey.create(Registries.DIMENSION_TYPE, EARTH_DIMENSION_ID),
			"earthDimensionKey"
	);
	private static final @NonNull ResourceKey<DimensionType> DYNAMIC_DIMENSION_KEY = Objects.requireNonNull(
			ResourceKey.create(Registries.DIMENSION_TYPE, DYNAMIC_DIMENSION_ID),
			"dynamicDimensionKey"
	);
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	// This logger is used to write text to the console and the log file.
	// It is considered best practice to use your mod id as the logger's name.
	// That way, it's clear which mod wrote info, warnings, and errors.
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	public static @NonNull Identifier id(@NonNull String path) {
		return Objects.requireNonNull(Identifier.fromNamespaceAndPath(MOD_ID, path), "identifier");
	}

	@Override
	public void onInitialize() {
		Registry.register(BuiltInRegistries.BIOME_SOURCE, id("earth"), EarthBiomeSource.CODEC);
		Registry.register(BuiltInRegistries.CHUNK_GENERATOR, id("earth"), EarthChunkGenerator.CODEC);
		PayloadTypeRegistry.playC2S().register(
				GeoTpTeleportPayload.TYPE,
				Objects.requireNonNull(GeoTpTeleportPayload.CODEC.cast(), "geoTpTeleportCodec")
		);
		PayloadTypeRegistry.playS2C().register(
				GeoTpOpenMapPayload.TYPE,
				Objects.requireNonNull(GeoTpOpenMapPayload.CODEC.cast(), "geoTpOpenMapCodec")
		);
		ServerPlayNetworking.registerGlobalReceiver(GeoTpTeleportPayload.TYPE, Terrarium::handleGeoTeleport);
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
				Commands.literal("geotp")
						.then(Commands.literal("map")
								.requires(source -> source.permissions()
										.hasPermission(new Permission.HasCommandLevel(PermissionLevel.GAMEMASTERS)))
								.executes(context -> openGeoTpMap(context.getSource())))
		));

		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			server.execute(() -> {
				var world = server.getLevel(Level.OVERWORLD);
				if (world == null) {
					return;
				}
				ChunkGenerator generator = world.getChunkSource().getGenerator();
				logOverworldSettings(server, world, generator);
				if (generator instanceof EarthChunkGenerator earthGenerator) {
					BlockPos spawn = Objects.requireNonNull(earthGenerator.getSpawnPosition(world), "spawnPosition");
					world.setRespawnData(LevelData.RespawnData.of(world.dimension(), spawn, 0.0F, 0.0F));
					ensureDynamicDimensionPack(server, world.dimensionTypeRegistration(), world.dimensionType(), earthGenerator);
				}
			});
		});

		LOGGER.info("Terrarium worldgen initialized");
		try {
			// Integrate with Distant Horizons if present
			com.yucareux.terrariumplus.integration.distant_horizons.DistantHorizonsIntegration.bootstrap();
		} catch (Throwable e) {
			LOGGER.error("Failed to load Distant Horizons integration", e);
		}
	}

	private static int openGeoTpMap(CommandSourceStack source) {
		ServerPlayer player = source.getPlayer();
		if (player == null) {
			source.sendFailure(Component.literal("Terrarium: GeoTP map can only be used by a player."));
			return 0;
		}
		ServerLevel level = player.level();
		ChunkGenerator generator = level.getChunkSource().getGenerator();
		if (!(generator instanceof EarthChunkGenerator earthGenerator)) {
			source.sendFailure(Component.literal("Terrarium: GeoTP map is only available in Terrarium worlds."));
			return 0;
		}
		double latitude = clampLatitude(earthGenerator.latitudeFromBlock(player.getZ()));
		double longitude = clampLongitude(earthGenerator.longitudeFromBlock(player.getX()));
		ServerPlayNetworking.send(player, new GeoTpOpenMapPayload(latitude, longitude));
		return 1;
	}

	private static void handleGeoTeleport(GeoTpTeleportPayload payload, ServerPlayNetworking.Context context) {
		if (!Double.isFinite(payload.latitude()) || !Double.isFinite(payload.longitude())) {
			return;
		}
		ServerPlayer player = context.player();
		ServerLevel level = player.level();
		ChunkGenerator generator = level.getChunkSource().getGenerator();
		if (!(generator instanceof EarthChunkGenerator earthGenerator)) {
			player.sendSystemMessage(Component.literal("Terrarium: GeoTP is only available in Terrarium worlds."));
			return;
		}
		double latitude = clampLatitude(payload.latitude());
		double longitude = clampLongitude(payload.longitude());
		BlockPos target = earthGenerator.getSurfacePosition(level, latitude, longitude);
		player.teleportTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5);
	}

	private static double clampLatitude(double latitude) {
		return Mth.clamp(latitude, -85.05112878, 85.05112878);
	}

	private static double clampLongitude(double longitude) {
		return Mth.clamp(longitude, -180.0, 180.0);
	}

	private static void logOverworldSettings(MinecraftServer server, Level world, ChunkGenerator generator) {
		DimensionType worldType = world.dimensionType();
		LOGGER.info(
				"Overworld dimension type: {}",
				describeDimensionType(worldType)
		);
		LOGGER.info(
				"Overworld generator: type={}, minY={}, height={}",
				generator.getClass().getSimpleName(),
				generator.getMinY(),
				generator.getGenDepth()
		);
		Registry<LevelStem> stems = server.registryAccess().lookupOrThrow(Registries.LEVEL_STEM);
		Optional<Holder.Reference<LevelStem>> stemRef = stems.get(LevelStem.OVERWORLD);
		if (stemRef.isEmpty()) {
			LOGGER.warn("Overworld level stem missing from registry");
			return;
		}
		LevelStem stem = stemRef.get().value();
		DimensionType stemType = stem.type().value();
		LOGGER.info(
				"Overworld level stem: dimensionType={}, generatorType={}",
				describeDimensionType(stemType),
				stem.generator().getClass().getSimpleName()
		);
	}

	private static String describeDimensionType(DimensionType type) {
		return "minY=" + type.minY() + ",height=" + type.height() + ",logicalHeight=" + type.logicalHeight();
	}

	private static void ensureDynamicDimensionPack(
			MinecraftServer server,
			Holder<DimensionType> dimensionTypeHolder,
			DimensionType currentDimensionType,
			EarthChunkGenerator earthGenerator
	) {
		ResourceKey<DimensionType> dimensionKey = resolveTerrariumDimensionKey(dimensionTypeHolder).orElse(null);
		if (dimensionKey == null) {
			return;
		}
		EarthGeneratorSettings settings = earthGenerator.settings();
		EarthGeneratorSettings.HeightLimits limits = EarthGeneratorSettings.resolveHeightLimits(settings);
		DimensionType updatedType = EarthGeneratorSettings.applyHeightLimits(currentDimensionType, limits);

		DynamicOps<JsonElement> jsonOps = Objects.requireNonNull(JsonOps.INSTANCE, "jsonOps");
		RegistryOps<JsonElement> registryOps = RegistryOps.create(jsonOps, server.registryAccess());
		JsonElement dimensionJson = DimensionType.DIRECT_CODEC.encodeStart(registryOps, updatedType)
				.resultOrPartial(message -> LOGGER.error("Failed to encode dynamic dimension type: {}", message))
				.orElse(null);
		if (dimensionJson == null) {
			return;
		}

		Path packDir = server.getWorldPath(LevelResource.DATAPACK_DIR).resolve(DYNAMIC_DIMENSION_PACK_NAME);
		Path packMetaPath = packDir.resolve("pack.mcmeta");
		Identifier dimensionId = dimensionKey.identifier();
		Path dimensionPath = packDir.resolve(
				"data/" + dimensionId.getNamespace() + "/dimension_type/" + dimensionId.getPath() + ".json"
		);
		try {
			Files.createDirectories(dimensionPath.getParent());
			writeJson(packMetaPath, createPackMeta());
			writeJson(dimensionPath, dimensionJson);
		} catch (IOException e) {
			LOGGER.warn("Failed to persist dynamic dimension type pack", e);
			return;
		}

		enableDynamicPack(server.getWorldData());
	}

	private static Optional<ResourceKey<DimensionType>> resolveTerrariumDimensionKey(
			Holder<DimensionType> dimensionTypeHolder
	) {
		return dimensionTypeHolder.unwrapKey().filter(Terrarium::isTerrariumDimensionKey);
	}

	private static boolean isTerrariumDimensionKey(ResourceKey<DimensionType> key) {
		return key.equals(DYNAMIC_DIMENSION_KEY) || key.equals(EARTH_DIMENSION_KEY);
	}

	private static JsonObject createPackMeta() {
		JsonObject pack = new JsonObject();
		int packFormat = SharedConstants.getCurrentVersion().packVersion(PackType.SERVER_DATA).major();
		pack.addProperty("pack_format", packFormat);
		pack.addProperty("description", "Terrarium dynamic dimension settings");
		JsonObject root = new JsonObject();
		root.add("pack", pack);
		return root;
	}

	private static void writeJson(Path path, JsonElement payload) throws IOException {
		try (var writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
			GSON.toJson(payload, writer);
		}
	}

	private static void enableDynamicPack(WorldData worldData) {
		WorldDataConfiguration configuration = worldData.getDataConfiguration();
		DataPackConfig dataPacks = configuration.dataPacks();
		List<String> enabled = new ArrayList<>(dataPacks.getEnabled());
		List<String> disabled = new ArrayList<>(dataPacks.getDisabled());
		if (!enabled.contains(DYNAMIC_DIMENSION_PACK_ID)) {
			enabled.add(DYNAMIC_DIMENSION_PACK_ID);
		}
		disabled.remove(DYNAMIC_DIMENSION_PACK_ID);
		if (enabled.equals(dataPacks.getEnabled()) && disabled.equals(dataPacks.getDisabled())) {
			return;
		}
		WorldDataConfiguration updated = new WorldDataConfiguration(
				new DataPackConfig(enabled, disabled),
				configuration.enabledFeatures()
		);
		worldData.setDataConfiguration(updated);
	}
}
