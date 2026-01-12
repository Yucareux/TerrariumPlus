package com.yucareux.tellus.world.data.source;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import net.minecraft.util.Mth;

public final class OpenMeteoClient {
	private static final int CONNECT_TIMEOUT_MS = 5000;
	private static final int READ_TIMEOUT_MS = 12000;
	private static final int HISTORY_HOURS = 72;
	private static final float MELT_RATE_PER_HOUR = 0.2f;
	private static final float SNOW_ACCUM_SCALE = 10.0f;
	private static final float TEMP_MELT_THRESHOLD = 2.0f;
	private static final String USER_AGENT = "Tellus/1.0 (open-meteo.com)";

	public WeatherPointData fetch(double latitude, double longitude) throws IOException {
		String url = buildUrl(latitude, longitude);
		HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
		connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
		connection.setReadTimeout(READ_TIMEOUT_MS);
		connection.setRequestProperty("User-Agent", USER_AGENT);
		connection.setRequestProperty("Accept", "application/json");
		int responseCode = connection.getResponseCode();
		if (responseCode != 200) {
			throw new IOException("Open-Meteo request failed with HTTP " + responseCode);
		}
		try (Reader reader = new InputStreamReader(new BufferedInputStream(connection.getInputStream()), StandardCharsets.UTF_8)) {
			JsonElement rootElement = JsonParser.parseReader(reader);
			if (rootElement.isJsonNull() || !rootElement.isJsonObject()) {
				throw new IOException("Open-Meteo response missing JSON");
			}
			JsonObject root = rootElement.getAsJsonObject();
			int utcOffset = root.get("utc_offset_seconds").getAsInt();
			JsonObject current = root.getAsJsonObject("current");
			int weatherCode = current.get("weather_code").getAsInt();
			float temperature = current.get("temperature_2m").getAsFloat();
			float precipitation = current.get("precipitation").getAsFloat();
			float snowfall = current.get("snowfall").getAsFloat();

			JsonObject hourly = root.getAsJsonObject("hourly");
			SnowHistory history = parseSnowHistory(hourly);
			float snowIndex = computeSnowIndex(history);

			return new WeatherPointData(
					latitude,
					longitude,
					utcOffset,
					weatherCode,
					temperature,
					precipitation,
					snowfall,
					snowIndex
			);
		}
	}

	private static SnowHistory parseSnowHistory(JsonObject hourly) {
		if (hourly == null) {
			return new SnowHistory(0.0f, 0, 0.0f);
		}
		JsonArray temps = hourly.getAsJsonArray("temperature_2m");
		JsonArray snowfall = hourly.getAsJsonArray("snowfall");
		if (temps == null || snowfall == null) {
			return new SnowHistory(0.0f, 0, 0.0f);
		}
		int size = Math.min(temps.size(), snowfall.size());
		int start = Math.max(0, size - HISTORY_HOURS);
		float snowSum = 0.0f;
		int meltHours = 0;
		float tempSum = 0.0f;
		int tempCount = 0;
		for (int i = start; i < size; i++) {
			float temp = temps.get(i).getAsFloat();
			float snow = snowfall.get(i).getAsFloat();
			snowSum += snow;
			if (temp > TEMP_MELT_THRESHOLD) {
				meltHours++;
			}
			tempSum += temp;
			tempCount++;
		}
		float avgTemp = tempCount == 0 ? 0.0f : tempSum / tempCount;
		return new SnowHistory(snowSum, meltHours, avgTemp);
	}

	private static float computeSnowIndex(SnowHistory history) {
		float snowAccum = Math.max(0.0f, history.snowfallSum() - history.meltHours() * MELT_RATE_PER_HOUR);
		float snowIndex = snowAccum / SNOW_ACCUM_SCALE;
		if (history.avgTemp() > TEMP_MELT_THRESHOLD) {
			float extraMelt = (history.avgTemp() - TEMP_MELT_THRESHOLD) * 0.05f;
			snowIndex -= extraMelt;
		}
		return Mth.clamp(snowIndex, 0.0f, 1.0f);
	}

	private static String buildUrl(double latitude, double longitude) {
		return String.format(
				java.util.Locale.ROOT,
				"https://api.open-meteo.com/v1/forecast?latitude=%.5f&longitude=%.5f"
						+ "&current=weather_code,temperature_2m,precipitation,snowfall"
						+ "&hourly=temperature_2m,snowfall"
						+ "&past_days=3&forecast_days=1&timezone=auto",
				latitude,
				longitude
		);
	}

	public record WeatherPointData(
			double latitude,
			double longitude,
			int utcOffsetSeconds,
			int weatherCode,
			float temperatureC,
			float precipitationMm,
			float snowfallCm,
			float snowIndex
	) {
		public WeatherPointData {
		}
	}

	private record SnowHistory(float snowfallSum, int meltHours, float avgTemp) {
	}
}
