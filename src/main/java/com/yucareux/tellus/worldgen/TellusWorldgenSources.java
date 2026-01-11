package com.yucareux.tellus.worldgen;

import com.yucareux.tellus.world.data.cover.TellusLandCoverSource;
import com.yucareux.tellus.world.data.elevation.TellusElevationSource;
import com.yucareux.tellus.world.data.koppen.TellusKoppenSource;
import com.yucareux.tellus.world.data.mask.TellusLandMaskSource;
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
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NonNull;

final class TellusWorldgenSources {
	private static final TellusLandCoverSource LAND_COVER = new TellusLandCoverSource();
	private static final TellusElevationSource ELEVATION = new TellusElevationSource();
	private static final TellusKoppenSource KOPPEN = new TellusKoppenSource();
	private static final TellusLandMaskSource LAND_MASK = new TellusLandMaskSource();
	private static final boolean PREFETCH_ENABLED =
			Boolean.parseBoolean(System.getProperty("tellus.prefetch.enabled", "true"));
	private static final int LAND_COVER_PREFETCH_RADIUS =
			intProperty("tellus.prefetch.landcover.radius", 1);
	private static final int ELEVATION_PREFETCH_RADIUS =
			intProperty("tellus.prefetch.elevation.radius", 1);
	private static final int LAND_MASK_PREFETCH_RADIUS =
			intProperty("tellus.prefetch.landmask.radius", 1);
	private static final boolean WATER_PREFETCH_ENABLED =
			Boolean.parseBoolean(System.getProperty("tellus.prefetch.water.enabled", "true"));
	private static final int WATER_PREFETCH_RADIUS =
			intProperty("tellus.prefetch.water.radius", 1);
	private static final ExecutorService PREFETCH_EXECUTOR = createPrefetchExecutor();
	private static final ConcurrentMap<EarthGeneratorSettings, WaterSurfaceResolver> WATER_RESOLVERS =
			new ConcurrentHashMap<>();

	private TellusWorldgenSources() {
	}

	static TellusLandCoverSource landCover() {
		return LAND_COVER;
	}

	static TellusElevationSource elevation() {
		return ELEVATION;
	}

	static TellusKoppenSource koppen() {
		return KOPPEN;
	}

	static TellusLandMaskSource landMask() {
		return LAND_MASK;
	}

	static @NonNull WaterSurfaceResolver waterResolver(EarthGeneratorSettings settings) {
		Objects.requireNonNull(settings, "settings");
		WaterSurfaceResolver resolver = WATER_RESOLVERS.computeIfAbsent(
				settings,
				value -> new WaterSurfaceResolver(LAND_COVER, LAND_MASK, ELEVATION, value)
		);
		return Objects.requireNonNull(resolver, "waterResolver");
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
		if (LAND_MASK_PREFETCH_RADIUS > 0) {
			submitPrefetch(() -> LAND_MASK.prefetchTiles(centerX, centerZ, worldScale, LAND_MASK_PREFETCH_RADIUS));
		}
		if (WATER_PREFETCH_ENABLED && WATER_PREFETCH_RADIUS > 0) {
			submitPrefetch(() -> waterResolver(settings).prefetchRegionsForChunk(pos.x, pos.z, WATER_PREFETCH_RADIUS));
		}
	}

	private static void submitPrefetch(Runnable task) {
		try {
            assert PREFETCH_EXECUTOR != null;
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
		int queueSize = intProperty("tellus.prefetch.queue", 256);
		ThreadFactory factory = new ThreadFactory() {
			private final AtomicInteger index = new AtomicInteger();

			@Override
			public Thread newThread(@NotNull Runnable runnable) {
				Thread thread = new Thread(runnable, "tellus-prefetch-" + index.incrementAndGet());
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
		Integer maxOverride = intPropertyNullable("tellus.prefetch.threads.max");
		Integer minOverride = intPropertyNullable("tellus.prefetch.threads.min");
		Integer legacyThreads = intPropertyNullable("tellus.prefetch.threads");

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
		public void execute(@NotNull Runnable command) {
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
