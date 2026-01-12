package com.yucareux.tellus.world.realtime;

import com.yucareux.tellus.Tellus;
import com.yucareux.tellus.network.TellusWeatherPayload;
import com.yucareux.tellus.world.data.source.OpenMeteoClient;
import com.yucareux.tellus.world.data.source.OpenMeteoClient.WeatherPointData;
import com.yucareux.tellus.worldgen.EarthChunkGenerator;
import com.yucareux.tellus.worldgen.EarthGeneratorSettings;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkSource;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.gamerules.GameRules;

public final class TellusRealtimeManager {
	private static final long WEATHER_REFRESH_MS = 30L * 60L * 1000L;
	private static final long TIME_APPLY_TICK_INTERVAL = 20L;
	private static final long SNOW_QUEUE_REFRESH_TICKS = 100L;
	private static final int SNOW_CHUNKS_PER_TICK = 8;
	private static final int SNOW_MAX_RADIUS = 10;
	private static final int GRID_RADIUS = 1;
	private static final int GRID_SIZE = SnowGrid.GRID_SIZE;
	private static final int GRID_POINTS = SnowGrid.GRID_POINTS;

	private final OpenMeteoClient client = new OpenMeteoClient();
	private final ThreadFactory threadFactory;
	private volatile ExecutorService executor;
	private final AtomicBoolean requestInFlight = new AtomicBoolean(false);
	private final AtomicBoolean pendingInitialSnowPass = new AtomicBoolean(false);
	private final LongArrayFIFOQueue snowQueue = new LongArrayFIFOQueue();
	private final LongOpenHashSet snowQueued = new LongOpenHashSet();

	private long lastWeatherUpdateMs;
	private long lastTimeApplyTick;
	private long lastSnowQueueTick;
	private boolean timeOffsetReady;
	private GridAnchor lastAnchor;
	private int utcOffsetSeconds;
	private boolean cachedDaylightCycle;
	private boolean cachedWeatherCycle;
	private int cachedSleepPercentage = -1;
	private boolean timeRulesCaptured;
	private boolean weatherRulesCaptured;

	public TellusRealtimeManager() {
		this.threadFactory = runnable -> {
			Thread thread = new Thread(runnable, "tellus-realtime");
			thread.setDaemon(true);
			return thread;
		};
		this.executor = createExecutor();
	}

	public void shutdown() {
		ExecutorService exec = this.executor;
		if (exec != null) {
			exec.shutdownNow();
		}
	}

	public boolean hasTimeOffset() {
		return timeOffsetReady;
	}

	public int currentUtcOffsetSeconds() {
		return utcOffsetSeconds;
	}

	public void onPlayerJoin(MinecraftServer server, ServerPlayer player) {
		Level level = player.level();
		if (!(level instanceof ServerLevel serverLevel)) {
			return;
		}
		ChunkGenerator generator = serverLevel.getChunkSource().getGenerator();
		if (!(generator instanceof EarthChunkGenerator earthGenerator)) {
			return;
		}
		EarthGeneratorSettings settings = earthGenerator.settings();
		if (!settings.historicalSnow()) {
			return;
		}
		pendingInitialSnowPass.set(true);
		int spacingBlocks = computeGridSpacing(settings.worldScale());
		GridAnchor anchor = GridAnchor.from(player.blockPosition(), spacingBlocks);
		requestUpdate(server, earthGenerator, anchor, settings.realtimeWeather(), true, System.currentTimeMillis());
	}

	public void onServerTick(MinecraftServer server) {
		ServerLevel level = server.getLevel(Level.OVERWORLD);
		if (level == null) {
			return;
		}
		ChunkGenerator generator = level.getChunkSource().getGenerator();
		if (!(generator instanceof EarthChunkGenerator earthGenerator)) {
			return;
		}
		EarthGeneratorSettings settings = earthGenerator.settings();
		boolean enableTime = settings.realtimeTime();
		boolean enableWeather = settings.realtimeWeather();
		boolean enableSnow = settings.historicalSnow();

		if (!enableTime && !enableWeather && !enableSnow) {
			restoreRules(level, server);
			TellusRealtimeState.updateWeatherState(false, TellusRealtimeState.PrecipitationMode.CLEAR, false, SnowGrid.empty());
			timeOffsetReady = false;
			return;
		}
		if (!enableWeather && !enableSnow) {
			TellusRealtimeState.updateWeatherState(false, TellusRealtimeState.PrecipitationMode.CLEAR, false, SnowGrid.empty());
		}

		BlockPos samplePos = resolveSamplePosition(server);
		if (samplePos == null) {
			return;
		}

		if (enableTime) {
			applyTimeRules(level, server);
			int offsetSeconds = computeUtcOffsetSeconds(earthGenerator, samplePos);
			this.utcOffsetSeconds = offsetSeconds;
			this.timeOffsetReady = true;
			applyRealtimeTime(level, server, offsetSeconds);
		} else {
			restoreTimeRules(level, server);
			timeOffsetReady = false;
		}

		if (enableWeather) {
			applyWeatherRules(level, server);
			applyRealtimeWeather(level);
		} else {
			restoreWeatherRules(level, server);
		}

		long now = System.currentTimeMillis();
		int spacingBlocks = computeGridSpacing(settings.worldScale());
		GridAnchor anchor = GridAnchor.from(samplePos, spacingBlocks);
		boolean movedAnchor = lastAnchor == null || !lastAnchor.equals(anchor);

		boolean needsWeatherUpdate = enableWeather || enableSnow;
		if (needsWeatherUpdate && (movedAnchor || now - lastWeatherUpdateMs >= WEATHER_REFRESH_MS)) {
			requestUpdate(server, earthGenerator, anchor, enableWeather, enableSnow, now);
		}

		if (enableSnow || (enableWeather && TellusRealtimeState.precipitationMode() == TellusRealtimeState.PrecipitationMode.SNOW)) {
			tickSnowPlacement(server, level, earthGenerator);
		} else {
			clearSnowQueue();
		}
	}

	private void requestUpdate(
			MinecraftServer server,
			EarthChunkGenerator generator,
			GridAnchor anchor,
			boolean includeWeather,
			boolean includeSnow,
			long now
	) {
		if (!requestInFlight.compareAndSet(false, true)) {
			return;
		}
		List<BlockPos> samplePoints = new ArrayList<>();
		if (includeSnow) {
			for (int dz = -GRID_RADIUS; dz <= GRID_RADIUS; dz++) {
				for (int dx = -GRID_RADIUS; dx <= GRID_RADIUS; dx++) {
					int x = anchor.centerX() + dx * anchor.spacingBlocks();
					int z = anchor.centerZ() + dz * anchor.spacingBlocks();
					samplePoints.add(new BlockPos(x, 0, z));
				}
			}
		} else {
			samplePoints.add(new BlockPos(anchor.centerX(), 0, anchor.centerZ()));
		}

		ExecutorService exec = ensureExecutor();
		if (exec == null) {
			requestInFlight.set(false);
			return;
		}
		try {
			exec.execute(() -> {
				try {
					WeatherPointData[] points = new WeatherPointData[samplePoints.size()];
					for (int i = 0; i < samplePoints.size(); i++) {
						BlockPos pos = samplePoints.get(i);
						double lat = Mth.clamp(generator.latitudeFromBlock(pos.getZ()), -85.05112878, 85.05112878);
						double lon = Mth.clamp(generator.longitudeFromBlock(pos.getX()), -180.0, 180.0);
						points[i] = client.fetch(lat, lon);
					}
					server.execute(() -> applyUpdate(server, anchor, includeWeather, includeSnow, points, now));
				} catch (Exception ex) {
					Tellus.LOGGER.warn("Failed to fetch real-time weather data: {}", ex.getMessage());
					Tellus.LOGGER.debug("Real-time weather fetch failure", ex);
				} finally {
					requestInFlight.set(false);
				}
			});
		} catch (RejectedExecutionException rejected) {
			requestInFlight.set(false);
			Tellus.LOGGER.debug("Realtime weather executor rejected task (server stopping or restarting).", rejected);
		}
	}

	private ExecutorService createExecutor() {
		return Executors.newSingleThreadExecutor(threadFactory);
	}

	private ExecutorService ensureExecutor() {
		ExecutorService exec = this.executor;
		if (exec != null && !exec.isShutdown() && !exec.isTerminated()) {
			return exec;
		}
		synchronized (this) {
			exec = this.executor;
			if (exec == null || exec.isShutdown() || exec.isTerminated()) {
				exec = createExecutor();
				this.executor = exec;
				requestInFlight.set(false);
			}
		}
		return exec;
	}

	private void tickSnowPlacement(MinecraftServer server, ServerLevel level, EarthChunkGenerator generator) {
		long tick = server.getTickCount();
		if (tick - lastSnowQueueTick >= SNOW_QUEUE_REFRESH_TICKS || snowQueue.isEmpty()) {
			queueSnowChunks(server);
			lastSnowQueueTick = tick;
		}
		processSnowQueue(level, generator, SNOW_CHUNKS_PER_TICK);
	}

	private void queueSnowChunks(MinecraftServer server) {
		int radius = Math.min(server.getPlayerList().getViewDistance(), SNOW_MAX_RADIUS);
		if (radius <= 0) {
			return;
		}
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			ChunkPos center = player.chunkPosition();
			int baseX = center.x;
			int baseZ = center.z;
			for (int dz = -radius; dz <= radius; dz++) {
				for (int dx = -radius; dx <= radius; dx++) {
					long key = ChunkPos.asLong(baseX + dx, baseZ + dz);
					if (snowQueued.add(key)) {
						snowQueue.enqueue(key);
					}
				}
			}
		}
	}

	private void processSnowQueue(ServerLevel level, EarthChunkGenerator generator, int maxChunks) {
		ChunkSource source = level.getChunkSource();
		int processed = 0;
		while (processed < maxChunks && !snowQueue.isEmpty()) {
			long key = snowQueue.dequeueLong();
			snowQueued.remove(key);
			int chunkX = ChunkPos.getX(key);
			int chunkZ = ChunkPos.getZ(key);
			LevelChunk chunk = source.getChunkNow(chunkX, chunkZ);
			if (chunk == null) {
				continue;
			}
			generator.applyRealtimeSnowCover(level, chunk);
			processed++;
		}
	}

	private void clearSnowQueue() {
		snowQueue.clear();
		snowQueued.clear();
	}

	private void applyUpdate(
			MinecraftServer server,
			GridAnchor anchor,
			boolean includeWeather,
			boolean includeSnow,
			WeatherPointData[] points,
			long now
	) {
		if (points.length == 0) {
			return;
		}
		int centerIndex = includeSnow ? (GRID_RADIUS * GRID_SIZE + GRID_RADIUS) : 0;
		WeatherPointData centerPoint = points[Math.min(centerIndex, points.length - 1)];
		this.lastAnchor = anchor;
		if (includeWeather || includeSnow) {
			this.lastWeatherUpdateMs = now;
		}
		if (!timeOffsetReady) {
			this.utcOffsetSeconds = centerPoint.utcOffsetSeconds();
		}

		TellusRealtimeState.PrecipitationMode mode = TellusRealtimeState.PrecipitationMode.CLEAR;
		if (includeWeather) {
			mode = resolvePrecipitation(centerPoint);
		}

		SnowGrid grid = SnowGrid.empty();
		if (includeSnow) {
			float[] snowIndex = new float[GRID_POINTS];
			for (int i = 0; i < snowIndex.length && i < points.length; i++) {
				snowIndex[i] = points[i].snowIndex();
			}
			grid = new SnowGrid(anchor.centerX(), anchor.centerZ(), anchor.spacingBlocks(), snowIndex);
		}

		TellusRealtimeState.updateWeatherState(includeWeather, mode, includeSnow, grid);
		sendWeatherPayload(server, includeWeather, mode, includeSnow, grid);

		if (includeSnow && pendingInitialSnowPass.getAndSet(false)) {
			ServerLevel level = server.getLevel(Level.OVERWORLD);
			if (level == null) {
				return;
			}
			ChunkGenerator generator = level.getChunkSource().getGenerator();
			if (!(generator instanceof EarthChunkGenerator earthGenerator)) {
				return;
			}
			clearSnowQueue();
			queueSnowChunks(server);
			processSnowQueue(level, earthGenerator, Integer.MAX_VALUE);
		}
	}

	private void sendWeatherPayload(
			MinecraftServer server,
			boolean weatherEnabled,
			TellusRealtimeState.PrecipitationMode mode,
			boolean historicalSnow,
			SnowGrid grid
	) {
		TellusWeatherPayload payload = new TellusWeatherPayload(
				weatherEnabled,
				mode,
				historicalSnow,
				grid.centerX(),
				grid.centerZ(),
				grid.spacingBlocks(),
				grid.isEmpty() ? new float[GRID_POINTS] : gridSample(grid)
		);
		for (ServerPlayer rawPlayer : server.getPlayerList().getPlayers()) {
			ServerPlayer player = Objects.requireNonNull(rawPlayer, "player");
			ServerPlayNetworking.send(player, payload);
		}
	}

	private static float[] gridSample(SnowGrid grid) {
		float[] snowIndex = new float[GRID_POINTS];
		for (int i = 0; i < GRID_POINTS; i++) {
			snowIndex[i] = grid.isEmpty() ? 0.0f : gridSampleAtIndex(grid, i);
		}
		return snowIndex;
	}

	private static float gridSampleAtIndex(SnowGrid grid, int index) {
		int gx = index % GRID_SIZE;
		int gz = index / GRID_SIZE;
		int x = grid.centerX() + (gx - GRID_RADIUS) * grid.spacingBlocks();
		int z = grid.centerZ() + (gz - GRID_RADIUS) * grid.spacingBlocks();
		return grid.sample(x, z);
	}

	private static TellusRealtimeState.PrecipitationMode resolvePrecipitation(WeatherPointData data) {
		int code = data.weatherCode();
		float temp = data.temperatureC();
		float precipitation = data.precipitationMm();
		if (code >= 95) {
			return TellusRealtimeState.PrecipitationMode.THUNDER;
		}
		if (isSnowCode(code) || (temp <= 0.0f && precipitation > 0.0f)) {
			return TellusRealtimeState.PrecipitationMode.SNOW;
		}
		if (precipitation > 0.0f || isRainCode(code)) {
			return TellusRealtimeState.PrecipitationMode.RAIN;
		}
		return TellusRealtimeState.PrecipitationMode.CLEAR;
	}

	private static boolean isSnowCode(int code) {
		return (code >= 71 && code <= 77) || code == 85 || code == 86;
	}

	private static boolean isRainCode(int code) {
		return (code >= 51 && code <= 67) || (code >= 80 && code <= 82);
	}

	private void applyRealtimeTime(ServerLevel level, MinecraftServer server, int offsetSeconds) {
		long tickCount = server.getTickCount();
		if (tickCount - lastTimeApplyTick < TIME_APPLY_TICK_INTERVAL) {
			return;
		}
		lastTimeApplyTick = tickCount;
		long nowSeconds = System.currentTimeMillis() / 1000L;
		long localSeconds = nowSeconds + offsetSeconds;
		long daySeconds = Math.floorMod(localSeconds, 86400L);
		double hours = daySeconds / 3600.0;
		int tickOfDay = (int) Math.floor(((hours - 6.0 + 24.0) % 24.0) * 1000.0);
		long dayBase = level.getDayTime() / 24000L * 24000L;
		level.setDayTime(dayBase + tickOfDay);
	}

	private void applyRealtimeWeather(ServerLevel level) {
		TellusRealtimeState.PrecipitationMode mode = TellusRealtimeState.precipitationMode();
		boolean raining = mode == TellusRealtimeState.PrecipitationMode.RAIN
				|| mode == TellusRealtimeState.PrecipitationMode.SNOW
				|| mode == TellusRealtimeState.PrecipitationMode.THUNDER;
		boolean thundering = mode == TellusRealtimeState.PrecipitationMode.THUNDER;
		level.setWeatherParameters(0, 6000, raining, thundering);
	}

	private void applyTimeRules(ServerLevel level, MinecraftServer server) {
		GameRules rules = level.getGameRules();
		Boolean daylight = (Boolean) rules.get(GameRules.ADVANCE_TIME);
		Integer sleepingPercent = (Integer) rules.get(GameRules.PLAYERS_SLEEPING_PERCENTAGE);
		if (!timeRulesCaptured) {
			cachedDaylightCycle = daylight != null && daylight;
			cachedSleepPercentage = sleepingPercent == null ? -1 : sleepingPercent;
			timeRulesCaptured = true;
		}
		if (daylight == null || daylight) {
			rules.set(GameRules.ADVANCE_TIME, Boolean.FALSE, server);
		}
		if (sleepingPercent == null || sleepingPercent != 101) {
			rules.set(GameRules.PLAYERS_SLEEPING_PERCENTAGE, 101, server);
		}
	}

	private void applyWeatherRules(ServerLevel level, MinecraftServer server) {
		GameRules rules = level.getGameRules();
		Boolean weatherCycle = (Boolean) rules.get(GameRules.ADVANCE_WEATHER);
		if (!weatherRulesCaptured) {
			cachedWeatherCycle = weatherCycle != null && weatherCycle;
			weatherRulesCaptured = true;
		}
		if (weatherCycle == null || weatherCycle) {
			rules.set(GameRules.ADVANCE_WEATHER, Boolean.FALSE, server);
		}
	}

	private void restoreRules(ServerLevel level, MinecraftServer server) {
		restoreTimeRules(level, server);
		restoreWeatherRules(level, server);
	}

	private void restoreTimeRules(ServerLevel level, MinecraftServer server) {
		if (!timeRulesCaptured) {
			return;
		}
		GameRules rules = level.getGameRules();
		Boolean daylight = (Boolean) rules.get(GameRules.ADVANCE_TIME);
		Integer sleepingPercent = (Integer) rules.get(GameRules.PLAYERS_SLEEPING_PERCENTAGE);
		if (daylight == null || daylight != cachedDaylightCycle) {
			rules.set(GameRules.ADVANCE_TIME, cachedDaylightCycle, server);
		}
		if (cachedSleepPercentage >= 0 && (sleepingPercent == null || sleepingPercent != cachedSleepPercentage)) {
			rules.set(GameRules.PLAYERS_SLEEPING_PERCENTAGE, cachedSleepPercentage, server);
		}
		timeRulesCaptured = false;
	}

	private void restoreWeatherRules(ServerLevel level, MinecraftServer server) {
		if (!weatherRulesCaptured) {
			return;
		}
		GameRules rules = level.getGameRules();
		Boolean weatherCycle = (Boolean) rules.get(GameRules.ADVANCE_WEATHER);
		if (weatherCycle == null || weatherCycle != cachedWeatherCycle) {
			rules.set(GameRules.ADVANCE_WEATHER, cachedWeatherCycle, server);
		}
		weatherRulesCaptured = false;
	}

	private static int computeGridSpacing(double worldScale) {
		double targetMeters = 32_000.0;
		double spacing = targetMeters / Math.max(1.0, worldScale);
		int spacingBlocks = Mth.floor(spacing);
		return Mth.clamp(spacingBlocks, 1024, 16384);
	}

	private static BlockPos resolveSamplePosition(MinecraftServer server) {
		List<ServerPlayer> players = server.getPlayerList().getPlayers();
		if (players.isEmpty()) {
			return null;
		}
		for (ServerPlayer player : players) {
			if (player.level().dimension() == Level.OVERWORLD) {
				return player.blockPosition();
			}
		}
		return players.get(0).blockPosition();
	}

	private record GridAnchor(int centerX, int centerZ, int spacingBlocks) {
		private static GridAnchor from(BlockPos pos, int spacingBlocks) {
			int centerX = Math.floorDiv(pos.getX(), spacingBlocks) * spacingBlocks;
			int centerZ = Math.floorDiv(pos.getZ(), spacingBlocks) * spacingBlocks;
			return new GridAnchor(centerX, centerZ, spacingBlocks);
		}
	}

	private static int computeUtcOffsetSeconds(EarthChunkGenerator generator, BlockPos pos) {
		double longitude = Mth.clamp(generator.longitudeFromBlock(pos.getX()), -180.0, 180.0);
		double hours = longitude / 15.0;
		return (int) Math.round(hours * 3600.0);
	}
}
