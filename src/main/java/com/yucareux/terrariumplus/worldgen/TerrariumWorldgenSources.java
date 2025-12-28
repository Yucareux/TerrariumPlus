package com.yucareux.terrariumplus.worldgen;

import com.yucareux.terrariumplus.world.data.cover.TerrariumLandCoverSource;
import com.yucareux.terrariumplus.world.data.elevation.TerrariumElevationSource;
import com.yucareux.terrariumplus.world.data.koppen.TerrariumKoppenSource;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.world.level.ChunkPos;

final class TerrariumWorldgenSources {
	private static final TerrariumLandCoverSource LAND_COVER = new TerrariumLandCoverSource();
	private static final TerrariumElevationSource ELEVATION = new TerrariumElevationSource();
	private static final TerrariumKoppenSource KOPPEN = new TerrariumKoppenSource();
	private static final boolean PREFETCH_ENABLED =
			Boolean.parseBoolean(System.getProperty("terrariumplus.prefetch.enabled", "true"));
	private static final int LAND_COVER_PREFETCH_RADIUS =
			intProperty("terrariumplus.prefetch.landcover.radius", 1);
	private static final int ELEVATION_PREFETCH_RADIUS =
			intProperty("terrariumplus.prefetch.elevation.radius", 1);
	private static final boolean WATER_PREFETCH_ENABLED =
			Boolean.parseBoolean(System.getProperty("terrariumplus.prefetch.water.enabled", "true"));
	private static final int WATER_PREFETCH_RADIUS =
			intProperty("terrariumplus.prefetch.water.radius", 1);
	private static final ExecutorService PREFETCH_EXECUTOR = createPrefetchExecutor();
	private static final ConcurrentMap<EarthGeneratorSettings, WaterSurfaceResolver> WATER_RESOLVERS =
			new ConcurrentHashMap<>();

	private TerrariumWorldgenSources() {
	}

	static TerrariumLandCoverSource landCover() {
		return LAND_COVER;
	}

	static TerrariumElevationSource elevation() {
		return ELEVATION;
	}

	static TerrariumKoppenSource koppen() {
		return KOPPEN;
	}

	static WaterSurfaceResolver waterResolver(EarthGeneratorSettings settings) {
		Objects.requireNonNull(settings, "settings");
		return WATER_RESOLVERS.computeIfAbsent(
				settings,
				value -> new WaterSurfaceResolver(LAND_COVER, ELEVATION, value)
		);
	}

	static void prefetchForChunk(ChunkPos pos, EarthGeneratorSettings settings) {
		if (!PREFETCH_ENABLED || PREFETCH_EXECUTOR == null) {
			return;
		}
		int centerX = pos.getMinBlockX() + 8;
		int centerZ = pos.getMinBlockZ() + 8;
		double worldScale = settings.worldScale();
		if (LAND_COVER_PREFETCH_RADIUS > 0) {
			submitPrefetch(() -> LAND_COVER.prefetchTiles(centerX, centerZ, worldScale, LAND_COVER_PREFETCH_RADIUS));
		}
		if (ELEVATION_PREFETCH_RADIUS > 0) {
			submitPrefetch(() -> ELEVATION.prefetchTiles(centerX, centerZ, worldScale, ELEVATION_PREFETCH_RADIUS));
		}
		if (WATER_PREFETCH_ENABLED && WATER_PREFETCH_RADIUS > 0) {
			submitPrefetch(() -> waterResolver(settings).prefetchRegionsForChunk(pos.x, pos.z, WATER_PREFETCH_RADIUS));
		}
	}

	private static void submitPrefetch(Runnable task) {
		try {
			PREFETCH_EXECUTOR.execute(task);
		} catch (RuntimeException ignored) {
			// Prefetch is best-effort; ignore rejections.
		}
	}

	private static ExecutorService createPrefetchExecutor() {
		if (!PREFETCH_ENABLED) {
			return null;
		}
		ThreadBounds bounds = resolveThreadBounds();
		int minThreads = bounds.min();
		int maxThreads = bounds.max();
		int queueSize = intProperty("terrariumplus.prefetch.queue", 256);
		ThreadFactory factory = new ThreadFactory() {
			private final AtomicInteger index = new AtomicInteger();

			@Override
			public Thread newThread(Runnable runnable) {
				Thread thread = new Thread(runnable, "terrariumplus-prefetch-" + index.incrementAndGet());
				thread.setDaemon(true);
				return thread;
			}
		};
		AdaptiveThreadPoolExecutor executor = new AdaptiveThreadPoolExecutor(
				Math.max(1, minThreads),
				Math.max(1, maxThreads),
				30L,
				TimeUnit.SECONDS,
				new ArrayBlockingQueue<>(Math.max(1, queueSize)),
				factory,
				new ThreadPoolExecutor.DiscardPolicy()
		);
		executor.allowCoreThreadTimeOut(true);
		return executor;
	}

	private static ThreadBounds resolveThreadBounds() {
		Integer maxOverride = intPropertyNullable("terrariumplus.prefetch.threads.max");
		Integer minOverride = intPropertyNullable("terrariumplus.prefetch.threads.min");
		Integer legacyThreads = intPropertyNullable("terrariumplus.prefetch.threads");

		int maxThreads;
		if (maxOverride != null) {
			maxThreads = maxOverride;
		} else if (legacyThreads != null) {
			maxThreads = legacyThreads;
		} else {
			int cores = Math.max(1, Runtime.getRuntime().availableProcessors());
			maxThreads = Math.min(8, Math.max(2, cores * 2));
		}

		int minThreads = minOverride != null ? minOverride : Math.min(2, maxThreads);
		minThreads = Math.max(1, Math.min(minThreads, maxThreads));
		maxThreads = Math.max(1, maxThreads);
		return new ThreadBounds(minThreads, maxThreads);
	}

	private static int intProperty(String key, int defaultValue) {
		String value = System.getProperty(key);
		if (value == null) {
			return defaultValue;
		}
		try {
			return Math.max(0, Integer.parseInt(value));
		} catch (NumberFormatException ignored) {
			return defaultValue;
		}
	}

	private static Integer intPropertyNullable(String key) {
		String value = System.getProperty(key);
		if (value == null) {
			return null;
		}
		try {
			return Integer.parseInt(value);
		} catch (NumberFormatException ignored) {
			return null;
		}
	}

	private record ThreadBounds(int min, int max) {
	}

	private static final class AdaptiveThreadPoolExecutor extends ThreadPoolExecutor {
		private final int minThreads;
		private final int maxThreads;

		private AdaptiveThreadPoolExecutor(
				int minThreads,
				int maxThreads,
				long keepAliveTime,
				TimeUnit unit,
				ArrayBlockingQueue<Runnable> workQueue,
				ThreadFactory threadFactory,
				RejectedExecutionHandler handler
		) {
			super(minThreads, maxThreads, keepAliveTime, unit, workQueue, threadFactory, handler);
			this.minThreads = minThreads;
			this.maxThreads = maxThreads;
		}

		@Override
		public void execute(Runnable command) {
			maybeAdjustCore();
			super.execute(command);
		}

		private void maybeAdjustCore() {
			int queueSize = getQueue().size();
			int active = getActiveCount();
			int core = getCorePoolSize();
			if (queueSize > active * 2 && core < maxThreads) {
				int nextCore = Math.min(maxThreads, core + 1);
				setCorePoolSize(nextCore);
				prestartCoreThread();
				return;
			}
			if (queueSize == 0 && active <= minThreads && core > minThreads) {
				setCorePoolSize(minThreads);
			}
		}
	}
}
