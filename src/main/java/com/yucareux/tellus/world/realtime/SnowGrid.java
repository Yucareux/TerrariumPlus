package com.yucareux.tellus.world.realtime;

import java.util.Arrays;
import net.minecraft.util.Mth;

public final class SnowGrid {
	public static final int GRID_SIZE = 3;
	public static final int GRID_POINTS = GRID_SIZE * GRID_SIZE;
	private static final float EMPTY_SAMPLE = 0.0f;

	private final int centerX;
	private final int centerZ;
	private final int spacingBlocks;
	private final float[] snowIndex;

	public SnowGrid(int centerX, int centerZ, int spacingBlocks, float[] snowIndex) {
		this.centerX = centerX;
		this.centerZ = centerZ;
		this.spacingBlocks = spacingBlocks;
		this.snowIndex = snowIndex == null ? new float[GRID_POINTS] : Arrays.copyOf(snowIndex, GRID_POINTS);
	}

	public static SnowGrid empty() {
		return new SnowGrid(0, 0, 0, null);
	}

	public int centerX() {
		return this.centerX;
	}

	public int centerZ() {
		return this.centerZ;
	}

	public int spacingBlocks() {
		return this.spacingBlocks;
	}

	public boolean isEmpty() {
		return this.spacingBlocks <= 0;
	}

	public float sample(int blockX, int blockZ) {
		if (this.spacingBlocks <= 0) {
			return EMPTY_SAMPLE;
		}
		float localX = (blockX - this.centerX) / (float) this.spacingBlocks + 1.0f;
		float localZ = (blockZ - this.centerZ) / (float) this.spacingBlocks + 1.0f;
		localX = Mth.clamp(localX, 0.0f, 2.0f);
		localZ = Mth.clamp(localZ, 0.0f, 2.0f);

		int ix = Mth.clamp(Mth.floor(localX), 0, 1);
		int iz = Mth.clamp(Mth.floor(localZ), 0, 1);
		float fx = localX - ix;
		float fz = localZ - iz;

		float v00 = samplePoint(ix, iz);
		float v10 = samplePoint(ix + 1, iz);
		float v01 = samplePoint(ix, iz + 1);
		float v11 = samplePoint(ix + 1, iz + 1);
		float v0 = Mth.lerp(fx, v00, v10);
		float v1 = Mth.lerp(fx, v01, v11);
		return Mth.lerp(fz, v0, v1);
	}

	private float samplePoint(int gridX, int gridZ) {
		int index = gridZ * GRID_SIZE + gridX;
		if (index < 0 || index >= this.snowIndex.length) {
			return EMPTY_SAMPLE;
		}
		return this.snowIndex[index];
	}
}
