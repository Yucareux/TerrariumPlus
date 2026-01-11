package com.yucareux.tellus.client.preview;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.yucareux.tellus.Tellus;
import com.yucareux.tellus.mixin.client.GuiGraphicsAccessor;
import com.yucareux.tellus.world.data.cover.TellusLandCoverSource;
import com.yucareux.tellus.world.data.elevation.TellusElevationSource;
import com.yucareux.tellus.world.data.koppen.TellusKoppenSource;
import com.yucareux.tellus.world.data.mask.TellusLandMaskSource;
import com.yucareux.tellus.worldgen.EarthGeneratorSettings;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.gui.render.state.GuiElementRenderState;
import net.minecraft.client.gui.render.state.GuiRenderState;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.NotNull;
import org.joml.Matrix3x2f;
import org.joml.Matrix3x2fc;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.jspecify.annotations.NonNull;

public final class TerrainPreview implements AutoCloseable {
	private static final double EQUATOR_CIRCUMFERENCE = 40075017.0;
	private static final int GRID_SIZE = 385;
	private static final int GRID_RADIUS_BLOCKS = 256;
	private static final int GRANULARITY = 1;
	private static final int COVER_SAMPLE_STRIDE = 2;
	private static final int CLIMATE_SAMPLE_STRIDE = 4;
	private static final float VERTICAL_SCALE = 0.7f;
	private static final float CAMERA_DISTANCE = 2.35f;
	private static final float FOV = 50.0f;
	private static final float MIN_FOV = 15.0f;
	private static final float MAX_FOV = 120.0f;
	private static final float Z_NEAR = 0.05f;
	private static final float Z_FAR = 100.0f;

	private static final int SHALLOW_SEA_COLOR = 0x4BA7DB;
	private static final int MID_SEA_COLOR = 0x1D5E9B;
	private static final int DEEP_SEA_COLOR = 0x071E3D;
	private static final int SHORE_COLOR = 0xC9B37E;
	private static final int LOW_LAND_COLOR = 0x3F9A53;
	private static final int MID_LAND_COLOR = 0x7F8F4D;
	private static final int HIGH_LAND_COLOR = 0x8C7A64;
	private static final int ROCK_COLOR = 0xA0A0A0;
	private static final int PEAK_COLOR = 0xF5F5F5;
	private static final int TREE_COLOR = 0x3F9A53;
	private static final int SHRUB_COLOR = 0x8FA354;
	private static final int GRASS_COLOR = 0x7DBF5B;
	private static final int CROPLAND_COLOR = 0xA7B96A;
	private static final int BUILT_COLOR = 0x8A8A8A;
	private static final int BARE_COLOR = 0xC7B27A;
	private static final int WETLAND_COLOR = 0x4B8C5A;
	private static final int MANGROVE_COLOR = 0x2F6B3E;
	private static final int MOSS_COLOR = 0x7FAE6B;
	private static final int TINT_TROPICAL = 0x2E9D57;
	private static final int TINT_ARID = 0xD0B072;
	private static final int TINT_TEMPERATE = 0x7FAF63;
	private static final int TINT_COLD = 0x6D8292;
	private static final int TINT_POLAR = 0xC9D6E2;
	private static final double ROCK_SLOPE_THRESHOLD = 1.2;
	private static final double ROCK_SLOPE_RANGE = 1.6;
	private static final double INLAND_WATER_DEPTH_BLOCKS = 6.0;

	private static final int ESA_NO_DATA = 0;
	private static final int ESA_TREE_COVER = 10;
	private static final int ESA_SHRUBLAND = 20;
	private static final int ESA_GRASSLAND = 30;
	private static final int ESA_CROPLAND = 40;
	private static final int ESA_BUILT_UP = 50;
	private static final int ESA_BARE = 60;
	private static final int ESA_SNOW_ICE = 70;
	private static final int ESA_WATER = 80;
	private static final int ESA_WETLAND = 90;
	private static final int ESA_MANGROVES = 95;
	private static final int ESA_MOSS = 100;
	private static final byte CLIMATE_UNKNOWN = 0;
	private static final byte CLIMATE_TROPICAL = 1;
	private static final byte CLIMATE_ARID = 2;
	private static final byte CLIMATE_TEMPERATE = 3;
	private static final byte CLIMATE_COLD = 4;
	private static final byte CLIMATE_POLAR = 5;
	private static final Vector3f LIGHT_DIR = new Vector3f(-0.4f, 0.8f, -0.4f).normalize();
	private static final float AMBIENT_SHADE = 0.45f;
	private static final float SHADE_STEPS = 8.0f;

	private final TellusElevationSource elevationSource = new TellusElevationSource();
	private final TellusLandCoverSource landCoverSource = new TellusLandCoverSource();
	private final TellusKoppenSource koppenSource = new TellusKoppenSource();
	private final TellusLandMaskSource landMaskSource = new TellusLandMaskSource();
	private final ExecutorService executor;
	private final AtomicInteger requestId = new AtomicInteger();

	private CompletableFuture<PreviewMesh> pending;
	private PreviewMesh mesh;

	public TerrainPreview() {
		this.executor = Executors.newSingleThreadExecutor(new PreviewThreadFactory());
	}

	public void requestRebuild(EarthGeneratorSettings settings) {
		int id = this.requestId.incrementAndGet();
		if (this.pending != null) {
			this.pending.cancel(true);
		}
		this.pending = CompletableFuture.supplyAsync(() -> buildMesh(settings), this.executor)
				.exceptionally(error -> {
					Tellus.LOGGER.warn("Failed to build terrain preview", error);
					return null;
				})
				.thenApply(mesh -> {
					if (mesh != null && id != this.requestId.get()) {
						return null;
					}
					return mesh;
				});
	}

	public void tick() {
		CompletableFuture<PreviewMesh> future = this.pending;
		if (future != null && future.isDone()) {
			this.pending = null;
			try {
				PreviewMesh preview = future.join();
				if (preview != null) {
					this.mesh = preview;
				}
			} catch (RuntimeException error) {
				Tellus.LOGGER.warn("Preview render update failed", error);
			}
		}
	}

	public void render(GuiGraphics graphics, int x, int y, int width, int height, float rotationX, float rotationY, float zoom) {
		PreviewMesh preview = this.mesh;
		if (preview == null || width <= 0 || height <= 0) {
			return;
		}

		Matrix4f modelView = buildModelView(rotationX, rotationY);
		Matrix4f projection = buildProjection(width, height, zoom);
		Matrix3x2f pose = new Matrix3x2f(graphics.pose());
		ScreenRectangle rawBounds = new ScreenRectangle(x, y, width, height);
		ScreenRectangle bounds = rawBounds.transformAxisAligned(pose);
		GuiRenderState renderState = ((GuiGraphicsAccessor) graphics).tellus$getGuiRenderState();
		renderState.submitGuiElement(new TerrainPreviewRenderState(preview, modelView, projection, pose, rawBounds, bounds));
	}

	private static Matrix4f buildProjection(int width, int height, float zoom) {
		float aspect = width / (float) height;
		float effectiveFov = Mth.clamp(FOV / Math.max(zoom, 0.01f), MIN_FOV, MAX_FOV);
		return new Matrix4f().setPerspective((float) Math.toRadians(effectiveFov), aspect, Z_NEAR, Z_FAR);
	}

	private static Matrix4f buildModelView(float rotationX, float rotationY) {
		return new Matrix4f()
				.identity()
				.translate(0.0f, 0.0f, -CAMERA_DISTANCE)
				.rotateX(rotationX)
				.rotateY(rotationY);
	}

	private PreviewMesh buildMesh(EarthGeneratorSettings settings) {
		int size = GRID_SIZE;
		double[] blockHeights = new double[size * size];
		double[] elevations = new double[size * size];

		double metersPerDegree = EQUATOR_CIRCUMFERENCE / 360.0;
		double worldScale = settings.worldScale();
		double blocksPerDegree = metersPerDegree / worldScale;
		double centerX = settings.spawnLongitude() * blocksPerDegree;
		double centerZ = -settings.spawnLatitude() * blocksPerDegree;
		double radius = GRID_RADIUS_BLOCKS;
		double step = (radius * 2.0) / (size - 1);

		for (int z = 0; z < size; z++) {
			double blockZ = centerZ - radius + z * step;
			for (int x = 0; x < size; x++) {
				double blockX = centerX - radius + x * step;
				int idx = x + z * size;
				boolean oceanZoom = useOceanZoom(blockX, blockZ, worldScale);
				double elevation = this.elevationSource.sampleElevationMeters(
						blockX,
						blockZ,
						worldScale,
						oceanZoom
				);
				elevations[idx] = elevation;
				blockHeights[idx] = applyHeightScale(elevation, settings);
			}
		}

		float[] heights = new float[size * size];
		float min = Float.POSITIVE_INFINITY;
		float max = Float.NEGATIVE_INFINITY;
		for (int i = 0; i < blockHeights.length; i++) {
			float value = (float) ((blockHeights[i] - settings.heightOffset()) / radius * VERTICAL_SCALE);
			heights[i] = value;
			min = Math.min(min, value);
			max = Math.max(max, value);
		}
		float center = (min + max) * 0.5f;
		for (int i = 0; i < heights.length; i++) {
			heights[i] -= center;
		}

		int coverStride = COVER_SAMPLE_STRIDE;
		int coverSize = (size + coverStride - 1) / coverStride;
		int[] coverClasses = new int[coverSize * coverSize];
		for (int z = 0; z < coverSize; z++) {
			int sampleZ = Math.min(size - 1, z * coverStride);
			double blockZ = centerZ - radius + sampleZ * step;
			for (int x = 0; x < coverSize; x++) {
				int sampleX = Math.min(size - 1, x * coverStride);
				double blockX = centerX - radius + sampleX * step;
				int idx = x + z * coverSize;
				coverClasses[idx] = this.landCoverSource.sampleCoverClass(blockX, blockZ, worldScale);
			}
		}

		int climateStride = CLIMATE_SAMPLE_STRIDE;
		int climateSize = (size + climateStride - 1) / climateStride;
		byte[] climateGroups = new byte[climateSize * climateSize];
		for (int z = 0; z < climateSize; z++) {
			int sampleZ = Math.min(size - 1, z * climateStride);
			double blockZ = centerZ - radius + sampleZ * step;
			for (int x = 0; x < climateSize; x++) {
				int sampleX = Math.min(size - 1, x * climateStride);
				double blockX = centerX - radius + sampleX * step;
				int idx = x + z * climateSize;
				String koppen = this.koppenSource.sampleDitheredCode(blockX, blockZ, worldScale);
				climateGroups[idx] = climateGroup(koppen);
			}
		}

		int seaLevel = settings.resolveSeaLevel();
		int[] colors = new int[size * size];
		for (int z = 0; z < size; z++) {
			int coverZ = Math.min(coverSize - 1, z / coverStride);
			int climateZ = Math.min(climateSize - 1, z / climateStride);
			for (int x = 0; x < size; x++) {
				int idx = x + z * size;
				int coverX = Math.min(coverSize - 1, x / coverStride);
				int climateX = Math.min(climateSize - 1, x / climateStride);
				int coverIdx = coverX + coverZ * coverSize;
				int climateIdx = climateX + climateZ * climateSize;
				int coverClass = coverClasses[coverIdx];
				byte climateGroup = climateGroups[climateIdx];
				double slope = computeSlope(blockHeights, size, idx, step);
				colors[idx] = colorForPreview(
						coverClass,
						climateGroup,
						elevations[idx],
						blockHeights[idx],
						slope,
						seaLevel
				);
			}
		}

		float[] xCoords = new float[size];
		for (int i = 0; i < size; i++) {
			xCoords[i] = (float) (-1.0 + (2.0 * i) / (size - 1));
		}

		return new PreviewMesh(size, GRANULARITY, heights, colors, xCoords);
	}

	private static double applyHeightScale(double elevation, EarthGeneratorSettings settings) {
		double scale = elevation >= 0.0 ? settings.terrestrialHeightScale() : settings.oceanicHeightScale();
		double scaled = elevation * scale / settings.worldScale();
		int base = elevation >= 0.0 ? Mth.ceil(scaled) : Mth.floor(scaled);
		return base + settings.heightOffset();
	}

	private static double computeSlope(double[] heights, int size, int idx, double step) {
		int x = idx % size;
		int z = idx / size;
		int idxRight = x + 1 < size ? idx + 1 : idx;
		int idxDown = z + 1 < size ? idx + size : idx;
		double dx = Math.abs(heights[idxRight] - heights[idx]);
		double dz = Math.abs(heights[idxDown] - heights[idx]);
		double diff = Math.max(dx, dz);
		return step <= 0.0 ? diff : diff / step;
	}

	private static byte climateGroup(String koppen) {
		if (koppen == null || koppen.isEmpty()) {
			return CLIMATE_UNKNOWN;
		}
		char group = Character.toUpperCase(koppen.charAt(0));
		return switch (group) {
			case 'A' -> CLIMATE_TROPICAL;
			case 'B' -> CLIMATE_ARID;
			case 'C' -> CLIMATE_TEMPERATE;
			case 'D' -> CLIMATE_COLD;
			case 'E' -> CLIMATE_POLAR;
			default -> CLIMATE_UNKNOWN;
		};
	}

	private static int colorForPreview(
			int coverClass,
			byte climateGroup,
			double elevationMeters,
			double terrainHeight,
			double slope,
			int seaLevel
	) {
		if (coverClass == ESA_NO_DATA) {
			double depth = Math.max(0.0, seaLevel - terrainHeight);
			return waterColorForDepth(depth);
		}
		if (coverClass == ESA_WATER) {
			double depth = Math.max(INLAND_WATER_DEPTH_BLOCKS, seaLevel - terrainHeight);
			return waterColorForDepth(depth);
		}
		if (coverClass == ESA_SNOW_ICE) {
			return PEAK_COLOR;
		}
		int base = baseColorForCover(coverClass, elevationMeters);
		int tinted = applyClimateTint(base, climateGroup, coverClass);
		return applyRockTint(tinted, slope);
	}

	private static int baseColorForCover(int coverClass, double elevationMeters) {
		return switch (coverClass) {
			case ESA_TREE_COVER -> TREE_COLOR;
			case ESA_SHRUBLAND -> SHRUB_COLOR;
			case ESA_GRASSLAND -> GRASS_COLOR;
			case ESA_CROPLAND -> CROPLAND_COLOR;
			case ESA_BUILT_UP -> BUILT_COLOR;
			case ESA_BARE -> BARE_COLOR;
			case ESA_WETLAND -> WETLAND_COLOR;
			case ESA_MANGROVES -> MANGROVE_COLOR;
			case ESA_MOSS -> MOSS_COLOR;
			default -> colorForElevation(elevationMeters);
		};
	}

	private static int waterColorForDepth(double depthBlocks) {
		if (depthBlocks <= 2.0) {
			return lerpColor(SHORE_COLOR, SHALLOW_SEA_COLOR, depthBlocks / 2.0);
		}
		if (depthBlocks <= 12.0) {
			return lerpColor(SHALLOW_SEA_COLOR, MID_SEA_COLOR, (depthBlocks - 2.0) / 10.0);
		}
		if (depthBlocks <= 80.0) {
			return lerpColor(MID_SEA_COLOR, DEEP_SEA_COLOR, (depthBlocks - 12.0) / 68.0);
		}
		return DEEP_SEA_COLOR;
	}

	private boolean useOceanZoom(double blockX, double blockZ, double worldScale) {
		TellusLandMaskSource.LandMaskSample landSample = this.landMaskSource.sampleLandMask(blockX, blockZ, worldScale);
		if (!landSample.known()) {
			return true;
		}
		if (landSample.land()) {
			return false;
		}
		int coverClass = this.landCoverSource.sampleCoverClass(blockX, blockZ, worldScale);
		return coverClass == ESA_NO_DATA;
	}

	private static int applyClimateTint(int base, byte climateGroup, int coverClass) {
		float amount = climateBlendStrength(coverClass);
		if (amount <= 0.0f || climateGroup == CLIMATE_UNKNOWN) {
			return base;
		}
		int tint = tintForClimate(climateGroup);
		if (tint == 0) {
			return base;
		}
		if (climateGroup == CLIMATE_POLAR) {
			amount = Math.min(0.65f, amount + 0.2f);
		}
		return blendColor(base, tint, amount);
	}

	private static float climateBlendStrength(int coverClass) {
		return switch (coverClass) {
			case ESA_TREE_COVER, ESA_GRASSLAND, ESA_CROPLAND, ESA_WETLAND, ESA_MOSS -> 0.35f;
			case ESA_SHRUBLAND -> 0.25f;
			case ESA_BARE -> 0.15f;
			case ESA_MANGROVES -> 0.2f;
			default -> 0.0f;
		};
	}

	private static int tintForClimate(byte climateGroup) {
		return switch (climateGroup) {
			case CLIMATE_TROPICAL -> TINT_TROPICAL;
			case CLIMATE_ARID -> TINT_ARID;
			case CLIMATE_TEMPERATE -> TINT_TEMPERATE;
			case CLIMATE_COLD -> TINT_COLD;
			case CLIMATE_POLAR -> TINT_POLAR;
			default -> 0;
		};
	}

	private static int applyRockTint(int base, double slope) {
		if (slope <= ROCK_SLOPE_THRESHOLD) {
			return base;
		}
		double amount = (slope - ROCK_SLOPE_THRESHOLD) / ROCK_SLOPE_RANGE;
		return blendColor(base, ROCK_COLOR, (float) Mth.clamp(amount, 0.0, 1.0));
	}

	private static int colorForElevation(double elevation) {
		if (elevation < 0.0) {
			double depth = -elevation;
			if (depth < 60.0) {
				return lerpColor(SHALLOW_SEA_COLOR, MID_SEA_COLOR, depth / 60.0);
			}
			if (depth < 2000.0) {
				return lerpColor(MID_SEA_COLOR, DEEP_SEA_COLOR, (depth - 60.0) / 1940.0);
			}
			return DEEP_SEA_COLOR;
		}

		if (elevation < 120.0) {
			return lerpColor(SHORE_COLOR, LOW_LAND_COLOR, elevation / 120.0);
		}
		if (elevation < 900.0) {
			return lerpColor(LOW_LAND_COLOR, MID_LAND_COLOR, (elevation - 120.0) / 780.0);
		}
		if (elevation < 2200.0) {
			return lerpColor(MID_LAND_COLOR, HIGH_LAND_COLOR, (elevation - 900.0) / 1300.0);
		}
		if (elevation < 3800.0) {
			return lerpColor(HIGH_LAND_COLOR, ROCK_COLOR, (elevation - 2200.0) / 1600.0);
		}
		if (elevation < 5200.0) {
			return lerpColor(ROCK_COLOR, PEAK_COLOR, (elevation - 3800.0) / 1400.0);
		}
		return PEAK_COLOR;
	}

	private static int lerpColor(int a, int b, double t) {
		double clamped = Mth.clamp(t, 0.0, 1.0);
		int ar = (a >> 16) & 0xFF;
		int ag = (a >> 8) & 0xFF;
		int ab = a & 0xFF;
		int br = (b >> 16) & 0xFF;
		int bg = (b >> 8) & 0xFF;
		int bb = b & 0xFF;
		int r = (int) Math.round(ar + (br - ar) * clamped);
		int g = (int) Math.round(ag + (bg - ag) * clamped);
		int bch = (int) Math.round(ab + (bb - ab) * clamped);
		return (r << 16) | (g << 8) | bch;
	}

	private static int blendColor(int base, int tint, float amount) {
		if (amount <= 0.0f) {
			return base;
		}
		float clamped = Mth.clamp(amount, 0.0f, 1.0f);
		int br = (base >> 16) & 0xFF;
		int bg = (base >> 8) & 0xFF;
		int bb = base & 0xFF;
		int tr = (tint >> 16) & 0xFF;
		int tg = (tint >> 8) & 0xFF;
		int tb = tint & 0xFF;
		int r = Math.round(br + (tr - br) * clamped);
		int g = Math.round(bg + (tg - bg) * clamped);
		int b = Math.round(bb + (tb - bb) * clamped);
		return (r << 16) | (g << 8) | b;
	}

	@Override
	public void close() {
		if (this.pending != null) {
			this.pending.cancel(true);
		}
		this.executor.shutdownNow();
	}

	private static final class PreviewMesh {
		private final int size;
		private final int granularity;
		private final float[] heights;
		private final int[] colors;
		private final float[] axis;

		private PreviewMesh(int size, int granularity, float[] heights, int[] colors, float[] axis) {
			this.size = size;
			this.granularity = granularity;
			this.heights = heights;
			this.colors = colors;
			this.axis = axis;
		}
	}

	private static final class TerrainPreviewRenderState implements GuiElementRenderState {
		private final PreviewMesh mesh;
		private final Matrix4f modelView;
		private final Matrix4f projection;
		private final @NonNull Matrix3x2fc pose;
		private final ScreenRectangle rawBounds;
		private final ScreenRectangle bounds;
		private final ScreenRectangle scissor;

		private TerrainPreviewRenderState(
				PreviewMesh mesh,
				Matrix4f modelView,
				Matrix4f projection,
				@NonNull Matrix3x2fc pose,
				ScreenRectangle rawBounds,
				ScreenRectangle bounds
		) {
			this.mesh = mesh;
			this.modelView = modelView;
			this.projection = projection;
			this.pose = pose;
			this.rawBounds = rawBounds;
			this.bounds = bounds;
			this.scissor = bounds;
		}

		@Override
		public @NonNull RenderPipeline pipeline() {
			return RenderPipelines.GUI;
		}

		@Override
		public @NonNull TextureSetup textureSetup() {
			return TextureSetup.noTexture();
		}

		@Override
		public ScreenRectangle scissorArea() {
			return this.scissor;
		}

		@Override
		public ScreenRectangle bounds() {
			return this.bounds;
		}

		@Override
		public void buildVertices(@NonNull VertexConsumer consumer) {
			int stride = this.mesh.granularity;
			if (this.mesh.size <= stride) {
				return;
			}

			int quadsX = (this.mesh.size - 1) / stride;
			int quadsZ = (this.mesh.size - 1) / stride;
			int quadCount = quadsX * quadsZ;
			int[] quadTopLeft = new int[quadCount];
			float[] quadDepth = new float[quadCount];
			boolean[] quadVisible = new boolean[quadCount];

			Vector3f view = new Vector3f();
			Vector3f normal = new Vector3f();
			float depthScale = 0.25f;
			int quadIndex = 0;

			for (int z = 0; z < this.mesh.size - stride; z += stride) {
				float z0 = this.mesh.axis[z];
				float z1 = this.mesh.axis[z + stride];
				int rowIndex = z * this.mesh.size;
				int nextRowIndex = (z + stride) * this.mesh.size;
				for (int x = 0; x < this.mesh.size - stride; x += stride) {
					int idx = rowIndex + x;
					int idxRight = idx + stride;
					int idxDown = nextRowIndex + x;
					int idxDownRight = idxDown + stride;

					float v0 = this.modelView.transformPosition(this.mesh.axis[x], this.mesh.heights[idx], z0, view).z;
					float v1 = this.modelView.transformPosition(this.mesh.axis[x + stride], this.mesh.heights[idxRight], z0, view).z;
					float v2 = this.modelView.transformPosition(this.mesh.axis[x], this.mesh.heights[idxDown], z1, view).z;
					float v3 = this.modelView.transformPosition(this.mesh.axis[x + stride], this.mesh.heights[idxDownRight], z1, view).z;
					float maxZ = Math.max(Math.max(v0, v1), Math.max(v2, v3));

					if (maxZ > -Z_NEAR) {
						quadTopLeft[quadIndex] = -1;
						quadDepth[quadIndex] = Float.POSITIVE_INFINITY;
						quadVisible[quadIndex] = false;
					} else {
						float depth = (v0 + v1 + v2 + v3) * depthScale;
						quadTopLeft[quadIndex] = idx;
						quadDepth[quadIndex] = depth;
						quadVisible[quadIndex] = true;
					}
					quadIndex++;
				}
			}

			if (quadCount > 1) {
				sortQuads(quadTopLeft, quadDepth, 0, quadCount - 1);
			}

			Vector3f projected = new Vector3f();
			float x0 = this.rawBounds.left();
			float y0 = this.rawBounds.top();
			float width = this.rawBounds.width();
			float height = this.rawBounds.height();

			for (int i = 0; i < quadCount; i++) {
				int idx = quadTopLeft[i];
				if (idx < 0 || !quadVisible[i]) {
					continue;
				}
				int x = idx % this.mesh.size;
				int z = idx / this.mesh.size;
				int idxRight = idx + stride;
				int idxDown = idx + stride * this.mesh.size;
				int idxDownRight = idxDown + stride;

				float worldX0 = this.mesh.axis[x];
				float worldX1 = this.mesh.axis[x + stride];
				float worldZ0 = this.mesh.axis[z];
				float worldZ1 = this.mesh.axis[z + stride];

				float shade = computeQuadShade(
						worldX0, this.mesh.heights[idx], worldZ0,
						worldX1, this.mesh.heights[idxRight], worldZ0,
						worldX0, this.mesh.heights[idxDown], worldZ1,
						normal
				);
				int quadColor = applyShade(this.mesh.colors[idx], shade);
				emitVertex(consumer, worldX0, this.mesh.heights[idxDown], worldZ1, quadColor, x0, y0, width, height, view, projected);
				emitVertex(consumer, worldX1, this.mesh.heights[idxDownRight], worldZ1, quadColor, x0, y0, width, height, view, projected);
				emitVertex(consumer, worldX1, this.mesh.heights[idxRight], worldZ0, quadColor, x0, y0, width, height, view, projected);
				emitVertex(consumer, worldX0, this.mesh.heights[idx], worldZ0, quadColor, x0, y0, width, height, view, projected);
			}
		}

		private void emitVertex(
				VertexConsumer consumer,
				float worldX,
				float worldY,
				float worldZ,
				int rgb,
				float x0,
				float y0,
				float width,
				float height,
				Vector3f view,
				Vector3f projected
		) {
			this.modelView.transformPosition(worldX, worldY, worldZ, view);
			this.projection.transformProject(view, projected);

			float screenX = x0 + (projected.x + 1.0f) * 0.5f * width;
			float screenY = y0 + (1.0f - projected.y) * 0.5f * height;
			int argb = 0xFF000000 | (rgb & 0x00FFFFFF);
			consumer.addVertexWith2DPose(this.pose, screenX, screenY).setColor(argb);
		}

		private float computeQuadShade(
				float x0,
				float y0,
				float z0,
				float x1,
				float y1,
				float z1,
				float x2,
				float y2,
				float z2,
				Vector3f normal
		) {
			float ax = x1 - x0;
			float ay = y1 - y0;
			float az = z1 - z0;
			float bx = x2 - x0;
			float by = y2 - y0;
			float bz = z2 - z0;

			normal.set(
					ay * bz - az * by,
					az * bx - ax * bz,
					ax * by - ay * bx
			);
			if (normal.y < 0.0f) {
				normal.negate();
			}
			normal.normalize();

			float shade = Mth.clamp(normal.dot(LIGHT_DIR), 0.0f, 1.0f);
			shade = AMBIENT_SHADE + shade * (1.0f - AMBIENT_SHADE);
			return Math.round(shade * SHADE_STEPS) / SHADE_STEPS;
		}

		private static int applyShade(int rgb, float shade) {
			int r = (rgb >> 16) & 0xFF;
			int g = (rgb >> 8) & 0xFF;
			int b = rgb & 0xFF;
			r = Mth.clamp(Math.round(r * shade), 0, 255);
			g = Mth.clamp(Math.round(g * shade), 0, 255);
			b = Mth.clamp(Math.round(b * shade), 0, 255);
			return (r << 16) | (g << 8) | b;
		}


		private static void sortQuads(int[] quadTopLeft, float[] quadDepth, int left, int right) {
			int i = left;
			int j = right;
			float pivot = quadDepth[(left + right) >>> 1];

			while (i <= j) {
				while (quadDepth[i] < pivot) {
					i++;
				}
				while (quadDepth[j] > pivot) {
					j--;
				}
				if (i <= j) {
					swap(quadTopLeft, quadDepth, i, j);
					i++;
					j--;
				}
			}

			if (left < j) {
				sortQuads(quadTopLeft, quadDepth, left, j);
			}
			if (i < right) {
				sortQuads(quadTopLeft, quadDepth, i, right);
			}
		}

		private static void swap(int[] quadTopLeft, float[] quadDepth, int i, int j) {
			int tempIndex = quadTopLeft[i];
			quadTopLeft[i] = quadTopLeft[j];
			quadTopLeft[j] = tempIndex;

			float tempDepth = quadDepth[i];
			quadDepth[i] = quadDepth[j];
			quadDepth[j] = tempDepth;
		}
	}

	private static final class PreviewThreadFactory implements ThreadFactory {
		@Override
		public Thread newThread(@NotNull Runnable runnable) {
			Thread thread = new Thread(runnable, "tellus-preview");
			thread.setDaemon(true);
			return thread;
		}
	}
}
