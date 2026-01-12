package com.yucareux.tellus.world.data.cover;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.yucareux.tellus.Tellus;
import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.zip.InflaterInputStream;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.Mth;

public final class TellusLandCoverSource {
	private static final double EQUATOR_CIRCUMFERENCE = 40075017.0;
	private static final double MIN_LAT = -60.0;
	private static final double MAX_LAT = 84.0;
	private static final double MIN_LON = -180.0;
	private static final double MAX_LON = 180.0;
	private static final int TILE_DEGREES = 3;
	private static final int SNOW_ICE_CLASS = 70;
	private static final int WATER_CLASS = 80;
	private static final int NO_DATA_CLASS = 0;
	private static final int MAX_CACHE_TILES = intProperty("tellus.landcover.cacheTiles", 64);
	private static final double RESOLUTION_METERS = 10.0;
	private static final double DOWNSAMPLE_START_PIXELS = 4.0;
	private static final int MAX_DOWNSAMPLE_STEP = 256;
	private static final int TILE_CACHE_ENTRIES = intProperty("tellus.landcover.tileCacheEntries", 32);
	private static final int SMOOTH_RADIUS_PIXELS = 1;
	private static final ThreadLocal<CoverSmoothScratch> COVER_SMOOTH_SCRATCH =
			ThreadLocal.withInitial(CoverSmoothScratch::new);

	private static final String ENDPOINT = "https://esa-worldcover.s3.eu-central-1.amazonaws.com/v200/2021/map";
	private static final String TILE_PATTERN = "ESA_WorldCover_10m_2021_v200_%s_Map.tif";

	private final Path cacheRoot;
	private final LoadingCache<TileKey, GeoTiffTile> cache;

	public TellusLandCoverSource() {
		this.cacheRoot = FabricLoader.getInstance().getGameDir().resolve("tellus/cache/worldcover2021");
		this.cache = CacheBuilder.newBuilder()
				.maximumSize(MAX_CACHE_TILES)
				.removalListener(notification -> {
					GeoTiffTile tile = (GeoTiffTile) notification.getValue();
					if (tile != null) {
						tile.close();
					}
				})
				.build(new CacheLoader<>() {
					@Override
					public GeoTiffTile load(TileKey key) throws Exception {
						return TellusLandCoverSource.this.loadTile(key);
					}
				});
	}

	public boolean isSnowIce(double blockX, double blockZ, double worldScale) {
		return sampleCoverClass(blockX, blockZ, worldScale) == SNOW_ICE_CLASS;
	}

	public int sampleCoverClass(double blockX, double blockZ, double worldScale) {
		if (worldScale <= 0.0) {
			return 0;
		}
		int step = downsampleStep(worldScale, RESOLUTION_METERS);
		if (step > 1) {
			blockX = downsampleBlock(blockX, step);
			blockZ = downsampleBlock(blockZ, step);
		}

		double metersPerDegree = EQUATOR_CIRCUMFERENCE / 360.0;
		double blocksPerDegree = metersPerDegree / worldScale;
		double lon = blockX / blocksPerDegree;
		double lat = -blockZ / blocksPerDegree;

		return sampleCoverClassAtLonLat(lon, lat);
	}

	public int sampleSmoothedCoverClass(double blockX, double blockZ, double worldScale) {
		if (worldScale <= 0.0) {
			return 0;
		}
		int step = downsampleStep(worldScale, RESOLUTION_METERS);
		if (step > 1) {
			blockX = downsampleBlock(blockX, step);
			blockZ = downsampleBlock(blockZ, step);
		}

		double metersPerDegree = EQUATOR_CIRCUMFERENCE / 360.0;
		double blocksPerDegree = metersPerDegree / worldScale;
		double lon = blockX / blocksPerDegree;
		double lat = -blockZ / blocksPerDegree;
		return sampleSmoothedCoverClassAtLonLat(lon, lat, SMOOTH_RADIUS_PIXELS);
	}

	private int sampleCoverClassAtLonLat(double lon, double lat) {
		TileKey key = tileKeyForLonLat(lon, lat);
		if (key == null) {
			return 0;
		}
		GeoTiffTile tile = getTile(key);
		return tile.sample(lon, lat);
	}

	private int sampleSmoothedCoverClassAtLonLat(double lon, double lat, int radiusPixels) {
		TileKey key = tileKeyForLonLat(lon, lat);
		if (key == null) {
			return 0;
		}
		GeoTiffTile tile = getTile(key);
		Pixel center = tile.toPixel(lon, lat);
		if (center == null) {
			return 0;
		}
		int centerValue = tile.sampleValue(center.x(), center.y());
		if (radiusPixels <= 0 || centerValue == WATER_CLASS || centerValue == NO_DATA_CLASS) {
			return centerValue;
		}

		CoverSmoothScratch scratch = COVER_SMOOTH_SCRATCH.get();
		scratch.reset();

		if (tile.isNeighborhoodInBounds(center.x(), center.y(), radiusPixels)) {
			for (int dy = -radiusPixels; dy <= radiusPixels; dy++) {
				int py = center.y() + dy;
				for (int dx = -radiusPixels; dx <= radiusPixels; dx++) {
					int px = center.x() + dx;
					int value = tile.sampleValue(px, py);
					if (value == WATER_CLASS || value == NO_DATA_CLASS) {
						continue;
					}
					scratch.add(value);
				}
			}
		} else {
			for (int dy = -radiusPixels; dy <= radiusPixels; dy++) {
				int py = center.y() + dy;
				for (int dx = -radiusPixels; dx <= radiusPixels; dx++) {
					int px = center.x() + dx;
					int value;
					if (tile.isInside(px, py)) {
						value = tile.sampleValue(px, py);
					} else {
						double neighborLon = tile.lonForPixel(px);
						double neighborLat = tile.latForPixel(py);
						value = sampleCoverClassAtLonLat(neighborLon, neighborLat);
					}
					if (value == WATER_CLASS || value == NO_DATA_CLASS) {
						continue;
					}
					scratch.add(value);
				}
			}
		}

		return scratch.pickMajority(centerValue);
	}

	public void prefetchTiles(double blockX, double blockZ, double worldScale, int radius) {
		TileKey center = tileKeyForBlock(blockX, blockZ, worldScale);
		if (center == null) {
			return;
		}
		int clampedRadius = Math.max(0, radius);
		for (int dz = -clampedRadius; dz <= clampedRadius; dz++) {
			int lat = center.lat() + dz * TILE_DEGREES;
			if (lat < MIN_LAT || lat > MAX_LAT) {
				continue;
			}
			for (int dx = -clampedRadius; dx <= clampedRadius; dx++) {
				int lon = center.lon() + dx * TILE_DEGREES;
				if (lon < MIN_LON || lon > MAX_LON) {
					continue;
				}
				prefetchTile(new TileKey(lat, lon));
			}
		}
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

	private static TileKey tileKeyForBlock(double blockX, double blockZ, double worldScale) {
		if (worldScale <= 0.0) {
			return null;
		}
		int step = downsampleStep(worldScale, RESOLUTION_METERS);
		if (step > 1) {
			blockX = downsampleBlock(blockX, step);
			blockZ = downsampleBlock(blockZ, step);
		}

		double metersPerDegree = EQUATOR_CIRCUMFERENCE / 360.0;
		double blocksPerDegree = metersPerDegree / worldScale;
		double lon = blockX / blocksPerDegree;
		double lat = -blockZ / blocksPerDegree;
		return tileKeyForLonLat(lon, lat);
	}

	private static TileKey tileKeyForLonLat(double lon, double lat) {
		if (lat < MIN_LAT || lat > MAX_LAT || lon < MIN_LON || lon > MAX_LON) {
			return null;
		}
		int tileLat = (int) Math.floor(lat / TILE_DEGREES) * TILE_DEGREES;
		int tileLon = (int) Math.floor(lon / TILE_DEGREES) * TILE_DEGREES;
		return new TileKey(tileLat, tileLon);
	}

	private void prefetchTile(@org.jspecify.annotations.NonNull TileKey key) {
		if (this.cache.getIfPresent(key) != null) {
			return;
		}
		try {
			this.cache.get(key);
		} catch (Exception e) {
			Tellus.LOGGER.debug("Failed to prefetch land cover tile {}", key, e);
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

	private GeoTiffTile getTile(TileKey key) {
		try {
			return this.cache.get(key);
		} catch (Exception e) {
			Tellus.LOGGER.warn("Failed to load land cover tile {}", key, e);
			return GeoTiffTile.MISSING;
		}
	}

	private GeoTiffTile loadTile(TileKey key) throws IOException {
		Path cachePath = this.cacheRoot.resolve(key.fileName());
		if (Files.exists(cachePath)) {
			return GeoTiffTile.open(cachePath);
		}
		byte[] data = downloadTile(key);
		if (data == null) {
			return GeoTiffTile.MISSING;
		}

		cacheTile(cachePath, data);
		return GeoTiffTile.open(cachePath);
	}

	private byte[] downloadTile(TileKey key) throws IOException {
		URI uri = URI.create(String.format("%s/%s", ENDPOINT, key.fileName()));
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
			Path tempPath = cachePath.resolveSibling(cachePath.getFileName() + ".tmp");
			Files.write(tempPath, data);
			Files.move(tempPath, cachePath, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			Tellus.LOGGER.warn("Failed to cache land cover tile {}", cachePath, e);
		}
	}

	private record TileKey(int lat, int lon) {
		String fileName() {
			return String.format(Locale.ROOT, TILE_PATTERN, formatLatLon(this.lat, this.lon));
		}

		private static String formatLatLon(int lat, int lon) {
			char latPrefix = lat >= 0 ? 'N' : 'S';
			char lonPrefix = lon >= 0 ? 'E' : 'W';
			int latAbs = Math.abs(lat);
			int lonAbs = Math.abs(lon);
			return String.format(Locale.ROOT, "%c%02d%c%03d", latPrefix, latAbs, lonPrefix, lonAbs);
		}
	}

	private static final class GeoTiffTile {
		private static final int TAG_IMAGE_WIDTH = 256;
		private static final int TAG_IMAGE_HEIGHT = 257;
		private static final int TAG_TILE_WIDTH = 322;
		private static final int TAG_TILE_HEIGHT = 323;
		private static final int TAG_TILE_OFFSETS = 324;
		private static final int TAG_TILE_BYTE_COUNTS = 325;
		private static final int TAG_COMPRESSION = 259;
		private static final int TAG_MODEL_PIXEL_SCALE = 33550;
		private static final int TAG_MODEL_TIEPOINT = 33922;

		private static final int TYPE_SHORT = 3;
		private static final int TYPE_LONG = 4;

		private static final int COMPRESSION_DEFLATE = 8;
		private static final GeoTiffTile MISSING = new GeoTiffTile();

		private final Path path;
		private final FileChannel channel;
		private final int width;
		private final int height;
		private final int tileWidth;
		private final int tileHeight;
		private final int tilesPerRow;
		private final long[] tileOffsets;
		private final int[] tileByteCounts;
		private final double pixelScaleX;
		private final double pixelScaleY;
		private final double tieLon;
		private final double tieLat;
		private final Map<Integer, byte[]> tileCache;

		private GeoTiffTile() {
			this.path = null;
			this.channel = null;
			this.width = 0;
			this.height = 0;
			this.tileWidth = 0;
			this.tileHeight = 0;
			this.tilesPerRow = 0;
			this.tileOffsets = null;
			this.tileByteCounts = null;
			this.pixelScaleX = 0.0;
			this.pixelScaleY = 0.0;
			this.tieLon = 0.0;
			this.tieLat = 0.0;
			this.tileCache = Map.of();
		}

		private GeoTiffTile(
				Path path,
				FileChannel channel,
				int width,
				int height,
				int tileWidth,
				int tileHeight,
				long[] tileOffsets,
				int[] tileByteCounts,
				double pixelScaleX,
				double pixelScaleY,
				double tieLon,
				double tieLat
		) {
			this.path = path;
			this.channel = channel;
			this.width = width;
			this.height = height;
			this.tileWidth = tileWidth;
			this.tileHeight = tileHeight;
			this.tilesPerRow = (int) Math.ceil(width / (double) tileWidth);
			this.tileOffsets = tileOffsets;
			this.tileByteCounts = tileByteCounts;
			this.pixelScaleX = pixelScaleX;
			this.pixelScaleY = pixelScaleY;
			this.tieLon = tieLon;
			this.tieLat = tieLat;
			this.tileCache = new LinkedHashMap<>(TILE_CACHE_ENTRIES, 0.75f, true) {
				@Override
				protected boolean removeEldestEntry(Map.Entry<Integer, byte[]> eldest) {
					return size() > TILE_CACHE_ENTRIES;
				}
			};
		}

		static GeoTiffTile open(Path path) throws IOException {
			FileChannel channel = FileChannel.open(path, StandardOpenOption.READ);
			try {
				return readFromChannel(path, channel);
			} catch (IOException e) {
				channel.close();
				throw e;
			}
		}

		int sample(double lon, double lat) {
			Pixel pixel = toPixel(lon, lat);
			if (pixel == null) {
				return 0;
			}
			return sampleValue(pixel.x, pixel.y);
		}

		Pixel toPixel(double lon, double lat) {
			if (this == MISSING) {
				return null;
			}
			int pixelX = (int) Math.floor((lon - this.tieLon) / this.pixelScaleX);
			int pixelY = (int) Math.floor((this.tieLat - lat) / this.pixelScaleY);
			if (pixelX < 0 || pixelY < 0 || pixelX >= this.width || pixelY >= this.height) {
				return null;
			}
			return new Pixel(pixelX, pixelY);
		}

		int sampleValue(int pixelX, int pixelY) {
			if (this == MISSING || pixelX < 0 || pixelY < 0 || pixelX >= this.width || pixelY >= this.height) {
				return 0;
			}
			int tileX = pixelX / this.tileWidth;
			int tileY = pixelY / this.tileHeight;
			int tileIndex = tileY * this.tilesPerRow + tileX;

			byte[] tile;
			try {
				tile = getTile(tileIndex);
			} catch (ClosedByInterruptException e) {
				Thread.currentThread().interrupt();
				return 0;
			} catch (IOException e) {
				Tellus.LOGGER.warn("Failed to read land cover tile {} in {}", tileIndex, this.path, e);
				return 0;
			}

			int localX = pixelX - tileX * this.tileWidth;
			int localY = pixelY - tileY * this.tileHeight;
			return Byte.toUnsignedInt(tile[localX + localY * this.tileWidth]);
		}

		boolean isInside(int pixelX, int pixelY) {
			return pixelX >= 0 && pixelY >= 0 && pixelX < this.width && pixelY < this.height;
		}

		boolean isNeighborhoodInBounds(int pixelX, int pixelY, int radius) {
			return pixelX - radius >= 0
					&& pixelY - radius >= 0
					&& pixelX + radius < this.width
					&& pixelY + radius < this.height;
		}

		double lonForPixel(int pixelX) {
			return this.tieLon + (pixelX + 0.5) * this.pixelScaleX;
		}

		double latForPixel(int pixelY) {
			return this.tieLat - (pixelY + 0.5) * this.pixelScaleY;
		}

		void close() {
			if (this.channel == null) {
				return;
			}
			try {
				this.channel.close();
			} catch (IOException e) {
				Tellus.LOGGER.warn("Failed to close land cover tile {}", this.path, e);
			}
		}

		private byte[] getTile(int tileIndex) throws IOException {
			synchronized (this.tileCache) {
				byte[] cached = this.tileCache.get(tileIndex);
				if (cached != null) {
					return cached;
				}
			}

			byte[] tile = readTile(tileIndex);
			synchronized (this.tileCache) {
				this.tileCache.put(tileIndex, tile);
			}
			return tile;
		}

		private byte[] readTile(int tileIndex) throws IOException {
			long offset = this.tileOffsets[tileIndex];
			int length = this.tileByteCounts[tileIndex];
			byte[] compressed = new byte[length];
			try {
				readFully(this.channel, compressed, offset);
			} catch (ClosedChannelException e) {
				if (this.path == null) {
					throw e;
				}
				try (FileChannel reopened = FileChannel.open(this.path, StandardOpenOption.READ)) {
					readFully(reopened, compressed, offset);
				}
			}
			return inflate(compressed, this.tileWidth * this.tileHeight);
		}

		private static GeoTiffTile readFromChannel(Path path, FileChannel channel) throws IOException {
			ByteBuffer header = ByteBuffer.allocate(8);
			readFully(channel, header, 0);
			header.flip();

			short order = header.getShort();
			ByteOrder byteOrder = switch (order) {
				case 0x4949 -> ByteOrder.LITTLE_ENDIAN;
				case 0x4D4D -> ByteOrder.BIG_ENDIAN;
				default -> throw new IOException("Invalid TIFF byte order");
			};
			header.order(byteOrder);

			short magic = header.getShort();
			if (magic != 42) {
				throw new IOException("Invalid TIFF magic");
			}

			int ifdOffset = header.getInt();
			ByteBuffer countBuffer = ByteBuffer.allocate(2).order(byteOrder);
			readFully(channel, countBuffer, ifdOffset);
			countBuffer.flip();
			int entryCount = Short.toUnsignedInt(countBuffer.getShort());

			ByteBuffer entries = ByteBuffer.allocate(entryCount * 12).order(byteOrder);
			readFully(channel, entries, ifdOffset + 2L);
			entries.flip();

			int width = -1;
			int height = -1;
			int tileWidth = -1;
			int tileHeight = -1;
			int compression = -1;
			long[] tileOffsets = null;
			int[] tileByteCounts = null;
			double[] pixelScale = null;
			double[] tiepoint = null;

			for (int i = 0; i < entryCount; i++) {
				int tag = Short.toUnsignedInt(entries.getShort());
				int type = Short.toUnsignedInt(entries.getShort());
				int count = entries.getInt();
				int value = entries.getInt();
				switch (tag) {
					case TAG_IMAGE_WIDTH -> width = readIntValue(type, count, value, byteOrder);
					case TAG_IMAGE_HEIGHT -> height = readIntValue(type, count, value, byteOrder);
					case TAG_TILE_WIDTH -> tileWidth = readIntValue(type, count, value, byteOrder);
					case TAG_TILE_HEIGHT -> tileHeight = readIntValue(type, count, value, byteOrder);
					case TAG_COMPRESSION -> compression = readIntValue(type, count, value, byteOrder);
					case TAG_TILE_OFFSETS -> tileOffsets = readLongArray(channel, value, count, byteOrder);
					case TAG_TILE_BYTE_COUNTS -> tileByteCounts = readIntArray(channel, value, count, byteOrder);
					case TAG_MODEL_PIXEL_SCALE -> pixelScale = readDoubleArray(channel, value, count, byteOrder);
					case TAG_MODEL_TIEPOINT -> tiepoint = readDoubleArray(channel, value, count, byteOrder);
					default -> {
					}
				}
			}

			if (compression != COMPRESSION_DEFLATE) {
				throw new IOException("Unsupported TIFF compression " + compression);
			}
			if (width <= 0 || height <= 0 || tileWidth <= 0 || tileHeight <= 0) {
				throw new IOException("Missing TIFF size tags");
			}
			if (tileOffsets == null || tileByteCounts == null) {
				throw new IOException("Missing TIFF tile offsets");
			}
			if (pixelScale == null || pixelScale.length < 2 || tiepoint == null || tiepoint.length < 5) {
				throw new IOException("Missing TIFF georeference tags");
			}

			return new GeoTiffTile(
					path,
					channel,
					width,
					height,
					tileWidth,
					tileHeight,
					tileOffsets,
					tileByteCounts,
					pixelScale[0],
					pixelScale[1],
					tiepoint[3],
					tiepoint[4]
			);
		}

		private static int readIntValue(int type, int count, int value, ByteOrder order) throws IOException {
			if (count != 1) {
				throw new IOException("Expected single TIFF value");
			}
			ByteBuffer buffer = ByteBuffer.allocate(4).order(order);
			buffer.putInt(value);
			buffer.flip();
			if (type == TYPE_SHORT) {
				return Short.toUnsignedInt(buffer.getShort());
			}
			if (type == TYPE_LONG) {
				return buffer.getInt();
			}
			throw new IOException("Unsupported TIFF value type " + type);
		}

		private static long[] readLongArray(FileChannel channel, long offset, int count, ByteOrder order) throws IOException {
			if (count <= 0) {
				return new long[0];
			}
			ByteBuffer buffer = ByteBuffer.allocate(count * 4).order(order);
			readFully(channel, buffer, offset);
			buffer.flip();
			long[] values = new long[count];
			for (int i = 0; i < count; i++) {
				values[i] = Integer.toUnsignedLong(buffer.getInt());
			}
			return values;
		}

		private static int[] readIntArray(FileChannel channel, long offset, int count, ByteOrder order) throws IOException {
			if (count <= 0) {
				return new int[0];
			}
			ByteBuffer buffer = ByteBuffer.allocate(count * 4).order(order);
			readFully(channel, buffer, offset);
			buffer.flip();
			int[] values = new int[count];
			for (int i = 0; i < count; i++) {
				values[i] = buffer.getInt();
			}
			return values;
		}

		private static double[] readDoubleArray(FileChannel channel, long offset, int count, ByteOrder order) throws IOException {
			if (count <= 0) {
				return new double[0];
			}
			ByteBuffer buffer = ByteBuffer.allocate(count * 8).order(order);
			readFully(channel, buffer, offset);
			buffer.flip();
			double[] values = new double[count];
			for (int i = 0; i < count; i++) {
				values[i] = buffer.getDouble();
			}
			return values;
		}

		private static void readFully(FileChannel channel, ByteBuffer buffer, long offset) throws IOException {
			long position = offset;
			while (buffer.hasRemaining()) {
				int read = channel.read(buffer, position);
				if (read < 0) {
					throw new EOFException("Unexpected end of file");
				}
				position += read;
			}
		}

		private static void readFully(FileChannel channel, byte[] dest, long offset) throws IOException {
			readFully(channel, ByteBuffer.wrap(dest), offset);
		}

		private static byte[] inflate(byte[] compressed, int expectedSize) throws IOException {
			byte[] output = new byte[expectedSize];
			try (InflaterInputStream inflater = new InflaterInputStream(new ByteArrayInputStream(compressed))) {
				int offset = 0;
				while (offset < expectedSize) {
					int read = inflater.read(output, offset, expectedSize - offset);
					if (read < 0) {
						break;
					}
					offset += read;
				}
				if (offset != expectedSize) {
					throw new IOException("Unexpected inflated data length");
				}
				return output;
			}
		}
	}

	private record Pixel(int x, int y) {
	}

	private static final class CoverSmoothScratch {
		private final int[] counts = new int[256];
		private final int[] used = new int[256];
		private int usedCount;

		private void reset() {
			for (int i = 0; i < this.usedCount; i++) {
				this.counts[this.used[i]] = 0;
			}
			this.usedCount = 0;
		}

		private void add(int value) {
			if (this.counts[value] == 0) {
				this.used[this.usedCount++] = value;
			}
			this.counts[value]++;
		}

		private int pickMajority(int centerValue) {
			if (this.usedCount == 0) {
				return centerValue;
			}
			int bestValue = centerValue;
			int bestCount = -1;
			for (int i = 0; i < this.usedCount; i++) {
				int value = this.used[i];
				int count = this.counts[value];
				if (count > bestCount || (count == bestCount && value == centerValue)) {
					bestCount = count;
					bestValue = value;
				}
			}
			return bestValue;
		}
	}
}
