package com.yucareux.tellus.world.data.elevation;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.yucareux.tellus.Tellus;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.Mth;
import org.jspecify.annotations.NonNull;

public final class TellusElevationSource {
	private static final double EQUATOR_CIRCUMFERENCE = 40075017.0;
	private static final int TILE_SIZE = 256;
	private static final int MIN_ZOOM = 0;
	private static final int LAND_MAX_ZOOM = 15;
	private static final int OCEAN_MAX_ZOOM = 10;
	private static final double MIN_LAT = -85.05112878;
	private static final double MAX_LAT = 85.05112878;
	private static final double MIN_LON = -180.0;
	private static final double MAX_LON = 180.0;
	private static final double RESOLUTION_METERS = 30.0;
	private static final double DOWNSAMPLE_START_PIXELS = 4.0;
	private static final int MAX_DOWNSAMPLE_STEP = 256;
	private static final String ENDPOINT = "https://s3.amazonaws.com/elevation-tiles-prod/terrarium";
	private static final int MAX_CACHE_TILES = intProperty("tellus.elevation.cacheTiles", 512);

	private final Path cacheRoot;
	private final LoadingCache<@NonNull TileKey, ShortRaster> cache;

	public TellusElevationSource() {
		this.cacheRoot = FabricLoader.getInstance().getGameDir().resolve("tellus/cache/elevation-tellus");
		this.cache = CacheBuilder.newBuilder()
				.maximumSize(MAX_CACHE_TILES)
				.build(new CacheLoader<@NonNull TileKey, ShortRaster>() {
					@Override
					public ShortRaster load(@NonNull TileKey key) throws Exception {
						return TellusElevationSource.this.loadTile(key);
					}
				});
	}

	public double sampleElevationMeters(double blockX, double blockZ, double worldScale) {
		return sampleElevationMeters(blockX, blockZ, worldScale, true);
	}

	public double sampleElevationMeters(double blockX, double blockZ, double worldScale, boolean highResOcean) {
		if (worldScale <= 0.0) {
			return 0.0;
		}

		int step = downsampleStep(worldScale, RESOLUTION_METERS);
		if (step > 1) {
			blockX = downsampleBlock(blockX, step);
			blockZ = downsampleBlock(blockZ, step);
		}

		int zoom = Mth.clamp(selectZoom(worldScale), MIN_ZOOM, LAND_MAX_ZOOM);
		double sample = sampleAtZoom(blockX, blockZ, worldScale, zoom);
		if (!Double.isNaN(sample)) {
			if (sample <= 0.0 && highResOcean) {
				double oceanSample = sampleAtZoom(blockX, blockZ, worldScale, OCEAN_MAX_ZOOM);
				if (!Double.isNaN(oceanSample)) {
					return oceanSample;
				}
			}
			return sample;
		}
		double oceanSample = sampleAtZoom(blockX, blockZ, worldScale, OCEAN_MAX_ZOOM);
		if (!Double.isNaN(oceanSample)) {
			return oceanSample;
		}
		return 0.0;
	}

	public void prefetchTiles(double blockX, double blockZ, double worldScale, int radius) {
		if (worldScale <= 0.0) {
			return;
		}
		int step = downsampleStep(worldScale, RESOLUTION_METERS);
		if (step > 1) {
			blockX = downsampleBlock(blockX, step);
			blockZ = downsampleBlock(blockZ, step);
		}

		int zoom = Mth.clamp(selectZoom(worldScale), MIN_ZOOM, LAND_MAX_ZOOM);
		TileKey center = tileKeyForBlock(blockX, blockZ, worldScale, zoom);
		if (center == null) {
			return;
		}
		int tilesPerAxis = 1 << zoom;
		int clampedRadius = Math.max(0, radius);
		int minX = Math.max(0, center.x() - clampedRadius);
		int maxX = Math.min(tilesPerAxis - 1, center.x() + clampedRadius);
		int minY = Math.max(0, center.y() - clampedRadius);
		int maxY = Math.min(tilesPerAxis - 1, center.y() + clampedRadius);
		for (int tileY = minY; tileY <= maxY; tileY++) {
			for (int tileX = minX; tileX <= maxX; tileX++) {
				prefetchTile(new TileKey(zoom, tileX, tileY));
			}
		}
	}

	private double sampleAtZoom(double blockX, double blockZ, double worldScale, int zoom) {
		double metersPerDegree = EQUATOR_CIRCUMFERENCE / 360.0;
		double blocksPerDegree = metersPerDegree / worldScale;
		double lon = blockX / blocksPerDegree;
		double lat = -blockZ / blocksPerDegree;
		if (lat < MIN_LAT || lat > MAX_LAT || lon < MIN_LON || lon > MAX_LON) {
			return Double.NaN;
		}

		double latRad = Math.toRadians(lat);
		double n = Math.pow(2.0, zoom);
		double x = (lon + 180.0) / 360.0 * n;
		double y = (1.0 - Math.log(Math.tan(latRad) + 1.0 / Math.cos(latRad)) / Math.PI) / 2.0 * n;
		if (x < 0.0 || y < 0.0 || x >= n || y >= n) {
			return Double.NaN;
		}

		int tileX = Mth.floor(x);
		int tileY = Mth.floor(y);

		ShortRaster raster = getTile(new TileKey(zoom, tileX, tileY));
		if (raster == null) {
			return Double.NaN;
		}

		double globalX = x * TILE_SIZE;
		double globalY = y * TILE_SIZE;
		return sampleBilinearAcrossTiles(zoom, globalX, globalY, tileX, tileY, raster);
	}

	private static int downsampleStep(double worldScale, double resolutionMeters) {
		if (worldScale <= 0.0 || resolutionMeters <= 0.0) {
			return 1;
		}
		double pixelsPerBlock = worldScale / resolutionMeters;
		if (pixelsPerBlock <= DOWNSAMPLE_START_PIXELS) {
			return 1;
		}
		int step = (int) Math.floor(pixelsPerBlock / DOWNSAMPLE_START_PIXELS);
		return Mth.clamp(step, 1, MAX_DOWNSAMPLE_STEP);
	}

	private static double downsampleBlock(double blockCoord, int step) {
		if (step <= 1) {
			return blockCoord;
		}
		int block = Mth.floor(blockCoord);
		int snapped = Math.floorDiv(block, step) * step;
		return snapped + step * 0.5;
	}

	private static TileKey tileKeyForBlock(double blockX, double blockZ, double worldScale, int zoom) {
		double metersPerDegree = EQUATOR_CIRCUMFERENCE / 360.0;
		double blocksPerDegree = metersPerDegree / worldScale;
		double lon = blockX / blocksPerDegree;
		double lat = -blockZ / blocksPerDegree;
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

	private void prefetchTile(@NonNull TileKey key) {
		if (this.cache.getIfPresent(key) != null) {
			return;
		}
		try {
			this.cache.get(key);
		} catch (Exception e) {
			Tellus.LOGGER.debug("Failed to prefetch elevation tile {}", key, e);
		}
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

	private ShortRaster getTile(@NonNull TileKey key) {
		try {
			return this.cache.get(key);
		} catch (Exception e) {
			Tellus.LOGGER.warn("Failed to load elevation tile {}", key, e);
			return null;
		}
	}

	private ShortRaster loadTile(@NonNull TileKey key) throws IOException {
		Path cachePath = this.cacheRoot.resolve(key.zoom() + "/" + key.x() + "/" + key.y() + ".png");
		if (Files.exists(cachePath)) {
			try (InputStream input = Files.newInputStream(cachePath)) {
				return readPngRaster(input);
			}
		}
		byte[] data = downloadTile(key);
		if (data == null) {
			return null;
		}

		cacheTile(cachePath, data);
		try (InputStream input = new ByteArrayInputStream(data)) {
			return readPngRaster(input);
		}
	}

	private byte[] downloadTile(TileKey key) throws IOException {
		URI uri = URI.create(String.format("%s/%d/%d/%d.png", ENDPOINT, key.zoom(), key.x(), key.y()));
		HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
		connection.setConnectTimeout(8000);
		connection.setReadTimeout(8000);
		connection.setRequestProperty("User-Agent", "Tellus/1.0 (Minecraft Mod)");
		if (connection.getResponseCode() == 404) {
			return null;
		}
		try (InputStream input = connection.getInputStream()) {
			return input.readAllBytes();
		}
	}

	private void cacheTile(Path cachePath, byte[] data) {
		try {
			Files.createDirectories(cachePath.getParent());
			Files.write(cachePath, data);
		} catch (IOException e) {
			Tellus.LOGGER.warn("Failed to cache elevation tile {}", cachePath, e);
		}
	}

	private double sampleBilinearAcrossTiles(
			int zoom,
			double globalX,
			double globalY,
			int baseTileX,
			int baseTileY,
			ShortRaster baseRaster
	) {
		int tilesPerAxis = 1 << zoom;
		int maxPixel = tilesPerAxis * TILE_SIZE - 1;
		double clampedX = Mth.clamp(globalX, 0.0, maxPixel);
		double clampedY = Mth.clamp(globalY, 0.0, maxPixel);
		int x0 = Mth.floor(clampedX);
		int y0 = Mth.floor(clampedY);
		int x1 = Math.min(x0 + 1, maxPixel);
		int y1 = Math.min(y0 + 1, maxPixel);

		double dx = clampedX - x0;
		double dy = clampedY - y0;

		double v00 = samplePixel(zoom, x0, y0, baseTileX, baseTileY, baseRaster);
		double v10 = samplePixel(zoom, x1, y0, baseTileX, baseTileY, baseRaster);
		double v01 = samplePixel(zoom, x0, y1, baseTileX, baseTileY, baseRaster);
		double v11 = samplePixel(zoom, x1, y1, baseTileX, baseTileY, baseRaster);
		if (Double.isNaN(v00) || Double.isNaN(v10) || Double.isNaN(v01) || Double.isNaN(v11)) {
			double localX = clampedX - baseTileX * TILE_SIZE;
			double localY = clampedY - baseTileY * TILE_SIZE;
			return sampleBilinearLocal(baseRaster, localX, localY);
		}

		double lerpX0 = Mth.lerp(dx, v00, v10);
		double lerpX1 = Mth.lerp(dx, v01, v11);
		return Mth.lerp(dy, lerpX0, lerpX1);
	}

	private double samplePixel(
			int zoom,
			int pixelX,
			int pixelY,
			int baseTileX,
			int baseTileY,
			ShortRaster baseRaster
	) {
		int tileX = Math.floorDiv(pixelX, TILE_SIZE);
		int tileY = Math.floorDiv(pixelY, TILE_SIZE);
		ShortRaster raster = (tileX == baseTileX && tileY == baseTileY)
				? baseRaster
				: getTile(new TileKey(zoom, tileX, tileY));
		if (raster == null) {
			return Double.NaN;
		}
		int localX = pixelX - tileX * TILE_SIZE;
		int localY = pixelY - tileY * TILE_SIZE;
		return raster.get(localX, localY);
	}

	private static double sampleBilinearLocal(ShortRaster raster, double x, double y) {
		int maxX = raster.width() - 1;
		int maxY = raster.height() - 1;
		int x0 = Mth.clamp(Mth.floor(x), 0, maxX);
		int y0 = Mth.clamp(Mth.floor(y), 0, maxY);
		int x1 = Math.min(x0 + 1, maxX);
		int y1 = Math.min(y0 + 1, maxY);

		double dx = x - x0;
		double dy = y - y0;

		double v00 = raster.get(x0, y0);
		double v10 = raster.get(x1, y0);
		double v01 = raster.get(x0, y1);
		double v11 = raster.get(x1, y1);

		double lerpX0 = Mth.lerp(dx, v00, v10);
		double lerpX1 = Mth.lerp(dx, v01, v11);
		return Mth.lerp(dy, lerpX0, lerpX1);
	}

	private static int selectZoom(double worldScale) {
		double zoom = zoomForScale(worldScale);
		return Math.max((int) Math.round(zoom), MIN_ZOOM);
	}

	private static double zoomForScale(double meters) {
		return Math.log(EQUATOR_CIRCUMFERENCE / (TILE_SIZE * meters)) / Math.log(2.0);
	}

	private static ShortRaster readPngRaster(InputStream input) throws IOException {
		BufferedImage image = ImageIO.read(input);
		if (image == null) {
			throw new IOException("Invalid tellus PNG tile");
		}

		int width = image.getWidth();
		int height = image.getHeight();
		ShortRaster raster = ShortRaster.create(width, height);

		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				int argb = image.getRGB(x, y);
				int red = (argb >> 16) & 0xFF;
				int green = (argb >> 8) & 0xFF;
				int blue = argb & 0xFF;
				double elevation = (red * 256.0 + green + blue / 256.0) - 32768.0;
				raster.set(x, y, (short) Math.round(elevation));
			}
		}

		return raster;
	}

	private record TileKey(int zoom, int x, int y) {
	}
}
