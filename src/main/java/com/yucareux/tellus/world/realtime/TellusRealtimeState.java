package com.yucareux.tellus.world.realtime;

import java.util.Objects;
import net.minecraft.util.Mth;
import net.minecraft.world.level.biome.Biome;

public final class TellusRealtimeState {
	public enum PrecipitationMode {
		CLEAR,
		RAIN,
		SNOW,
		THUNDER
	}

	private static final float SNOW_THRESHOLD = 0.35f;
	private static final float SNOW_JITTER = 0.15f;
	private static volatile boolean weatherEnabled;
	private static volatile boolean historicalSnowEnabled;
	private static volatile PrecipitationMode precipitationMode = PrecipitationMode.CLEAR;
	private static volatile SnowGrid snowGrid = SnowGrid.empty();

	private TellusRealtimeState() {
	}

	public static void updateWeatherState(
			boolean weatherEnabled,
			PrecipitationMode precipitationMode,
			boolean historicalSnowEnabled,
			SnowGrid snowGrid
	) {
		TellusRealtimeState.weatherEnabled = weatherEnabled;
		TellusRealtimeState.historicalSnowEnabled = historicalSnowEnabled;
		TellusRealtimeState.precipitationMode = Objects.requireNonNull(precipitationMode, "precipitationMode");
		TellusRealtimeState.snowGrid = snowGrid == null ? SnowGrid.empty() : snowGrid;
	}

	public static boolean isWeatherEnabled() {
		return weatherEnabled;
	}

	public static boolean isHistoricalSnowEnabled() {
		return historicalSnowEnabled;
	}

	public static PrecipitationMode precipitationMode() {
		return precipitationMode;
	}

	public static Biome.Precipitation precipitationOverride() {
		PrecipitationMode mode = precipitationMode;
		if (!weatherEnabled || mode == PrecipitationMode.CLEAR) {
			return null;
		}
		return mode == PrecipitationMode.SNOW ? Biome.Precipitation.SNOW : Biome.Precipitation.RAIN;
	}

	public static float sampleSnowCoverage(int blockX, int blockZ) {
		if (!historicalSnowEnabled) {
			return 0.0f;
		}
		SnowGrid grid = snowGrid;
		if (grid == null || grid.isEmpty()) {
			return 0.0f;
		}
		float base = grid.sample(blockX, blockZ);
		if (base <= 0.0f) {
			return 0.0f;
		}
		float jitter = (hashToUnit(blockX, blockZ) - 0.5f) * SNOW_JITTER;
		return Mth.clamp(base + jitter, 0.0f, 1.0f);
	}

	public static boolean shouldApplySnow(int blockX, int blockZ) {
		if (weatherEnabled && precipitationMode == PrecipitationMode.SNOW) {
			return true;
		}
		return sampleSnowCoverage(blockX, blockZ) >= SNOW_THRESHOLD;
	}

	private static float hashToUnit(int x, int z) {
		long seed = (long) x * 0x4F9939F508L + (long) z * 0x1EF1565BD5L;
		seed ^= (seed >>> 33);
		seed *= 0xff51afd7ed558ccdL;
		seed ^= (seed >>> 33);
		seed *= 0xc4ceb9fe1a85ec53L;
		seed ^= (seed >>> 33);
		return (seed & 0xFFFFFFL) / (float) 0xFFFFFFL;
	}
}
