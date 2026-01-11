package com.yucareux.tellus.worldgen.geology;

import com.yucareux.tellus.worldgen.EarthGeneratorSettings;
import com.yucareux.tellus.worldgen.WaterSurfaceResolver;
import java.util.Objects;
import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import it.unimi.dsi.fastutil.doubles.DoubleList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import org.jspecify.annotations.NonNull;

public final class TellusGeologyGenerator {
	private static final int CAVE_STEP_Y = 4;
	private static final int MIN_CAVE_ROOF = 10;
	private static final int LARGE_CAVE_ROOF = 14;
	private static final int CANYON_ROOF = 4;
	private static final int MAX_CAVE_DEPTH = 96;
	private static final int MAX_CANYON_DEPTH = 80;
	private static final int MIN_CANYON_DEPTH = 28;
	private static final int WATER_TABLE_SURFACE_BUFFER = 12;
	private static final int WATER_TABLE_LAKE_BUFFER = 4;
	private static final int MIN_WATER_TABLE = 8;
	private static final int DRIPSTONE_MIN_HEIGHT = 5;
	private static final float DRIPSTONE_CHANCE = 0.06f;
	private static final float DEEP_DARK_CHANCE = 0.35f;
	private static final float LAVA_POOL_CHANCE = 0.08f;

	private static final double SMALL_FREQ = 0.035;
	private static final double LARGE_FREQ = 0.012;
	private static final double CANYON_FREQ = 0.007;
	private static final double WARP_FREQ = 0.02;
	private static final double WARP_AMPLITUDE = 12.0;

	private static final double SMALL_THRESHOLD_SHALLOW = 0.65;
	private static final double SMALL_THRESHOLD_DEEP = 0.45;
	private static final double LARGE_THRESHOLD_SHALLOW = 0.48;
	private static final double LARGE_THRESHOLD_DEEP = 0.28;
	private static final double CAVE_DEPTH_FALLOFF = 80.0;
	private static final double CANYON_THRESHOLD = 0.72;

	private final EarthGeneratorSettings settings;
	private final int minY;
	private final int maxY;
	private final int seaLevel;
	private final int deepDarkStart;
	private final int lavaLevel;
	private final long seedSalt;
	private final NormalNoise smallNoise;
	private final NormalNoise largeNoise;
	private final NormalNoise canyonNoise;
	private final NormalNoise warpX;
	private final NormalNoise warpZ;
	private final NormalNoise dripNoise;
	private final NormalNoise deepDarkNoise;
	private final NormalNoise lavaNoise;

	public TellusGeologyGenerator(EarthGeneratorSettings settings, int minY, int height, int seaLevel, long seed) {
		this.settings = settings;
		this.minY = minY;
		this.maxY = minY + height - 1;
		this.seaLevel = seaLevel;
		this.deepDarkStart = computeDeepDarkStart(minY, height, seaLevel);
		this.lavaLevel = Math.min(-54, seaLevel - 20);
		this.seedSalt = seed ^ 0x6F1D5E3A2B9C4D1EL;
		this.smallNoise = createNoise(seed ^ 0x1A2B3C4D5E6F7081L, -3, 1.0, 0.5, 0.25, 0.125);
		this.largeNoise = createNoise(seed ^ 0x4B5C6D7E8F901223L, -4, 1.0, 0.75, 0.5);
		this.canyonNoise = createNoise(seed ^ 0x91A2B3C4D5E6F778L, -2, 1.0, 0.5);
		this.warpX = createNoise(seed ^ 0x1F2E3D4C5B6A7988L, -2, 1.0, 0.5, 0.25);
		this.warpZ = createNoise(seed ^ 0x8A7B6C5D4E3F2101L, -2, 1.0, 0.5, 0.25);
		this.dripNoise = createNoise(seed ^ 0x0F1E2D3C4B5A6978L, -1, 1.0);
		this.deepDarkNoise = createNoise(seed ^ 0x1020304050607080L, -2, 1.0, 0.5);
		this.lavaNoise = createNoise(seed ^ 0xABCDEF0123456789L, -1, 1.0);
	}

	public boolean shouldRun() {
		return this.settings.caveCarvers() || this.settings.largeCaves() || this.settings.canyonCarvers();
	}

	public void carveChunk(ChunkAccess chunk, WaterSurfaceResolver.WaterChunkData waterData) {
		if (!shouldRun()) {
			return;
		}
		ChunkPos pos = chunk.getPos();
		int chunkMinY = chunk.getMinY();
		int chunkMaxY = chunkMinY + chunk.getHeight() - 1;
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		double[] warpOffsetsX = new double[16 * 16];
		double[] warpOffsetsZ = new double[16 * 16];
		double[] canyonMask = this.settings.canyonCarvers() ? new double[16 * 16] : null;

		for (int localX = 0; localX < 16; localX++) {
			int worldX = pos.getMinBlockX() + localX;
			for (int localZ = 0; localZ < 16; localZ++) {
				int worldZ = pos.getMinBlockZ() + localZ;
				int idx = localZ * 16 + localX;
				double warpSampleX = worldX * WARP_FREQ;
				double warpSampleZ = worldZ * WARP_FREQ;
				warpOffsetsX[idx] = this.warpX.getValue(warpSampleX, 0.0, warpSampleZ) * WARP_AMPLITUDE;
				warpOffsetsZ[idx] = this.warpZ.getValue(warpSampleX, 0.0, warpSampleZ) * WARP_AMPLITUDE;
				if (canyonMask != null) {
					double canyonValue = this.canyonNoise.getValue(worldX * CANYON_FREQ, 0.0, worldZ * CANYON_FREQ);
					canyonMask[idx] = canyonValue;
				}
			}
		}

		for (int localX = 0; localX < 16; localX++) {
			int worldX = pos.getMinBlockX() + localX;
			for (int localZ = 0; localZ < 16; localZ++) {
				int worldZ = pos.getMinBlockZ() + localZ;
				int idx = localZ * 16 + localX;
				int surface = Mth.clamp(waterData.terrainSurface(localX, localZ), chunkMinY, chunkMaxY);
				int waterSurface = Mth.clamp(waterData.waterSurface(localX, localZ), chunkMinY, chunkMaxY);
				boolean hasWater = waterData.hasWater(localX, localZ);
				int minRoof = this.settings.largeCaves() ? LARGE_CAVE_ROOF : MIN_CAVE_ROOF;
				int carveTop = Math.min(surface - minRoof, chunkMaxY - 1);
				int carveBottom = Math.max(chunkMinY + 1, surface - MAX_CAVE_DEPTH);
				int waterTable = resolveWaterTable(surface, waterSurface, hasWater, chunkMinY);
				int canyonDepth = 0;
				int canyonTop = 0;
				int canyonBottom = 0;
				boolean canyonActive = false;
				if (canyonMask != null) {
					double canyonValue = canyonMask[idx];
					if (canyonValue > CANYON_THRESHOLD) {
						double t = Mth.clamp((canyonValue - CANYON_THRESHOLD) / (1.0 - CANYON_THRESHOLD), 0.0, 1.0);
						canyonDepth = (int) Math.round(Mth.lerp(t, MIN_CANYON_DEPTH, MAX_CANYON_DEPTH));
						canyonTop = Math.max(carveBottom, surface - CANYON_ROOF);
						canyonBottom = Math.max(chunkMinY + 1, surface - canyonDepth);
						canyonActive = canyonTop > canyonBottom;
					}
				}
				if (canyonActive) {
					int canyonRoof = Math.min(chunkMaxY - 1, surface - CANYON_ROOF);
					if (canyonRoof > carveTop) {
						carveTop = canyonRoof;
					}
				}
				if (carveTop <= carveBottom) {
					continue;
				}

				boolean inCave = false;
				int caveStart = 0;
				int caveEnd = 0;
				for (int y = carveBottom; y <= carveTop; y += CAVE_STEP_Y) {
					int sampleY = Math.min(y + (CAVE_STEP_Y / 2), carveTop);
					double depth = surface - sampleY;
					double depthT = Mth.clamp(depth / CAVE_DEPTH_FALLOFF, 0.0, 1.0);
					boolean carveSegment = false;
					if (this.settings.caveCarvers()) {
						double threshold = Mth.lerp(depthT, SMALL_THRESHOLD_SHALLOW, SMALL_THRESHOLD_DEEP);
						double noise = sampleNoise(this.smallNoise, worldX, sampleY, worldZ, SMALL_FREQ, warpOffsetsX[idx], warpOffsetsZ[idx]);
						carveSegment |= noise > threshold;
					}
					if (this.settings.largeCaves()) {
						double threshold = Mth.lerp(depthT, LARGE_THRESHOLD_SHALLOW, LARGE_THRESHOLD_DEEP);
						double noise = sampleNoise(this.largeNoise, worldX, sampleY, worldZ, LARGE_FREQ, warpOffsetsX[idx], warpOffsetsZ[idx]);
						carveSegment |= noise > threshold;
					}
					if (canyonActive && sampleY >= canyonBottom && sampleY <= canyonTop) {
						carveSegment = true;
					}

					for (int dy = 0; dy < CAVE_STEP_Y && (y + dy) <= carveTop; dy++) {
						int carveY = y + dy;
						if (!carveSegment) {
							if (inCave) {
								caveEnd = carveY - 1;
								decorateCave(chunk, cursor, worldX, worldZ, caveStart, caveEnd, waterTable);
								inCave = false;
							}
							continue;
						}
						if (carveY <= this.minY) {
							continue;
						}
						cursor.set(worldX, carveY, worldZ);
						BlockState existing = chunk.getBlockState(cursor);
						if (existing.is(Blocks.BEDROCK)) {
							continue;
						}
						BlockState carved = resolveCarvedBlock(carveY, waterTable);
						chunk.setBlockState(cursor, carved);
						if (!inCave) {
							inCave = true;
							caveStart = carveY;
						}
					}
				}
				if (inCave) {
					caveEnd = carveTop;
					decorateCave(chunk, cursor, worldX, worldZ, caveStart, caveEnd, waterTable);
				}
			}
		}
	}

	private @NonNull BlockState resolveCarvedBlock(int y, int waterTable) {
		if (!this.settings.aquifers()) {
			return Blocks.AIR.defaultBlockState();
		}
		if (this.settings.lavaPools() && y <= this.lavaLevel) {
			return Blocks.LAVA.defaultBlockState();
		}
		if (y <= waterTable) {
			return Blocks.WATER.defaultBlockState();
		}
		return Blocks.AIR.defaultBlockState();
	}

	private int resolveWaterTable(int surface, int waterSurface, boolean hasWater, int minY) {
		int table = this.seaLevel;
		if (hasWater) {
			table = Math.max(table, waterSurface - WATER_TABLE_LAKE_BUFFER);
		}
		if (surface > this.seaLevel + 4) {
			table = Math.min(table, surface - WATER_TABLE_SURFACE_BUFFER);
		}
		int minTable = Math.max(minY + MIN_WATER_TABLE, this.minY + MIN_WATER_TABLE);
		return Math.max(table, minTable);
	}

	private void decorateCave(
			ChunkAccess chunk,
			BlockPos.MutableBlockPos cursor,
			int worldX,
			int worldZ,
			int caveStart,
			int caveEnd,
			int waterTable
	) {
		if (caveEnd < caveStart) {
			return;
		}
		int floorY = caveStart - 1;
		int ceilingY = caveEnd + 1;
		int caveHeight = caveEnd - caveStart + 1;
		if (floorY < this.minY || ceilingY > this.maxY) {
			return;
		}
		long seed = seedFromCoords(worldX, floorY, worldZ) ^ this.seedSalt;
		RandomSource random = RandomSource.create(seed);
		boolean flooded = this.settings.aquifers() && caveStart <= waterTable;
		if (this.settings.dripstone() && !flooded && caveHeight >= DRIPSTONE_MIN_HEIGHT) {
			double dripValue = this.dripNoise.getValue(worldX * 0.05, 0.0, worldZ * 0.05);
			if (dripValue > 0.2 && random.nextFloat() < DRIPSTONE_CHANCE) {
				cursor.set(worldX, floorY, worldZ);
				if (!chunk.getBlockState(cursor).isAir()) {
					chunk.setBlockState(cursor, Blocks.DRIPSTONE_BLOCK.defaultBlockState());
				}
				cursor.set(worldX, ceilingY, worldZ);
				if (!chunk.getBlockState(cursor).isAir()) {
					BlockState drip = Objects.requireNonNull(
							Blocks.POINTED_DRIPSTONE.defaultBlockState()
									.setValue(BlockStateProperties.VERTICAL_DIRECTION, Direction.DOWN),
							"dripstoneState"
					);
					chunk.setBlockState(cursor, drip);
				}
			}
		}
		if (this.settings.deepDark() && floorY <= this.deepDarkStart) {
			double sculkValue = this.deepDarkNoise.getValue(worldX * 0.02, 0.0, worldZ * 0.02);
			if (sculkValue > 0.15 && random.nextFloat() < DEEP_DARK_CHANCE) {
				cursor.set(worldX, floorY, worldZ);
				if (!chunk.getBlockState(cursor).isAir()) {
					chunk.setBlockState(cursor, Blocks.SCULK.defaultBlockState());
				}
			}
		}
		if (this.settings.lavaPools() && floorY <= this.lavaLevel + 6) {
			double lavaValue = this.lavaNoise.getValue(worldX * 0.04, 0.0, worldZ * 0.04);
			if (lavaValue > 0.1 && random.nextFloat() < LAVA_POOL_CHANCE) {
				placeLavaPool(chunk, cursor, worldX, caveStart, worldZ);
			}
		}
	}

	private void placeLavaPool(
			ChunkAccess chunk,
			BlockPos.MutableBlockPos cursor,
			int worldX,
			int caveStart,
			int worldZ
	) {
		int radius = 1;
		for (int dx = -radius; dx <= radius; dx++) {
			for (int dz = -radius; dz <= radius; dz++) {
				cursor.set(worldX + dx, caveStart, worldZ + dz);
				BlockState state = chunk.getBlockState(cursor);
				if (!state.isAir()) {
					continue;
				}
				chunk.setBlockState(cursor, Blocks.LAVA.defaultBlockState());
			}
		}
	}

	private static double sampleNoise(
			NormalNoise noise,
			int worldX,
			int worldY,
			int worldZ,
			double freq,
			double warpX,
			double warpZ
	) {
		double nx = (worldX + warpX) * freq;
		double ny = worldY * freq;
		double nz = (worldZ + warpZ) * freq;
		return noise.getValue(nx, ny, nz);
	}

	private static int computeDeepDarkStart(int minY, int height, int seaLevel) {
		int byHeight = minY + height / 3;
		int bySea = seaLevel - 32;
		int start = Math.min(byHeight, bySea);
		return Math.max(start, minY + 32);
	}

	private static NormalNoise createNoise(long seed, int firstOctave, double... amplitudes) {
		DoubleList list = new DoubleArrayList(amplitudes);
		return NormalNoise.create(RandomSource.create(seed), new NormalNoise.NoiseParameters(firstOctave, list));
	}

	private static long seedFromCoords(int x, int y, int z) {
		long seed = (x * 3129871L) ^ (long) z * 116129781L ^ (long) y;
		seed = seed * seed * 42317861L + seed * 11L;
		return seed >> 16;
	}
}
