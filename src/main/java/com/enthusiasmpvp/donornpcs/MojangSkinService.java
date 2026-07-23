package com.enthusiasmpvp.donornpcs;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class MojangSkinService {
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final Map<UUID, SkinTexture> textureCache = new ConcurrentHashMap<>();
    private final Map<String, UUID> nameToUuidCache = new ConcurrentHashMap<>();

    /** Resolve a real Mojang Java UUID from a username. */
    public Optional<UUID> resolveUuid(String username) throws IOException, InterruptedException {
        String key = username.toLowerCase();
        UUID cached = nameToUuidCache.get(key);
        if (cached != null) return Optional.of(cached);

        URI uri = URI.create("https://api.minecraftservices.com/minecraft/profile/lookup/name/" + username);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 204 || response.statusCode() == 404) return Optional.empty();
        if (response.statusCode() != 200) throw new IOException("Minecraft API returned HTTP " + response.statusCode());

        JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
        String id = root.get("id").getAsString();
        UUID uuid = UuidUtil.parseUuid(id).orElseThrow(() -> new IOException("Invalid UUID from API: " + id));
        nameToUuidCache.put(key, uuid);
        return Optional.of(uuid);
    }

    public SkinTexture fetchTexture(UUID uuid, boolean forceRefresh) throws IOException, InterruptedException {
        if (!forceRefresh) {
            SkinTexture cached = textureCache.get(uuid);
            if (cached != null) {
                return cached;
            }
        }

        URI uri = URI.create("https://sessionserver.mojang.com/session/minecraft/profile/"
                + UuidUtil.undashed(uuid)
                + "?unsigned=false");
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 204 || response.statusCode() == 404) {
            throw new SkinProfileNotFoundException(uuid, response.statusCode());
        }
        if (response.statusCode() != 200) {
            throw new IOException("Mojang session server returned HTTP " + response.statusCode());
        }

        SkinTexture texture = parseTexture(response.body());
        textureCache.put(uuid, texture);
        return texture;
    }

    private SkinTexture parseTexture(String responseBody) throws IOException {
        JsonObject root = JsonParser.parseString(responseBody).getAsJsonObject();
        JsonArray properties = root.getAsJsonArray("properties");
        if (properties == null) {
            throw new IOException("Mojang response did not include skin properties");
        }

        for (JsonElement element : properties) {
            JsonObject property = element.getAsJsonObject();
            if (!"textures".equals(property.get("name").getAsString())) {
                continue;
            }

            String value = property.has("value") ? property.get("value").getAsString() : "";
            String signature = property.has("signature") ? property.get("signature").getAsString() : "";
            if (value.isBlank() || signature.isBlank()) {
                throw new IOException("Mojang texture property was missing value or signature");
            }
            return new SkinTexture(value, signature);
        }

        throw new IOException("Mojang response did not include a textures property");
    }
}
