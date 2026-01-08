package com.yucareux.tellus.world.data.mask;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.yucareux.tellus.Tellus;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Objects;
import javax.imageio.ImageIO;
import net.minecraft.util.Mth;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

public final class TellusLandMaskSource {
	private static final double EQUATOR_CIRCUMFERENCE = 40075017.0;
	private static final int TILE_SIZE = 256;
	private static final double MIN_LAT = -85.05112878;
	private static final double MAX_LAT = 85.05112878;
	private static final double MIN_LON = -180.0;
	private static final double MAX_LON = 180.0;
	private static final String DEFAULT_BASE_URL =
			"https://github.com/Yucareux/Tellus-Land-Polygons/releases/download/v1.0.0/";
	private static final String PMTILES_NAME = "tellus_landmask.pmtiles";
	private static final int MAX_CACHE_TILES = intProperty("tellus.landmask.cacheTiles", 256);

	private final PmTilesReader reader;
	private final LoadingCache<TileKey, @Nullable LandMaskTile> cache;
	private final int minZoom;
	private final int maxZoom;
	private final boolean available;

	public TellusLandMaskSource() {
		String baseUrl = System.getProperty("tellus.landmask.baseUrl", DEFAULT_BASE_URL);
		this.reader = new PmTilesReader(normalizeBaseUrl(baseUrl) + PMTILES_NAME);
		int resolvedMin = 0;
		int resolvedMax = 0;
		boolean ok = false;
		try {
			PmTilesReader.PmTilesHeader header = this.reader.header();
			resolvedMin = header.minZoom();
			resolvedMax = header.maxZoom();
			ok = true;
		} catch (IOException e) {
			Tellus.LOGGER.warn("Land mask PMTiles unavailable, falling back to ESA only", e);
		}
		this.available = ok;
		this.minZoom = ok ? resolvedMin : 0;
		this.maxZoom = ok ? resolvedMax : 0;
		this.cache = CacheBuilder.newBuilder()
				.maximumSize(MAX_CACHE_TILES)
				.build(new CacheLoader<>() {
					@Override
					public @Nullable LandMaskTile load(TileKey key) throws Exception {
						return TellusLandMaskSource.this.loadTile(key);
					}
				});
	}

	public LandMaskSample sampleLandMask(double blockX, double blockZ, double worldScale) {
		if (!this.available || worldScale <= 0.0) {
			return LandMaskSample.unknown();
		}
		double metersPerDegree = EQUATOR_CIRCUMFERENCE / 360.0;
		double blocksPerDegree = metersPerDegree / worldScale;
		double lon = blockX / blocksPerDegree;
		double lat = -blockZ / blocksPerDegree;
		if (lat < MIN_LAT || lat > MAX_LAT || lon < MIN_LON || lon > MAX_LON) {
			return LandMaskSample.unknown();
		}

		int zoom = selectZoom(worldScale);
		TileKey key = tileKeyForLonLat(lon, lat, zoom);
		if (key == null) {
			return LandMaskSample.unknown();
		}
		LandMaskTile tile = getTile(key);
		if (tile == null) {
			return LandMaskSample.unknown();
		}

		if (tile.isEmpty()) {
			return LandMaskSample.known(false);
		}

		double latRad = Math.toRadians(lat);
		double n = Math.pow(2.0, zoom);
		double x = (lon + 180.0) / 360.0 * n;
		double y = (1.0 - Math.log(Math.tan(latRad) + 1.0 / Math.cos(latRad)) / Math.PI) / 2.0 * n;
		int tileX = Mth.floor(x);
		int tileY = Mth.floor(y);
		double localX = (x - tileX) * TILE_SIZE;
		double localY = (y - tileY) * TILE_SIZE;
		int px = Mth.clamp((int) localX, 0, tile.width() - 1);
		int py = Mth.clamp((int) localY, 0, tile.height() - 1);
		return LandMaskSample.known(tile.isLand(px, py));
	}

	public void prefetchTiles(double blockX, double blockZ, double worldScale, int radius) {
		if (!this.available || worldScale <= 0.0 || radius <= 0) {
			return;
		}
		int zoom = selectZoom(worldScale);
		TileKey center = tileKeyForBlock(blockX, blockZ, worldScale, zoom);
		if (center == null) {
			return;
		}
		int tilesPerAxis = 1 << zoom;
		int minX = Math.max(0, center.x() - radius);
		int maxX = Math.min(tilesPerAxis - 1, center.x() + radius);
		int minY = Math.max(0, center.y() - radius);
		int maxY = Math.min(tilesPerAxis - 1, center.y() + radius);
		for (int tileY = minY; tileY <= maxY; tileY++) {
			for (int tileX = minX; tileX <= maxX; tileX++) {
				getTile(new TileKey(zoom, tileX, tileY));
			}
		}
	}

	private LandMaskTile getTile(@NonNull TileKey key) {
		try {
			return this.cache.get(key);
		} catch (Exception e) {
			Tellus.LOGGER.debug("Failed to load land mask tile {}", key, e);
			return null;
		}
	}

	private LandMaskTile loadTile(TileKey key) throws IOException {
		TileKey resolvedKey = Objects.requireNonNull(key, "key");
		byte[] bytes = this.reader.getTileBytes(resolvedKey.zoom(), resolvedKey.x(), resolvedKey.y());
		if (bytes == null) {
			return LandMaskTile.empty();
		}
		BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
		if (image == null) {
			throw new IOException("Invalid land mask tile image");
		}
		int width = image.getWidth();
		int height = image.getHeight();
		byte[] mask = new byte[width * height];
		for (int y = 0; y < height; y++) {
			int row = y * width;
			for (int x = 0; x < width; x++) {
				int value = image.getRaster().getSample(x, y, 0);
				mask[row + x] = (byte) (value > 0 ? 1 : 0);
			}
		}
		return new LandMaskTile(width, height, mask, false);
	}

	private int selectZoom(double worldScale) {
		if (!this.available || worldScale <= 0.0) {
			return this.minZoom;
		}
		double raw = Math.log(EQUATOR_CIRCUMFERENCE / (TILE_SIZE * worldScale)) / Math.log(2.0);
		int zoom = (int) Math.round(raw);
		return Mth.clamp(zoom, this.minZoom, this.maxZoom);
	}

	private static TileKey tileKeyForBlock(double blockX, double blockZ, double worldScale, int zoom) {
		double metersPerDegree = EQUATOR_CIRCUMFERENCE / 360.0;
		double blocksPerDegree = metersPerDegree / worldScale;
		double lon = blockX / blocksPerDegree;
		double lat = -blockZ / blocksPerDegree;
		return tileKeyForLonLat(lon, lat, zoom);
	}

	private static TileKey tileKeyForLonLat(double lon, double lat, int zoom) {
		if (lat < MIN_LAT || lat > MAX_LAT || lon < MIN_LON || lon > MAX_LON) {
			return null;
		}
		double latRad = Math.toRadians(lat);
		double n = Math.pow(2.0, zoom);
		double x = (lon + 180.0) / 360.0 * n;
		double y = (1.0 - Math.log(Math.tan(latRad) + 1.0 / Math.cos(latRad)) / Math.PI) / 2.0 * n;
		if (x < 0.0 || y < 0.0 || x >= n || y >= n) {
			return null;
		}
		int tileX = Mth.floor(x);
		int tileY = Mth.floor(y);
		return new TileKey(zoom, tileX, tileY);
	}

	private static String normalizeBaseUrl(String baseUrl) {
		Objects.requireNonNull(baseUrl, "baseUrl");
		if (baseUrl.endsWith("/")) {
			return baseUrl;
		}
		return baseUrl + "/";
	}

	private static int intProperty(String key, int defaultValue) {
		String value = System.getProperty(key);
		if (value == null) {
			return defaultValue;
		}
		try {
			return Math.max(1, Integer.parseInt(value));
		} catch (NumberFormatException ignored) {
			return defaultValue;
		}
	}

	public record LandMaskSample(boolean known, boolean land) {
		public static LandMaskSample known(boolean land) {
			return new LandMaskSample(true, land);
		}

		public static LandMaskSample unknown() {
			return new LandMaskSample(false, false);
		}
	}

	private record TileKey(int zoom, int x, int y) {
	}

	private static final class LandMaskTile {
		private static final LandMaskTile EMPTY = new LandMaskTile(0, 0, new byte[0], true);

		private final int width;
		private final int height;
		private final byte[] mask;
		private final boolean empty;

		private LandMaskTile(int width, int height, byte[] mask, boolean empty) {
			this.width = width;
			this.height = height;
			this.mask = mask;
			this.empty = empty;
		}

		public static LandMaskTile empty() {
			return EMPTY;
		}

		public boolean isEmpty() {
			return this.empty;
		}

		public int width() {
			return this.width;
		}

		public int height() {
			return this.height;
		}

		public boolean isLand(int x, int y) {
			if (this.empty || this.mask.length == 0) {
				return false;
			}
			int index = y * this.width + x;
			if (index < 0 || index >= this.mask.length) {
				return false;
			}
			return this.mask[index] != 0;
		}
	}
}
