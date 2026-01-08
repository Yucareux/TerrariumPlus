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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class NominatimGeocoder implements Geocoder {
	private static final String SEARCH_URL = "https://nominatim.openstreetmap.org/search?format=json&limit=%d&q=%s";
	private static final int GET_LIMIT = 1;
	private static final int SUGGEST_LIMIT = 5;

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
		} catch (IOException e) {
			Tellus.LOGGER.error("Failed to suggest places for: {}", place, e);
		}
		return new Suggestion[0];
	}

	private JsonElement query(String place, int limit) throws IOException {
		String encodedPlace = URLEncoder.encode(place, StandardCharsets.UTF_8);
		URI uri = URI.create(String.format(SEARCH_URL, limit, encodedPlace));
		HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
		connection.setRequestProperty("User-Agent", "Tellus/2.0.0 (Minecraft Mod)");
		connection.setConnectTimeout(5000);
		connection.setReadTimeout(5000);

		try (InputStream input = connection.getInputStream();
				InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
			return JsonParser.parseReader(reader);
		}
	}
}
