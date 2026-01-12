package com.yucareux.tellus.world.data.koppen;

import com.yucareux.tellus.Tellus;
import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
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
import java.util.Map;
import java.util.zip.InflaterInputStream;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.Mth;

public final class TellusKoppenSource {
	private static final double EQUATOR_CIRCUMFERENCE = 40075017.0;
	private static final double MIN_LAT = -90.0;
	private static final double MAX_LAT = 90.0;
	private static final double MIN_LON = -180.0;
	private static final double MAX_LON = 180.0;
	private static final double DOWNSAMPLE_START_PIXELS = 4.0;
	private static final int MAX_DOWNSAMPLE_STEP = 256;

	private static final String RESOURCE_PATH = "tellus/koppen/koppen_geiger_0p00833333.tif";
	private static final double SEARCH_RADIUS_METERS = 5000.0;
	private static final int SMOOTH_RADIUS_PIXELS = 2;
	private static final double WARP_AMPLITUDE_METERS = 800.0;
	private static final double WARP_WAVELENGTH_METERS = 12000.0;
	private static final long WARP_SEED_X = 0x243f6a8885a308d3L;
	private static final long WARP_SEED_Z = 0x13198a2e03707344L;

	private static final String[] KOPPEN_CODES = new String[31];

	static {
		KOPPEN_CODES[1] = "Af";
		KOPPEN_CODES[2] = "Am";
		KOPPEN_CODES[3] = "Aw";
		KOPPEN_CODES[4] = "BWh";
		KOPPEN_CODES[5] = "BWk";
		KOPPEN_CODES[6] = "BSh";
		KOPPEN_CODES[7] = "BSk";
		KOPPEN_CODES[8] = "Csa";
		KOPPEN_CODES[9] = "Csb";
		KOPPEN_CODES[10] = "Csc";
		KOPPEN_CODES[11] = "Cwa";
		KOPPEN_CODES[12] = "Cwb";
		KOPPEN_CODES[13] = "Cwc";
		KOPPEN_CODES[14] = "Cfa";
		KOPPEN_CODES[15] = "Cfb";
		KOPPEN_CODES[16] = "Cfc";
		KOPPEN_CODES[17] = "Dsa";
		KOPPEN_CODES[18] = "Dsb";
		KOPPEN_CODES[19] = "Dsc";
		KOPPEN_CODES[20] = "Dsd";
		KOPPEN_CODES[21] = "Dwa";
		KOPPEN_CODES[22] = "Dwb";
		KOPPEN_CODES[23] = "Dwc";
		KOPPEN_CODES[24] = "Dwd";
		KOPPEN_CODES[25] = "Dfa";
		KOPPEN_CODES[26] = "Dfb";
		KOPPEN_CODES[27] = "Dfc";
		KOPPEN_CODES[28] = "Dfd";
		KOPPEN_CODES[29] = "ET";
		KOPPEN_CODES[30] = "EF";
	}

	private final Path cachePath;
	private final GeoTiffRaster raster;

	public TellusKoppenSource() {
		this.cachePath = FabricLoader.getInstance().getGameDir()
				.resolve("tellus/cache/koppen/koppen_geiger_0p00833333.tif");
		this.raster = loadRaster();
	}

	public String sampleDitheredCode(double blockX, double blockZ, double worldScale) {
		return sampleRawCode(blockX, blockZ, worldScale);
	}

	public String sampleRawCode(double blockX, double blockZ, double worldScale) {
		Pixel center = toPixel(blockX, blockZ, worldScale);
		if (center == null) {
			return null;
		}
		if (this.raster == GeoTiffRaster.MISSING) {
			return null;
		}
		return this.raster.sample(center);
	}

	public String sampleSmoothedCode(double blockX, double blockZ, double worldScale) {
		Pixel center = toPixel(blockX, blockZ, worldScale);
		if (center == null) {
			return null;
		}
		if (this.raster == GeoTiffRaster.MISSING) {
			return null;
		}
		return this.raster.sampleSmoothed(center, SMOOTH_RADIUS_PIXELS);
	}

	public String findNearestCode(double blockX, double blockZ, double worldScale) {
		Pixel center = toPixel(blockX, blockZ, worldScale);
		if (center == null) {
			return null;
		}
		if (this.raster == GeoTiffRaster.MISSING) {
			return null;
		}
		int radius = this.raster.radiusForMeters(SEARCH_RADIUS_METERS);
		return this.raster.findNearest(center, radius);
	}

	private Pixel toPixel(double blockX, double blockZ, double worldScale) {
		if (worldScale <= 0.0) {
			return null;
		}
		WarpedCoords warped = warpBlock(blockX, blockZ, worldScale);
		blockX = warped.x();
		blockZ = warped.z();
		int step = downsampleStep(worldScale, this.raster.pixelSizeMeters());
		if (step > 1) {
			blockX = downsampleBlock(blockX, step);
			blockZ = downsampleBlock(blockZ, step);
		}
		double metersPerDegree = EQUATOR_CIRCUMFERENCE / 360.0;
		double blocksPerDegree = metersPerDegree / worldScale;
		double lon = blockX / blocksPerDegree;
		double lat = -blockZ / blocksPerDegree;
		if (lat < MIN_LAT || lat > MAX_LAT || lon < MIN_LON || lon > MAX_LON) {
			return null;
		}
		return this.raster.toPixel(lon, lat);
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

	private static WarpedCoords warpBlock(double blockX, double blockZ, double worldScale) {
		if (worldScale <= 0.0) {
			return new WarpedCoords(blockX, blockZ);
		}
		double amplitudeBlocks = WARP_AMPLITUDE_METERS / worldScale;
		if (amplitudeBlocks < 0.5) {
			return new WarpedCoords(blockX, blockZ);
		}
		double wavelengthBlocks = WARP_WAVELENGTH_METERS / worldScale;
		if (wavelengthBlocks <= 1.0) {
			return new WarpedCoords(blockX, blockZ);
		}
		double nx = blockX / wavelengthBlocks;
		double nz = blockZ / wavelengthBlocks;
		double offsetX = valueNoise(nx, nz, WARP_SEED_X) * amplitudeBlocks;
		double offsetZ = valueNoise(nx + 37.0, nz - 59.0, WARP_SEED_Z) * amplitudeBlocks;
		return new WarpedCoords(blockX + offsetX, blockZ + offsetZ);
	}

	private static double valueNoise(double x, double z, long seed) {
		int x0 = Mth.floor(x);
		int z0 = Mth.floor(z);
		double fx = x - x0;
		double fz = z - z0;
		double v00 = hashToUnit(x0, z0, seed);
		double v10 = hashToUnit(x0 + 1, z0, seed);
		double v01 = hashToUnit(x0, z0 + 1, seed);
		double v11 = hashToUnit(x0 + 1, z0 + 1, seed);
		double u = fade(fx);
		double v = fade(fz);
		double lerpX0 = Mth.lerp(u, v00, v10);
		double lerpX1 = Mth.lerp(u, v01, v11);
		return Mth.lerp(v, lerpX0, lerpX1) * 2.0 - 1.0;
	}

	private static double fade(double t) {
		return t * t * t * (t * (t * 6.0 - 15.0) + 10.0);
	}

	private static double hashToUnit(int x, int z, long seed) {
		long h = seed;
		h ^= (long) x * 0x9e3779b97f4a7c15L;
		h ^= (long) z * 0xc2b2ae3d27d4eb4fL;
		h = mix64(h);
		return (h >>> 11) * 0x1.0p-53;
	}

	private static long mix64(long value) {
		long z = value;
		z = (z ^ (z >>> 33)) * 0xff51afd7ed558ccdL;
		z = (z ^ (z >>> 33)) * 0xc4ceb9fe1a85ec53L;
		return z ^ (z >>> 33);
	}

	private GeoTiffRaster loadRaster() {
		try {
			if (!Files.exists(this.cachePath)) {
				cacheRaster();
			}
			if (!Files.exists(this.cachePath)) {
				return GeoTiffRaster.MISSING;
			}
			return GeoTiffRaster.open(this.cachePath);
		} catch (IOException e) {
			Tellus.LOGGER.warn("Failed to load Koppen raster", e);
			return GeoTiffRaster.MISSING;
		}
	}

	private void cacheRaster() {
		try (InputStream input = TellusKoppenSource.class.getClassLoader().getResourceAsStream(RESOURCE_PATH)) {
			if (input == null) {
				Tellus.LOGGER.warn("Missing Koppen raster resource {}", RESOURCE_PATH);
				return;
			}
			Files.createDirectories(this.cachePath.getParent());
			Path temp = this.cachePath.resolveSibling(this.cachePath.getFileName() + ".tmp");
			Files.copy(input, temp, StandardCopyOption.REPLACE_EXISTING);
			Files.move(temp, this.cachePath, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			Tellus.LOGGER.warn("Failed to cache Koppen raster", e);
		}
	}

	private record Pixel(int x, int y) {
	}

	private record WarpedCoords(double x, double z) {
	}

	private static final class GeoTiffRaster {
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

		private static final int COMPRESSION_LZW = 5;
		private static final int COMPRESSION_DEFLATE = 8;
		private static final int MAX_TILE_CACHE = 64;

		private static final GeoTiffRaster MISSING = new GeoTiffRaster();

		private final Path path;
		private final FileChannel channel;
		private final int width;
		private final int height;
		private final int tileWidth;
		private final int tileHeight;
		private final int tilesPerRow;
		private final int compression;
		private final long[] tileOffsets;
		private final int[] tileByteCounts;
		private final double pixelScaleX;
		private final double pixelScaleY;
		private final double tieLon;
		private final double tieLat;
		private final double pixelSizeMeters;
		private final Map<Integer, byte[]> tileCache;

		private GeoTiffRaster() {
			this.path = null;
			this.channel = null;
			this.width = 0;
			this.height = 0;
			this.tileWidth = 0;
			this.tileHeight = 0;
			this.tilesPerRow = 0;
			this.compression = 0;
			this.tileOffsets = null;
			this.tileByteCounts = null;
			this.pixelScaleX = 0.0;
			this.pixelScaleY = 0.0;
			this.tieLon = 0.0;
			this.tieLat = 0.0;
			this.pixelSizeMeters = 0.0;
			this.tileCache = Map.of();
		}

		private GeoTiffRaster(
				Path path,
				FileChannel channel,
				int width,
				int height,
				int tileWidth,
				int tileHeight,
				int compression,
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
			this.compression = compression;
			this.tileOffsets = tileOffsets;
			this.tileByteCounts = tileByteCounts;
			this.pixelScaleX = pixelScaleX;
			this.pixelScaleY = pixelScaleY;
			this.tieLon = tieLon;
			this.tieLat = tieLat;
			this.pixelSizeMeters = Math.abs(pixelScaleX) * (EQUATOR_CIRCUMFERENCE / 360.0);
			this.tileCache = new LinkedHashMap<>(MAX_TILE_CACHE, 0.75f, true) {
				@Override
				protected boolean removeEldestEntry(Map.Entry<Integer, byte[]> eldest) {
					return size() > MAX_TILE_CACHE;
				}
			};
		}

		static GeoTiffRaster open(Path path) throws IOException {
			FileChannel channel = FileChannel.open(path, StandardOpenOption.READ);
			try {
				return readFromChannel(path, channel);
			} catch (IOException e) {
				channel.close();
				throw e;
			}
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

		String sampleSmoothed(Pixel center, int radius) {
			if (center == null || radius <= 0) {
				return sample(center);
			}
			int[] counts = new int[KOPPEN_CODES.length];
			int centerValue = sampleValue(center.x, center.y);
			for (int dy = -radius; dy <= radius; dy++) {
				for (int dx = -radius; dx <= radius; dx++) {
					if (dx * dx + dy * dy > radius * radius) {
						continue;
					}
					int value = sampleValue(center.x + dx, center.y + dy);
					if (value > 0 && value < counts.length) {
						counts[value]++;
					}
				}
			}
			int bestIndex = -1;
			int bestCount = -1;
			for (int i = 1; i < counts.length; i++) {
				int count = counts[i];
				if (count > bestCount) {
					bestCount = count;
					bestIndex = i;
					continue;
				}
				if (count == bestCount && count > 0 && i == centerValue) {
					bestIndex = i;
				}
			}
			if (bestIndex <= 0) {
				return null;
			}
			return KOPPEN_CODES[bestIndex];
		}

		String findNearest(Pixel center, int radius) {
			if (center == null || radius <= 0) {
				return null;
			}
			int bestValue = 0;
			int bestDist = Integer.MAX_VALUE;
			int maxDist = radius * radius;
			for (int dy = -radius; dy <= radius; dy++) {
				for (int dx = -radius; dx <= radius; dx++) {
					int dist = dx * dx + dy * dy;
					if (dist > maxDist) {
						continue;
					}
					int value = sampleValue(center.x + dx, center.y + dy);
					if (value <= 0 || value >= KOPPEN_CODES.length) {
						continue;
					}
					if (dist < bestDist) {
						bestDist = dist;
						bestValue = value;
					}
				}
			}
			return bestValue > 0 ? KOPPEN_CODES[bestValue] : null;
		}

		int radiusForMeters(double meters) {
			if (this.pixelSizeMeters <= 0.0) {
				return 0;
			}
			return Math.max(1, (int) Math.ceil(meters / this.pixelSizeMeters));
		}

		double pixelSizeMeters() {
			return this.pixelSizeMeters;
		}

		private String sample(Pixel pixel) {
			if (pixel == null) {
				return null;
			}
			int value = sampleValue(pixel.x, pixel.y);
			if (value <= 0 || value >= KOPPEN_CODES.length) {
				return null;
			}
			return KOPPEN_CODES[value];
		}

		private int sampleValue(int pixelX, int pixelY) {
			if (this == MISSING) {
				return 0;
			}
			if (pixelX < 0 || pixelY < 0 || pixelX >= this.width || pixelY >= this.height) {
				return 0;
			}
			int tileX = pixelX / this.tileWidth;
			int tileY = pixelY / this.tileHeight;
			int tileIndex = tileY * this.tilesPerRow + tileX;

			byte[] tile;
			try {
				tile = getTile(tileIndex);
			} catch (IOException e) {
				if (e instanceof ClosedByInterruptException || Thread.currentThread().isInterrupted()) {
					Thread.currentThread().interrupt();
					return 0;
				}
				Tellus.LOGGER.warn("Failed to read Koppen tile {} in {}", tileIndex, this.path, e);
				return 0;
			}

			int localX = pixelX - tileX * this.tileWidth;
			int localY = pixelY - tileY * this.tileHeight;
			return Byte.toUnsignedInt(tile[localX + localY * this.tileWidth]);
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
			int expectedSize = this.tileWidth * this.tileHeight;
			if (this.compression == COMPRESSION_DEFLATE) {
				return inflate(compressed, expectedSize);
			}
			if (this.compression == COMPRESSION_LZW) {
				return decompressLzw(compressed, expectedSize);
			}
			throw new IOException("Unsupported TIFF compression " + this.compression);
		}

		private static GeoTiffRaster readFromChannel(Path path, FileChannel channel) throws IOException {
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

			if (compression != COMPRESSION_DEFLATE && compression != COMPRESSION_LZW) {
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

			return new GeoTiffRaster(
					path,
					channel,
					width,
					height,
					tileWidth,
					tileHeight,
					compression,
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

		private static byte[] decompressLzw(byte[] compressed, int expectedSize) throws IOException {
			byte[][] table = new byte[4096][];
			for (int i = 0; i < 256; i++) {
				table[i] = new byte[] { (byte) i };
			}

			int clearCode = 256;
			int endCode = 257;
			int codeSize = 9;
			int nextCode = 258;
			byte[] output = new byte[expectedSize];
			int outPos = 0;

			LzwBitReader reader = new LzwBitReader(compressed);
			byte[] previous = null;

			while (true) {
				int code = reader.read(codeSize);
				if (code < 0) {
					break;
				}
				if (code == clearCode) {
					for (int i = 0; i < 256; i++) {
						table[i] = new byte[] { (byte) i };
					}
					for (int i = 256; i < table.length; i++) {
						table[i] = null;
					}
					codeSize = 9;
					nextCode = 258;
					previous = null;
					continue;
				}
				if (code == endCode) {
					break;
				}

				byte[] entry;
				if (code < nextCode && table[code] != null) {
					entry = table[code];
				} else if (code == nextCode && previous != null) {
					entry = concat(previous, previous[0]);
				} else {
					throw new IOException("Invalid LZW code " + code);
				}

				if (outPos + entry.length > output.length) {
					throw new IOException("Unexpected LZW output size");
				}
				System.arraycopy(entry, 0, output, outPos, entry.length);
				outPos += entry.length;

				if (previous != null && nextCode < table.length) {
					table[nextCode++] = concat(previous, entry[0]);
					int threshold = (1 << codeSize) - 1;
					if (nextCode == threshold && codeSize < 12) {
						codeSize++;
					}
				}

				previous = entry;
				if (outPos == output.length) {
					break;
				}
			}

			if (outPos != output.length) {
				throw new IOException("Unexpected LZW output length " + outPos);
			}
			return output;
		}

		private static byte[] concat(byte[] prefix, byte suffix) {
			byte[] combined = new byte[prefix.length + 1];
			System.arraycopy(prefix, 0, combined, 0, prefix.length);
			combined[prefix.length] = suffix;
			return combined;
		}

		private static final class LzwBitReader {
			private final byte[] data;
			private int bitPos;

			private LzwBitReader(byte[] data) {
				this.data = data;
			}

			private int read(int bits) {
				int totalBits = this.data.length * 8;
				if (this.bitPos + bits > totalBits) {
					return -1;
				}
				int value = 0;
				for (int i = 0; i < bits; i++) {
					int offset = this.bitPos + i;
					int byteIndex = offset >> 3;
					int bitIndex = 7 - (offset & 7);
					int current = this.data[byteIndex] & 0xFF;
					value = (value << 1) | ((current >> bitIndex) & 1);
				}
				this.bitPos += bits;
				return value;
			}
		}
	}
}
