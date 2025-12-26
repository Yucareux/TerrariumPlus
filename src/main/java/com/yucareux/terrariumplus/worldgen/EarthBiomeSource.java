package com.yucareux.terrariumplus.worldgen;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.yucareux.terrariumplus.world.data.biome.BiomeClassification;
import com.yucareux.terrariumplus.world.data.cover.TerrariumLandCoverSource;
import com.yucareux.terrariumplus.world.data.elevation.TerrariumElevationSource;
import com.yucareux.terrariumplus.world.data.koppen.TerrariumKoppenSource;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.Climate;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

public final class EarthBiomeSource extends BiomeSource {
	public static final MapCodec<EarthBiomeSource> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
			RegistryOps.<Biome, EarthBiomeSource>retrieveGetter(Registries.BIOME),
			EarthGeneratorSettings.CODEC.fieldOf("settings").forGetter(EarthBiomeSource::settings)
	).apply(instance, EarthBiomeSource::new));

	private static final int ESA_SNOW_ICE = 70;
	private static final int ESA_WATER = 80;
	private static final int ESA_MANGROVES = 95;
	private static final int ESA_NO_DATA = 0;

	private static final TerrariumLandCoverSource LAND_COVER_SOURCE = new TerrariumLandCoverSource();
	private static final TerrariumKoppenSource KOPPEN_SOURCE = new TerrariumKoppenSource();
	private static final TerrariumElevationSource ELEVATION_SOURCE = new TerrariumElevationSource();

	private final @NonNull HolderGetter<Biome> biomeLookup;
	private final @NonNull EarthGeneratorSettings settings;
	private final @NonNull Set<Holder<Biome>> possibleBiomes;
	private final @NonNull Holder<Biome> plains;
	private final @NonNull Holder<Biome> ocean;
	private final @NonNull Holder<Biome> river;
	private final @NonNull Holder<Biome> frozenPeaks;
	private final @NonNull Holder<Biome> mangrove;
	private final @NonNull WaterSurfaceResolver waterResolver;

	public EarthBiomeSource(HolderGetter<Biome> biomeLookup, EarthGeneratorSettings settings) {
		this.biomeLookup = Objects.requireNonNull(biomeLookup, "biomeLookup");
		this.settings = Objects.requireNonNull(settings, "settings");
		this.plains = this.biomeLookup.getOrThrow(Biomes.PLAINS);
		this.ocean = resolveBiome(Biomes.OCEAN, this.plains);
		this.river = resolveBiome(Biomes.RIVER, this.plains);
		this.frozenPeaks = resolveBiome(Biomes.FROZEN_PEAKS, this.plains);
		this.mangrove = resolveBiome(Biomes.MANGROVE_SWAMP, this.plains);
		this.waterResolver = new WaterSurfaceResolver(LAND_COVER_SOURCE, ELEVATION_SOURCE, this.settings);
		this.possibleBiomes = buildPossibleBiomes();
	}

	public EarthGeneratorSettings settings() {
		return this.settings;
	}

	@Override
	protected @NonNull Stream<Holder<Biome>> collectPossibleBiomes() {
		return Objects.requireNonNull(this.possibleBiomes.stream(), "possibleBiomes.stream()");
	}

	@Override
	protected @NonNull MapCodec<? extends BiomeSource> codec() {
		return Objects.requireNonNull(CODEC, "CODEC");
	}

	@Override
	public @NonNull Holder<Biome> getNoiseBiome(int x, int y, int z, Climate.@NonNull Sampler sampler) {
		int blockX = QuartPos.toBlock(x);
		int blockZ = QuartPos.toBlock(z);
		int coverClass = LAND_COVER_SOURCE.sampleCoverClass(blockX, blockZ, this.settings.worldScale());

		if (coverClass == ESA_SNOW_ICE) {
			return this.frozenPeaks;
		}
		if (coverClass == ESA_MANGROVES) {
			return this.mangrove;
		}
		if (coverClass == ESA_NO_DATA || coverClass == ESA_WATER) {
			WaterSurfaceResolver.WaterColumnData column = this.waterResolver.resolveColumnData(blockX, blockZ);
			if (column.hasWater()) {
				if (column.isOcean()) {
					return this.ocean;
				}
				return this.river;
			}
		}

		String koppen = KOPPEN_SOURCE.sampleDitheredCode(blockX, blockZ, this.settings.worldScale());
		if (koppen == null) {
			koppen = KOPPEN_SOURCE.findNearestCode(blockX, blockZ, this.settings.worldScale());
		}

		ResourceKey<Biome> biomeKey = BiomeClassification.findBiomeKey(coverClass, koppen);
		if (biomeKey == null) {
			biomeKey = BiomeClassification.findFallbackKey(coverClass);
		}
		if (biomeKey == null) {
			return this.plains;
		}
		return resolveBiome(biomeKey, this.plains);
	}

	private @NonNull Set<Holder<Biome>> buildPossibleBiomes() {
		Set<Holder<Biome>> holders = new HashSet<>();
		for (ResourceKey<Biome> key : BiomeClassification.allBiomeKeys()) {
			holders.add(resolveBiome(key, this.plains));
		}
		holders.add(this.plains);
		holders.add(this.ocean);
		holders.add(this.river);
		holders.add(this.frozenPeaks);
		holders.add(this.mangrove);
		return holders;
	}

	private @NonNull Holder<Biome> resolveBiome(@Nullable ResourceKey<Biome> key, @NonNull Holder<Biome> fallback) {
		if (key == null) {
			return fallback;
		}
		Holder<Biome> resolved = this.biomeLookup.get(key).map(holder -> (Holder<Biome>) holder).orElse(fallback);
		return Objects.requireNonNull(resolved, "resolvedBiome");
	}
}
