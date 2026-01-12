package com.yucareux.tellus;

import com.yucareux.tellus.client.screen.EarthTeleportScreen;
import com.yucareux.tellus.network.GeoTpOpenMapPayload;
import com.yucareux.tellus.network.TellusWeatherPayload;
import com.yucareux.tellus.world.realtime.SnowGrid;
import com.yucareux.tellus.world.realtime.TellusRealtimeState;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

public class TellusClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		ClientPlayNetworking.registerGlobalReceiver(GeoTpOpenMapPayload.TYPE, (payload, context) -> {
			context.client().execute(() -> {
				Minecraft minecraft = context.client();
				Screen parent = minecraft.screen;
				minecraft.setScreen(new EarthTeleportScreen(parent, payload.latitude(), payload.longitude()));
			});
		});
		ClientPlayNetworking.registerGlobalReceiver(TellusWeatherPayload.TYPE, (payload, context) -> {
			context.client().execute(() -> {
				SnowGrid grid = payload.historicalSnowEnabled() && payload.spacingBlocks() > 0
						? new SnowGrid(
								payload.centerX(),
								payload.centerZ(),
								payload.spacingBlocks(),
								payload.snowIndex()
						)
						: SnowGrid.empty();
				TellusRealtimeState.updateWeatherState(
						payload.weatherEnabled(),
						payload.precipitationMode(),
						payload.historicalSnowEnabled(),
						grid
				);
			});
		});
	}
}
