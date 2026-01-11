package com.yucareux.tellus.network;

import com.yucareux.tellus.Tellus;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import java.util.Objects;
import org.jspecify.annotations.NonNull;

public record GeoTpOpenMapPayload(double latitude, double longitude) implements CustomPacketPayload {
	public static final @NonNull Type<GeoTpOpenMapPayload> TYPE = new Type<>(
			Tellus.id("geotp_open_map")
	);
	public static final StreamCodec<FriendlyByteBuf, GeoTpOpenMapPayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.DOUBLE,
			GeoTpOpenMapPayload::latitude,
			ByteBufCodecs.DOUBLE,
			GeoTpOpenMapPayload::longitude,
			GeoTpOpenMapPayload::fromBoxed
	);

	@Override
	public @NonNull Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}

	private static GeoTpOpenMapPayload fromBoxed(Double latitude, Double longitude) {
		return new GeoTpOpenMapPayload(
                Objects.requireNonNull(latitude, "latitude"),
                Objects.requireNonNull(longitude, "longitude")
		);
	}
}
