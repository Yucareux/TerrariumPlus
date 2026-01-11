package com.yucareux.tellus.network;

import com.yucareux.tellus.Tellus;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import java.util.Objects;
import org.jspecify.annotations.NonNull;

public record GeoTpTeleportPayload(double latitude, double longitude) implements CustomPacketPayload {
	public static final @NonNull Type<GeoTpTeleportPayload> TYPE = new Type<>(
			Tellus.id("geotp_teleport")
	);
	public static final StreamCodec<FriendlyByteBuf, GeoTpTeleportPayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.DOUBLE,
			GeoTpTeleportPayload::latitude,
			ByteBufCodecs.DOUBLE,
			GeoTpTeleportPayload::longitude,
			GeoTpTeleportPayload::fromBoxed
	);

	@Override
	public @NonNull Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}

	private static GeoTpTeleportPayload fromBoxed(Double latitude, Double longitude) {
		return new GeoTpTeleportPayload(
                Objects.requireNonNull(latitude, "latitude"),
                Objects.requireNonNull(longitude, "longitude")
		);
	}
}
