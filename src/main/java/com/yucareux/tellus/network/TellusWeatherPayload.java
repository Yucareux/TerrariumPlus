package com.yucareux.tellus.network;

import com.yucareux.tellus.Tellus;
import com.yucareux.tellus.world.realtime.SnowGrid;
import com.yucareux.tellus.world.realtime.TellusRealtimeState;
import java.util.Arrays;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.NonNull;

public record TellusWeatherPayload(
		boolean weatherEnabled,
		TellusRealtimeState.PrecipitationMode precipitationMode,
		boolean historicalSnowEnabled,
		int centerX,
		int centerZ,
		int spacingBlocks,
		float[] snowIndex
) implements CustomPacketPayload {
	public static final @NonNull Type<TellusWeatherPayload> TYPE = new Type<>(
			Identifier.fromNamespaceAndPath(Tellus.MOD_ID, "realtime_weather")
	);

	private static final int GRID_POINTS = SnowGrid.GRID_POINTS;

	public static final StreamCodec<FriendlyByteBuf, TellusWeatherPayload> CODEC = new StreamCodec<>() {
		@Override
		public TellusWeatherPayload decode(FriendlyByteBuf buffer) {
			boolean weatherEnabled = buffer.readBoolean();
			byte precipitationId = buffer.readByte();
			boolean historicalSnowEnabled = buffer.readBoolean();
			int centerX = buffer.readVarInt();
			int centerZ = buffer.readVarInt();
			int spacingBlocks = buffer.readVarInt();
			float[] snowIndex = new float[GRID_POINTS];
			for (int i = 0; i < GRID_POINTS; i++) {
				snowIndex[i] = buffer.readFloat();
			}
			TellusRealtimeState.PrecipitationMode mode = decodePrecipitation(precipitationId);
			return new TellusWeatherPayload(
					weatherEnabled,
					mode,
					historicalSnowEnabled,
					centerX,
					centerZ,
					spacingBlocks,
					snowIndex
			);
		}

		@Override
		public void encode(FriendlyByteBuf buffer, TellusWeatherPayload value) {
			buffer.writeBoolean(value.weatherEnabled());
			buffer.writeByte(encodePrecipitation(value.precipitationMode()));
			buffer.writeBoolean(value.historicalSnowEnabled());
			buffer.writeVarInt(value.centerX());
			buffer.writeVarInt(value.centerZ());
			buffer.writeVarInt(value.spacingBlocks());
			float[] snowIndex = value.snowIndex();
			if (snowIndex == null || snowIndex.length < GRID_POINTS) {
				snowIndex = new float[GRID_POINTS];
			}
			for (int i = 0; i < GRID_POINTS; i++) {
				buffer.writeFloat(snowIndex[i]);
			}
		}
	};

	@Override
	public @NonNull Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}

	public TellusWeatherPayload {
		if (snowIndex == null || snowIndex.length != GRID_POINTS) {
			snowIndex = new float[GRID_POINTS];
		} else {
			snowIndex = Arrays.copyOf(snowIndex, GRID_POINTS);
		}
	}

	private static byte encodePrecipitation(TellusRealtimeState.PrecipitationMode mode) {
		if (mode == null) {
			return 0;
		}
		return (byte) mode.ordinal();
	}

	private static TellusRealtimeState.PrecipitationMode decodePrecipitation(byte id) {
		TellusRealtimeState.PrecipitationMode[] values = TellusRealtimeState.PrecipitationMode.values();
		int index = id < 0 ? 0 : Math.min(id, values.length - 1);
		return values[index];
	}
}
