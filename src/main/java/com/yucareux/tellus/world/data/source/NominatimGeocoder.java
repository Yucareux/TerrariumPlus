package com.yucareux.tellus.world.data.source;

import com.yucareux.tellus.Tellus;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class NominatimGeocoder implements Geocoder {
	private static final String SEARCH_URL = "https://nominatim.openstreetmap.org/search?format=json&limit=%d&q=%s";
	private static final int GET_LIMIT = 1;
	private static final int SUGGEST_LIMIT = 5;
	private static final int CONNECT_TIMEOUT_MS = 5000;
	private static final int READ_TIMEOUT_MS = 12000;
	private static final int TIMEOUT_RETRIES = 1;
	private static final int RETRY_BACKOFF_MS = 300;

	@Override
	public double[] get(String place) {
		try {
			JsonElement result = this.query(place, GET_LIMIT);
			if (result.isJsonArray()) {
				JsonArray array = result.getAsJsonArray();
				if (!array.isEmpty()) {
					JsonObject first = array.get(0).getAsJsonObject();
					double lat = first.get("lat").getAsDouble();
					double lon = first.get("lon").getAsDouble();
					return new double[] { lat, lon };
				}
			}
		} catch (SocketTimeoutException e) {
			Tellus.LOGGER.warn("Geocoder timed out for: {}", place);
			Tellus.LOGGER.debug("Geocoder timeout details", e);
		} catch (IOException e) {
			Tellus.LOGGER.error("Failed to geocode place: {}", place, e);
		}
		return null;
	}

	@Override
	public Suggestion[] suggest(String place) {
		try {
			JsonElement result = this.query(place, SUGGEST_LIMIT);
			if (result.isJsonArray()) {
				JsonArray array = result.getAsJsonArray();
				List<Suggestion> suggestions = new ArrayList<>();
				for (JsonElement element : array) {
					if (element.isJsonObject()) {
						JsonObject object = element.getAsJsonObject();
						if (!object.has("display_name") || !object.has("lat") || !object.has("lon")) {
							continue;
						}
						String name = object.get("display_name").getAsString();
						double lat = object.get("lat").getAsDouble();
						double lon = object.get("lon").getAsDouble();
						suggestions.add(new Suggestion(name, lat, lon));
					}
				}
				return suggestions.toArray(new Suggestion[0]);
			}
		} catch (SocketTimeoutException e) {
			Tellus.LOGGER.warn("Geocoder timed out for: {}", place);
			Tellus.LOGGER.debug("Geocoder timeout details", e);
		} catch (IOException e) {
			Tellus.LOGGER.error("Failed to suggest places for: {}", place, e);
		}
		return new Suggestion[0];
	}

	private JsonElement query(String place, int limit) throws IOException {
		String encodedPlace = URLEncoder.encode(place, StandardCharsets.UTF_8);
		URI uri = URI.create(String.format(SEARCH_URL, limit, encodedPlace));
		IOException lastError = null;
		for (int attempt = 0; attempt <= TIMEOUT_RETRIES; attempt++) {
			HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
			connection.setRequestProperty("User-Agent", "Tellus/2.0.0 (Minecraft Mod)");
			connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
			connection.setReadTimeout(READ_TIMEOUT_MS);

			try (InputStream input = connection.getInputStream();
					InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
				return JsonParser.parseReader(reader);
			} catch (SocketTimeoutException e) {
				lastError = e;
				if (attempt < TIMEOUT_RETRIES) {
					try {
						Thread.sleep(RETRY_BACKOFF_MS);
					} catch (InterruptedException interrupted) {
						Thread.currentThread().interrupt();
						throw new IOException("Geocoder retry interrupted", interrupted);
					}
					continue;
				}
				throw e;
			} catch (IOException e) {
				lastError = e;
				throw e;
			}
		}
		throw lastError != null ? lastError : new IOException("Geocoder query failed");
	}
}
